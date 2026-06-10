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
    private var clearCommandVolumeRunnable: Runnable? = null
    private var lastReplyWakeSignature = ""
    private var lastReplyWakeAtMs = 0L
    private val directionDebouncer = RelayDirectionDebouncer()
    private val inboxDirectionGate = RelayInboxDirectionGate()
    private var lastAppliedInboxDirection: AppliedInboxDirection? = null
    private var tapArmed = false
    private val grabbedKeys = HashSet<Int>()
    private val comboBuffer = RelayInputComboBuffer()
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
        RelayHudController.setNotificationFontSizeSp(NotificationOverlaySettings.fontSizeSp(this))
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
            if (!directionKeysEnabled()) return false
            if (!directionDebouncer.accept(direction, SystemClock.elapsedRealtime())) return relayActive
            if (RelayHudController.hasNotification()) {
                keepReplyScreenOn()
                if (!pageNotification(direction)) RelayBridge.startVoice()
                return true
            }
            return handleDirectionalComboFallback(direction)
        }

        return when (event.keyCode) {
            in CONFIRM_KEYS -> {
                if (RelayHudController.hasNotification()) {
                    keepReplyScreenOn()
                    RelayBridge.startVoice()
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
        lastAppliedInboxDirection = null
        clearCommandVolumeRunnable?.let { main.removeCallbacks(it) }
        clearCommandVolumeRunnable = null
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
            val now = SystemClock.elapsedRealtime()
            if (!directionDebouncer.accept(direction, now)) return true
            if (inboxDirectionGate.acceptDirectionKey(now)) {
                lastAppliedInboxDirection = applyInboxDirection(direction, now)
            }
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
        lastAppliedInboxDirection = null
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
        lastAppliedInboxDirection = null
        if (RelayHudController.isVoiceActive()) RelayBridge.cancelVoice()
        RelayHudController.backInInbox()
    }

    private fun onTwoFinger(direction: RelayDirection) {
        val now = SystemClock.elapsedRealtime()
        if (RelayHudController.isInboxOpen()) {
            suppressInboxDirectionForTwoFinger(now)
            return
        }
        if (!RelayHudController.twoFingerCommandsEnabled()) return
        suppressInboxDirectionForTwoFinger(now)
        if (!directionDebouncer.accept(direction, now)) return
        if (RelayHudController.hasNotification()) {
            if (commandVolume == null) commandVolume = VolumeSnapshot.capture(audioManager)
            keepReplyScreenOn()
            if (!pageNotification(direction)) RelayBridge.startVoice()
            restoreCommandVolumeSoon()
            return
        }

        val hadComboInput = comboBuffer.snapshot().isNotEmpty()
        val comboResult = addToCombo(direction)
        if (!hadComboInput || comboResult.resetBeforeAdd) {
            commandVolume = VolumeSnapshot.capture(audioManager)
        }
        if (comboResult.matched) {
            comboBuffer.clear()
            RelayHudController.openInbox()
            keepReplyScreenOn(INBOX_WAKE_MS)
            restoreCommandVolumeSoon()
        } else {
            scheduleCommandVolumeClear()
        }
    }

    private fun applyInboxDirection(direction: RelayDirection, nowMs: Long): AppliedInboxDirection? {
        tapArmed = false
        main.removeCallbacks(singleTapRunnable)
        if (!RelayHudController.isInboxOpen() || RelayHudController.isVoiceActive()) return null
        val kind = if (RelayHudController.isInboxDetailOpen()) {
            if (!pageInboxDetail(direction)) return null
            AppliedInboxDirection.Kind.DETAIL_PAGE
        } else {
            RelayHudController.navigateInbox(if (direction == RelayDirection.LEFT) -1 else 1)
            AppliedInboxDirection.Kind.LIST
        }
        keepReplyScreenOn(INBOX_WAKE_MS)
        return AppliedInboxDirection(kind, direction, nowMs)
    }

    private fun suppressInboxDirectionForTwoFinger(nowMs: Long) {
        inboxDirectionGate.onTwoFinger(nowMs)
        undoRecentInboxDirectionForTwoFinger(nowMs)
    }

    private fun undoRecentInboxDirectionForTwoFinger(nowMs: Long) {
        val applied = lastAppliedInboxDirection ?: return
        if (!inboxDirectionGate.shouldUndoDirectionForTwoFinger(applied.receivedAtMs, nowMs)) return
        lastAppliedInboxDirection = null
        if (!RelayHudController.isInboxOpen() || RelayHudController.isVoiceActive()) return
        val reverseDelta = if (applied.direction == RelayDirection.LEFT) 1 else -1
        when (applied.kind) {
            AppliedInboxDirection.Kind.LIST -> RelayHudController.navigateInbox(reverseDelta)
            AppliedInboxDirection.Kind.DETAIL_PAGE -> RelayHudController.pageInboxDetail(reverseDelta)
        }
    }

    private fun handleDirectionalComboFallback(direction: RelayDirection): Boolean {
        if (!addToCombo(direction).matched) return false
        comboBuffer.clear()
        RelayHudController.openInbox()
        keepReplyScreenOn(INBOX_WAKE_MS)
        return true
    }

    private fun pageInboxDetail(direction: RelayDirection): Boolean {
        return RelayHudController.pageInboxDetail(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun pageNotification(direction: RelayDirection): Boolean {
        if (!RelayHudController.hasPagedNotification()) return false
        return RelayHudController.pageNotification(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun notificationWakeDuration(popupDurationMs: Long): Long =
        if (popupDurationMs > 0L) {
            (popupDurationMs + NOTIFICATION_WAKE_MARGIN_MS).coerceAtLeast(MIN_NOTIFICATION_WAKE_MS)
        } else {
            NOTIFICATION_VISIBLE_WAKE_MS
        }

    private fun addToCombo(direction: RelayDirection): RelayInputComboBuffer.Result =
        comboBuffer.add(
            nowMs = SystemClock.elapsedRealtime(),
            direction = direction,
            combo = RelayHudController.inputCombo(),
        )

    private fun directionFromKey(keyCode: Int): RelayDirection? =
        RelayDirectionKeyMapper.directionFromKey(keyCode)

    /**
     * Direction keys come from single-finger swipes; two-finger swipes arrive as broadcasts.
     */
    private fun directionKeysEnabled(): Boolean =
        RelayHudController.directionKeysEnabled()

    private fun isRelayControlKey(keyCode: Int): Boolean =
        (directionFromKey(keyCode) != null && directionKeysEnabled()) ||
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
        clearCommandVolumeRunnable?.let { main.removeCallbacks(it) }
        clearCommandVolumeRunnable = null
        main.postDelayed({ snapshot.restore(audioManager) }, 80L)
        main.postDelayed({
            snapshot.restore(audioManager)
            commandVolume = null
        }, 300L)
    }

    private fun scheduleCommandVolumeClear() {
        clearCommandVolumeRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            commandVolume = null
            clearCommandVolumeRunnable = null
        }
        clearCommandVolumeRunnable = runnable
        main.postDelayed(runnable, RelayInputComboBuffer.DEFAULT_TIMEOUT_MS + COMMAND_VOLUME_CLEAR_MARGIN_MS)
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
        private const val DOUBLE_TAP_MS = 220L
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
        private const val COMMAND_VOLUME_CLEAR_MARGIN_MS = 200L
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

    private data class AppliedInboxDirection(
        val kind: Kind,
        val direction: RelayDirection,
        val receivedAtMs: Long,
    ) {
        enum class Kind {
            LIST,
            DETAIL_PAGE,
        }
    }

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
