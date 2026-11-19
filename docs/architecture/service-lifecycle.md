# Foreground Service Lifecycle

## 1. Configuration

`NetworkMonitorService` declares `android:foregroundServiceType="specialUse|dataSync"` in the manifest and declares the `network_monitor` Special Use subtype.

At runtime:

- Android 14+: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
- Earlier supported versions: `FOREGROUND_SERVICE_TYPE_DATA_SYNC`

## 2. Start Sources

The Service may start from the main screen, "Finish and start" in Onboarding, or `BootReceiver` when auto-start is enabled. Quick Settings Tiles toggle display preferences; they do not start or stop the Service.

`POST_NOTIFICATIONS` is not a Foreground Service startup prerequisite. `DisplayPermissionState` shares the display requirements between the main screen and Onboarding:

- An enabled Overlay requires `Settings.canDrawOverlays()`, but never notification permission.
- With an authorized Overlay, monitoring can start even if notification speed / Live Update remains selected and notification permission was denied or revoked. The UI explains that notifications are unavailable.
- Notification-only display still asks for notification permission on Android 13+, so it does not start with an unavailable selected output.
- Android 12 and 12L do not have `POST_NOTIFICATIONS` and are never blocked by its runtime check.
- No selected display can still be monitored from the main screen; Onboarding saves that configuration without automatically starting.

## 3. Initial Notification

`onStartCommand()` must call `startForeground()` immediately. The initial notification reads the current Repository configuration, including dynamic notification state, prefixes and order, display mode, text sizes, low-traffic behavior, custom color, speed unit, and minimum display unit.

A basic notification object remains required even when dynamic notification speed or notification permission is disabled. Do not skip `startForeground()` or replace the Service with an ordinary background Service to hide the notification. On Android 13+, denied notification permission hides the Foreground Service notification from the notification drawer, but Android can still show the app in Active apps / Task Manager.

## 4. Active Operation

After startup, the Service:

1. Calls `NetworkRepository.startMonitoring()`.
2. Collects the `speedSamples` SharedFlow on a background Dispatcher.
3. Updates the Overlay on the main thread. The main-thread hop is skipped entirely while the Overlay is disabled and no Overlay window exists.
4. Computes the notification render state for every sample, which is cheap and builds no `Notification`.
5. Builds and posts a `Notification` only when the render state requires a repost (see [Notifications](../ui/notification.md), Update Deduplication).

`Settings.canDrawOverlays()` is cached and re-checked at most every 30 seconds (`OVERLAY_PERMISSION_RECHECK_INTERVAL_MS`), and additionally on monitoring start and on `ACTION_SCREEN_ON`. A revoked or regranted Overlay permission therefore takes effect within that interval rather than on the very next sample.

It supports a basic static notification, a dynamic Bitmap small icon, and Android 16+ Live Update.

The Overlay update runs independently of notification permission. When `POST_NOTIFICATIONS` is denied, optional `notify()` updates are skipped and the last posted render state is invalidated so a later grant refreshes even unchanged content. A permission race while posting is caught without cancelling monitoring. Enabling notification speed without permission must not stop a working Overlay.

## 5. Screen-Off and Lock Screen Policy

Nothing is sampled or updated while nobody can use the speed display, and the monitor never occupies the lock screen (Live Update chip or Samsung Now Bar), where more important information may need the space.

- `ACTION_SCREEN_OFF` pauses immediately (`pauseForLock()`): the collector job is cancelled and Repository sampling stops. The Foreground Service then switches to a separate static notification (`PAUSED_NOTIFICATION_ID`, `NotificationHelper.createPausedRenderState()`: `ic_speed`, "paused while locked", never promoted, no Samsung extras) and the live notification (`NOTIFICATION_ID`) is cancelled. Cancelling instead of updating in place is deliberate: One UI can keep a Now Bar card for an existing notification even after it stops requesting promotion. Calling `startForeground()` again on an already-foreground Service is allowed from the background.
- `ACTION_SCREEN_ON` resumes only when the keyguard is not locked. On a locked lock screen the Service stays paused and polls `KeyguardManager.isKeyguardLocked` every second (`KEYGUARD_POLL_INTERVAL_MS`) as a fallback, until the screen turns off or the device unlocks.
- `ACTION_USER_PRESENT` (the user unlocked) resumes without re-checking the keyguard, because the keyguard can still report "locked" while the unlock animation runs.
- Resuming (`resumeIfInUse()`) moves the Foreground Service back to `NOTIFICATION_ID`, cancels the paused notification, resets the speed baseline and the low-traffic hysteresis, and lets the first sample restore the Live Update chip when enabled.
- `onStartCommand()` starts sampling only when the device is in use; a `START_STICKY` restart or a boot start before the first unlock starts paused.
- A lock (`notificationLock`) guards the paused flag together with posting, so a sample that was in flight when the screen turned off cannot re-post the live notification.
- The Service and its Foreground Service notification stay alive throughout.

