package com.rokid.relay.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class RelayService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            Constants.ACTION_STOP -> {
                RelayBridge.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val token = intent?.getStringExtra(Constants.EXTRA_TOKEN)
                    ?: getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                        .getString(Constants.PREF_AUTH_TOKEN, null)
                val reason = intent?.getStringExtra(Constants.EXTRA_START_REASON).orEmpty()
                if (!token.isNullOrBlank()) {
                    if (reason.isNotBlank()) RelayBridge.setStatus("relay started: $reason")
                    RelayBridge.start(applicationContext, token)
                } else {
                    RelayBridge.setStatus("missing auth token")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "foreground start failed: ${it.message}")
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RelayService::class.java).setAction(Constants.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rokid Relay",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        @Volatile var running: Boolean = false
            private set

        @Volatile private var instance: RelayService? = null

        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "rokid_relay"
        private const val NOTIFICATION_ID = 7201
    }
}
