# Notifications and Live Update

## 1. Basic Foreground Service Notification

Android requires a notification object while the monitoring Foreground Service runs. When dynamic speed is disabled, `NotificationHelper` provides a minimal basic notification instead of removing it. This requirement does not mean the user must grant `POST_NOTIFICATIONS`: on Android 13+, an authorized Overlay can run with notification permission denied, with the service notification hidden from the notification drawer and the system Active apps / Task Manager indication retained. Notification speed and Live Update cannot be displayed while notification permission is denied.

The notification channel uses a stable `CHANNEL_ID`; its name and description come from localized resources. Channel availability, sound, and importance remain controlled by system settings.

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

## 4. Live Update

Android 16+ can enable Live Update:

- Use the static `ic_speed` small icon.
- Show compact automatically scaled Total, Upload, or Download text through `setShortCriticalText()`.
- Request Promoted Ongoing presentation with `setRequestPromotedOngoing(true)`.
- Require ordinary notification permission and system support; the manifest declares `POST_PROMOTED_NOTIFICATIONS`.

Android decides whether the notification is ultimately promoted.

On Android 16+, Settings shows whether `NotificationManager.canPostPromotedNotifications()` allows Live Updates for the app and opens `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` (falling back to the app notification settings) so users can re-enable it.

### Samsung One UI

One UI routes ongoing notifications through its own Now Bar pipeline, which predates AOSP Live Updates and ignores plain promoted notifications from apps outside Samsung's allowlist. On Samsung devices, `SamsungCompat.nowBarExtras()` adds two undocumented extras to the Live Update notification:

- `android.ongoingActivityNoti.automation = true`
- `android.ongoingActivityNoti.automationPackage = <applicationId>`

These keys come from community reverse engineering of One UI 8.5 SystemUI and are reported to work on One UI 8.5 and 9.0. They only take effect when the notification still satisfies `Notification.hasPromotableCharacteristics()`, so never add `setColorized(true)`, custom views, or a group summary to the Live Update notification. Do not set `android.ongoingActivityNoti.style`, which moves the notification to Samsung's private card lane and disables the bypass. The keys are private, can change in any One UI release, and are inert on non-Samsung devices and older One UI versions.

Settings also shows a Samsung-only hint with the detected One UI version (read from `ro.build.version.oneui`) and a reminder that Good Lock QuickStar's "Ongoing Chip" toggle can hide all chips. The Background section adds a Samsung-only link to the "Never sleeping apps" guide.

## 5. Formatting

`SpeedFormatter` is the single source for speed formatting across the main screen, notifications, Live Update, Overlay, and threshold summaries.

The global Rate unit selects Bytes or Bits. Byte units use the existing binary scale with corrected 1024 Auto boundaries; bit units use decimal SI (`b/s`, `kb/s`, `Mb/s`, `Gb/s`). Fixed units show zero, one, or two decimal places based on value range. A minimum display unit can render smaller traffic as zero. All decimal output uses `.`.

## 6. Update Deduplication

`NotificationHelper` creates a fingerprint containing visible state plus Rate unit and icon mode. The Service calls `NotificationManager.notify()` only when the fingerprint changes, preventing redundant refreshes and status-bar icon reordering.

## 7. Device Validation

Validate Android 12/12L startup, Android 13+ permission grant and denial, Android 16+ Live Update (including the Samsung One UI Now Bar chip), light and dark status-bar readability, custom colors across ROMs, and refresh stability with multiple ongoing notifications.
