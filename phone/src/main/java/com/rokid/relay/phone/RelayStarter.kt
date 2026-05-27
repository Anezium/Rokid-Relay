package com.rokid.relay.phone

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object RelayStarter {
    private const val TAG = "RelayStarter"

    fun startIfReady(context: Context, reason: String): Boolean {
        val token = context
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString(Constants.PREF_AUTH_TOKEN, null)
        if (token.isNullOrBlank()) {
            RelayBridge.setStatus("relay not started: missing auth token")
            return false
        }
        return start(context, token, reason)
    }

    fun start(context: Context, token: String, reason: String): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, RelayService::class.java)
            .setAction(Constants.ACTION_START)
            .putExtra(Constants.EXTRA_TOKEN, token)
            .putExtra(Constants.EXTRA_START_REASON, reason)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            RelayBridge.setStatus("relay starting: $reason")
            true
        }.getOrElse {
            Log.w(TAG, "relay start failed reason=$reason: ${it.message}")
            RelayBridge.setStatus("relay start blocked: $reason")
            false
        }
    }

    fun stop(context: Context) {
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, RelayService::class.java)
                    .setAction(Constants.ACTION_STOP),
            )
        }.onFailure {
            Log.w(TAG, "relay stop failed: ${it.message}")
        }
    }
}
