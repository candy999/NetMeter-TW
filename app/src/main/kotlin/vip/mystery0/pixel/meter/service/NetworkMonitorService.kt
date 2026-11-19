package vip.mystery0.pixel.meter.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import vip.mystery0.pixel.meter.data.repository.NetworkRepository
import vip.mystery0.pixel.meter.data.source.NetSpeedData
import vip.mystery0.pixel.meter.ui.overlay.OverlayWindow

class NetworkMonitorService : Service() {
    companion object {
        private const val TAG = "NetworkMonitorService"

        /**
         * When only the notification drawer text changed, the notification is reposted at most
         * once per this interval. Status-bar-visible changes are always reposted immediately.
         */
        private const val CONTENT_TEXT_REPOST_INTERVAL_MS = 10_000L

        /** Settings.canDrawOverlays() is re-checked at most once per this interval. */
        private const val OVERLAY_PERMISSION_RECHECK_INTERVAL_MS = 30_000L

        /**
         * While the screen is on but locked, the keyguard state is polled at this interval as a
         * fallback in case ACTION_USER_PRESENT is not delivered or arrives before the keyguard
         * reports itself unlocked.
         */
        private const val KEYGUARD_POLL_INTERVAL_MS = 1_000L
    }

    private val repository: NetworkRepository by inject()
    private val notificationHelper: NotificationHelper by inject()
    private val notificationManager: NotificationManager by inject()
    private val overlayWindow: OverlayWindow by inject()
    private val powerManager: PowerManager by inject()
    private val keyguardManager: KeyguardManager by lazy {
        getSystemService(KeyguardManager::class.java)
    }

    private var serviceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var notificationPostedAtMillis = 0L

    // Last successfully posted state; null forces a repost on the next sample.
    @Volatile
    private var lastPostedRenderState: NotificationHelper.NotificationRenderState? = null

    @Volatile
    private var lastNotificationPostElapsedMs = 0L

    /**
     * True while sampling is paused because the screen is off or the device is locked. Guarded by
     * [notificationLock] together with posting, so a late sample cannot re-promote the
     * notification after the paused state was posted.
     */
    @Volatile
    private var isPausedForLock = false
    private val notificationLock = Any()

    // Polls the keyguard while the lock screen is showing. Only touched on the main thread.
    private var keyguardWatchJob: Job? = null

    // Cached Settings.canDrawOverlays() result. Only touched on the main thread.
    private var isOverlayPermissionGranted = false
    private var overlayPermissionCheckedElapsedMs = 0L
    private var isOverlayPermissionChecked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (notificationPostedAtMillis == 0L) {
            notificationPostedAtMillis = System.currentTimeMillis()
        }
        val initialState = createRenderState(
            speed = NetSpeedData(0, 0),
            isLiveUpdate = false,
            // A zero speed is below any positive threshold.
            isLowTraffic = repository.notificationThreshold.value > 0L
        )
        val initialNotification =
            notificationHelper.buildNotification(initialState, notificationPostedAtMillis)

        try {
            // This notification is mandatory even when POST_NOTIFICATIONS is denied.
            startForegroundCompat(NotificationHelper.NOTIFICATION_ID, initialNotification)
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: start foreground error", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // A previous pause may have left the paused notification behind.
        notificationManager.cancel(NotificationHelper.PAUSED_NOTIFICATION_ID)

        synchronized(notificationLock) {
            isPausedForLock = false
            lastPostedRenderState = initialState
            lastNotificationPostElapsedMs = SystemClock.elapsedRealtime()
        }

        if (isDeviceInUse()) {
            startMonitoring()
        } else {
            // A START_STICKY restart or boot start can happen while the screen is off or locked,
            // in which case no ACTION_SCREEN_OFF broadcast will follow.
            Log.d(TAG, "Started while the device is locked: staying paused until unlock")
            pauseForLock()
            if (powerManager.isInteractive) startKeyguardWatch()
        }
        return START_STICKY
    }

    /**
     * Computes the visible notification state from the current settings. This does not build a
     * Notification.
     */
    private fun createRenderState(
        speed: NetSpeedData,
        isLiveUpdate: Boolean,
        isLowTraffic: Boolean
    ): NotificationHelper.NotificationRenderState = notificationHelper.createRenderState(
        speed = speed,
        isLiveUpdate = isLiveUpdate,
        isNotificationEnabled = repository.isNotificationEnabled.value,
        textUp = repository.notificationTextUp.value,
        textDown = repository.notificationTextDown.value,
        upFirst = repository.notificationOrderUpFirst.value,
        displayMode = repository.notificationDisplayMode.value,
        iconMode = repository.notificationIconMode.value,
        textSize = repository.notificationTextSize.value,
        unitSize = repository.notificationUnitSize.value,
        isLowTraffic = isLowTraffic,
        lowTrafficMode = repository.notificationLowTrafficMode.value,
        useCustomColor = repository.notificationUseCustomColor.value,
        color = repository.notificationColor.value,
        speedUnit = repository.speedUnit.value,
        minSpeedUnit = repository.minSpeedUnit.value,
        rateUnit = repository.speedRateUnit.value,
        useQuietChannel = repository.isNotificationQuiet.value,
        // Only Live Update notifications carry the extras, so skip the lookup otherwise.
        samsungNowBarExtras = isLiveUpdate && SamsungCompat.shouldAddNowBarExtras(
            this,
            repository.samsungNowBarMode.value
        )
    )

