package vip.mystery0.pixel.meter.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle

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
 */
object SamsungCompat {
    private const val EXTRA_AUTOMATION = "android.ongoingActivityNoti.automation"
    private const val EXTRA_AUTOMATION_PACKAGE = "android.ongoingActivityNoti.automationPackage"
    private const val PROP_ONE_UI_VERSION = "ro.build.version.oneui"

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

    @SuppressLint("PrivateApi")
    private fun readSystemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
