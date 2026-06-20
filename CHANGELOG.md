# Changelog

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
