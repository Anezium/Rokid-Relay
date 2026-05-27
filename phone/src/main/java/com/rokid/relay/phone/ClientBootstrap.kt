package com.rokid.relay.phone

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
    fun ensureRunning(): String {
        if (!queryInstalled()) {
            if (!installFromAssets()) return "glasses install failed"
        }
        return if (startClient()) "glasses app running" else "glasses start failed"
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

    private fun installFromAssets(): Boolean {
        val apk = extractAssetApk() ?: return false
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

    private fun await(latch: CountDownLatch, timeoutMs: Long, label: String): Boolean {
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) Log.w(TAG, "$label timed out")
        return ok
    }

    companion object {
        private const val TAG = "RelayBootstrap"
    }
}
