package com.anezium.rokidrelay.glasses

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
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class RelayAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val notificationWakeListener: () -> Unit = { wakeScreen() }
    private val replyWakeListener: (RelayHudController.State) -> Unit = { state ->
        applyOverlayPosition(state.notificationOverlayYOffsetDp)
        if (
            state.voiceState == "listening" ||
            state.voiceState == "recognizing" ||
            state.voiceState == "processing" ||
            state.voiceState == "reviewing"
        ) {
            val duration = if (state.voiceState == "reviewing" && state.countdownMs > 0L) {
                (state.countdownMs + REVIEW_WAKE_MARGIN_MS).coerceAtLeast(MIN_REPLY_WAKE_MS)
            } else {
                REPLY_WAKE_MS
            }
            keepReplyScreenOnThrottled("voice:${state.voiceState}", duration)
        } else if (state.inboxVisible) {
            keepReplyScreenOnThrottled("inbox", INBOX_WAKE_MS)
        } else if (state.replyOk && state.resultLine.isNotBlank()) {
            keepReplyScreenOnThrottled("sent:${state.replyEventId}", POST_REPLY_WAKE_MS)
        } else if (state.notification != null) {
            val duration = notificationWakeDuration(state.notificationPopupDurationMs)
            keepReplyScreenOnThrottled(
                "notification:${state.notification.id}:${state.notificationPopupDurationMs}",
                duration,
            )
        } else {
            releaseReplyWakeLock()
        }
    }
    private var windowManager: WindowManager? = null
    private var overlay: RelayHudView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayYOffsetDp = NotificationOverlaySettings.DEFAULT_Y_OFFSET_DP
    private var replyWakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var commandVolume: VolumeSnapshot? = null
    private var lastReplyWakeSignature = ""
    private var lastReplyWakeAtMs = 0L
    private var lastComboInputAt = 0L
    private var lastInboxPageAtMs = 0L
    private var tapArmed = false
    private val grabbedKeys = HashSet<Int>()
    private val comboBuffer = ArrayList<RelayDirection>(RelayInputSettings.MAX_COMBO_LENGTH)
    private val singleTapRunnable = Runnable {
        tapArmed = false
        runInboxSingleTap()
    }

    private val twoFingerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TWO_FINGER_SWIPE_BACK -> onTwoFinger(RelayDirection.LEFT)
                ACTION_TWO_FINGER_SWIPE_FORWARD -> onTwoFinger(RelayDirection.RIGHT)
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
        RelayHudController.setNotificationOverlayYOffset(NotificationOverlaySettings.yOffsetDp(this))
        RelayHudController.refreshAccessibility(this)
        RelayHudController.addNotificationShownListener(notificationWakeListener)
        RelayHudController.addStateListener(replyWakeListener)
        RelayBridge.start(this)
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val relayActive = isRelayInteractionActive()
        val relayKey = isRelayControlKey(event.keyCode)
        if (event.action == KeyEvent.ACTION_UP && grabbedKeys.remove(event.keyCode)) {
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return (event.keyCode in grabbedKeys) || (relayActive && relayKey)
        }
        if (relayActive && relayKey) {
            grabbedKeys.add(event.keyCode)
        }

        if (RelayHudController.isInboxOpen()) {
            return handleInboxKey(event.keyCode)
        }

        if (RelayHudController.isVoiceReviewing() && relayKey) {
            if (isConfirmKey(event.keyCode)) {
                RelayBridge.startVoice()
            } else if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                RelayBridge.cancelVoice()
            }
            return true
        }

        if (RelayHudController.isVoiceActive() && relayKey) {
            if (isConfirmKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_BACK) {
                RelayBridge.cancelVoice()
            }
            return true
        }

        directionFromKey(event.keyCode)?.let { direction ->
            keyInputSource(event.keyCode)?.let { source ->
                if (!RelayHudController.isInputSourceEnabled(source)) return true
            }
            if (RelayHudController.hasNotification()) {
                keepReplyScreenOn()
                RelayBridge.startVoice()
                return true
            }
            return handleDirectionalComboFallback(direction)
        }

        return when (event.keyCode) {
            in CONFIRM_KEYS -> {
                if (RelayHudController.hasNotification()) {
                    RelayBridge.hideNotification()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (RelayHudController.hasNotification()) {
                    RelayBridge.hideNotification()
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
        if (RelayHudController.isVoiceActive()) {
            return when {
                RelayHudController.isVoiceReviewing() && isConfirmKey(keyCode) -> {
                    RelayBridge.startVoice()
                    true
                }
                isConfirmKey(keyCode) -> {
                    RelayBridge.cancelVoice()
                    true
                }
                keyCode == KeyEvent.KEYCODE_BACK -> {
                    handleInboxBack()
                    true
                }
                isRelayControlKey(keyCode) -> true
                else -> false
            }
        }

        directionFromKey(keyCode)?.let { direction ->
            keyInputSource(keyCode)?.let { source ->
                if (!RelayHudController.isInputSourceEnabled(source)) return true
            }
            tapArmed = false
            main.removeCallbacks(singleTapRunnable)
            if (RelayHudController.isInboxDetailOpen()) {
                pageInboxDetail(direction)
            } else {
                RelayHudController.navigateInbox(if (direction == RelayDirection.LEFT) -1 else 1)
            }
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

    private fun onTwoFinger(direction: RelayDirection) {
        if (!RelayHudController.isInputSourceEnabled(RelayInputSource.TWO_FINGER)) return
        if (commandVolume == null) commandVolume = VolumeSnapshot.capture(audioManager)
        if (RelayHudController.isInboxOpen()) {
            if (RelayHudController.isInboxDetailOpen()) {
                pageInboxDetail(direction)
            } else {
                RelayHudController.navigateInbox(if (direction == RelayDirection.LEFT) -1 else 1)
            }
            keepReplyScreenOn(INBOX_WAKE_MS)
            restoreCommandVolumeSoon()
            return
        }
        if (RelayHudController.hasNotification()) {
            keepReplyScreenOn()
            RelayBridge.startVoice()
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

    private fun handleDirectionalComboFallback(direction: RelayDirection): Boolean {
        if (!addToCombo(direction)) return false
        comboBuffer.clear()
        RelayHudController.openInbox()
        keepReplyScreenOn(INBOX_WAKE_MS)
        return true
    }

    private fun pageInboxDetail(direction: RelayDirection) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInboxPageAtMs < INBOX_PAGE_DEBOUNCE_MS) return
        lastInboxPageAtMs = now
        RelayHudController.pageInboxDetail(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun notificationWakeDuration(popupDurationMs: Long): Long =
        if (popupDurationMs > 0L) {
            (popupDurationMs + NOTIFICATION_WAKE_MARGIN_MS).coerceAtLeast(MIN_NOTIFICATION_WAKE_MS)
        } else {
            NOTIFICATION_VISIBLE_WAKE_MS
        }

    private fun addToCombo(direction: RelayDirection): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastComboInputAt > COMBO_TIMEOUT_MS) {
            comboBuffer.clear()
            commandVolume = null
        }
        lastComboInputAt = now
        comboBuffer.add(direction)
        while (comboBuffer.size > RelayInputSettings.MAX_COMBO_LENGTH) comboBuffer.removeAt(0)
        return RelayInputSettings.matchesCombo(comboBuffer, RelayHudController.inputCombo())
    }

    private fun directionFromKey(keyCode: Int): RelayDirection? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_SWIPE_BACK,
            -> RelayDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KEYCODE_SWIPE_FORWARD,
            -> RelayDirection.RIGHT
            else -> null
        }

    private fun keyInputSource(keyCode: Int): RelayInputSource? =
        when (keyCode) {
            KEYCODE_SWIPE_BACK,
            KEYCODE_SWIPE_FORWARD,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            -> RelayInputSource.NORMAL
            else -> null
        }

    private fun isRelayControlKey(keyCode: Int): Boolean =
        directionFromKey(keyCode) != null ||
            isConfirmKey(keyCode) ||
            keyCode == KeyEvent.KEYCODE_BACK

    private fun isRelayInteractionActive(): Boolean =
        RelayHudController.isInboxOpen() ||
            RelayHudController.hasNotification() ||
            RelayHudController.isVoiceActive()

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode in CONFIRM_KEYS

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
            val popupWidth = (screenWidth - dp(20))
                .coerceAtLeast((screenWidth * 0.9f).toInt())
                .coerceAtMost(screenWidth - dp(8))
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
                y = dp(RelayHudController.notificationOverlayYOffsetDp())
            }
            runCatching {
                wm.addView(view, params)
                windowManager = wm
                overlay = view
                overlayParams = params
                overlayYOffsetDp = RelayHudController.notificationOverlayYOffsetDp()
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
            overlayParams = null
            windowManager = null
        }
    }

    private fun applyOverlayPosition(yOffsetDp: Int) {
        val wm = windowManager ?: return
        val view = overlay ?: return
        val params = overlayParams ?: return
        val cleanOffset = NotificationOverlaySettings.sanitizeYOffsetDp(yOffsetDp)
        val cleanOffsetPx = dp(cleanOffset)
        if (overlayYOffsetDp == cleanOffset && params.y == cleanOffsetPx) return
        params.y = cleanOffsetPx
        overlayYOffsetDp = cleanOffset
        runCatching {
            wm.updateViewLayout(view, params)
        }.onFailure {
            Log.w(TAG, "Overlay position update failed: ${it.message}")
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

    private fun keepReplyScreenOnThrottled(signature: String, durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (signature == lastReplyWakeSignature && now - lastReplyWakeAtMs < REPLY_WAKE_REFRESH_MS) return
        lastReplyWakeSignature = signature
        lastReplyWakeAtMs = now
        keepReplyScreenOn(durationMs)
    }

    private fun releaseReplyWakeLock() {
        val wakeLock = replyWakeLock
        if (wakeLock == null) {
            lastReplyWakeSignature = ""
            lastReplyWakeAtMs = 0L
            return
        }
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure {
            Log.w(TAG, "Reply wake lock release failed: ${it.message}")
        }
        replyWakeLock = null
        lastReplyWakeSignature = ""
        lastReplyWakeAtMs = 0L
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
        private const val INBOX_PAGE_DEBOUNCE_MS = 480L
        private const val NOTIFICATION_WAKE_MS = 5_000L
        private const val NOTIFICATION_WAKE_MARGIN_MS = 2_000L
        private const val MIN_NOTIFICATION_WAKE_MS = 5_000L
        private const val NOTIFICATION_VISIBLE_WAKE_MS = 45_000L
        private const val REPLY_WAKE_MS = 35_000L
        private const val REPLY_WAKE_REFRESH_MS = 12_000L
        private const val MIN_REPLY_WAKE_MS = 5_000L
        private const val REVIEW_WAKE_MARGIN_MS = 2_500L
        private const val INBOX_WAKE_MS = 30_000L
        private const val POST_REPLY_WAKE_MS = 4_000L
        private val CONFIRM_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_BUTTON_A,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
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
