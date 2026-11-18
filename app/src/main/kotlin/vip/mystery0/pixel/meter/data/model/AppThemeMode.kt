package vip.mystery0.pixel.meter.data.model

/**
 * Application theme mode.
 *
 * The value is persisted in DataStore. Keep existing numeric values stable when adding modes to preserve user settings.
 */
enum class AppThemeMode(val value: Int) {
    Dynamic(0),
    Fixed(1);

    companion object {
        /** Theme mode used when no mode has been persisted yet. */
        val DEFAULT: AppThemeMode = Fixed

        val DEFAULT_THEME_COLOR: Int = 0xFF0E4686.toInt()

        fun fromValue(value: Int): AppThemeMode =
            entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
