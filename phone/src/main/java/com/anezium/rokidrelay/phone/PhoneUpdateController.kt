package com.anezium.rokidrelay.phone

import android.content.Context
import android.os.Handler
import java.io.File

class PhoneUpdateController(
    context: Context,
    private val handler: Handler,
    private val onStateChanged: (AppUpdateUiState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val updateManager = GitHubUpdateManager(appContext)
    private var state = AppUpdateUiState()

    fun refreshInstalledUpdateState() {
        val installed = updateManager.installedVersion()
        setState(
            state.copy(
                currentVersionName = installed.versionName,
                currentVersionCode = installed.versionCode,
            ),
        )
    }

    fun handlePrimaryAction() {
        when {
            state.checking || state.downloading -> Unit
            state.available && state.apkPath.isNotBlank() -> openDownloadedUpdateInstaller()
            state.available -> downloadAndInstallUpdate()
            else -> checkForUpdates()
        }
    }

    fun openReleasePage() {
        updateManager.openReleasePage(state.releaseUrl)
    }

    private fun checkForUpdates(downloadIfAvailable: Boolean = false) {
        if (state.checking || state.downloading) return
        val installed = updateManager.installedVersion()
        setState(
            state.copy(
                currentVersionName = installed.versionName,
                currentVersionCode = installed.versionCode,
                checking = true,
                status = "Checking GitHub Releases...",
                apkPath = "",
            ),
        )
        Thread {
            val result = runCatching { updateManager.fetchLatestRelease() }
            handler.post {
                result
                    .onSuccess { latest ->
                        val available = latest.isNewerThan(installed)
                        setState(
                            state.copy(
                                currentVersionName = installed.versionName,
                                currentVersionCode = installed.versionCode,
                                checking = false,
                                available = available,
                                latestTag = latest.tagName,
                                latestVersionName = latest.versionName,
                                latestVersionCode = latest.versionCode,
                                releaseUrl = latest.releaseUrl,
                                releaseNotes = latest.releaseNotes,
                                apkName = latest.apkName,
                                apkUrl = latest.apkDownloadUrl,
                                status = if (available) {
                                    "Update available: ${latest.title}"
                                } else {
                                    "You're up to date."
                                },
                            ),
                        )
                        if (available && downloadIfAvailable) downloadAndInstallUpdate()
                    }
                    .onFailure { error ->
                        val message = error.message.orEmpty()
                        setState(
                            state.copy(
                                checking = false,
                                available = false,
                                status = if (message.contains("HTTP 404")) {
                                    "No GitHub release is published yet."
                                } else {
                                    "Update check failed: ${message.ifBlank { "unknown error" }}"
                                },
                            ),
                        )
                    }
            }
        }.apply {
            name = "RokidRelayUpdateCheck"
            start()
        }
    }

    private fun downloadAndInstallUpdate() {
        val release = state.toGitHubReleaseUpdate() ?: run {
            checkForUpdates(downloadIfAvailable = true)
            return
        }
        val expectedPackageName = appContext.packageName
        if (!updateManager.canInstallPackages()) {
            setState(state.copy(status = "Allow installs from Rokid Relay, then tap update again."))
            updateManager.openInstallPermissionSettings()
            return
        }
        setState(
            state.copy(
                downloading = true,
                status = "Downloading ${release.apkName}...",
            ),
        )
        Thread {
            val result = runCatching {
                val file = updateManager.downloadApk(release)
                val installed = updateManager.installedVersion()
                val apk = updateManager.inspectDownloadedApk(file)
                validateDownloadedApk(apk, release, installed, expectedPackageName)
                file
            }
            handler.post {
                result
                    .onSuccess { file ->
                        setState(
                            state.copy(
                                downloading = false,
                                apkPath = file.absolutePath,
                                status = "APK validated. Opening installer.",
                            ),
                        )
                        openDownloadedUpdateInstaller()
                    }
                    .onFailure { error ->
                        setState(
                            state.copy(
                                downloading = false,
                                apkPath = "",
                                status = error.downloadUpdateStatus(state),
                            ),
                        )
                    }
            }
        }.apply {
            name = "RokidRelayUpdateDownload"
            start()
        }
    }

    private fun openDownloadedUpdateInstaller() {
        if (!updateManager.canInstallPackages()) {
            setState(state.copy(status = "Allow installs from Rokid Relay, then tap update again."))
            updateManager.openInstallPermissionSettings()
            return
        }
        val release = state.toGitHubReleaseUpdate() ?: run {
            setState(state.copy(apkPath = "", status = "Release metadata missing. Check again."))
            return
        }
        val file = File(state.apkPath)
        if (!file.exists()) {
            setState(state.copy(apkPath = "", status = "Downloaded APK missing. Tap install again."))
            return
        }
        val expectedPackageName = appContext.packageName
        setState(state.copy(downloading = true, status = "Validating downloaded APK..."))
        Thread {
            val result = runCatching {
                val installed = updateManager.installedVersion()
                val apk = updateManager.inspectDownloadedApk(file)
                validateDownloadedApk(apk, release, installed, expectedPackageName)
            }
            handler.post {
                result
                    .onSuccess {
                        runCatching {
                            updateManager.installApk(file)
                        }.onSuccess {
                            setState(
                                state.copy(
                                    downloading = false,
                                    status = "Android Package Installer opened.",
                                ),
                            )
                        }.onFailure { error ->
                            setState(
                                state.copy(
                                    downloading = false,
                                    status = "Install failed: ${error.updateStatusMessage(state)}",
                                ),
                            )
                        }
                    }
                    .onFailure { error ->
                        setState(
                            state.copy(
                                downloading = false,
                                apkPath = "",
                                status = "Install blocked: ${error.updateStatusMessage(state)}",
                            ),
                        )
                    }
            }
        }.apply {
            name = "RokidRelayUpdateValidate"
            start()
        }
    }

    private fun setState(next: AppUpdateUiState) {
        state = next
        onStateChanged(state)
    }
}

internal fun AppUpdateUiState.toGitHubReleaseUpdate(): GitHubReleaseUpdate? {
    if (apkUrl.isBlank() || apkName.isBlank()) return null
    return GitHubReleaseUpdate(
        tagName = latestTag,
        versionName = latestVersionName,
        versionCode = latestVersionCode,
        title = latestTag.ifBlank { latestVersionName.ifBlank { apkName } },
        releaseUrl = releaseUrl,
        releaseNotes = releaseNotes,
        apkName = apkName,
        apkDownloadUrl = apkUrl,
    )
}

private fun Throwable.downloadUpdateStatus(state: AppUpdateUiState): String {
    val message = updateStatusMessage(state)
    return if (message.startsWith("APK download failed")) {
        message
    } else {
        "Install blocked: $message"
    }
}

private fun Throwable.updateStatusMessage(state: AppUpdateUiState): String {
    val fallback = "unknown error"
    val raw = message.orEmpty().ifBlank { fallback }
    val apkLabel = state.apkName.ifBlank { "downloaded APK" }
    val redactedPath = state.apkPath.takeIf { it.isNotBlank() }
        ?.let { raw.replace(it, apkLabel) }
        ?: raw
    val redactedApkPaths = redactedPath
        .replace(Regex("""[A-Za-z]:\\[^\n]+?\.apk"""), apkLabel)
        .replace(Regex("""/[^\n]+?\.apk"""), apkLabel)
    val firstLine = redactedApkPaths.lineSequence().firstOrNull().orEmpty().ifBlank { fallback }
    return if (firstLine.length <= UPDATE_STATUS_MAX_CHARS) {
        firstLine
    } else {
        firstLine.take(UPDATE_STATUS_MAX_CHARS - 3) + "..."
    }
}

private const val UPDATE_STATUS_MAX_CHARS = 140
