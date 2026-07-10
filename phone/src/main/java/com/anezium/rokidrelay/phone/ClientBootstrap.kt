package com.anezium.rokidrelay.phone

import android.content.Context
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IGlassAppCbk
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The blocking CXR transport operations used by [GlassesHelperUpdater]. Version decisions and
 * successful-install bookkeeping deliberately live in the updater so an upload callback cannot
 * be mistaken for glasses-confirmed success.
 */
class ClientBootstrap(
    private val context: Context,
    private val link: CXRLink? = null,
) {
    data class Result(
        val status: String,
        val success: Boolean,
        val openedClient: Boolean,
        val readyForMessages: Boolean,
    )

    internal fun queryInstalled(): Boolean? {
        val activeLink = link ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        activeLink.appIsInstalled(object : IGlassAppCbk {
            override fun onQueryAppResult(installed: Boolean) {
                result.set(installed)
                latch.countDown()
            }
        })
        return if (await(latch, QUERY_TIMEOUT_MS, "query")) result.get() else null
    }

    internal fun bundledClient(): ClientAssetInfo? {
        val apk = extractAssetApk() ?: return null
        return apk.clientAssetInfo()
    }

    internal fun installBundledClient(assetInfo: ClientAssetInfo): Result {
        Log.i(TAG, "installing bundled glasses app ${assetInfo.label}")
        val install = installApk(assetInfo.apk)
        if (!install.success) {
            return Result(
                status = install.reason,
                success = false,
                openedClient = false,
                readyForMessages = false,
            )
        }
        markClientLaunchPending()
        return Result(
            status = "glasses app installed/updated in background",
            success = true,
            openedClient = false,
            readyForMessages = false,
        )
    }

    internal fun openClient(successStatus: String = "glasses app running"): Result {
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

    internal fun clientLaunchPending(): Boolean =
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_CLIENT_NEEDS_FOREGROUND_LAUNCH, false)

    private fun installApk(apk: File): TransportResult {
        val activeLink = link ?: return TransportResult(false, "glasses link unavailable")
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        activeLink.appUploadAndInstall(apk.absolutePath, object : IGlassAppCbk {
            override fun onInstallAppResult(success: Boolean) {
                result.set(success)
                latch.countDown()
            }
        })
        if (!await(latch, INSTALL_TIMEOUT_MS, "install")) {
            return TransportResult(false, "install timed out")
        }
        return if (result.get()) {
            TransportResult(true, "")
        } else {
            TransportResult(false, "install failed")
        }
    }

    private fun startClient(): Boolean {
        val activeLink = link ?: return false
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        activeLink.appStart(Constants.CLIENT_MAIN_ACTIVITY, object : IGlassAppCbk {
            override fun onOpenAppResult(success: Boolean) {
                result.set(success)
                latch.countDown()
            }
        })
        return await(latch, START_TIMEOUT_MS, "start") && result.get()
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
            apk = this,
            versionName = packageInfo.versionName.orEmpty().ifBlank { "0.0.0" },
            versionCode = packageInfo.longVersionCode,
        )
    }.onFailure {
        Log.w(TAG, "asset package read failed: ${it.message}")
    }.getOrNull()

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

    private data class TransportResult(
        val success: Boolean,
        val reason: String,
    )

    companion object {
        private const val TAG = "RelayBootstrap"
        private const val QUERY_TIMEOUT_MS = 5_000L
        private const val INSTALL_TIMEOUT_MS = 90_000L
        private const val START_TIMEOUT_MS = 8_000L
    }
}

internal data class ClientAssetInfo(
    val apk: File,
    val versionName: String,
    val versionCode: Long,
) {
    val fingerprint: String = clientVersionFingerprint(versionCode, versionName)
    val label: String = "$versionName ($versionCode)"
}

internal fun bundledClientChanged(
    lastFingerprint: String?,
    nextVersionCode: Long,
    nextVersionName: String,
): Boolean {
    val next = clientVersionFingerprint(nextVersionCode, nextVersionName)
    return lastFingerprint != next && lastFingerprint?.startsWith("$next:") != true
}

internal fun clientVersionFingerprint(versionCode: Long, versionName: String): String =
    "$versionCode:$versionName"
