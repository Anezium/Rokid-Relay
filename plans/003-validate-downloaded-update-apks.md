# Plan 003: Validate Downloaded Update APKs Before Opening The Installer

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report. Do not improvise. When done, update the status row for this plan in
> `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 04e9d1e..HEAD -- phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt phone/src/main/java/com/anezium/rokidrelay/phone/ClientBootstrap.kt phone/src/test/java/com/anezium/rokidrelay/phone`
>
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding. On a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW-MED
- **Depends on**: `plans/002-add-phone-unit-test-baseline.md`
- **Category**: security
- **Planned at**: commit `04e9d1e`, 2026-06-13

## Why This Matters

The phone app can fetch the latest GitHub release, download an APK asset, and
open Android's package installer. Today it selects an APK by asset name and
opens the installer after confirming only that the downloaded file exists. The
Android installer will enforce package/signature rules later, but Relay should
catch wrong assets, stale assets, and obvious release metadata mismatches before
asking the user to install.

## Current State

- `GitHubUpdateManager.fetchLatestRelease` parses GitHub's latest release:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt:59
fun fetchLatestRelease(): GitHubReleaseUpdate {
    val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
```

- `GitHubUpdateManager.downloadApk` writes the selected asset to cache without
  inspecting the APK metadata:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt:77
fun downloadApk(update: GitHubReleaseUpdate): File {
    val outputDir = File(context.cacheDir, "updates").apply { mkdirs() }
    val output = File(outputDir, update.apkName.sanitizeFileName())
```

- `GitHubUpdateManager.installApk` opens Android Package Installer for whatever
  file path it is given:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt:122
fun installApk(file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
```

- `MainActivity.openDownloadedUpdateInstaller` checks file existence only:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt:1379
val file = File(updateState.apkPath)
if (!file.exists()) {
    updateState = updateState.copy(apkPath = "", status = "Downloaded APK missing. Tap install again.")
    renderUpdateStatus()
    return
}
runCatching {
    updateManager.installApk(file)
}
```

- There is already local APK-inspection code for the bundled glasses helper in
  `ClientBootstrap`:

```kotlin
phone/src/main/java/com/anezium/rokidrelay/phone/ClientBootstrap.kt:95
val packageInfo = context.packageManager.getPackageArchiveInfo(absolutePath, 0) ?: return null
ClientAssetInfo(
    versionName = packageInfo.versionName.orEmpty().ifBlank { "0.0.0" },
    versionCode = packageInfo.longVersionCode,
    sha256 = sha256(),
)
```

Repo conventions to match: keep update UI status strings short, use
`runCatching` around Android/external operations, avoid logging secrets, and
keep network calls off the main thread.

## Commands You Will Need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Phone tests | `.\gradlew.bat :phone:testDebugUnitTest` | exit 0 |
| Build phone | `.\gradlew.bat :phone:assembleDebug` | exit 0 |
| Existing glasses tests | `.\gradlew.bat :glasses:testDebugUnitTest` | exit 0 |

## Scope

**In scope**:
- `phone/src/main/java/com/anezium/rokidrelay/phone/GitHubUpdateManager.kt`
- `phone/src/main/java/com/anezium/rokidrelay/phone/MainActivity.kt`
- `phone/src/test/java/com/anezium/rokidrelay/phone/GitHubUpdateManagerTest.kt`

**Out of scope**:
- Do not implement a custom APK installer.
- Do not add private signing keys, hashes, or release credentials.
- Do not change the GitHub repository owner/name constants unless the operator explicitly asks.
- Do not change bundled glasses helper installation in `ClientBootstrap.kt`.

## Git Workflow

- Branch: `advisor/003-validate-update-apks`
- Commit message style: imperative sentence, for example `Validate downloaded update APKs`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Add downloaded APK metadata inspection

In `GitHubUpdateManager.kt`, add a small data class, for example:

```kotlin
data class DownloadedApkInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
)
```

Add a method such as `fun inspectDownloadedApk(file: File): DownloadedApkInfo`
that:

- requires the file to exist;
- calls `context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)`;
- reads `packageInfo.packageName`, `versionName`, and `longVersionCode`;
- computes SHA-256 using the same buffered pattern as `ClientBootstrap.File.sha256`;
- throws a clear `error(...)` message when the APK cannot be parsed.

Do not log or display the full SHA-256 unless needed for diagnostics; it is not
a secret, but short status lines are better UX.

**Verify**:
`.\gradlew.bat :phone:testDebugUnitTest --tests "*GitHubUpdateManagerTest"`
-> exit 0.

### Step 2: Add pure validation logic

Still in `GitHubUpdateManager.kt`, add a pure helper that validates metadata
against the expected update and installed app, for example:

```kotlin
fun validateDownloadedApk(
    apk: DownloadedApkInfo,
    update: GitHubReleaseUpdate,
    installed: InstalledAppVersion,
    expectedPackageName: String,
): DownloadedApkInfo
```

Required checks:

- `apk.packageName == expectedPackageName`.
- `GitHubReleaseUpdate(versionName = apk.versionName, versionCode = apk.versionCode, ...).isNewerThan(installed)` is true, or equivalent logic proves the downloaded APK is newer.
- If `update.versionCode != null`, require `apk.versionCode == update.versionCode`.
- If `update.versionName` is not blank, require the APK version to be compatible with the release version. Use the repo's existing `compareVersions` normalization rather than raw string equality if suffixes are possible.

Return the `DownloadedApkInfo` on success. Throw short user-readable messages on
failure, such as `Downloaded APK package mismatch` or `Downloaded APK is not newer`.

**Verify**:
Add/extend `GitHubUpdateManagerTest.kt` for package mismatch, stale version,
versionCode mismatch, and valid metadata.
Run `.\gradlew.bat :phone:testDebugUnitTest --tests "*GitHubUpdateManagerTest"`
-> exit 0.

### Step 3: Validate after download and before install

In `MainActivity.downloadAndInstallUpdate`, after `updateManager.downloadApk(release)`
returns a file and before setting status to open the installer:

- call `updateManager.inspectDownloadedApk(file)`;
- call the validation helper with the current installed version and `packageName`;
- only set `apkPath` and call `openDownloadedUpdateInstaller()` if validation succeeds;
- show a clear status message and clear `apkPath` if validation fails.

In `MainActivity.openDownloadedUpdateInstaller`, re-run validation before
`updateManager.installApk(file)` because the cached file path may be stale or
tampered with between download and button tap.

Keep this work on the existing background thread for download-time validation;
avoid doing file hashing on the main thread for large APKs.

**Verify**:
`.\gradlew.bat :phone:assembleDebug`
-> exit 0.

### Step 4: Keep status messages useful but redacted

Update status text so a user can tell what failed:

- package mismatch;
- APK not newer than installed;
- release metadata mismatch;
- APK parse failure.

Do not include file paths longer than the APK name in UI status. Do not include
any tokens, release credentials, or local signing details.

**Verify**:
`rg -n "package mismatch|not newer|metadata|inspectDownloadedApk|validateDownloadedApk" phone/src/main/java/com/anezium/rokidrelay/phone -S`
-> matches show validation is present and status strings are short.

### Step 5: Run the full relevant checks

Run the checks after code and tests are complete.

**Verify**:
`.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug`
-> exit 0.

## Test Plan

Add or extend `phone/src/test/java/com/anezium/rokidrelay/phone/GitHubUpdateManagerTest.kt`.

Tests to cover:

- valid downloaded APK metadata for `com.anezium.rokidrelay.phone` is accepted;
- wrong package name is rejected;
- stale versionCode is rejected;
- release versionCode mismatch is rejected;
- versionName fallback works when release versionCode is null.

Do not create real APK files in unit tests. Test the pure metadata validation
helper separately from `PackageManager.getPackageArchiveInfo`.

## Done Criteria

- [ ] Downloaded update APK metadata is inspected before installer launch.
- [ ] Wrong package, stale version, and release metadata mismatch stop the update flow with clear status text.
- [ ] Installer opens only after validation passes.
- [ ] `.\gradlew.bat :phone:testDebugUnitTest` exits 0 with validation tests.
- [ ] `.\gradlew.bat :glasses:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug` exits 0.
- [ ] No secrets, signing files, or private hashes are added to the repo.
- [ ] `plans/README.md` status row updated.

## STOP Conditions

Stop and report back if:

- `PackageManager.getPackageArchiveInfo` cannot read downloaded APK metadata on the target Android API level.
- Validation requires release signing material or hardcoded certificate hashes.
- The fix requires changing GitHub release publishing conventions outside this repo.
- `MainActivity` update flow has already been extracted by plan 004 or another branch, making the cited code stale.

## Maintenance Notes

This plan does not replace Android's package/signature enforcement. It adds a
pre-installer sanity check so users are not prompted to install the wrong file.
If public distribution is finalized later, consider adding published checksums
or signed update metadata as a separate plan.

