# Changelog

## v0.1.15-preview.6 - 2026-06-21

- Added Android speech recognition service visibility so `Android CXR` can reliably discover the phone speech engine on Android 11+ target builds.
- Blocked `Android CXR` setup when the phone is below Android 13, the microphone permission is missing, or Android 14+ lacks the Companion Device link required for background microphone foreground service access.
- Preserved the BLE wake reply `notificationId` on the phone and automatically started voice capture once CXR-L and the glasses helper are ready, even if the glasses-side retry window is slow.
- Increased the Android CXR first-audio wait window and set the glasses BLE wake reply timeout to 20 seconds to tolerate slower just-woken CXR-L audio startup without making the user wait too long.
- Bumped the phone app to `0.1.15-preview.6` / `versionCode 21` and the bundled glasses helper to `0.1.10-preview.5` / `versionCode 15`.

## v0.1.15-preview.5 - 2026-06-20

- Replaced the `preview.4` pending-inbox CXR-L keepalive with a BLE wake bridge.
- The phone now arms a low-power BLE wake GATT endpoint while Relay is enabled, without keeping CXR-L open.
- When the glasses inbox is visible after the phone has gone to sleep, tapping reply sends a BLE wake request, the phone opens CXR-L, and the glasses continue the voice reply once the link returns.
- Refreshed active phone notifications on BLE wake so old inbox reply IDs can recover their Android `RemoteInput` action before voice capture starts.
- Added Bluetooth advertise permission handling on the phone and Bluetooth scan/connect permission handling on the glasses helper.
- Bumped the phone app to `0.1.15-preview.5` / `versionCode 20` and the bundled glasses helper to `0.1.10-preview.4` / `versionCode 14`.

## v0.1.15-preview.4 - 2026-06-20

- Kept CXR-L awake while the phone still has pending replyable inbox entries, so older glasses inbox items can still start voice reply after the original two-minute idle window.
- Retained the glasses-side sleep fallback for cases where the phone link is genuinely unavailable.
- Bumped the phone app to `0.1.15-preview.4` / `versionCode 19` and the bundled glasses helper to `0.1.10-preview.3` / `versionCode 13`.

## v0.1.15-preview.3 - 2026-06-20

- Kept the glasses inbox visible when the phone enters wake-on-notification sleep.
- Marked sleeping inbox state in the HUD and blocked reply start until the next notification wakes the phone link.
- Bumped the phone app to `0.1.15-preview.3` / `versionCode 18` and the bundled glasses helper to `0.1.10-preview.2` / `versionCode 12`.

## v0.1.15-preview.2 - 2026-06-20

- Added a `phone_sleeping` event before the phone service disconnects CXR-L.
- Updated the glasses helper so stale inbox entries are cleared when the phone sleeps, and reply controls no longer optimistically enter voice mode after the link is gone.
- Bumped the phone app to `0.1.15-preview.2` / `versionCode 17` and the bundled glasses helper to `0.1.10-preview.1` / `versionCode 11`.

## v0.1.15-preview.1 - 2026-06-20

- Switched the phone relay to wake-on-notification mode: `Start` arms Relay, replyable notifications wake CXR, and the service sleeps again after the reply window.
- Stopped boot, app update, Bluetooth reconnect, notification-listener connection, and normal app open from keeping CXR-L running continuously.
- Kept microphone foreground mode limited to active Android CXR voice capture.
- Increased disconnected CXR-L reconnect backoff to reduce idle retry churn.
- Updated setup copy and docs to describe armed/asleep behavior.
- Bumped the phone app to `0.1.15-preview.1` / `versionCode 16`; bundled glasses helper remains `0.1.9` / `versionCode 10`.

## v0.1.14 - 2026-06-14

- Added opt-in notification image previews on the glasses HUD.
- Added phone-side image extraction for notification large icons, MessagingStyle images, BigPicture images, and test notifications.
- Added the `rokid_relay.media` stream channel plus a bounded glasses image cache.
- Added a diagnostics image test that posts a Rokid preview image and keeps direct replies working.
- Updated the project logo to the white Rokid wordmark on black.
- Bumped the phone app to `0.1.14` / `versionCode 15` and the bundled glasses helper to `0.1.9` / `versionCode 10`.
- Added tests, docs, and QA coverage for the image-preview path.

## v0.1.13 - 2026-06-11

- Improved glasses input reliability, notification paging, voice configuration, and repeatable QA.
- Corrected the published package so the bundled glasses helper version matched the release notes.
