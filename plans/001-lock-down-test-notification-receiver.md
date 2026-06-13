# Plan 001: Lock Down The Test Notification Receiver

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report. Do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 04e9d1e..HEAD -- phone/src/main/AndroidManifest.xml phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationReceiver.kt phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationPoster.kt phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationHarness.kt phone/build.gradle.kts README.md scripts/rokid-regression.ps1 qa/TEST_MATRIX.md qa/scenarios/notification-direct-reply.yaml`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding. On a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `04e9d1e`, 2026-06-13

## Why This Matters

The phone app currently exposes its test notification harness as an exported
broadcast receiver in the main manifest. That is convenient for ADB and QA, but
it also lets any other app on the phone trigger Relay's test notification flow
in normal builds. The direct-reply PendingIntent path should remain available
to notifications created by Relay itself, while the public ADB trigger should
be debug-only or otherwise explicitly documented as debug-only.

## Current State

- `phone/src/main/AndroidManifest.xml` declares `TestNotificationReceiver` in the main manifest:

```xml
phone/src/main/AndroidManifest.xml:87
<receiver
    android:name=".TestNotificationReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.anezium.rokidrelay.phone.POST_TEST_NOTIFICATION" />
    </intent-filter>
</receiver>
```

- `phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationReceiver.kt` handles both the external test-post action and the direct-reply action:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationReceiver.kt:7
class TestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Constants.ACTION_POST_TEST_NOTIFICATION -> TestNotificationHarness.handlePostIntent(context, intent)
            Constants.ACTION_TEST_REPLY -> TestNotificationHarness.handleReply(context, intent)
        }
    }
}
```

