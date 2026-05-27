# Rokid Relay

Rokid Relay is a split Android app for replying to phone notifications from Rokid Glasses.

## Modules

- `phone`: Android phone relay app.
  - Uses Notification Listener access to detect replyable notifications.
  - Uses CXR-L through the local `../CxrGlobal` wrapper and Hi Rokid Global.
  - Bundles the debug glasses APK as `assets/rokid-relay-glasses.apk`.
  - Runs a foreground service while relay mode is active.
  - Starts Android `SpeechRecognizer` when the glasses request voice input, then replies with Android `RemoteInput`.
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
2. Grant microphone, Bluetooth, and notification permissions when prompted.
3. Tap `Authorize Hi Rokid`.
4. Tap `Enable notification access` and enable Rokid Relay.
5. Return to Rokid Relay and tap `Start relay`.
6. Open Rokid Relay on the glasses if it is not already started.
7. On the glasses app, tap while no notification is shown to open Accessibility settings, then enable `Rokid Relay`.

When the glasses Accessibility service is enabled, Rokid Relay uses `TYPE_ACCESSIBILITY_OVERLAY` only for a small replyable-notification popup. The setup app is just for connection/accessibility status and opening Android Accessibility settings.

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
- Key message types: `notification`, `voice_state`, `reply_result`, `start_voice`, `dismiss_notification`, `request_state`.

Keep logs redacted: do not dump full notification text, auth tokens, MAC, SN, or socket UUIDs.

## CXR-L Global Auth Note

For Global Hi Rokid, do not call the upstream CXR-L authorization helper directly. Use the `Rokid-Apks` pattern: first start the explicit component `com.rokid.sprite.global.aiapp/com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity`, then fallback to action `com.rokid.sprite.aiapp.externalapp.AUTHORIZATION` scoped to `com.rokid.sprite.global.aiapp`.

Keep the phone app launcher icon as a bitmap PNG, like `Rokid-Apks` does with its `@mipmap/ic_launcher` assets. Global Hi Rokid's auth activity casts the caller app icon to `BitmapDrawable`; a vector launcher icon can crash Hi Rokid before the auth screen appears.
