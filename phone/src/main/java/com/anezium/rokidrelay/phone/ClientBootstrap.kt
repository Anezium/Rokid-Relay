package com.anezium.rokidrelay.phone

import android.content.Context
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IGlassAppCbk
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClientBootstrap(
    private val context: Context,
    private val link: CXRLink,
) {
    fun ensureRunning(): String {
        val installed = queryInstalled()
        val apk = extractAssetApk()
        val assetInfo = apk?.clientAssetInfo()
        val shouldInstall = !installed || bundledClientChanged(assetInfo)
        val installedFromBundle = if (shouldInstall) {
            if (apk == null) return "glasses asset missing"
            Log.i(TAG, "installing bundled glasses app ${assetInfo?.label.orEmpty().ifBlank { apk.name }}")
            if (!installApk(apk)) return "glasses install failed"
            assetInfo?.let(::rememberInstalledClient)
            true
        } else {
            false
        }
        return if (startClient()) {
            if (installedFromBundle) "glasses app installed/updated" else "glasses app running"
        } else {
            "glasses start failed"
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
            sha256 = sha256(),
        )
    }.onFailure {
        Log.w(TAG, "asset package read failed: ${it.message}")
    }.getOrNull()

    private fun bundledClientChanged(assetInfo: ClientAssetInfo?): Boolean {
        val next = assetInfo?.fingerprint ?: return false
        val last = context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString(Constants.PREF_CLIENT_APK_FINGERPRINT, null)
        return last != next
    }

    private fun rememberInstalledClient(assetInfo: ClientAssetInfo) {
        context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_CLIENT_APK_FINGERPRINT, assetInfo.fingerprint)
            .apply()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun await(latch: CountDownLatch, timeoutMs: Long, label: String): Boolean {
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) Log.w(TAG, "$label timed out")
        return ok
    }

    private data class ClientAssetInfo(
        val versionName: String,
        val versionCode: Long,
        val sha256: String,
    ) {
        val fingerprint: String = "$versionCode:$versionName:$sha256"
        val label: String = "$versionName ($versionCode)"
    }

    companion object {
        private const val TAG = "RelayBootstrap"
    }
}
