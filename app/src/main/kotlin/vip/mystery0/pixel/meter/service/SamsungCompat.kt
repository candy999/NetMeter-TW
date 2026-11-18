package vip.mystery0.pixel.meter.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings

/**
 * Samsung One UI compatibility helpers.
 *
 * One UI routes ongoing notifications through its own Now Bar pipeline, which predates AOSP
 * Live Updates and ignores plain promoted notifications from apps outside Samsung's allowlist.
 * Community reverse engineering of One UI 8.5 SystemUI shows that the undocumented
 * `android.ongoingActivityNoti.automation` extra lets a notification that already satisfies
 * `Notification.hasPromotableCharacteristics()` bypass that allowlist.
 *
 * The keys are private to Samsung and may change at any time. They are inert on non-Samsung
 * devices and on One UI releases before 8.5, so posting falls back to plain AOSP behavior.
 * Do not set `android.ongoingActivityNoti.style`: it moves the notification to Samsung's
 * private card lane and disables the bypass.
 *
 * Side effect: One UI treats the automation extras as an assistant acting inside an app and
 * labels the Now Bar card and chip "Gemini Intelligence". When the One UI developer option
 * "Live notifications for all apps" is on, plain promoted notifications already reach the Now
 * Bar under the app's own name, so the extras are skipped in that case (see
 * [shouldAddNowBarExtras]).
 */
object SamsungCompat {
    private const val EXTRA_AUTOMATION = "android.ongoingActivityNoti.automation"
    private const val EXTRA_AUTOMATION_PACKAGE = "android.ongoingActivityNoti.automationPackage"
    private const val PROP_ONE_UI_VERSION = "ro.build.version.oneui"

    /** Secure setting behind the developer option "Live notifications for all apps". */
    private const val SECURE_LIVE_NOTIFICATIONS_FOR_ALL_APPS = "enable_notification_nowbar_test"

    /** The developer option is re-read at most once per this interval. */
    private const val DETECTION_CACHE_MS = 30_000L

    /** Add the extras only when the developer option is off or cannot be detected. */
    const val NOW_BAR_MODE_AUTO = 0

    /** Always add the extras; the chip is labeled "Gemini Intelligence". */
    const val NOW_BAR_MODE_ALWAYS = 1

    /** Never add the extras; the chip needs the developer option to appear. */
    const val NOW_BAR_MODE_NEVER = 2

    private class Detection(val enabled: Boolean?, val readAtElapsedMs: Long)

    @Volatile
    private var cachedDetection: Detection? = null

    /** First One UI release (encoded as major * 10000 + minor * 100) known to honor the extras. */
    const val ONE_UI_NOW_BAR_AUTOMATION = 80500

    val isSamsung: Boolean by lazy {
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /** One UI version encoded as major * 10000 + minor * 100 (for example 90000), or null. */
    val oneUiVersion: Int? by lazy {
        if (isSamsung) readSystemProperty(PROP_ONE_UI_VERSION)?.toIntOrNull() else null
    }

    /** Human-readable One UI version such as "One UI 9.0", or null when unknown. */
    val oneUiVersionName: String? by lazy {
        oneUiVersion?.let { "One UI ${it / 10000}.${(it % 10000) / 100}" }
    }

    /**
     * Extras that let One UI show a promoted ongoing notification in the Now Bar and status bar
     * chip. Returns null on non-Samsung devices.
     */
    fun nowBarExtras(context: Context): Bundle? {
        if (!isSamsung) return null
        return Bundle(2).apply {
            putBoolean(EXTRA_AUTOMATION, true)
            putString(EXTRA_AUTOMATION_PACKAGE, context.packageName)
        }
    }

    /**
     * Returns whether the One UI developer option "Live notifications for all apps" is on.
     *
     * Returns null on non-Samsung devices and whenever the state is unknown: the key is private,
     * may be absent, and may not be readable by ordinary apps on every build. The result is
     * cached for [DETECTION_CACHE_MS] because this is called for every notification sample.
     */
    fun isLiveNotificationsForAllAppsEnabled(context: Context): Boolean? {
        if (!isSamsung) return null
        val now = SystemClock.elapsedRealtime()
        cachedDetection?.let { cached ->
            if (now - cached.readAtElapsedMs < DETECTION_CACHE_MS) return cached.enabled
        }
        val enabled: Boolean? = try {
            when (
                Settings.Secure.getString(
                    context.contentResolver,
                    SECURE_LIVE_NOTIFICATIONS_FOR_ALL_APPS
                )
            ) {
                null -> null
                "1" -> true
                else -> false
            }
        } catch (ignored: Exception) {
            null
        }
        cachedDetection = Detection(enabled, now)
        return enabled
    }

    /** Forces the next [isLiveNotificationsForAllAppsEnabled] call to re-read the setting. */
    fun invalidateLiveNotificationsForAllAppsCache() {
        cachedDetection = null
    }

    /**
     * Decides whether the Live Update notification should carry [nowBarExtras].
     *
     * @param mode one of [NOW_BAR_MODE_AUTO], [NOW_BAR_MODE_ALWAYS], or [NOW_BAR_MODE_NEVER].
     */
    fun shouldAddNowBarExtras(context: Context, mode: Int): Boolean {
        if (!isSamsung) return false
        return when (mode) {
            NOW_BAR_MODE_ALWAYS -> true
            NOW_BAR_MODE_NEVER -> false
            // Unknown (null) is treated like "off" so the chip keeps working.
            else -> isLiveNotificationsForAllAppsEnabled(context) != true
        }
    }

    @SuppressLint("PrivateApi")
    private fun readSystemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