## 6. Boot Startup

`BootReceiver` listens for `BOOT_COMPLETED` and `QUICKBOOT_POWERON`. It calls `startForegroundService()` only when `key_auto_start_service` is true. Startup exceptions are caught and logged.

## 7. Shutdown and Cleanup

When destroyed, the Service cancels the speed-collection and screen-off Jobs, hides and releases the Overlay, stops Repository sampling, removes the Foreground Notification, and unregisters the screen broadcast Receiver.

## 8. Android System Constraints

- Android 12+ limits background Foreground Service starts. The current target is API 37.
- Permission requests and ordinary starts should originate from visible UI or another system-approved entry point.
- On Android 15+, the `SYSTEM_ALERT_WINDOW` background-start exemption requires an already visible Overlay. Holding Overlay permission alone does not allow an arbitrary background start; this fix adds no such start path. `BOOT_COMPLETED` has its own exemption and remains subject to service-type restrictions. The Service uses `specialUse` on Android 14+, not `dataSync`.
- `POST_PROMOTED_NOTIFICATIONS` is only for optional Live Update and does not replace ordinary notification permission.
- Changes to start sources, service types, or survival strategies must be checked against target-SDK behavior and Google Play policy.

References: [notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [launching a Foreground Service](https://developer.android.com/develop/background-work/services/fgs/launch), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## 9. Permission Regression Checks

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug lint`. `DisplayPermissionStateTest` covers all 16 combinations of selected notification / Overlay displays and granted / denied display permissions, plus restart, permission changes, toggling, and empty Onboarding selections. These JVM tests validate decision logic, not Android framework behavior.

On a real device, validate Android 12/12L, Android 13+, and Android 15+ / the current target as available:

1. Fresh Onboarding, Overlay only: grant Overlay permission and deny notifications. Only the Overlay permission card is required; "Finish and start" starts monitoring.
2. Both displays selected: with Overlay granted and notifications denied, show the notification limitation and allow start. Repeat after stopping and reopening the app, with notification speed and Live Update preferences retained.
3. Notification only: deny notification permission and verify the notification permission prompt; grant it and verify normal notification speed / Live Update behavior.
4. Deny Overlay permission while Overlay is selected: verify startup asks for Overlay permission regardless of notification permission. Disable Overlay and verify notification-only startup with notifications granted.
5. While Overlay is running, toggle notification speed repeatedly with notifications denied. Sampling and Overlay must continue. Grant notification permission and confirm notification display refreshes even when the speed has not changed.
6. Revoke notifications, stop and restart monitoring, then regrant notifications. Also revoke Overlay permission while running and confirm the window is hidden safely; regrant and confirm it returns on a later sample (the permission is re-checked every 30 seconds and on screen-on). Check logs for startup or notification-posting exceptions.
7. Stop/start repeatedly, leave and return to the Activity, toggle both Quick Settings Tiles, and test screen-off sleep / screen-on resume. Confirm there is only one Overlay and one Foreground Service notification when notifications are permitted.
8. Enable auto-start with Overlay permission granted and notifications denied, reboot, and verify the existing boot path on supported devices. Background startup denial must be caught; Overlay permission alone must not be treated as a new API 35+ background-start exemption.

These device checks remain required before claiming runtime validation; successful compilation, Lint, and JVM tests are not a substitute.


### Automated Android 13 Smoke Check

The `Overlay permission smoke` workflow checks out the exact PR head and runs debug assembly, JVM tests, Lint, and a hardware-accelerated API 33 x86_64 emulator on a standard GitHub-hosted Ubuntu runner. It uses no release signing secrets and has read-only repository permissions. Third-party actions are pinned to full commit SHAs.

The ADB harness in `.github/scripts/overlay_permission_smoke.py` navigates Onboarding and the main Activity using their actual accessible UI. ADB changes only the disposable emulator's system permissions; it never starts `NetworkMonitorService` directly or writes application preferences. It verifies the active Foreground Service and the visible application Overlay above the launcher, and saves per-case screenshots, UI hierarchies, window/service/permission dumps, Logcat, JSON results, and JUnit results as a 14-day workflow artifact. A missing assertion or failed case fails the job.

The check includes notification denial, both display selections, repeated display toggles, stop/start and force-stop/relaunch, grant/revoke/regrant, and blocked starts when selected output permissions are missing. Runtime notification revocation can kill the app process on Android; the test expects that OS transition and then verifies a foreground UI restart with the Overlay still authorized.

This is an emulator regression check, not a substitute for real-device validation. Boot startup, screen-off sleep/resume, Quick Settings Tiles, Live Update on Android 16+, newer target-SDK behavior, and OEM variations remain outside this smoke suite.
