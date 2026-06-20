package com.anezium.rokidrelay.phone

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object RelayStarter {
    private const val TAG = "RelayStarter"

    fun startIfReady(context: Context, reason: String): Boolean {
        val appContext = context.applicationContext
        val userInitiated = isUserInitiatedRelayStart(reason)
        if (!userInitiated && !isRelayEnabled(appContext)) {
            RelayBridge.setStatus("relay not started: disabled")
            return false
        }
        val token = appContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString(Constants.PREF_AUTH_TOKEN, null)
        if (token.isNullOrBlank()) {
            RelayBridge.setStatus("relay not started: missing auth token")
            return false
        }
        return start(appContext, token, reason, persistEnabled = userInitiated)
    }

    fun arm(context: Context) {
        setRelayEnabled(context.applicationContext, true)
        RelayBridge.setStatus("relay armed: wake on notification")
    }

    fun armAndPrepare(context: Context, reason: String = START_REASON_MANUAL): Boolean {
        val appContext = context.applicationContext
        setRelayEnabled(appContext, true)
        val token = authToken(appContext)
        if (token.isNullOrBlank()) {
            RelayBridge.setStatus("relay armed: missing auth token")
            return false
        }
        return start(appContext, token, reason, persistEnabled = false)
    }

    fun wakeForNotification(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!isRelayEnabled(appContext)) {
            RelayBridge.setStatus("notification ignored: relay disabled")
            return false
        }
        val token = authToken(appContext)
        if (token.isNullOrBlank()) {
            RelayBridge.setStatus("notification wake skipped: missing auth token")
            return false
        }
        return start(appContext, token, START_REASON_NOTIFICATION, persistEnabled = false)
    }

    fun start(context: Context, token: String, reason: String): Boolean =
        start(context, token, reason, persistEnabled = true)

    private fun start(context: Context, token: String, reason: String, persistEnabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, RelayService::class.java)
            .setAction(Constants.ACTION_START)
            .putExtra(Constants.EXTRA_TOKEN, token)
            .putExtra(Constants.EXTRA_START_REASON, reason)
        return runCatching {
            if (persistEnabled) setRelayEnabled(appContext, true)
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
        setRelayEnabled(context.applicationContext, false)
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, RelayService::class.java)
                    .setAction(Constants.ACTION_STOP),
            )
        }.onFailure {
            Log.w(TAG, "relay stop failed: ${it.message}")
        }
    }

    private fun authToken(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString(Constants.PREF_AUTH_TOKEN, null)

    fun isRelayEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_RELAY_ENABLED, false)

    fun setRelayEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_RELAY_ENABLED, enabled)
            .apply()
    }

    const val START_REASON_MANUAL = "manual_start"
    const val START_REASON_NOTIFICATION = "notification_posted"
}

internal fun isUserInitiatedRelayStart(reason: String): Boolean =
    when (reason) {
        RelayStarter.START_REASON_MANUAL,
        "authorization",
        "permissions",
        "stt_engine",
        "stt_provider",
        "stt_model",
        "microphone_permission",
        -> true
        else -> false
    }

internal fun isNotificationWakeStart(reason: String): Boolean =
    reason == RelayStarter.START_REASON_NOTIFICATION
