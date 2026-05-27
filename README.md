# Rokid Relay

Rokid Relay is a split Android app for replying to phone notifications from Rokid Glasses.

## Modules

- `phone`: Android phone relay app.
  - Uses Notification Listener access to detect replyable notifications.
  - Uses CXR-L through the local `../CxrGlobal` wrapper and Hi Rokid Global.
  - Bundles the debug glasses APK as `assets/rokid-relay-glasses.apk`.
  - Runs a foreground connected-device service while relay mode is active.
  - Uses the glasses microphone through CXR for voice replies, then replies with Android `RemoteInput`.
  - Supports Android CXR STT, OpenAI realtime/completed-audio STT, and ElevenLabs realtime/completed-audio STT without a silent phone-mic fallback.
- `glasses`: Rokid glasses setup app plus notification popup.
  - Uses CXR-S `CXRServiceBridge`.
  - Shows a black AR-safe setup screen for enabling Accessibility.
  - Provides a lightweight Accessibility overlay popup for replyable notifications above other glasses apps.
  - Slide/DPAD starts voice reply; tap dismisses the current notification.

## Build

```powershell
.\gradlew.bat :phone:assembleDebug :glasses:assembleDebug
```

Outputs:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

## Install During Development

```powershell
adb -s R5CW12DK1AY install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s 1901092534053723 install -r glasses/build/outputs/apk/debug/glasses-debug.apk
```

## First Run

1. Open Rokid Relay on the phone.
2. Grant Bluetooth and notification permissions when prompted. Grant microphone only if using the `Android CXR` STT engine.
3. Tap `Authorize Hi Rokid`.
4. Tap `Enable notification access` and enable Rokid Relay.
5. Pick a speech-to-text engine:
   - `Android CXR`: uses `SpeechRecognizer` with a CXR PCM pipe from the glasses microphone. Requires microphone permission and a microphone foreground-service type.
   - `OpenAI GPT Realtime Whisper`: streams CXR PCM for live transcript updates.
   - `OpenAI GPT-4o Transcribe` / `GPT-4o mini`: buffers CXR PCM and sends completed audio to OpenAI.
   - `ElevenLabs Scribe v2 Realtime`: streams CXR PCM for live transcript updates.
   - `ElevenLabs Scribe`: buffers CXR PCM and sends completed audio to ElevenLabs.
6. Open Rokid Relay on the glasses if it is not already started.
7. On the glasses app, tap while no notification is shown to open Accessibility settings, then enable `Rokid Relay`.

When the glasses Accessibility service is enabled, Rokid Relay uses `TYPE_ACCESSIBILITY_OVERLAY` only for a small replyable-notification popup. The setup app is just for connection/accessibility status and opening Android Accessibility settings.
The phone relay starts automatically when the phone app opens or when Android delivers the configured autostart events after authorization. If no Hi Rokid token is stored, opening the phone app automatically launches the Hi Rokid authorization screen once; after approval, Relay stores the returned token and starts the service.

## Test Notification

After `Start relay`, tap `Post test notification` in the phone app to create a local replyable notification. You can also trigger it with ADB:

```powershell
adb -s R5CW12DK1AY shell am broadcast -n com.rokid.relay.phone/.TestNotificationReceiver -a com.rokid.relay.phone.POST_TEST_NOTIFICATION
```

The phone status should end with `replyable notification from Rokid Relay`, and the glasses HUD should show `Rokid Relay test`.

After replying from the glasses, the phone app shows `Last sent reply` for the speech-to-text text sent through Android Reply. For the local test notification, it also shows `Last received reply`, which is the exact text received by the test notification receiver.

## Protocol

- Phone to glasses: `rokid_relay.event`
- Glasses to phone: `rokid_relay.command`
- Payload: first `Caps` slot contains JSON.
- Key message types: `notification`, `voice_state`, `reply_result`, `start_voice`, `retry_voice`, `dismiss_notification`, `request_state`.

Keep logs redacted: do not dump full notification text, auth tokens, API keys, MAC, SN, or socket UUIDs.

## Voice / STT Notes

`startAudioStream(1)` is treated as the source of truth for glasses voice replies. If the CXR stream is unavailable, Rokid Relay reports the failure instead of silently opening the phone microphone.

The Android engine intentionally uses `RecognizerIntent.EXTRA_AUDIO_SOURCE` with the CXR PCM pipe. OpenAI and ElevenLabs realtime engines stream the same CXR PCM path over WebSocket for partial transcripts; completed-audio engines transcribe a completed WAV buffer. After any engine produces a final transcript, the HUD shows the text and waits a short length-scaled review countdown before sending. Press OK during the countdown to retry the audio capture.

## CXR-L Global Auth Note

For Global Hi Rokid, do not call the upstream CXR-L authorization helper directly. Use the `Rokid-Apks` pattern: first start the explicit component `com.rokid.sprite.global.aiapp/com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity`, then fallback to action `com.rokid.sprite.aiapp.externalapp.AUTHORIZATION` scoped to `com.rokid.sprite.global.aiapp`.

Keep the phone app launcher icon as a bitmap PNG, like `Rokid-Apks` does with its `@mipmap/ic_launcher` assets. Global Hi Rokid's auth activity casts the caller app icon to `BitmapDrawable`; a vector launcher icon can crash Hi Rokid before the auth screen appears.
