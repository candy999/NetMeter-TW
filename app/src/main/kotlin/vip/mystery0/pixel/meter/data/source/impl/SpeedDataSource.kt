package vip.mystery0.pixel.meter.data.source.impl

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import vip.mystery0.pixel.meter.data.source.ISpeedDataSource
import vip.mystery0.pixel.meter.data.source.NetworkTrafficData
import java.util.concurrent.ConcurrentHashMap

class SpeedDataSource(
    private val connectivityManager: ConnectivityManager
) : ISpeedDataSource {
    private val validInterfaces = ConcurrentHashMap<Network, String>()
    private val networkCapabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val linkProperties = ConcurrentHashMap<Network, LinkProperties>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, capabilities)
            networkCapabilities[network] = capabilities
            updateNetwork(network)
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            super.onLinkPropertiesChanged(network, properties)
            linkProperties[network] = properties
            updateNetwork(network)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            networkCapabilities.remove(network)
            linkProperties.remove(network)
            validInterfaces.remove(network)
        }
    }

    init {
        // Listen for supported physical transports and filter them again in updateNetwork.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun updateNetwork(network: Network) {
        val caps = networkCapabilities[network]
        val props = linkProperties[network]
        if (caps == null || props == null) {
            validInterfaces.remove(network)
            return
        }

        // Ignore VPN transports to avoid counting the virtual interface in addition to the
        // underlying physical link.
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (isVpn) {
            validInterfaces.remove(network)
            return
        }

        // The request already filters transports, but capabilities may change after registration.
        val isPhysical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (isPhysical) {
            val ifaceName = props.interfaceName
            if (!ifaceName.isNullOrEmpty()) {
                validInterfaces[network] = ifaceName
            }
        } else {
            validInterfaces.remove(network)
        }
    }

    /**
     * Reads the counters of every cached physical interface once.
     *
     * Several [Network] objects can share one interface name, so names are de-duplicated before
     * reading. The reads are sequential and blocking; the caller's sampling loop already runs on
     * a background Dispatcher, so no extra Dispatcher hop or per-interface coroutine is needed.
     */
    override fun getInterfaceTrafficData(): Map<String, NetworkTrafficData> {
        // Read cached interface names directly without querying ConnectivityManager in the
        // sampling loop.
        val interfaceNames = validInterfaces.values.toSet()
        if (interfaceNames.isEmpty()) return emptyMap()

        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        val result = HashMap<String, NetworkTrafficData>(interfaceNames.size)
        for (ifaceName in interfaceNames) {
            val rx = TrafficStats.getRxBytes(ifaceName)
            val tx = TrafficStats.getTxBytes(ifaceName)
            // Treat the interface as unavailable for this sample so that it gets a fresh
            // baseline once its counters can be read again.
            if (rx == unsupported || tx == unsupported) continue
            result[ifaceName] = NetworkTrafficData(rx, tx)
        }
        return result
    }
}
