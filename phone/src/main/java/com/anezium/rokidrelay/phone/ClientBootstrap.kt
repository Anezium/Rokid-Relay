package com.anezium.rokidrelay.phone

import android.content.Context
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IGlassAppCbk
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClientBootstrap(
    private val context: Context,
    private val link: CXRLink,
) {
    data class Result(
        val status: String,
        val success: Boolean,
        val openedClient: Boolean,
        val readyForMessages: Boolean,
    )

    fun ensureReady(openAfterInstall: Boolean = false): Result {
        val installed = queryInstalled()
        val apk = extractAssetApk()
        val assetInfo = apk?.clientAssetInfo()
        val rememberedClient = rememberedClientFingerprint() != null
        val shouldInstall = !installed || bundledClientChanged(assetInfo)
        if (!shouldInstall) {
            if (clientLaunchPending()) {
                if (openAfterInstall) {
                    return openClient(successStatus = "glasses app started after background install")
                }
                return Result(
                    "glasses app waiting for foreground launch",
                    success = true,
                    openedClient = false,
                    readyForMessages = false,
                )
            }
            return Result(
                "glasses app ready in background",
                success = true,
                openedClient = false,
                readyForMessages = true,
            )
        }
        if (!openAfterInstall) {
            val readyForMessages = installed
            Log.i(
                TAG,
                "deferring bundled glasses app install/update until foreground start installed=$installed remembered=$rememberedClient",
            )
            return Result(
                if (readyForMessages) {
                    "glasses helper update pending"
                } else {
                    "glasses helper install pending"
                },
                success = true,
                openedClient = false,
                readyForMessages = readyForMessages,
            )
        }
        if (apk == null) {
            return Result(
                "glasses asset missing",
                success = false,
                openedClient = false,
                readyForMessages = false,
            )
        }
        Log.i(TAG, "installing bundled glasses app ${assetInfo?.label.orEmpty().ifBlank { apk.name }}")
        if (!installApk(apk)) {
            return Result(
                "glasses install failed",
                success = false,
                openedClient = false,
                readyForMessages = false,
            )
        }
        assetInfo?.let(::rememberInstalledClient)
        markClientLaunchPending()
        if (!openAfterInstall) {
            return Result(
                "glasses app installed/updated in background",
                success = true,
                openedClient = false,
                readyForMessages = false,
            )
        }
        return openClient(successStatus = "glasses app installed/updated")
    }

    fun openClient(successStatus: String = "glasses app running"): Result {
        return if (startClient()) {
            clearClientLaunchPending()
            Result(successStatus, success = true, openedClient = true, readyForMessages = true)
        } else {
            Result(
                "glasses start failed",
                success = false,
                openedClient = false,
                readyForMessages = false,
            )
        }
    }

    private fun queryInstalled(): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        link.appIsInstalled(object : IGlassAppCbk {
            override fun onQueryAppResult(installed: Boolean) {
                result.set(installed)
                latch.countDown()
            }
        })
        return await(latch, 5_000L, "query") && result.get()
    }

    private fun installApk(apk: File): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        link.appUploadAndInstall(apk.absolutePath, object : IGlassAppCbk {
            override fun onInstallAppResult(success: Boolean) {
                result.set(success)
                latch.countDown()
            }
        })
        return await(latch, 90_000L, "install") && result.get()
    }

    private fun startClient(): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        link.appStart(Constants.CLIENT_MAIN_ACTIVITY, object : IGlassAppCbk {
            override fun onOpenAppResult(success: Boolean) {
                result.set(success)
                latch.countDown()
            }
        })
        return await(latch, 8_000L, "start") && result.get()
    }

    private fun extractAssetApk(): File? = runCatching {
        val dest = File(context.filesDir, Constants.CLIENT_ASSET_NAME)
        context.assets.open(Constants.CLIENT_ASSET_NAME).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest
    }.onFailure {
        Log.w(TAG, "asset extraction failed: ${it.message}")
    }.getOrNull()

    private fun File.clientAssetInfo(): ClientAssetInfo? = runCatching {
        val packageInfo = context.packageManager.getPackageArchiveInfo(absolutePath, 0) ?: return null
        ClientAssetInfo(
            versionName = packageInfo.versionName.orEmpty().ifBlank { "0.0.0" },
            versionCode = packageInfo.longVersionCode,
        )
    }.onFailure {
        Log.w(TAG, "asset package read failed: ${it.message}")
    }.getOrNull()

    private fun bundledClientChanged(assetInfo: ClientAssetInfo?): Boolean {
        assetInfo ?: return false
        return bundledClientChanged(
            lastFingerprint = rememberedClientFingerprint(),
            nextVersionCode = assetInfo.versionCode,
            nextVersionName = assetInfo.versionName,
        )
    }

    private fun rememberedClientFingerprint(): String? =
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString(Constants.PREF_CLIENT_APK_FINGERPRINT, null)

    private fun rememberInstalledClient(assetInfo: ClientAssetInfo) {
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_CLIENT_APK_FINGERPRINT, assetInfo.fingerprint)
            .apply()
    }

    private fun clientLaunchPending(): Boolean =
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_CLIENT_NEEDS_FOREGROUND_LAUNCH, false)

    private fun markClientLaunchPending() {
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_CLIENT_NEEDS_FOREGROUND_LAUNCH, true)
            .apply()
    }

    private fun clearClientLaunchPending() {
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_CLIENT_NEEDS_FOREGROUND_LAUNCH, false)
            .apply()
    }

    private fun await(latch: CountDownLatch, timeoutMs: Long, label: String): Boolean {
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) Log.w(TAG, "$label timed out")
        return ok
    }

    private data class ClientAssetInfo(
        val versionName: String,
        val versionCode: Long,
    ) {
        val fingerprint: String = clientVersionFingerprint(versionCode, versionName)
        val label: String = "$versionName ($versionCode)"
    }

    companion object {
        private const val TAG = "RelayBootstrap"
    }
}

internal fun bundledClientChanged(
    lastFingerprint: String?,
    nextVersionCode: Long,
    nextVersionName: String,
): Boolean {
    val next = clientVersionFingerprint(nextVersionCode, nextVersionName)
    return lastFingerprint != next && lastFingerprint?.startsWith("$next:") != true
}

private fun clientVersionFingerprint(versionCode: Long, versionName: String): String =
    "$versionCode:$versionName"