    private fun startMonitoring() {
        serviceJob?.cancel()

        // Re-check the Overlay permission on the first sample after a (re)start.
        isOverlayPermissionChecked = false
        // The One UI developer option may have changed while monitoring was stopped.
        SamsungCompat.invalidateLiveNotificationsForAllAppsCache()

        // Start Repository Monitoring
        repository.startMonitoring()

        // The collector runs on Dispatchers.Default; only the Overlay update hops to Main.
        serviceJob = scope.launch {
            // Created per collector so the low-traffic state resets when monitoring restarts.
            val lowTrafficHysteresis = LowTrafficHysteresis()
            repository.speedSamples.collect { speed ->
                updateOverlay(speed)
                updateNotification(speed, lowTrafficHysteresis)
            }
        }
    }

    private suspend fun updateOverlay(speed: NetSpeedData) {
        val isOverlayEnabled = repository.isOverlayEnabled.value
        // Nothing to show and nothing to remove: skip the main-thread hop entirely.
        if (!isOverlayEnabled && !overlayWindow.isActive) return

        withContext(Dispatchers.Main) {
            try {
                if (isOverlayEnabled && canDrawOverlays()) {
                    overlayWindow.show()
                    overlayWindow.update(speed)
                } else {
                    overlayWindow.hide()
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateOverlay: overlay window error", e)
            }
        }
    }

    /**
     * Returns the cached Overlay permission state, refreshing it at most once per
     * [OVERLAY_PERMISSION_RECHECK_INTERVAL_MS]. Must be called on the main thread.
     */
    private fun canDrawOverlays(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!isOverlayPermissionChecked ||
            now - overlayPermissionCheckedElapsedMs >= OVERLAY_PERMISSION_RECHECK_INTERVAL_MS
        ) {
            isOverlayPermissionGranted = Settings.canDrawOverlays(this)
            overlayPermissionCheckedElapsedMs = now
            isOverlayPermissionChecked = true
            if (!isOverlayPermissionGranted) {
                Log.w(TAG, "overlay enabled but permission not granted, hiding overlay.")
            }
        }
        return isOverlayPermissionGranted
    }

    private fun updateNotification(
        speed: NetSpeedData,
        lowTrafficHysteresis: LowTrafficHysteresis
    ) {
        // Feed every sample, including the ones skipped below, to keep the counter continuous.
        val iconMode = repository.notificationIconMode.value
        val isLowTraffic = lowTrafficHysteresis.update(
            iconSpeed = notificationHelper.selectIconSpeed(speed, iconMode),
            threshold = repository.notificationThreshold.value,
            iconMode = iconMode
        )

        // Notification denial must not interrupt the Overlay or sampling. The required
        // initial Foreground Service notification is still always supplied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Repost on the next permitted sample even if the visible content is unchanged.
            lastPostedRenderState = null
            return
        }

        val renderState = createRenderState(
            speed = speed,
            isLiveUpdate = repository.isLiveUpdateEnabled.value,
            isLowTraffic = isLowTraffic
        )
        if (!shouldRepost(renderState)) return

