# Plan 004: Extract The Phone Update Workflow Out Of MainActivity

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report. Do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 04e9d1e..HEAD -- phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt phone/src/test/java/com/anezium/rokidrelay/phone`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding. On a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: MED
- **Depends on**: `plans/003-validate-downloaded-update-apks.md`
- **Category**: tech-debt
- **Planned at**: commit `04e9d1e`, 2026-06-13

## Why This Matters

`MainActivity.kt` is currently doing too many jobs: building four pages of UI,
requesting permissions, handling Hi Rokid authorization, rendering diagnostics,
managing STT settings, and running the GitHub update workflow. The update flow
is a good first extraction target because it is self-contained and already has
its own `GitHubUpdateManager` data types. Moving update state and side effects
out of `MainActivity` reduces future merge conflicts and makes the update logic
easier to test after plan 003.

## Current State

- `MainActivity` is the phone app's central Activity:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:38
class MainActivity : Activity() {
```

- Update-specific fields live alongside setup, notification, speech, and tab UI fields:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:45
private lateinit var updateManager: GitHubUpdateManager
private lateinit var setupRows: LinearLayout
...
private lateinit var updatePage: ScrollView
...
private lateinit var updateSummaryText: TextView
private lateinit var updateCurrentText: TextView
private lateinit var updateLatestText: TextView
private lateinit var updateNotesText: TextView
private lateinit var updateButton: Button
private lateinit var updateReleaseButton: Button
```

- Update state also lives in `MainActivity`:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:104
private var updateState = AppUpdateUiState()
private var notificationFontSizeInputSaving = false
private var updateNotesExpanded = false
private var lastRenderedReleaseNotes = ""
```

