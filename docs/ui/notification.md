# Notifications and Live Update

## 1. Basic Foreground Service Notification

Android requires a notification object while the monitoring Foreground Service runs. When dynamic speed is disabled, `NotificationHelper` provides a minimal basic notification instead of removing it. This requirement does not mean the user must grant `POST_NOTIFICATIONS`: on Android 13+, an authorized Overlay can run with notification permission denied, with the service notification hidden from the notification drawer and the system Active apps / Task Manager indication retained. Notification speed and Live Update cannot be displayed while notification permission is denied.

The notification uses one of two stable channels in the same channel group; names and descriptions come from localized resources. Channel availability, sound, and importance remain controlled by system settings.

| Channel ID | Importance | Used when |
|---|---|---|
| `net_monitor_silent` (`CHANNEL_ID`) | `IMPORTANCE_DEFAULT`, no sound | Default |
| `net_monitor_quiet` (`CHANNEL_ID_QUIET`) | `IMPORTANCE_LOW`, no sound | "Lower notification priority" (`key_notification_quiet`) is on |

### Lower Notification Priority

An app cannot change a channel's importance after creating it, so the setting switches channels instead; the notification keeps the same `NOTIFICATION_ID`. The channel choice is part of `NotificationRenderState` (`quietChannel`), so toggling the setting reposts on the next sample, and the initial `startForeground()` notification uses it too.

On the low-importance channel the notification moves to the silent section at the bottom of the notification shade and stops competing with conversations and alerts. Most devices do not show a status-bar icon for silent notifications, so the dynamic Bitmap icon may disappear; the setting is meant to be combined with Live Update or the Overlay. The notification itself cannot be removed while monitoring.

Live Update eligibility only forbids `IMPORTANCE_MIN`, so a promoted notification on the `IMPORTANCE_LOW` channel is allowed by AOSP. Whether the Samsung Now Bar still shows the chip from a low-importance channel needs device validation.

Settings also offers "Notification channel settings", which opens `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` for the active channel (falling back to the app notification settings) so users can minimize or silence it further.

## 2. Dynamic Bitmap Icon

Standard notification mode uses `IconCompat.createWithBitmap()`:

1. The Status bar icon setting selects Total, Upload, or Download.
2. `SpeedFormatter` automatically selects the value and unit from the global Rate unit; Speed Unit and Minimum Display Unit do not constrain the tiny icon.
3. Canvas draws the selected value and unit with two Paint objects.
4. Users can adjust value and unit text-size ratios.

Notification drawer content is configured independently: Upload and download, Upload only, or Download only. Drawer text keeps custom prefixes and order.

## 3. Low-Traffic Mode

When the speed selected by Status bar icon is below `key_notification_threshold` (Total, Upload, or Download respectively):

- Static mode uses the application icon and monitoring text.
- Dynamic mode continues to show a low-speed value without Live Update.

A threshold of zero disables low-traffic handling.

The decision uses hysteresis (`LowTrafficHysteresis`) for both modes: the notification enters or leaves the low-traffic state only after three consecutive samples on the other side of the threshold (`SWITCH_SAMPLE_COUNT`). Without it, a speed hovering around the threshold would toggle Live Update promotion (and the Samsung Now Bar extras) every few seconds, making the chip appear and disappear and reordering status-bar icons. The first sample after monitoring starts or resumes, and the first sample after the threshold or Status bar icon setting changes, is applied immediately.

## 4. Live Update

Android 16+ can enable Live Update:

- Use the static `ic_speed` small icon.
- Show compact automatically scaled Total, Upload, or Download text through `setShortCriticalText()`.
- Request Promoted Ongoing presentation with `setRequestPromotedOngoing(true)`.
- Require ordinary notification permission and system support; the manifest declares `POST_PROMOTED_NOTIFICATIONS`.

Android decides whether the notification is ultimately promoted.

On Android 16+, Settings shows whether `NotificationManager.canPostPromotedNotifications()` allows Live Updates for the app and opens `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` (falling back to the app notification settings) so users can re-enable it.

### Samsung One UI

One UI routes ongoing notifications through its own Now Bar pipeline, which predates AOSP Live Updates and ignores plain promoted notifications from apps outside Samsung's allowlist. On Samsung devices, `SamsungCompat.nowBarExtras()` adds two undocumented extras to the Live Update notification:

- `android.ongoingActivityNoti.automation = true`
- `android.ongoingActivityNoti.automationPackage = <applicationId>`