- `phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationPoster.kt` creates an explicit PendingIntent targeting the same receiver for local test notification replies:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationPoster.kt:81
private fun replyAction(context: Context, notificationId: Int, threadIndex: Int): Notification.Action {
    val replyIntent = Intent(context, TestNotificationReceiver::class.java)
        .setAction(Constants.ACTION_TEST_REPLY)
```

- `README.md` and `scripts/rokid-regression.ps1` rely on the public ADB action for QA:

```text
README.md:320
adb shell am broadcast -n com.anezium.rokidrelay.phone/.TestNotificationReceiver -a com.anezium.rokidrelay.phone.POST_TEST_NOTIFICATION

scripts/rokid-regression.ps1:291
"com.anezium.rokidrelay.phone/.TestNotificationReceiver",
"com.anezium.rokidrelay.phone.POST_TEST_NOTIFICATION",
```

Repo conventions to match: changes should be small and Android-native. The
project already keeps debug artifacts out of git via `.gitignore`, and debug
QA lives under `scripts/` and `qa/`.

## Commands You Will Need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Build phone debug | `.\gradlew.bat :phone:assembleDebug` | exit 0 |
| Build glasses debug | `.\gradlew.bat :glasses:assembleDebug` | exit 0 |
| Unit tests | `.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest` | exit 0; phone may still be `NO-SOURCE` until plan 002 |
| Manifest check | `Select-String -Path phone/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml -Pattern "TestNotificationReceiver","POST_TEST_NOTIFICATION","exported"` | debug manifest shows the intended receiver state |

## Scope

**In scope**:
- `phone/src/main/AndroidManifest.xml`
- `phone/src/debug/AndroidManifest.xml` (create if using a debug-only exported receiver)
- `phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationReceiver.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/TestNotificationPoster.kt`
- `README.md`
- `scripts/rokid-regression.ps1`
- `qa/TEST_MATRIX.md`
- `qa/scenarios/notification-direct-reply.yaml`

**Out of scope**:
- Do not change notification capture or direct reply semantics in `ReplyRepository.kt`.
- Do not change the package names or notification channel names.
- Do not remove the diagnostics panel's in-app test notification feature.
- Do not touch ignored `qa/artifacts/**` files.

## Git Workflow

- Branch: `advisor/001-lock-down-test-receiver`
- Commit message style: imperative sentence, matching recent commits such as `Make QA runner exercise connected devices`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Make the main receiver non-public

In `phone/src/main/AndroidManifest.xml`, change the `TestNotificationReceiver`
declaration so normal builds do not expose the `POST_TEST_NOTIFICATION` action
to other apps. The preferred shape is:

- `android:exported="false"` in the main manifest.
- no main-manifest intent-filter for `Constants.ACTION_POST_TEST_NOTIFICATION`.
- keep the receiver class available so app-created PendingIntents for
  `Constants.ACTION_TEST_REPLY` still target it explicitly.

If removing the intent-filter from the main receiver breaks the local
PendingIntent reply path during verification, STOP and report before choosing a
different receiver design.

**Verify**: `.\gradlew.bat :phone:assembleDebug` -> exit 0.

### Step 2: Preserve the ADB QA trigger only for debug builds

If `scripts/rokid-regression.ps1` must keep posting test notifications via ADB,
add a debug-only manifest merge file at `phone/src/debug/AndroidManifest.xml`.
Use the same receiver class, add the `POST_TEST_NOTIFICATION` intent-filter
only in debug, and use manifest merger attributes only if needed to replace
`android:exported`.

Target behavior:

- debug APK: ADB QA command can still post a test notification.
- release APK: no exported `POST_TEST_NOTIFICATION` receiver exists.

If the manifest merger cannot express this cleanly with the existing receiver,
STOP and report rather than introducing a second receiver class without review.

**Verify**:
`.\gradlew.bat :phone:assembleDebug` -> exit 0.
Then inspect the debug merged manifest and confirm the exported debug receiver
state is intentional:
`Select-String -Path phone/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml -Pattern "TestNotificationReceiver","POST_TEST_NOTIFICATION"`.

### Step 3: Update docs and QA script wording

Update `README.md`, `scripts/rokid-regression.ps1`, `qa/TEST_MATRIX.md`, and
`qa/scenarios/notification-direct-reply.yaml` so they describe the ADB broadcast
as a debug-build QA hook. Do not imply it is available in release builds.

If the script has no build-variant awareness, add a short warning message near
the broadcast step that the installed phone app must be a debug build.

**Verify**:
`rg -n "POST_TEST_NOTIFICATION|TestNotificationReceiver|debug build|debug-only" README.md scripts qa phone/src/main/AndroidManifest.xml phone/src/debug -S`
-> matches show the main manifest is locked down and docs/scripts call the ADB
path debug-only.

### Step 4: Run the normal checks

Run the build and existing tests.

**Verify**:
`.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug :glasses:assembleDebug`
-> exit 0.

## Test Plan

- Keep existing glasses tests passing.
- Manual/ADB debug test after installing the debug phone APK: run the documented
  `adb shell am broadcast ... POST_TEST_NOTIFICATION` command and confirm a test
  notification appears.
- Confirm a release build, if built, does not expose the public test action in
  its merged manifest.

## Done Criteria

- [ ] Main manifest no longer exports `POST_TEST_NOTIFICATION` in normal builds.
- [ ] Debug QA path is either preserved via `phone/src/debug/AndroidManifest.xml` or the docs/scripts explicitly use an alternative debug path.
- [ ] `.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug :glasses:assembleDebug` exits 0.
- [ ] `README.md` and `scripts/rokid-regression.ps1` accurately describe the debug-only status.
- [ ] No files outside the in-scope list are modified.
- [ ] `plans/README.md` status row updated.

## STOP Conditions

Stop and report back if:

- The live receiver or manifest code no longer matches the excerpts above.
- Non-exporting the receiver breaks the direct-reply PendingIntent path.
- Preserving ADB debug access requires broad package visibility, a custom permission, or a second production receiver.
- The fix requires changing `ReplyRepository.kt` or the notification protocol.

## Maintenance Notes

Future diagnostic hooks should default to in-app UI or debug-only manifests.
Reviewer should specifically inspect the merged debug and release manifests, not
only the source manifest.

