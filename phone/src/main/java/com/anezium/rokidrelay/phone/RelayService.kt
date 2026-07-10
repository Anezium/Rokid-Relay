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
        microphoneForegroundActive = false
        microphoneForegroundRequested = false
        microphoneForegroundHeldForAwakeWindow = false
        lastMicrophoneForegroundError = ""
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            Constants.ACTION_STOP -> {
                RelayStarter.setRelayEnabled(this, false)
                BleWakeServer.stop()
                microphoneForegroundRequested = false
                microphoneForegroundHeldForAwakeWindow = false
                cancelIdleStop()
                RelayBridge.disableSelfArmBestEffort(this) {
                    RelayBridge.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            Constants.ACTION_RELAUNCH -> {
                if (!RelayStarter.isRelayEnabled(this)) {
                    RelayStarter.setRelayEnabled(this, true)
                }
                val token = intent.getStringExtra(Constants.EXTRA_TOKEN)
                    ?: getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                        .getString(Constants.PREF_AUTH_TOKEN, null)
                val reason = intent.getStringExtra(Constants.EXTRA_START_REASON)
                    ?: RelayStarter.START_REASON_RELAUNCH
                if (!token.isNullOrBlank()) {
                    cancelIdleStop()
                    RelayBridge.setStatus("relay relaunching")
                    RelayBridge.stop()
                    BleWakeServer.ensureStarted(this)
                    RelayBridge.start(applicationContext, token, reason)
                    scheduleIdleStop()
                } else {
                    RelayBridge.setStatus("relay relaunch skipped: missing auth token")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
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
        microphoneForegroundActive = false
        microphoneForegroundRequested = false
        microphoneForegroundHeldForAwakeWindow = false
        lastMicrophoneForegroundError = "Relay service stopped"
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        val requestMicrophone = shouldRequestMicrophoneForeground()
        if (requestMicrophone && microphoneForegroundActive) return
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
                if (microphoneForegroundActive) {
                    lastMicrophoneForegroundError = ""
                } else if (requestMicrophone) {
                    lastMicrophoneForegroundError = "Microphone foreground type unavailable on this Android version"
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
                microphoneForegroundActive = requestMicrophone
                if (microphoneForegroundActive) lastMicrophoneForegroundError = ""
            }
        }.onFailure {
            microphoneForegroundActive = false
            lastMicrophoneForegroundError = microphoneForegroundFailureDetail(it)
            Log.w(TAG, "foreground start failed: $lastMicrophoneForegroundError", it)
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
        if (microphoneForegroundHeldForAwakeWindow && microphoneForegroundActive) return true
        if (!microphoneForegroundRequested && !microphoneForegroundHeldForAwakeWindow) return false
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

        @Volatile var microphoneForegroundActive: Boolean = false
            private set

        @Volatile var lastMicrophoneForegroundError: String = ""
            private set

        @Volatile private var instance: RelayService? = null
        @Volatile private var microphoneForegroundRequested: Boolean = false
        @Volatile private var microphoneForegroundHeldForAwakeWindow: Boolean = false

        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "rokid_relay"
        private const val NOTIFICATION_ID = 7201
        private const val IDLE_STOP_DELAY_MS = 120_000L
        private const val SLEEP_EVENT_GRACE_MS = 750L

        fun setMicrophoneForegroundRequested(requested: Boolean): Boolean {
            microphoneForegroundRequested = requested
            if (!requested && microphoneForegroundHeldForAwakeWindow) {
                return microphoneForegroundActive
            }
            return refreshForeground()
        }

        fun promoteMicrophoneForegroundForAwakeWindow(): Boolean {
            microphoneForegroundHeldForAwakeWindow = true
            val promoted = refreshForeground()
            if (!promoted) microphoneForegroundHeldForAwakeWindow = false
            return promoted
        }

        fun scheduleIdleStop(delayMs: Long = IDLE_STOP_DELAY_MS) {
            instance?.scheduleIdleStop(delayMs)
        }

        fun cancelIdleStop() {
            instance?.cancelIdleStop()
        }

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
