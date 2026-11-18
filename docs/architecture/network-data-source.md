# Network Data Source

## 1. Goal

NetMeter TW must count only physical-link traffic while a VPN is active, preventing the same bytes from being counted on both physical and virtual interfaces.

## 2. Network Detection

`SpeedDataSource` registers a `ConnectivityManager.NetworkCallback` for these transports:

- `TRANSPORT_WIFI`
- `TRANSPORT_CELLULAR`
- `TRANSPORT_ETHERNET`

`onCapabilitiesChanged` and `onLinkPropertiesChanged` cache the `NetworkCapabilities` and `LinkProperties` supplied directly by the callbacks. They do not synchronously call `getNetworkCapabilities()` or `getLinkProperties()`, avoiding stale or null callback-time lookup results.

A network is added to the interface cache only when:

1. Both capabilities and link properties are available.
2. The capabilities do not contain `TRANSPORT_VPN`.
3. At least one supported physical transport is present.
4. `LinkProperties.interfaceName` is not null.
5. The `Network` and interface name can be stored in `ConcurrentHashMap<Network, String>`.

`onLost` removes the Network, its interface entry, and its cached callback parameters.

## 3. Why There Is No Interface-Name Blacklist

VPN interface names vary by implementation and device. Names such as `tun0` are not reliable identifiers. Filtering by `TRANSPORT_VPN` follows Android's network model and avoids maintaining incomplete device-specific rules.

## 4. Traffic Counters

For each cached physical interface, the data source reads:

```kotlin
TrafficStats.getRxBytes(interfaceName)
TrafficStats.getTxBytes(interfaceName)
```

Several `Network` objects can report the same interface name, so the cached names are de-duplicated and each distinct interface is read exactly once per sample.

The data source returns the counters per interface (`Map<String, NetworkTrafficData>`); it does not sum them. If either counter of an interface is `TrafficStats.UNSUPPORTED`, that interface is omitted from the sample and treated as unavailable.

The sampling loop reads cached interface names directly and does not query `ConnectivityManager` for every sample.

## 5. Speed Calculation

`NetworkRepository` keeps the previous sample's per-interface counters and calculates speed from per-interface deltas:

```text
rxDelta = Σ max(currentRx[i] - previousRx[i], 0)   for interfaces i present in both samples
txDelta = Σ max(currentTx[i] - previousTx[i], 0)   for interfaces i present in both samples
downloadSpeed = rxDelta × 1000 / elapsedMs
uploadSpeed   = txDelta × 1000 / elapsedMs
```

- An interface seen for the first time only establishes its baseline. Its since-boot counter is never reported as one sample's traffic, so joining Wi-Fi does not produce a spike.
- An interface that vanished is dropped from the baseline. It does not create a negative total that would hide real traffic on the remaining interfaces.
- A negative delta on one interface (counter reset) is ignored for that interface and direction only.

Sampling durations and loop timing use `SystemClock.elapsedRealtime()`, a monotonic clock unaffected by wall-clock corrections.

The first sample after monitoring starts or resumes (including screen-on) establishes the baseline for all interfaces. The `monitoringGeneration` guard discards a sample that raced with a stop or restart.

## 6. Concurrency and Threads

- Interface and callback-parameter caches use `ConcurrentHashMap`.
- The Repository sampling loop runs on `Dispatchers.Default`. Interfaces are read sequentially in that coroutine, with no per-interface coroutine and no extra Dispatcher hop; there are normally only one or two interfaces.
- `getInterfaceTrafficData()` is blocking and must not be called on the main thread.
- The speed calculation runs in the same coroutine under the monitoring state lock.

## 7. Validation Scenarios

Validate changes to this path on a real device (Samsung One UI and a near-stock device where possible) with:

- Wi-Fi only.
- Cellular only.
- Ethernet, where available.
- A VPN enabled and disabled.
- Multiple simultaneous networks.
- Network disconnection and reconnection.
- No near-doubling of reported speed after enabling a VPN.

## 8. Known Limitation

Interfaces are matched by name. If an interface is destroyed and recreated under the same name between two adjacent samples and its new counter is already larger than the old one, the difference is reported as one sample of traffic. In the usual case the recreated counter restarts near zero, which yields a negative delta that is ignored.

Traffic on an interface during the sample in which it first appears, or in the last interval before it vanishes, is not counted.
