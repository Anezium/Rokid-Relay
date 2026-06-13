# Plan 005: Add Notification Image Previews To The Glasses HUD

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report. Do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat aad085f..HEAD -- phone/build.gradle.kts phone/src/main/java/com/anezium/rokidrelay/phone/Constants.kt phone/src/main/java/com/anezium/rokidrelay/phone/ReplyRepository.kt phone/src/main/java/com/anezium/rokidrelay/phone/RelayBridge.kt phone/src/main/java/com/anezium/rokidrelay/phone/RelayNotificationListener.kt phone/src/main/java/com/anezium/rokidrelay/phone/NotificationSettingsStore.kt phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt phone/src/main/java/com/anezium/rokidrelay/phone/DiagnosticsPanel.kt phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationPoster.kt phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationHarness.kt glasses/src/main/java/com/anezium/rokidrelay/glasses/Constants.kt glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayBridge.kt glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayHudController.kt glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayHudView.kt README.md qa/TEST_MATRIX.md`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding. On a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: MED
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `aad085f`, 2026-06-13

## Why This Matters

Rokid Relay currently forwards replyable notifications as text-only HUD
events. That keeps the protocol simple, but it drops an important class of
notification content: photos, rich message attachments, and BigPicture-style
notifications. Adding small, explicitly enabled image previews lets the user
glance at image-bearing notifications on the glasses without opening the phone,
while keeping the relay lean enough for CXR and a 480 x 640 AR display.

This is a privacy-sensitive feature. The implementation must keep image bytes
out of logs, use small bounded thumbnails, avoid app-icon noise, and degrade to
text-only whenever extraction or CXR media transfer fails.

## Current State

- The phone module captures only text fields in `ReplyRepository.PendingReply`:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/ReplyRepository.kt:23
data class PendingReply(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val revision: String,
    val notificationKey: String,
    val actionIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
    val capturedAtMs: Long,
)
```

- `ReplyRepository.capture` extracts title and notification text, then decides
  whether the visible content changed. There is no image extraction and the
  `revision` only reflects message count/timestamps or `notification.when`:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/ReplyRepository.kt:52
val title = extras.charSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
val messageLimit = NotificationSettingsStore(context).threadMessageLimit()
val text = notificationText(extras, messageLimit)
val revision = notificationRevision(sbn, extras)
```

- Phone-to-glasses notification payloads are JSON in `Caps`; `sendNotification`
  and `sendInbox` only send text metadata:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/RelayBridge.kt:152
fun sendNotification(reply: ReplyRepository.PendingReply) {
    ...
    val json = JSONObject()
        .put("version", Constants.PROTOCOL_VERSION)
        .put("type", "notification")
        .put("source", "phone")
        .put("notificationId", reply.id)
        .put("appPackage", reply.packageName)
        .put("appLabel", reply.appLabel)
        .put("title", reply.title)
        .put("text", reply.text)
        .put("canReply", true)
        .appendUserSettings()
```

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/RelayBridge.kt:208
fun sendInbox() {
    ...
    notifications.put(
        JSONObject()
            .put("notificationId", reply.id)
            .put("appPackage", reply.packageName)
            .put("appLabel", reply.appLabel)
            .put("title", reply.title)
            .put("text", reply.text)
            .put("canReply", true),
    )
```

- The local CXR-L wrapper already exposes stream sending. Use this if it works
  on device; do not add base64 into normal notification JSON unless the stream
  path fails during the spike:

```kotlin
../CxrGlobal/lib/src/main/java/com/example/cxrglobal/CXRLink.kt:299
fun sendCustomCmd(key: String, payload: ByteArray): Int? =
    tryCall { service?.sendCustomCmd(key, payload) }

../CxrGlobal/lib/src/main/java/com/example/cxrglobal/CXRLink.kt:305
fun sendCustomCmd(key: String, payload: Caps, stream: ByteArray): Int? =
    tryCall { service?.sendCustomCmdStream(key, payload.serialize(), stream) }
```

- The glasses side currently subscribes only to `rokid_relay.event`, decodes
  event bytes as text or serialized `Caps`, and builds a text-only
  `NotificationModel`:

```kotlin
glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayBridge.kt:32
val result = cxr.subscribe(Constants.KEY_EVENT, msgCallback)

glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayBridge.kt:108
override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
    val payload = decodePayload(caps, data)
    ...
    main.post { handleEvent(payload) }
}

glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayBridge.kt:155
RelayHudController.showNotification(
    RelayHudView.NotificationModel(
        id = obj.optString("notificationId"),
        app = obj.optString("appLabel", obj.optString("appPackage")),
        title = obj.optString("title"),
        text = obj.optString("text"),
    ),
)
```

- `RelayHudView.NotificationModel` and popup rendering have no image slot:

```kotlin
glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayHudView.kt:26
data class NotificationModel(
    val id: String,
    val app: String,
    val title: String,
    val text: String,
)

glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayHudView.kt:258
private fun renderPopup() {
    ...
    val pages = notificationPages(model.text)
    val pageIndex = notificationPage.coerceIn(0, pages.lastIndex)
    val hasVoiceTranscript = renderMessageBody(pages[pageIndex])
```

- The phone diagnostics panel can post plain, long, and thread notifications,
  but it cannot post an image-bearing test notification:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/DiagnosticsPanel.kt:63
smallButton("Single test", ButtonTone.Secondary) {
    TestNotificationHarness.postTestNotification(context)
    onStatusChanged()
}
```

Repo conventions to match:

- Plain Android Views, Kotlin, no Compose.
- Phone settings live in `NotificationSettingsStore` and `MainActivity`.
- Phone-to-glasses protocol uses stable channel constants, JSON metadata in
  `Caps`, tolerant parsing, and no raw user notification bodies in logs.
- Glasses UI must stay AR-safe: black root, thin outline, small high-contrast
  elements, no large opaque panels. Image previews are the exception requested
  by this feature; keep them small and bounded.

## Commands You Will Need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Phone unit tests | `.\gradlew.bat :phone:testDebugUnitTest` | exit 0; new image extractor tests pass |
| Glasses unit tests | `.\gradlew.bat :glasses:testDebugUnitTest` | exit 0 |
| Full debug build | `.\gradlew.bat :phone:assembleDebug :glasses:assembleDebug` | exit 0; phone debug APK bundles the updated glasses APK |
| Source audit | `rg -n "imagePreview|notification_image|KEY_MEDIA|sendCustomCmd\\(|sendCustomCmdStream|Base64|payloadLen|textLen|Bitmap|ImageView" phone/src glasses/src -S` | shows bounded image path and no raw image/text logging |

## Suggested Executor Toolkit

- Use the `rokid-glasses-dev` skill if available. Relevant references:
  `references/cxr-s.md`, `references/cxr-l-app-install.md`,
  `references/protocols-and-safety.md`, and `references/ui-input-debug.md`.
- Read `../CxrGlobal/ARCHITECTURE.md` before using `sendCustomCmdStream`.

## Scope

**In scope**:

- `phone/build.gradle.kts`
- `phone/src/main/java/com/anezium/rokidrelay/phone/Constants.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/NotificationImageExtractor.kt` (create)
- `phone/src/main/java/com/anezium/rokidrelay/phone/ReplyRepository.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/RelayBridge.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/NotificationSettingsStore.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/DiagnosticsPanel.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationPoster.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationHarness.kt`
- `phone/src/test/java/com/anezium/rokidrelay/phone/NotificationImageExtractorTest.kt` (create)
- `glasses/src/main/java/com/anezium/rokidrelay/glasses/Constants.kt`
- `glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayBridge.kt`
- `glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayHudController.kt`
- `glasses/src/main/java/com/anezium/rokidrelay/glasses/RelayHudView.kt`
- `glasses/src/test/java/com/anezium/rokidrelay/glasses/RelayNotificationImageCacheTest.kt` (create if a pure cache class is added)
- `README.md`
- `qa/TEST_MATRIX.md`

**Out of scope**:

- Do not modify `../CxrGlobal` unless the stream spike proves the existing
  wrapper method is unusable. If that happens, STOP and report.
- Do not add Glide, Coil, Picasso, Firebase, GMS, or any heavy image library.
- Do not persist notification images to disk.
- Do not forward full-resolution images.
- Do not log image bytes, content URIs, notification body text, sender names
  beyond existing redacted metadata, MAC addresses, SNs, tokens, or API keys.
- Do not change voice reply semantics.
- Do not try to suppress Hi Rokid's own notification mirroring in this plan.

## Git Workflow

- Branch: `advisor/005-notification-image-previews`
- Commit message style: imperative sentence, matching recent commits such as
  `Fix glasses input and notification paging`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Add a bounded phone-side image extractor

Create `phone/src/main/java/com/anezium/rokidrelay/phone/NotificationImageExtractor.kt`.

Target shape:

- Define a small model, for example:

```kotlin
data class NotificationImagePreview(
    val id: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val source: String,
)
```

- Provide one public function similar to:

```kotlin
object NotificationImageExtractor {
    fun extract(context: Context, notification: Notification): NotificationImagePreview?
}
```

- Source priority:
  1. Latest `Notification.MessagingStyle.Message` whose `dataMimeType`
     starts with `image/` and has a non-null `dataUri`.
  2. BigPicture-style extras from `Notification.EXTRA_PICTURE` and, when
     present on this SDK, icon-backed picture extras.
  3. Optional fallback to notification large icon only if it is clearly not
     just the app icon. If uncertain, skip the large-icon fallback in v1.

- Decode rules:
  - Use platform APIs only (`ImageDecoder`, `BitmapFactory`, `Icon.loadDrawable`,
    `Canvas` for drawables).
  - Cap preview dimensions to a constant such as `MAX_PREVIEW_LONG_EDGE_PX = 360`.
  - Compress to JPEG or WebP at a bounded quality such as 70.
  - Reject previews larger than `MAX_PREVIEW_BYTES`, recommended 80 KB.
  - Reject images smaller than roughly 24 x 24 after decode; they are likely
    icons or bad placeholders.
  - Compute `id` from SHA-256 of the compressed bytes, truncated similarly to
    `ReplyRepository.stableId`.
  - Never log a content URI or image bytes. At most log source, dimensions,
    byte length, and truncated hash.

If Android SDK constants differ from memory, inspect the installed Android SDK
or compile errors and use the constants that exist for `compileSdk = 36`.

**Verify**: `.\gradlew.bat :phone:testDebugUnitTest` -> exit 0, or if no tests
exist yet for this class, `.\gradlew.bat :phone:assembleDebug` -> exit 0.

### Step 2: Wire image metadata into `ReplyRepository`

Update `ReplyRepository.PendingReply` to include an optional image preview:

```kotlin
val imagePreview: NotificationImagePreview? = null
```

In `capture`, call the extractor only when
`NotificationSettingsStore(context).notificationImagePreviewsEnabled()` is
true. Keep text capture unchanged.

Update `hasSameVisibleContent` so image changes can show a new popup:

- Include image id/source/dimensions in the visible-content comparison.
- Or include the image id in `revision`, then keep comparison based on
  `revision`.

Expected behavior:

- Text-only notifications behave exactly as before.
- If a notification's text is unchanged but its image changes, `contentChanged`
  becomes true.
- If image extraction fails, the notification is still captured as text-only.

**Verify**: `.\gradlew.bat :phone:testDebugUnitTest` -> exit 0.

### Step 3: Add a phone setting and UI affordance

Add a preference key in `Constants.kt`, for example:

```kotlin
const val PREF_NOTIFICATION_IMAGE_PREVIEWS_ENABLED = "notification_image_previews_enabled"
```

Add methods and a default in `NotificationSettingsStore`:

- `notificationImagePreviewsEnabled(): Boolean`
- `saveNotificationImagePreviewsEnabled(enabled: Boolean)`
- `DEFAULT_NOTIFICATION_IMAGE_PREVIEWS_ENABLED = false`

Use default `false` unless the product owner explicitly asks for image previews
to be enabled by default. This is a privacy-sensitive feature and should be an
ability the user turns on.

In `MainActivity`, add a checkbox in the Notifications -> Popup panel near the
existing forwarding controls. Match the current checkbox pattern used for:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:269
pauseForwardingWhenScreenOnCheckBox = settingsCheckBox("Pause while phone screen is on") {
    savePauseForwardingWhenScreenOn(pauseForwardingWhenScreenOnCheckBox.isChecked)
}
```

When the value changes:

- Save it through `NotificationSettingsStore`.
- Call `RelayBridge.sendInbox()` so image metadata availability is refreshed.
- Show a short toast/notice using the existing `toastLine` pattern.

Do not add long explanatory in-app copy. A concise label such as
`Image previews on glasses` is enough.

**Verify**: `.\gradlew.bat :phone:assembleDebug` -> exit 0.

### Step 4: Add a media channel and send bounded image previews

In both `phone` and `glasses` `Constants.kt`, add:

```kotlin
const val KEY_MEDIA = "rokid_relay.media"
```

In `phone/src/main/java/.../RelayBridge.kt`:

- Add a private `sendNotificationImage(reply: PendingReply): Boolean`.
- Use `link?.sendCustomCmd(Constants.KEY_MEDIA, caps, preview.bytes)` where
  `caps` contains small JSON metadata:

```json
{
  "version": 1,
  "type": "notification_image",
  "source": "phone",
  "notificationId": "...",
  "imageId": "...",
  "mimeType": "image/jpeg",
  "width": 320,
  "height": 180,
  "byteSize": 24576
}
```

- Send normal notification JSON even when image sending fails.
- In `sendNotification`, include optional image metadata in the notification
  JSON:

```json
"image": {
  "imageId": "...",
  "mimeType": "image/jpeg",
  "width": 320,
  "height": 180,
  "byteSize": 24576
}
```

- In `sendInbox`, include the same metadata but never include bytes. This lets
  inbox rows show that an image exists and lets details render a cached image
  if the popup image already arrived.

Order:

1. Send the notification JSON first so text appears immediately.
2. Send image media next.
3. If media send succeeds, the glasses cache update will rerender the visible
   notification.

This order avoids hiding a replyable text notification behind a media-transfer
failure. It does allow a short text-first/image-second update, which is an
acceptable v1 tradeoff.

If `sendCustomCmd(Constants.KEY_MEDIA, caps, preview.bytes)` does not compile,
re-check `../CxrGlobal/lib/src/main/java/com/example/cxrglobal/CXRLink.kt`.
If it compiles but device testing shows `data` never reaches glasses, STOP and
report with logs; do not switch to base64 without review.

**Verify**:
`rg -n "KEY_MEDIA|notification_image|sendCustomCmd\\(Constants.KEY_MEDIA|imageId|byteSize" phone/src/main/java glasses/src/main/java -S`
-> matches in the new media protocol.
Then `.\gradlew.bat :phone:assembleDebug` -> exit 0.

### Step 5: Receive and cache image media on glasses

In `glasses/src/main/java/.../RelayBridge.kt`:

- Subscribe to both `Constants.KEY_EVENT` and `Constants.KEY_MEDIA`.
- Do not pass media bytes through `decodePayload`; that function treats `data`
  as text or serialized `Caps`, which is correct for events but wrong for JPEG
  or WebP bytes.
- Add media handling in `MsgCallback.onReceive`:
  - If `msgType == Constants.KEY_MEDIA`, parse metadata from `caps`, validate
    `type == "notification_image"`, validate byte count is within the expected
    max, copy `data`, and decode/cache it off the main thread.
  - If `msgType == Constants.KEY_EVENT`, keep the current text-event path.

Add a small cache, either in `RelayHudController` or a new
`RelayNotificationImageCache` object:

- Key by `imageId`.
- Store decoded `Bitmap` plus metadata.
- Limit entries, for example 12 images.
- Limit total bytes or dimensions; reject anything over the phone-side max.
- Expose a cache version or callback so `RelayHudView` rerenders when the image
  for the current notification arrives.

`RelayHudController.State` can add `imageCacheVersion: Long = 0L` if that is
the simplest way to cause attached views to rerender.

**Verify**: `.\gradlew.bat :glasses:testDebugUnitTest` -> exit 0.

### Step 6: Extend glasses notification models and render small previews

Extend `RelayHudView.NotificationModel` with optional image metadata, for
example:

```kotlin
val imageId: String = "",
val imageMimeType: String = "",
val imageWidth: Int = 0,
val imageHeight: Int = 0,
```

Update glasses `RelayBridge.handleEvent` for both `notification` and `inbox`
JSON to read optional image metadata tolerantly. Old phone builds must still
work.

In `RelayHudView`:

- Add an `ImageView` only in popup/overlay mode, probably between `titleLabel`
  and `messageLabel`.
- Use a black background and `ScaleType.FIT_CENTER`.
- Bound it tightly:
  - overlay popup max height roughly 120-150 dp,
  - width match parent,
  - visibility `GONE` when there is no image or image not cached.
- When an image is visible, reduce message text max lines from 9 to roughly 4
  or 5 so the popup does not grow beyond the glasses viewport.
- In inbox list rows, do not render thumbnails; show a compact indicator in
  the preview text only if it fits, such as `[img]`.
- In inbox detail, render the cached image if present; otherwise show text-only.

Keep the existing voice transcript behavior higher priority than image display:
when voice recognition/review is active, hide the image and give the transcript
the space.

**Verify**: `.\gradlew.bat :glasses:testDebugUnitTest :glasses:assembleDebug` -> exit 0.

### Step 7: Add a diagnostic image notification

Extend the debug diagnostics path so the feature can be tested without waiting
for a third-party app:

- Add `TestNotificationPoster.postImage(...)`.
- Generate a small in-memory bitmap with simple colored shapes/text. Do not
  add a binary asset unless necessary.
- Build a replyable notification with `Notification.BigPictureStyle`.
- Add a Diagnostics button such as `Image test` next to `Single test`.
- Add a harness method in `TestNotificationHarness`.

The test notification must still include a `RemoteInput` reply action, because
Rokid Relay intentionally ignores non-replyable notifications.

**Verify**: `.\gradlew.bat :phone:assembleDebug` -> exit 0.

### Step 8: Add tests around extraction, sizing, and protocol tolerance

Add phone-side tests using Robolectric. If `phone` does not already have
Robolectric, add:

```kotlin
testImplementation("org.robolectric:robolectric:4.13")
```

Recommended tests in `NotificationImageExtractorTest.kt`:

- BigPicture notification extracts a preview with non-empty bytes.
- Extracted preview long edge is <= the configured max.
- Extracted preview byte size is <= `MAX_PREVIEW_BYTES`.
- Text-only notification returns null.
- Disabled setting path in `ReplyRepository.capture` does not extract images
  and still captures text. If this test is too Android-heavy for the repository
  object, test the pure setting/extractor boundary separately.

Add glasses-side tests only for pure pieces:

- Image metadata JSON missing/unknown fields does not break notification parse.
- Cache eviction keeps max entries.
- Invalid/oversized image payload is rejected.

Do not try to pixel-test the `ImageView` in local unit tests unless it is
straightforward; device screenshots are the final visual gate.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest :glasses:testDebugUnitTest` -> exit 0.

### Step 9: Update docs, QA matrix, and versions

Update `README.md`:

- In "What it does", mention optional small image previews for image-bearing
  replyable notifications.
- In "Protocol", add `rokid_relay.media` and the `notification_image` payload.
- In "Limitations", mention previews are downscaled, transient, not persisted,
  and only sent when enabled.
- In "Diagnostics", mention the image test button.

Update `qa/TEST_MATRIX.md`:

- Add an image notification scenario or extend `notification-direct-reply` with
  an image variant.
- Include device checks: image appears in popup, text still pages, reply still
  works, and no raw image bytes/URIs appear in logs.

Bump app versions:

- Bump `glasses` `versionCode` and `versionName`.
- Bump `phone` `versionCode` and `versionName`.

This matters because the phone bundles the glasses APK and the companion
deployment path uses helper version/fingerprint state. If the glasses version
is not bumped, a phone update may leave an older glasses helper installed.

**Verify**:
`.\gradlew.bat :phone:assembleDebug :glasses:assembleDebug` -> exit 0.

### Step 10: Run a short real-device smoke test

Install the debug phone APK on the phone. Let the phone upload/start the
updated glasses helper through the existing Hi Rokid path.

Run:

1. Enable `Image previews on glasses` in the phone Notifications page.
2. Use Diagnostics -> `Image test`.
3. Confirm the glasses popup appears with app/title, a small image preview, and
   readable text.
4. Tap/OK to start voice reply; confirm the image hides or yields space to the
   voice transcript/review state.
5. Send or cancel the reply; confirm the reply flow still works.
6. Open inbox and confirm the image-bearing notification does not break list
   navigation.
7. Capture logs and verify only lengths/hashes/source names are logged.

Useful commands:

```powershell
adb logcat -d -s RokidRelayBridge:* RelayBridge:* RelayAccessibility:* RelayReplyRepo:* > relay-image-smoke-logcat.txt
adb shell screencap -p /sdcard/Download/rokid-relay-image.png
adb pull /sdcard/Download/rokid-relay-image.png .
```

**Verify**: the screenshot shows a bounded image preview and no popup overlap;
the log file contains no raw image bytes, content URIs, notification body dumps,
or device identifiers.

## Test Plan

- Phone unit tests for extraction bounds, BigPicture extraction, null/text-only
  behavior, and setting-gated capture.
- Glasses unit tests for media metadata parsing/cache rejection/eviction if
  those pieces are factored into pure helpers.
- Existing tests:
  `.\gradlew.bat :phone:testDebugUnitTest :glasses:testDebugUnitTest`.
- Build:
  `.\gradlew.bat :phone:assembleDebug :glasses:assembleDebug`.
- Device smoke with Diagnostics image notification and a glasses screenshot.

## Done Criteria

- [ ] Image previews are opt-in from the phone Notifications UI.
- [ ] `ReplyRepository` captures text-only notifications exactly as before.
- [ ] Image-bearing replyable notifications show a small preview on the glasses
      when enabled.
- [ ] If extraction, decode, or CXR media transfer fails, the notification still
      shows as text-only.
- [ ] Normal notification JSON does not contain base64 image data.
- [ ] Media bytes are sent only through the media path and are bounded to the
      configured max.
- [ ] Inbox JSON carries image metadata only, never image bytes.
- [ ] Logs contain only image source/dimensions/byte length/truncated hash, not
      raw content URIs or bytes.
- [ ] `.\gradlew.bat :phone:testDebugUnitTest :glasses:testDebugUnitTest` exits 0.
- [ ] `.\gradlew.bat :phone:assembleDebug :glasses:assembleDebug` exits 0.
- [ ] Phone and glasses version codes are bumped.
- [ ] `README.md` and `qa/TEST_MATRIX.md` document the feature and QA path.
- [ ] No files outside the in-scope list are modified.
- [ ] `plans/README.md` status row updated.

## STOP Conditions

Stop and report back if:

- The live code no longer matches the current-state excerpts above.
- `CXRLink.sendCustomCmd(key, Caps, stream)` compiles but the glasses never
  receive image bytes in `MsgCallback.onReceive(..., data)`.
- Device testing shows media payloads larger than roughly 80 KB make CXR-L
  unreliable or delay text notifications.
- Notification image extraction requires persisting content URIs or image files.
- A target app's image URI cannot be read by the notification listener without
  broad storage permissions.
- The feature requires adding a heavy image loading library.
- The popup cannot fit image + readable text on a 480 x 640 glasses display
  without overlap.
- Any log path would expose user image data, content URIs, or device identity.

## Maintenance Notes

Reviewers should scrutinize bounds and fallback behavior more than the happy
path. The safest implementation is text-first: image support must never prevent
replyable notifications from reaching the glasses.

Future work can add lazy image requests for inbox detail, per-app image
policies, or a base64 chunk fallback, but those are deliberately deferred. Do
not add them unless the stream path is proven unreliable and the product owner
accepts the extra protocol complexity.
