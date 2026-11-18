package vip.mystery0.pixel.meter.data.source

import androidx.compose.runtime.Immutable

@Immutable
data class NetSpeedData(
    val downloadSpeed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val totalSpeed: Long = downloadSpeed + uploadSpeed,
)

/**
 * Cumulative since-boot counters of a single network interface.
 */
data class NetworkTrafficData(
    val rxBytes: Long,
    val txBytes: Long,
)

interface ISpeedDataSource {
    /**
     * Returns the current counters keyed by distinct physical interface name. Interfaces whose
     * counters are unavailable are omitted.
     *
     * This call performs blocking counter reads and must not run on the main thread.
     */
    fun getInterfaceTrafficData(): Map<String, NetworkTrafficData>
}