- The Activity builds the update page inline:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:214
updatePage = page {
    addView(header("Update"), matchWrap())
```

- The Activity also owns update rendering, background threads, download, and installer launch:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:1207
private fun renderUpdateStatus() {

phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:1272
private fun checkForUpdates(downloadIfAvailable: Boolean = false) {

phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:1329
private fun downloadAndInstallUpdate() {

phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:1372
private fun openDownloadedUpdateInstaller() {
```

- Existing UI style helpers are private to `MainActivity`, so this plan should
  not attempt a full UI-component extraction unless those helpers are moved
  deliberately:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:884
private fun buttonRow(vararg buttons: Button): LinearLayout =

phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:895
private fun smallButton(label: String, tone: ButtonTone, onClick: () -> Unit): Button =

phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:935
private fun bodyText(): TextView =
```

Repo conventions to match: programmatic Android views, short status strings,
`Handler(Looper.getMainLooper())` for UI updates, and thread names like
`RokidRelayUpdateCheck`.

## Commands You Will Need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Phone tests | `.\gradlew.bat :phone:testDebugUnitTest` | exit 0 |
| Build phone | `.\gradlew.bat :phone:assembleDebug` | exit 0 |
| Existing glasses tests | `.\gradlew.bat :glasses:testDebugUnitTest` | exit 0 |

## Scope

**In scope**:
- `phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/PhoneUpdateController.kt` (create)
- `phone/src/test/java/com/anezium/rokidrelay/phone/PhoneUpdateControllerTest.kt` (create if a pure helper is exposed)
- `phone/src/test/java/com/anezium/rokidrelay/phone/GitHubUpdateManagerTest.kt`

**Out of scope**:
- Do not redesign the phone UI.
- Do not extract speech, notification, setup, or diagnostics panels in this plan.
- Do not change GitHub release parsing or APK validation behavior from plan 003.
- Do not move shared colors/buttons into a design system unless absolutely required for compilation.

## Git Workflow

- Branch: `advisor/004-extract-phone-update-workflow`
- Commit message style: imperative sentence, for example `Extract phone update workflow`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Introduce PhoneUpdateController

Create `PhoneUpdateController.kt` in the phone package. Move update state and
side-effect methods out of `MainActivity` into this controller:

- `updateManager`
- `updateState`
- `refreshInstalledUpdateState`
- `checkForUpdates`
- `downloadAndInstallUpdate`
- `openDownloadedUpdateInstaller`
- the `AppUpdateUiState.toGitHubReleaseUpdate` conversion, if still present
  after plan 003

Constructor shape should be explicit and small, for example:

```kotlin
class PhoneUpdateController(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (AppUpdateUiState) -> Unit,
)
```

The controller should own `GitHubUpdateManager(context.applicationContext)`.
It should call `onStateChanged(state)` whenever status changes. Keep network,
download, hash, and installer operations out of `MainActivity`.

**Verify**:
`.\gradlew.bat :phone:assembleDebug`
-> exit 0.

### Step 2: Wire MainActivity to the controller

In `MainActivity.kt`:

- replace `private lateinit var updateManager` with `private lateinit var updateController`;
- replace direct `updateState = ...` update-flow mutations with controller calls;
- keep `renderUpdateStatus` in `MainActivity` for now, but make it render from
  the latest state passed by the controller;
- keep existing tab layout, button styling, and release notes TextView behavior.

Button callbacks should become thin:

```kotlin
updateButton = smallButton("Check", ButtonTone.Primary) {
    updateController.handlePrimaryAction()
}
updateReleaseButton = smallButton("Release page", ButtonTone.Secondary) {
    updateController.openReleasePage()
}
```

If `handlePrimaryAction` needs UI-only knowledge such as expanded release notes,
leave that UI-only state in `MainActivity` and pass only the command to the
controller.

**Verify**:
`rg -n "GitHubUpdateManager|RokidRelayUpdateCheck|RokidRelayUpdateDownload|downloadAndInstallUpdate|openDownloadedUpdateInstaller|checkForUpdates" phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt`
-> no matches except possibly comments or calls into `updateController`.

### Step 3: Move testable update conversion into the controller

If plan 003 left pure validation or state conversion helpers in
`MainActivity.kt`, move them into `PhoneUpdateController.kt` or
`GitHubUpdateManager.kt` so they can be unit tested.

Add `PhoneUpdateControllerTest.kt` only for pure helpers. Do not try to unit
test Android installer launch or background thread scheduling in this plan.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest --tests "*PhoneUpdateControllerTest"`
-> exit 0 if the test file exists. If no pure helper exists after extraction,
skip this filtered command and rely on the full phone test command in Step 5.

### Step 4: Keep behavior and UI text stable

Compare the old and new user-facing status strings:

- "Checking GitHub Releases..."
- "Update available: ..."
- "You're up to date."
- "Allow installs from Rokid Relay, then tap update again."
- "Downloaded. Android Package Installer is opening."
- validation error strings from plan 003

The text may move files, but should not change meaning. Keep release notes
collapse/expand behavior in `MainActivity` unless extracting it is trivial.

**Verify**:
`rg -n "Checking GitHub Releases|Update available|You're up to date|Package Installer|Allow installs from Rokid Relay" phone/src/main/java/com/anezium/rokidrelay/phone -S`
-> strings are still present in the update controller or activity.

### Step 5: Run the full checks

Run the local baseline.

**Verify**:
`.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug`
-> exit 0.

## Test Plan

- Keep all tests from plans 002 and 003 passing.
- Add `PhoneUpdateControllerTest.kt` only for pure conversion/decision helpers introduced during extraction.
- Do not add connected-device or live GitHub tests.
- Manual smoke after build: open the phone app, switch to the Update tab, confirm the tab still renders installed/latest/status sections.

## Done Criteria

- [ ] `MainActivity.kt` no longer owns `GitHubUpdateManager` directly.
- [ ] `MainActivity.kt` no longer contains update network/download/installer methods.
- [ ] Update page UI and button behavior remain visible and equivalent.
- [ ] `.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug` exits 0.
- [ ] No speech, notification, setup, or diagnostics behavior is changed.
- [ ] No files outside the in-scope list are modified, except unavoidable test updates from plan 003.
- [ ] `plans/README.md` status row updated.

## STOP Conditions

Stop and report back if:

- Plan 003 has not landed and `MainActivity` still contains unvalidated update installation logic.
- Extracting update logic requires moving the Activity's private UI style system first.
- The extracted controller needs an Activity reference for anything except opening Android settings/installer intents through existing manager methods.
- The update tab behavior changes in a way that requires manual product decisions.

## Maintenance Notes

This is intentionally the first slice of a larger `MainActivity` cleanup. After
this lands, the next valuable extractions are the speech settings panel and the
notification settings panel, but they should be planned separately to keep risk
low.

