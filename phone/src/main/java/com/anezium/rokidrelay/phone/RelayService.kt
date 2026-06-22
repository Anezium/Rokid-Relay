package com.anezium.rokidrelay.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class RelayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val idleStopRunnable = Runnable {
        BleWakeServer.ensureStarted(this)
        RelayBridge.notifySleeping("idle_timeout")
        handler.postDelayed({
            RelayBridge.setStatus("relay sleeping until next notification")
            RelayBridge.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, SLEEP_EVENT_GRACE_MS)
    }

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
                RelayStarter.setRelayEnabled(this, false)
                BleWakeServer.stop()
                cancelIdleStop()
                RelayBridge.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!RelayStarter.isRelayEnabled(this)) {
                    RelayBridge.setStatus("relay not started: disabled")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val token = intent?.getStringExtra(Constants.EXTRA_TOKEN)
                    ?: getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                        .getString(Constants.PREF_AUTH_TOKEN, null)
                val reason = intent?.getStringExtra(Constants.EXTRA_START_REASON).orEmpty()
                val wakeNotificationId = intent
                    ?.getStringExtra(Constants.EXTRA_WAKE_NOTIFICATION_ID)
                    .orEmpty()
                if (!token.isNullOrBlank()) {
                    if (reason.isNotBlank()) RelayBridge.setStatus("relay started: $reason")
                    BleWakeServer.ensureStarted(this)
                    RelayBridge.start(applicationContext, token, reason, wakeNotificationId)
                    scheduleIdleStop()
                } else {
                    RelayBridge.setStatus("missing auth token")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelIdleStop()
        RelayBridge.stop()
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
            val message = it.message?.takeIf { text -> text.isNotBlank() } ?: it::class.java.simpleName
            Log.w(TAG, "foreground start failed: $message")
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

    private fun scheduleIdleStop(delayMs: Long = IDLE_STOP_DELAY_MS) {
        handler.removeCallbacks(idleStopRunnable)
        handler.postDelayed(idleStopRunnable, delayMs)
    }

    private fun cancelIdleStop() {
        handler.removeCallbacks(idleStopRunnable)
    }

    companion object {
        @Volatile var running: Boolean = false
            private set

        @Volatile private var instance: RelayService? = null

        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "rokid_relay"
        private const val NOTIFICATION_ID = 7201
        private const val IDLE_STOP_DELAY_MS = 120_000L
        private const val SLEEP_EVENT_GRACE_MS = 750L

        fun scheduleIdleStop(delayMs: Long = IDLE_STOP_DELAY_MS) {
            instance?.scheduleIdleStop(delayMs)
        }

        fun cancelIdleStop() {
            instance?.cancelIdleStop()
        }

        fun refreshForeground(): Boolean {
            val service = instance
            if (service == null) {
                return false
            }
            service.startForegroundCompat()
            return true
        }
    }
}
