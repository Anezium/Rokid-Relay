package com.rokid.relay.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class RelayAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val notificationWakeListener: () -> Unit = { wakeScreen() }
    private val replyWakeListener: (RelayHudController.State) -> Unit = { state ->
        if (
            state.voiceState == "listening" ||
            state.voiceState == "recognizing" ||
            state.voiceState == "processing"
        ) {
            keepReplyScreenOn()
        } else if (state.inboxVisible) {
            keepReplyScreenOn(INBOX_WAKE_MS)
        } else if (state.replyOk && state.resultLine.isNotBlank()) {
            keepReplyScreenOn(POST_REPLY_WAKE_MS)
        } else {
            releaseReplyWakeLock()
        }
    }
    private var windowManager: WindowManager? = null
    private var overlay: RelayHudView? = null
    private var replyWakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var commandVolume: VolumeSnapshot? = null
    private var lastComboInputAt = 0L
    private var tapArmed = false
    private val comboBuffer = ArrayList<Direction>(4)
    private val singleTapRunnable = Runnable {
        tapArmed = false
        runInboxSingleTap()
    }

    private val twoFingerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TWO_FINGER_SWIPE_BACK -> onTwoFinger(Direction.LEFT)
                ACTION_TWO_FINGER_SWIPE_FORWARD -> onTwoFinger(Direction.RIGHT)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val filter = IntentFilter().apply {
            addAction(ACTION_TWO_FINGER_SWIPE_BACK)
            addAction(ACTION_TWO_FINGER_SWIPE_FORWARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(twoFingerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(twoFingerReceiver, filter)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        RelayHudController.refreshAccessibility(this)
        RelayHudController.addNotificationShownListener(notificationWakeListener)
        RelayHudController.addStateListener(replyWakeListener)
        RelayBridge.start()
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return RelayHudController.isInboxOpen() && isRelayControlKey(event.keyCode)
        }

        if (RelayHudController.isInboxOpen()) {
            return handleInboxKey(event.keyCode)
        }

        directionFromKey(event.keyCode)?.let { direction ->
            if (RelayHudController.hasNotification()) {
                keepReplyScreenOn()
                RelayBridge.startVoice()
                return true
            }
            return handleDirectionalComboFallback(direction)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                if (RelayHudController.hasNotification()) {
                    RelayBridge.dismiss()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (RelayHudController.hasNotification()) {
                    RelayBridge.dismiss()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    override fun onDestroy() {
        hideOverlay()
        runCatching { unregisterReceiver(twoFingerReceiver) }
        main.removeCallbacks(singleTapRunnable)
        RelayHudController.removeNotificationShownListener(notificationWakeListener)
        RelayHudController.removeStateListener(replyWakeListener)
        releaseReplyWakeLock()
        RelayHudController.refreshAccessibility(this)
        super.onDestroy()
    }

    private fun handleInboxKey(keyCode: Int): Boolean {
        directionFromKey(keyCode)?.let { direction ->
            tapArmed = false
            main.removeCallbacks(singleTapRunnable)
            RelayHudController.navigateInbox(if (direction == Direction.LEFT) -1 else 1)
            keepReplyScreenOn(INBOX_WAKE_MS)
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                handleInboxTap()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleInboxBack()
                true
            }
            else -> false
        }
    }

    private fun handleInboxTap() {
        if (tapArmed) {
            tapArmed = false
            main.removeCallbacks(singleTapRunnable)
            handleInboxBack()
            return
        }
        tapArmed = true
        main.postDelayed(singleTapRunnable, DOUBLE_TAP_MS)
    }

    private fun runInboxSingleTap() {
        if (!RelayHudController.isInboxOpen()) return
        if (RelayHudController.isInboxDetailOpen()) {
            keepReplyScreenOn()
            RelayBridge.startVoice()
        } else {
            RelayHudController.openInboxDetail()
            keepReplyScreenOn(INBOX_WAKE_MS)
        }
    }

    private fun handleInboxBack() {
        if (RelayHudController.isVoiceActive()) RelayBridge.cancelVoice()
        RelayHudController.backInInbox()
    }

    private fun onTwoFinger(direction: Direction) {
        if (commandVolume == null) commandVolume = VolumeSnapshot.capture(audioManager)
        if (RelayHudController.isInboxOpen()) {
            RelayHudController.navigateInbox(if (direction == Direction.LEFT) -1 else 1)
            keepReplyScreenOn(INBOX_WAKE_MS)
            restoreCommandVolumeSoon()
            return
        }
        if (addToCombo(direction)) {
            comboBuffer.clear()
            RelayHudController.openInbox()
            keepReplyScreenOn(INBOX_WAKE_MS)
        }
        restoreCommandVolumeSoon()
    }

    private fun handleDirectionalComboFallback(direction: Direction): Boolean {
        if (!addToCombo(direction)) return false
        comboBuffer.clear()
        RelayHudController.openInbox()
        keepReplyScreenOn(INBOX_WAKE_MS)
        return true
    }

    private fun addToCombo(direction: Direction): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastComboInputAt > COMBO_TIMEOUT_MS) {
            comboBuffer.clear()
            commandVolume = null
        }
        lastComboInputAt = now
        comboBuffer.add(direction)
        if (comboBuffer.size > 4) comboBuffer.removeAt(0)
        return comboBuffer.size == 4 &&
            comboBuffer[0] == Direction.LEFT &&
            comboBuffer[1] == Direction.LEFT &&
            comboBuffer[2] == Direction.RIGHT &&
            comboBuffer[3] == Direction.RIGHT
    }

    private fun directionFromKey(keyCode: Int): Direction? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KEYCODE_SWIPE_BACK,
            -> Direction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KEYCODE_SWIPE_FORWARD,
            -> Direction.RIGHT
            else -> null
        }

    private fun isRelayControlKey(keyCode: Int): Boolean =
        directionFromKey(keyCode) != null ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_BACK

    private fun restoreCommandVolumeSoon() {
        val snapshot = commandVolume ?: return
        main.postDelayed({ snapshot.restore(audioManager) }, 80L)
        main.postDelayed({
            snapshot.restore(audioManager)
            if (!RelayHudController.isInboxOpen()) commandVolume = null
        }, 300L)
    }

    private fun showOverlay() {
        main.post {
            if (overlay != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = RelayHudView(this, overlayMode = true)
            val screenWidth = resources.displayMetrics.widthPixels
            val popupWidth = (screenWidth - dp(48))
                .coerceAtLeast((screenWidth * 0.72f).toInt())
                .coerceAtMost((screenWidth * 0.86f).toInt())
            val params = WindowManager.LayoutParams(
                popupWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(14)
            }
            runCatching {
                wm.addView(view, params)
                windowManager = wm
                overlay = view
                RelayHudController.attach(view)
            }.onFailure {
                RelayHudController.showTransient("Overlay failed: ${it.message}")
            }
        }
    }

    private fun hideOverlay() {
        main.post {
            val view = overlay ?: return@post
            RelayHudController.detach(view)
            runCatching { windowManager?.removeView(view) }
            overlay = null
            windowManager = null
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "RokidRelay:notification",
            )
            wakeLock.acquire(NOTIFICATION_WAKE_MS)
        }.onFailure {
            Log.w(TAG, "Wake screen failed: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun keepReplyScreenOn(durationMs: Long = REPLY_WAKE_MS) {
        runCatching {
            val wakeLock = replyWakeLock ?: run {
                val powerManager = getSystemService(PowerManager::class.java) ?: return
                powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "RokidRelay:reply",
                ).apply {
                    setReferenceCounted(false)
                    replyWakeLock = this
                }
            }
            wakeLock.acquire(durationMs)
        }.onFailure {
            Log.w(TAG, "Reply wake lock failed: ${it.message}")
        }
    }

    private fun releaseReplyWakeLock() {
        val wakeLock = replyWakeLock ?: return
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure {
            Log.w(TAG, "Reply wake lock release failed: ${it.message}")
        }
        replyWakeLock = null
    }

    companion object {
        private const val TAG = "RelayAccessibility"
        private const val ACTION_TWO_FINGER_SWIPE_FORWARD =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        private const val ACTION_TWO_FINGER_SWIPE_BACK =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
        private const val KEYCODE_SWIPE_FORWARD = 183
        private const val KEYCODE_SWIPE_BACK = 184
        private const val COMBO_TIMEOUT_MS = 2_200L
        private const val DOUBLE_TAP_MS = 220L
        private const val NOTIFICATION_WAKE_MS = 5_000L
        private const val REPLY_WAKE_MS = 60_000L
        private const val INBOX_WAKE_MS = 30_000L
        private const val POST_REPLY_WAKE_MS = 4_000L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Direction { LEFT, RIGHT }

    private class VolumeSnapshot(
        private val music: Int,
        private val system: Int,
    ) {
        fun restore(audioManager: AudioManager?) {
            audioManager ?: return
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, music, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, system, 0)
            }.onFailure {
                Log.w(TAG, "Volume restore failed: ${it.message}")
            }
        }

        companion object {
            fun capture(audioManager: AudioManager?): VolumeSnapshot? {
                audioManager ?: return null
                return runCatching {
                    VolumeSnapshot(
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                        audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM),
                    )
                }.getOrNull()
            }
        }
    }
}