Side effect, confirmed on a Galaxy S23 with One UI 9: One UI treats the automation extras as an assistant acting inside an app and labels the Now Bar card and chip "Gemini Intelligence" with the Gemini logo.

Community findings indicate that with the One UI developer option "Live notifications for all apps" turned on, plain AOSP promoted notifications reach the Now Bar under the app's own name without the extras, and that the extras would still brand the chip as Gemini. `SamsungCompat.isLiveNotificationsForAllAppsEnabled()` reads the private Secure setting `enable_notification_nowbar_test` (`"1"` means on) and returns `null` when the key is absent, unreadable, or throws, because whether ordinary apps can read it on every build is unverified. The result is cached for 30 seconds and invalidated when monitoring starts or resumes, when the screen turns on, and when Settings resumes.

`key_samsung_now_bar_mode` ("Now Bar label mode") decides whether the extras are attached (`SamsungCompat.shouldAddNowBarExtras()`):

| Mode | Extras | Result |
|---|---|---|
| 0 Auto (default) | Added when the developer option is off or cannot be detected; skipped when it is on | Chip always works; shows the app's name once the developer option is on |
| 1 Always | Always added | Labeled "Gemini Intelligence"; works without the developer option |
| 2 Never | Never added | App's own name; no chip on Samsung unless the developer option is on |

The decision is stored in `NotificationRenderState.samsungNowBarExtras` (only ever true in Live Update mode), so a change of mode or of the detected developer option reposts the notification. Non-Samsung devices never get the extras.

These keys come from community reverse engineering of One UI 8.5 SystemUI and are reported to work on One UI 8.5 and 9.0. They only take effect when the notification still satisfies `Notification.hasPromotableCharacteristics()`, so never add `setColorized(true)`, custom views, or a group summary to the Live Update notification. Do not set `android.ongoingActivityNoti.style`, which moves the notification to Samsung's private card lane and disables the bypass. The keys are private, can change in any One UI release, and are inert on non-Samsung devices and older One UI versions.

While Live Update is on, Settings shows a Samsung-only "Samsung Now Bar compatibility" entry with the detected One UI version (read from `ro.build.version.oneui`), the detected state of the developer option (on, off, or cannot be detected), an explanation of the "Gemini Intelligence" label, and a reminder that Good Lock QuickStar's "Ongoing Chip" toggle can hide all chips. Tapping it opens `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` (falling back to the app notification settings). A `ListPreference` below it selects the label mode. The Background section adds a Samsung-only link to the "Never sleeping apps" guide.

## 5. Formatting

`SpeedFormatter` is the single source for speed formatting across the main screen, notifications, Live Update, Overlay, and threshold summaries.

The global Rate unit selects Bytes or Bits. Byte units use the existing binary scale with corrected 1024 Auto boundaries; bit units use decimal SI (`b/s`, `kb/s`, `Mb/s`, `Gb/s`). Fixed units show zero, one, or two decimal places based on value range. A minimum display unit can render smaller traffic as zero. All decimal output uses `.`.

## 6. Update Deduplication

For every sample the Service asks `NotificationHelper.createRenderState()` for a `NotificationRenderState`: mode, drawer content text, Live Update status text, Bitmap value and unit text, Rate unit, icon mode, custom color settings, the value / unit text-size ratios, the channel choice (`quietChannel`), and whether the Samsung Now Bar extras are attached (`samsungNowBarExtras`). Computing it builds no `Notification`, `PendingIntent`, or Bitmap.

The Service compares it with the last posted state and calls `NotificationHelper.buildNotification()` and `NotificationManager.notify()` only when a repost is needed:

- Any change other than the drawer content text (everything visible in the status bar or affecting styling) is reposted immediately.
- A change limited to the drawer content text is reposted at most once every 10 seconds (`CONTENT_TEXT_REPOST_INTERVAL_MS`). The drawer text has B/s precision and changes on almost every sample even on an idle device, so the drawer text can lag by up to 10 seconds while the status-bar output is unchanged.
- An identical state is never reposted.

This prevents redundant refreshes and status-bar icon reordering. The content `PendingIntent` is created once and reused, and `setWhen` keeps the timestamp taken when the Service started so icon ordering stays stable.

## 7. Device Validation

Validate Android 12/12L startup, Android 13+ permission grant and denial, Android 16+ Live Update (including the Samsung One UI Now Bar chip in each label mode, with the developer option on and off, and on the low-importance channel), switching between the two channels while monitoring, light and dark status-bar readability, custom colors across ROMs, and refresh stability with multiple ongoing notifications.