        // Only build the Notification (Builder, Bitmap drawing) when it is actually posted.
        val notification =
            notificationHelper.buildNotification(renderState, notificationPostedAtMillis)
        synchronized(notificationLock) {
            // The device may have been locked while this sample was being processed.
            if (isPausedForLock) return
            postNotification(renderState, notification)
        }
    }

    private fun postNotification(
        renderState: NotificationHelper.NotificationRenderState,
        notification: Notification
    ) {
        try {
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
            lastPostedRenderState = renderState
            lastNotificationPostElapsedMs = SystemClock.elapsedRealtime()
        } catch (e: SecurityException) {
            // Permission can change between the check and posting the notification.
            lastPostedRenderState = null
            Log.w(TAG, "Notification permission changed; keeping monitoring active", e)
        }
    }

    /**
     * Status-bar-visible changes repost immediately. A change limited to the drawer content text
     * is rate limited, because it changes on almost every sample even on an idle device.
     */
    private fun shouldRepost(renderState: NotificationHelper.NotificationRenderState): Boolean {
        val lastPosted = lastPostedRenderState ?: return true
        if (renderState == lastPosted) return false
        if (!renderState.hasSameStatusBarOutput(lastPosted)) return true
        val elapsed = SystemClock.elapsedRealtime() - lastNotificationPostElapsedMs
        return elapsed >= CONTENT_TEXT_REPOST_INTERVAL_MS
    }

    /** Starts or updates the Foreground Service notification with the correct service type. */
    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    /** True when the screen is on and the keyguard is dismissed. */
    private fun isDeviceInUse(): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    /**
     * Stops sampling and moves the Foreground Service onto a separate, static paused notification,
     * then cancels the live notification. Cancelling (rather than updating it in place) makes the
     * system drop the Live Update chip and One UI drop the Now Bar card, which may otherwise keep
     * the entry for the same notification. Must be called on the main thread.
     */
    private fun pauseForLock() {
        serviceJob?.cancel()
        serviceJob = null
        repository.stopMonitoring()

        val pausedState = notificationHelper.createPausedRenderState(
            useQuietChannel = repository.isNotificationQuiet.value,
            useCustomColor = repository.notificationUseCustomColor.value,
            color = repository.notificationColor.value
        )
        val pausedNotification =
            notificationHelper.buildNotification(pausedState, notificationPostedAtMillis)
        synchronized(notificationLock) {
            if (isPausedForLock) return
            // From here on, in-flight samples cannot post the live notification again.
            isPausedForLock = true
            try {
                // Updating an already-foreground service is allowed from the background.
                startForegroundCompat(NotificationHelper.PAUSED_NOTIFICATION_ID, pausedNotification)
                notificationManager.cancel(NotificationHelper.NOTIFICATION_ID)
                lastPostedRenderState = null
            } catch (e: Exception) {
                // Fall back to replacing the live notification in place.
                Log.w(TAG, "pauseForLock: could not switch the foreground notification", e)
                postNotification(pausedState, pausedNotification)
            }
        }
    }

    /**
     * Resumes sampling and moves the Foreground Service back onto the live notification.
     *
     * @param unlocked true when the caller already knows the device was unlocked
     * (ACTION_USER_PRESENT or the keyguard poll), so the keyguard state is not re-checked; it can
     * still report "locked" while the unlock animation runs. Must be called on the main thread.
     */
    private fun resumeIfInUse(unlocked: Boolean = false) {
        if (!powerManager.isInteractive) return
        if (!unlocked && keyguardManager.isKeyguardLocked) return
        keyguardWatchJob?.cancel()
        keyguardWatchJob = null
        if (!isPausedForLock && repository.isMonitoring.value) return
        Log.d(TAG, "Device unlocked: resuming monitoring")

        val initialState = createRenderState(
            speed = NetSpeedData(0, 0),
            isLiveUpdate = false,
            isLowTraffic = repository.notificationThreshold.value > 0L
        )
        val initialNotification =
            notificationHelper.buildNotification(initialState, notificationPostedAtMillis)
        synchronized(notificationLock) {
            isPausedForLock = false
            try {
                startForegroundCompat(NotificationHelper.NOTIFICATION_ID, initialNotification)
                notificationManager.cancel(NotificationHelper.PAUSED_NOTIFICATION_ID)
                lastPostedRenderState = initialState
                lastNotificationPostElapsedMs = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                Log.w(TAG, "resumeIfInUse: could not switch the foreground notification", e)
                // Force the first sample to post the live notification.
                lastPostedRenderState = null
            }
        }
        startMonitoring()
    }

    /**
     * Polls the keyguard while the screen is on and locked, and resumes as soon as it unlocks.
     * Stops when the screen turns off or monitoring resumes.
     */
    private fun startKeyguardWatch() {
        keyguardWatchJob?.cancel()
        keyguardWatchJob = scope.launch(Dispatchers.Main) {
            while (isActive && powerManager.isInteractive) {
                delay(KEYGUARD_POLL_INTERVAL_MS)
                if (!keyguardManager.isKeyguardLocked) {
                    resumeIfInUse(unlocked = true)
                    break
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF: pausing monitoring")
                    keyguardWatchJob?.cancel()
                    keyguardWatchJob = null
                    pauseForLock()
                }

                Intent.ACTION_SCREEN_ON -> {
                    // Settings may have changed while the screen was off.
                    isOverlayPermissionChecked = false
                    SamsungCompat.invalidateLiveNotificationsForAllAppsCache()
                    if (keyguardManager.isKeyguardLocked) {
                        // Lock screen: stay paused; resume on ACTION_USER_PRESENT or the poll.
                        startKeyguardWatch()
                    } else {
                        resumeIfInUse()
                    }
                }

                Intent.ACTION_USER_PRESENT -> {
                    isOverlayPermissionChecked = false
                    SamsungCompat.invalidateLiveNotificationsForAllAppsCache()
                    resumeIfInUse(unlocked = true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        keyguardWatchJob?.cancel()
        serviceJob?.cancel()
        notificationPostedAtMillis = 0L
        lastPostedRenderState = null
        overlayWindow.hide()
        repository.stopMonitoring()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NotificationHelper.NOTIFICATION_ID)
        notificationManager.cancel(NotificationHelper.PAUSED_NOTIFICATION_ID)
    }
}
