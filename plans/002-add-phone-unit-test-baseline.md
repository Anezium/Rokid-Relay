# Plan 002: Add A Phone Unit-Test Baseline

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report. Do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 04e9d1e..HEAD -- phone/build.gradle.kts phone/src/main/java/com/anezium/rokidrelay/phone/VoiceActivityDetector.kt phone/src/main/java/com/anezium/rokidrelay/phone/SpeechToTextConfig.kt phone/src/main/java/com/anezium/rokidrelay/phone/TranscriptionLanguageConfig.kt phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt qa/TEST_MATRIX.md`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding. On a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: `plans/001-lock-down-test-notification-receiver.md`
- **Category**: tests
- **Planned at**: commit `04e9d1e`, 2026-06-13

## Why This Matters

The phone module owns the most failure-prone behavior: CXR-L connection,
notification direct replies, voice capture, STT provider selection, and the
GitHub update flow. At the planned commit, Gradle reports
`:phone:testDebugUnitTest NO-SOURCE`, so changes to these flows rely on manual
or device QA only. A small pure unit-test baseline gives future security and
refactor plans a fast guardrail without requiring connected Rokid devices.

## Current State

- `qa/TEST_MATRIX.md` explicitly shows zero phone tests:

```markdown
qa/TEST_MATRIX.md:8
| `phone` | phone | `com.anezium.rokidrelay.phone` | caps_protocol, cxr_l_or_m, debug_hooks, helper_install, input, notifications, voice | 0 |
```

- `phone/build.gradle.kts` has runtime dependencies but no test dependencies:

```kotlin
phone/build.gradle.kts:33
dependencies {
    implementation("com.example.cxrglobal:lib:0.2.0")
    implementation("androidx.core:core:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

- `glasses/build.gradle.kts` is the local pattern for JUnit/Robolectric test dependencies:

```kotlin
glasses/build.gradle.kts:33
dependencies {
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
```

- `VoiceActivityDetector` is pure Kotlin and critical to voice capture:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/VoiceActivityDetector.kt:30
class VoiceActivityDetector(
    private val config: VoiceActivityConfig = VoiceActivityConfig(),
)
```

- `SpeechToTextEngine.fromId` and `TranscriptionLanguage.fromId` are pure
  mapping functions used by app settings:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/SpeechToTextConfig.kt:127
fun fromId(id: String?): SpeechToTextEngine {
    val normalized = id.orEmpty().trim().lowercase()
    return values().firstOrNull { it.id == normalized } ?: ANDROID_CXR
}
```

- `GitHubReleaseUpdate.isNewerThan` is pure update comparison logic:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt:31
fun isNewerThan(installed: InstalledAppVersion): Boolean {
    versionCode?.let { code ->
        if (installed.versionCode > 0L) return code > installed.versionCode
    }
```

Repo conventions to match: place unit tests under
`phone/src/test/java/com/anezium/rokidrelay/phone`, use JUnit 4 like the glasses
module, and keep device-dependent behavior in QA scripts rather than unit tests.

## Commands You Will Need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Phone unit tests | `.\gradlew.bat :phone:testDebugUnitTest` | exit 0; no `NO-SOURCE` for phone tests |
| Existing glasses tests | `.\gradlew.bat :glasses:testDebugUnitTest` | exit 0 |
| Build phone | `.\gradlew.bat :phone:assembleDebug` | exit 0 |

## Scope

**In scope**:
- `phone/build.gradle.kts`
- `phone/src/test/java/com/anezium/rokidrelay/phone/VoiceActivityDetectorTest.kt` (create)
- `phone/src/test/java/com/anezium/rokidrelay/phone/SpeechToTextConfigTest.kt` (create)
- `phone/src/test/java/com/anezium/rokidrelay/phone/TranscriptionLanguageConfigTest.kt` (create)
- `phone/src/test/java/com/anezium/rokidrelay/phone/GitHubUpdateManagerTest.kt` (create)
- `qa/TEST_MATRIX.md`

**Out of scope**:
- Do not add connected-device tests in this plan.
- Do not mock CXR-L, CXR-S, Android `NotificationListenerService`, or live STT providers yet.
- Do not refactor production code unless a pure helper must become `internal` to be testable.

## Git Workflow

- Branch: `advisor/002-phone-test-baseline`
- Commit message style: imperative sentence, for example `Add phone unit test baseline`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Add phone test dependencies

Add JUnit 4 to `phone/build.gradle.kts`, matching the glasses module. Add
Robolectric only if a chosen phone test needs Android framework runtime behavior.
For the initial pure tests below, JUnit should be enough.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest`
-> exit 0. At this point it may still report `NO-SOURCE` until test files are
created.

### Step 2: Add VoiceActivityDetector tests

Create `VoiceActivityDetectorTest.kt` in the phone test package. Cover:

- no bytes received reaches `no-audio-bytes` after `firstByteTimeoutMs`;
- quiet bytes reach `no-vad-speech-timeout` after `initialNoSpeechTimeoutMs`;
- loud PCM marks speech detected;
- after speech, enough silence after `minCaptureMs` returns `silence-after-speech`;
- `maxCaptureMs` returns `safety-max` as a guard.

Use small helper functions inside the test to create little-endian PCM samples.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest --tests "*VoiceActivityDetectorTest"`
-> exit 0 and all new tests pass.

### Step 3: Add STT settings mapping tests

Create `SpeechToTextConfigTest.kt` and `TranscriptionLanguageConfigTest.kt`.
Cover:

- null, blank, and unknown engine IDs fall back to `SpeechToTextEngine.ANDROID_CXR`;
- known engine IDs map to their enum values;
- null, blank, and unknown language IDs fall back to `TranscriptionLanguage.AUTO`;
- Cantonese, Traditional Chinese, and Simplified Chinese preserve the provider
  codes currently documented in `TranscriptionLanguageConfig.kt`.

Do not instantiate `SpeechToTextSettingsStore`; it needs Android `Context` and
is out of scope for this pure baseline.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest --tests "*SpeechToTextConfigTest" --tests "*TranscriptionLanguageConfigTest"`
-> exit 0 and all new tests pass.

### Step 4: Add update version comparison tests

Create `GitHubUpdateManagerTest.kt` with tests for
`GitHubReleaseUpdate.isNewerThan`:

- higher `versionCode` wins over installed version code;
- lower or equal `versionCode` is not newer;
- when release `versionCode` is null, semantic `versionName` comparison works;
- tag-only fallback treats a different tag as newer only after equal version comparison.

Do not call GitHub or download any APK in unit tests.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest --tests "*GitHubUpdateManagerTest"`
-> exit 0 and all new tests pass.

### Step 5: Update QA matrix

Update `qa/TEST_MATRIX.md` so the phone module no longer says `0` tests. Add a
short note that the current phone unit baseline covers pure VAD, settings
mappings, and update version comparison, while CXR/notification/device flows
remain in device QA.

**Verify**:
`rg -n "\`phone\`|VoiceActivityDetector|update version|STT" qa/TEST_MATRIX.md phone/src/test/java -S`
-> matches show the new coverage is documented.

### Step 6: Run the repo checks

Run the full relevant local baseline.

**Verify**:
`.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug`
-> exit 0, with phone tests compiled and executed.

## Test Plan

New tests:

- `VoiceActivityDetectorTest`: VAD thresholds and close reasons.
- `SpeechToTextConfigTest`: engine ID fallback and mapping.
- `TranscriptionLanguageConfigTest`: language ID fallback and provider-specific codes.
- `GitHubUpdateManagerTest`: update comparison rules.

Use the existing glasses tests as style reference: `glasses/src/test/java/com/anezium/rokidrelay/glasses/RelayInputInterpreterTest.kt`.

## Done Criteria

- [ ] `phone/build.gradle.kts` has the minimal test dependencies needed.
- [ ] `phone/src/test/java/com/anezium/rokidrelay/phone` contains meaningful tests.
- [ ] `.\gradlew.bat :phone:testDebugUnitTest` exits 0 and no longer reports `NO-SOURCE`.
- [ ] `.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug` exits 0.
- [ ] `qa/TEST_MATRIX.md` reflects phone unit-test coverage.
- [ ] No connected-device, cloud STT, or CXR behavior is faked in unit tests.
- [ ] `plans/README.md` status row updated.

## STOP Conditions

Stop and report back if:

- Adding JUnit to `phone/build.gradle.kts` causes dependency resolution failures.
- A pure unit test requires Android framework runtime unexpectedly; do not add broad Robolectric coverage without reporting.
- Production code must be substantially refactored before tests can compile.
- A test reveals a production behavior change that is outside this plan's scope.

## Maintenance Notes

This plan intentionally starts small. Future phone tests should move risky
logic out of Android services and into pure helpers, following the successful
glasses-side `RelayInputInterpreter` pattern.

