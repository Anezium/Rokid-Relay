package com.anezium.rokidrelay.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
                    RelayBridge.start(applicationContext, token, reason)
                } else {
                    RelayBridge.setStatus("missing auth token")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RelayBridge.stop()
        running = false
        microphoneForegroundActive = false
        lastMicrophoneForegroundError = "Relay service stopped"
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        val requestMicrophone = shouldRequestMicrophoneForeground()
        lastMicrophoneForegroundError = ""
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val microphoneType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requestMicrophone) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or microphoneType,
                )
                microphoneForegroundActive = microphoneType != 0
                if (requestMicrophone && !microphoneForegroundActive) {
                    lastMicrophoneForegroundError = "Microphone foreground type unavailable on this Android version"
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
                microphoneForegroundActive = requestMicrophone
            }
        }.onFailure {
            microphoneForegroundActive = false
            lastMicrophoneForegroundError = it.message?.takeIf { message -> message.isNotBlank() }
                ?: it::class.java.simpleName
            Log.w(TAG, "foreground start failed: $lastMicrophoneForegroundError")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requestMicrophone) {
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                }.onFailure { fallbackError ->
                    Log.w(TAG, "connected foreground fallback failed: ${fallbackError.message}")
                }
            }
        }
    }

    private fun shouldRequestMicrophoneForeground(): Boolean {
        val selected = SpeechToTextSettingsStore(this).selectedEngine()
        return selected.requiresMicrophonePermission &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stat_relay, "Stop", stopIntent)
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

        @Volatile var microphoneForegroundActive: Boolean = false
            private set

        @Volatile var lastMicrophoneForegroundError: String = ""
            private set

        @Volatile private var instance: RelayService? = null

        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "rokid_relay"
        private const val NOTIFICATION_ID = 7201

        fun refreshForeground(): Boolean {
            val service = instance
            if (service == null) {
                microphoneForegroundActive = false
                lastMicrophoneForegroundError = "Relay service not running"
                return false
            }
            service.startForegroundCompat()
            return microphoneForegroundActive
        }
    }
}
