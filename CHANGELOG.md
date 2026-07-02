# Changelog

## v0.1.15-preview.14 - 2026-07-03

- Cleared legacy phone-side `Self-arm recovery` states that could leave the setup row stuck on `Disable pending` after upgrading from earlier self-arm test builds.
- Kept real disable requests tracked with a timestamp so confirmed pending disables still remain visible until the glasses ACKs them.
- Bumped the phone app to `0.1.15-preview.14` / `versionCode 29`; bundled glasses helper remains `0.1.10-preview.7` / `versionCode 17`.

## v0.1.15-preview.13 - 2026-07-02

- Added a Rokid RG 1.21.009 self-arm recovery path for the glasses Accessibility service, including a versioned `/data/local/tmp` watchdog script with `start`, `stop`, `restart`, `status`, and `repair` commands.
- Added glasses-side `SelfArmController` and `BootReceiver` so opening the glasses app or booting the device can repair Accessibility directly when `WRITE_SECURE_SETTINGS` is granted, or use a provisioned authorized ADB key over `127.0.0.1:5555`.
- Added phone-side self-arm provisioning over the existing CXR-L command channel during manual arm/bootstrap, plus disable handling that clears the glasses `armed` flag and tries to stop the watchdog.
- Documented the ADB TCP/key-security model in `docs/self-arm-recovery.md`; no ADB key material is generated, hardcoded, or committed.
- Bumped the phone app to `0.1.15-preview.13` / `versionCode 28` and the bundled glasses helper to `0.1.10-preview.7` / `versionCode 17`.

## v0.1.15-preview.12 - 2026-06-30

- Fixed Android CXR `Auto` language mode so it no longer sends the phone locale as an explicit `SpeechRecognizer` language hint.
- Kept non-`Auto` language chips disabled for Android CXR because Android injected-audio sessions still reject many explicit language tags; API engines keep explicit language selection.
- Deferred bundled glasses helper installs/updates during background notification wakes so Android does not repeatedly disable the glasses Accessibility service after a helper package update.
- Bumped the phone app to `0.1.15-preview.12` / `versionCode 27`; bundled glasses helper remains `0.1.10-preview.6` / `versionCode 16`.

## v0.1.15-preview.11 - 2026-06-29

- Treated Android `SpeechRecognizer` error 11 (`ERROR_SERVER_DISCONNECTED`) as a transient Android CXR recognizer disconnect instead of surfacing the raw error code immediately.
- Retried one interrupted Android CXR recognizer session after the service disconnects, while preserving partial text if Android disconnects after speech was already recognized.
- Added clearer Android CXR messages for recognizer client, server, disconnect, busy, and rate-limit failures.
- Refreshed the glasses setup screen accessibility status while it remains open, so `ACCESSIBILITY ON` / `ENABLE ACCESSIBILITY` reflects changes without leaving and returning.
- Bumped the phone app to `0.1.15-preview.11` / `versionCode 26` and the bundled glasses helper to `0.1.10-preview.6` / `versionCode 16`.

## v0.1.15-preview.10 - 2026-06-28

- Forced `Android CXR` voice replies back to `Auto` transcription language after device testing showed explicit `SpeechRecognizer` language tags can fail with injected CXR audio.
- Disabled and dimmed all non-`Auto` language chips in the phone Speech panel while `Android CXR` is selected.
- Added a safety path that coerces stale saved Android CXR language preferences back to `Auto` before voice capture starts.
- Kept API speech engines on the explicit language selector, so OpenAI, ElevenLabs, and Azure still use their provider-specific language settings.
- Added a debug-only `SpeechRecognizer` language probe for repeatable phone-side testing of support checks and injected-audio `startListening()` behavior.
- Bumped the phone app to `0.1.15-preview.10` / `versionCode 25`; bundled glasses helper remains `0.1.10-preview.5` / `versionCode 15`.

## v0.1.15-preview.9 - 2026-06-28

- Preferred the Google Android speech recognition service for `Android CXR` injected-audio sessions, with on-device/system fallbacks for phones that support them.
- Finalized segmented CXR audio by closing the injected pipe without also forcing `stopListening()`, avoiding recognizers that drop late partial/final results.
- Restored the Android CXR final-result safety timeout to 2.5 seconds after logs showed the 8-second fallback added excessive delay after phrase end.
- Added more Android CXR recognizer diagnostics around ready, partial, final, error, and CXR audio state callbacks.
- Bumped the phone app to `0.1.15-preview.9` / `versionCode 24`; bundled glasses helper remains `0.1.10-preview.5` / `versionCode 15`.

## v0.1.15-preview.8 - 2026-06-23

- Restored Android CXR's microphone foreground-service type while voice capture is active, fixing the `Microphone permission denied` regression introduced by `v0.1.15-preview.7` on recognizers that still gate injected audio behind microphone access.
- Kept the `EXTRA_AUDIO_SOURCE` segmented-session pipe so supported Android recognizers consume the CXR glasses microphone stream.
- Reworded Android CXR permission failures to ask the user to open the phone app instead of reporting a misleading runtime permission denial.
- Bumped the phone app to `0.1.15-preview.8` / `versionCode 23`; bundled glasses helper remains `0.1.10-preview.5` / `versionCode 15`.

## v0.1.15-preview.7 - 2026-06-22

- Changed `Android CXR` voice capture to force Android `SpeechRecognizer` through the injected CXR `EXTRA_AUDIO_SOURCE` segmented session instead of requiring an Android microphone foreground-service upgrade.
- Removed the intermittent `Open phone app for Android CXR mic` failure path on background wake replies while keeping the CXR glasses microphone stream as the only audio source.
- Updated diagnostics and setup copy so `Android CXR` is described as a CXR audio pipe with Android speech permission, not as phone-microphone capture.
- Bumped the phone app to `0.1.15-preview.7` / `versionCode 22`; bundled glasses helper remains `0.1.10-preview.5` / `versionCode 15`.

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
