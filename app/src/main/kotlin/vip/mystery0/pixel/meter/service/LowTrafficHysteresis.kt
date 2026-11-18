package vip.mystery0.pixel.meter.service

/**
 * Debounces the notification low-traffic decision so that a speed hovering around the threshold
 * does not toggle Live Update promotion (and the status-bar icon style) every few samples.
 *
 * The state only flips after [SWITCH_SAMPLE_COUNT] consecutive samples on the other side of the
 * threshold. The first sample, and the first sample after the threshold or icon mode changes,
 * is applied immediately. Instances are not thread-safe; use one per collector.
 */
class LowTrafficHysteresis {
    companion object {
        /** Consecutive samples required before entering or leaving the low-traffic state. */
        const val SWITCH_SAMPLE_COUNT = 3
    }

    private var initialized = false
    private var isLowTraffic = false
    private var oppositeSamples = 0
    private var lastThreshold = 0L
    private var lastIconMode = 0

    /**
     * Feeds one sample and returns the debounced low-traffic state.
     *
     * @param iconSpeed the speed selected by the Status bar icon setting.
     * @param threshold the notification threshold; zero or less disables low-traffic handling.
     * @param iconMode the Status bar icon setting that selected [iconSpeed].
     */
    fun update(iconSpeed: Long, threshold: Long, iconMode: Int): Boolean {
        val isBelowThreshold = threshold > 0L && iconSpeed < threshold
        if (!initialized || threshold != lastThreshold || iconMode != lastIconMode) {
            initialized = true
            lastThreshold = threshold
            lastIconMode = iconMode
            isLowTraffic = isBelowThreshold
            oppositeSamples = 0
            return isLowTraffic
        }

        if (isBelowThreshold == isLowTraffic) {
            oppositeSamples = 0
        } else {
            oppositeSamples += 1
            if (oppositeSamples >= SWITCH_SAMPLE_COUNT) {
                isLowTraffic = isBelowThreshold
                oppositeSamples = 0
            }
        }
        return isLowTraffic
    }
}
