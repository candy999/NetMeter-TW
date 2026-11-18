package vip.mystery0.pixel.meter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import vip.mystery0.pixel.meter.MainActivity
import vip.mystery0.pixel.meter.R
import vip.mystery0.pixel.meter.data.source.NetSpeedData
import vip.mystery0.pixel.meter.format.SpeedFormatter
import kotlin.math.roundToInt

class NotificationHelper(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "net_monitor_silent"
        const val NOTIFICATION_ID = 1001

        fun createNotificationChannel(context: Context) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val group = NotificationChannelGroup(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name)
            )
            notificationManager.createNotificationChannelGroup(group)

            // The channel is silent because its sound is null. IMPORTANCE_DEFAULT is kept on
            // purpose: it keeps the status-bar icon visible and the notification eligible for
            // Live Update promotion.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                setGroup(CHANNEL_ID)
                setSound(null, null)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Everything that determines the visible notification output. The Service compares render
     * states to decide whether a repost is needed before any [Notification] is built.
     */
    data class NotificationRenderState(
        val mode: String,
        val contentText: String,
        val statusText: String? = null,
        val valueText: String? = null,
        val unitText: String? = null,
        val rateUnit: Int = 0,
        val iconMode: Int = 0,
        val useCustomColor: Boolean = false,
        val color: Int = 0,
        val textSize: Float = 0f,
        val unitSize: Float = 0f
    ) {
        /**
         * Returns true when everything except the notification drawer [contentText] is equal,
         * that is, when the status-bar icon, Live Update chip, and styling would not change.
         */
        fun hasSameStatusBarOutput(other: NotificationRenderState): Boolean =
            copy(contentText = "") == other.copy(contentText = "")
    }

    // Reused for every notification; the Intent never changes.
    private val contentIntent: PendingIntent by lazy {
        val intent = Intent().apply {
            setClassName(context, MainActivity::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // Icon generation
    // On Pixel, small icon is typically 24dp. We render at higher res (e.g. 48px or 96px) for clarity
    private val size =
        (context.resources.displayMetrics.density * 24).roundToInt().coerceAtLeast(48)
    private val bitmap = createBitmap(size, size)
    private val canvas = Canvas(bitmap)

    // Paints
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        textSize = size * 0.65f // Value text
    }

    private val unitPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        textSize = size * 0.35f // Unit text
    }

    /**
     * Returns the speed that drives the status-bar icon and the low-traffic decision.
     */
    fun selectIconSpeed(speed: NetSpeedData, iconMode: Int): Long = when (iconMode) {
        1 -> speed.uploadSpeed
        2 -> speed.downloadSpeed
        else -> speed.totalSpeed
    }

    /**
     * Computes the visible state without building a [Notification]. This is cheap enough to run
     * for every sample.
     *
     * @param isLowTraffic whether the icon speed is considered below the notification threshold.
     * The caller applies hysteresis (see [LowTrafficHysteresis]).
     */
    fun createRenderState(
        speed: NetSpeedData,
        isLiveUpdate: Boolean,
        isNotificationEnabled: Boolean,
        textUp: String,
        textDown: String,
        upFirst: Boolean,
        displayMode: Int,
        iconMode: Int,
        textSize: Float = 0.65f,
        unitSize: Float = 0.35f,
        isLowTraffic: Boolean = false,
        lowTrafficMode: Int = 0, // 0: Static, 1: Dynamic
        useCustomColor: Boolean = false,
        color: Int = 0,
        speedUnit: Int = 0,
        minSpeedUnit: Int = 0,
        rateUnit: Int = 0
    ): NotificationRenderState {
        // The color only matters when the custom color is enabled.
        val effectiveColor = if (useCustomColor) color else 0
        var shouldLiveUpdate = isLiveUpdate
        val normalizedIconMode = if (iconMode in 0..2) iconMode else 0
        val iconSpeed = selectIconSpeed(speed, normalizedIconMode)

        if (!isNotificationEnabled) {
            return NotificationRenderState(
                mode = "disabled-static",
                contentText = context.getString(R.string.notification_content_text),
                rateUnit = rateUnit,
                iconMode = normalizedIconMode,
                useCustomColor = useCustomColor,
                color = effectiveColor,
                textSize = textSize,
                unitSize = unitSize
            )
        }

        if (isLowTraffic) {
            if (lowTrafficMode == 0) {
                return NotificationRenderState(
                    mode = "threshold-static",
                    contentText = context.getString(R.string.notification_content_text_monitoring),
                    rateUnit = rateUnit,
                    iconMode = normalizedIconMode,
                    useCustomColor = useCustomColor,
                    color = effectiveColor,
                    textSize = textSize,
                    unitSize = unitSize
                )
            }
            shouldLiveUpdate = false
        }

        val upText = "$textUp${
            SpeedFormatter.formatSpeedLine(
                speed.uploadSpeed,
                speedUnit,
                minSpeedUnit,
                rateUnit
            )
        }"
        val downText = "$textDown${
            SpeedFormatter.formatSpeedLine(
                speed.downloadSpeed,
                speedUnit,
                minSpeedUnit,
                rateUnit
            )
        }"
        val contentText = when (displayMode) {
            1 -> upText
            2 -> downText
            else -> if (upFirst) "$upText  $downText" else "$downText  $upText"
        }

        if (shouldLiveUpdate) {
            return NotificationRenderState(
                mode = "live-update",
                contentText = contentText,
                statusText = SpeedFormatter.formatSpeedTextForLiveUpdate(
                    iconSpeed,
                    rateUnit
                ),
                rateUnit = rateUnit,
                iconMode = normalizedIconMode,
                useCustomColor = useCustomColor,
                color = effectiveColor,
                textSize = textSize,
                unitSize = unitSize
            )
        }

        val (valueStr, unitStr) = SpeedFormatter.formatSpeedText(
            iconSpeed,
            rateUnit = rateUnit
        )
        return NotificationRenderState(
            mode = "bitmap",
            contentText = contentText,
            valueText = valueStr,
            unitText = unitStr,
            rateUnit = rateUnit,
            iconMode = normalizedIconMode,
            useCustomColor = useCustomColor,
            color = effectiveColor,
            textSize = textSize,
            unitSize = unitSize
        )
    }

    /**
     * Builds the [Notification] for [renderState]. Call this only when a repost is needed.
     */
    fun buildNotification(
        renderState: NotificationRenderState,
        postedAtMillis: Long
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(postedAtMillis)
            .setShowWhen(false)
            .setContentTitle(context.getString(R.string.notification_content_title))
            .setContentText(renderState.contentText)

        if (renderState.useCustomColor) {
            builder.setColor(renderState.color)
        }

        when (renderState.mode) {
            "live-update" -> {
                builder
                    .setSmallIcon(R.drawable.ic_speed)
                    .setShortCriticalText(requireNotNull(renderState.statusText))
                    .setRequestPromotedOngoing(true)
                // One UI ignores plain promoted notifications from apps outside its allowlist.
                SamsungCompat.nowBarExtras(context)?.let(builder::addExtras)
            }

            "bitmap" -> {
                val valueText = requireNotNull(renderState.valueText)
                val unitText = requireNotNull(renderState.unitText)

                bitmap.eraseColor(Color.TRANSPARENT)
                val cx = size / 2f
                val cyValue = size * 0.5f
                val cyUnit = size * 0.95f

                textPaint.textSize = size * renderState.textSize
                unitPaint.textSize = size * renderState.unitSize
                val maxTextWidth = size * 0.92f
                val valueWidth = textPaint.measureText(valueText)
                if (valueWidth > maxTextWidth) {
                    textPaint.textSize *= maxTextWidth / valueWidth
                }
                val unitWidth = unitPaint.measureText(unitText)
                if (unitWidth > maxTextWidth) {
                    unitPaint.textSize *= maxTextWidth / unitWidth
                }

                canvas.drawText(valueText, cx, cyValue, textPaint)
                canvas.drawText(unitText, cx, cyUnit, unitPaint)

                builder.setSmallIcon(IconCompat.createWithBitmap(bitmap))
            }

            else -> builder.setSmallIcon(R.drawable.ic_speed)
        }

        return builder.build()
    }
}
