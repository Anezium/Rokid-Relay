package com.anezium.rokidrelay.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

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
    private val grabbedKeys = HashSet<Int>()
    private val inputInterpreter = RelayInputInterpreter()
    private var wirelessDebuggingAutomator: SelfArmWirelessDebuggingAutomator? = null
    private var selfArmSettingsObserverRegistered = false
    private val selfArmSettingsObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            maybeRepairSelfArmSettings()
        }
    }
    private val singleTapRunnable = Runnable {
        executeInputActions(
            inputInterpreter.handleSingleTapTimer(RelayHudController.inputSnapshot()).actions,
        )
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
        activeService = this
        registerSelfArmSettingsObserver()
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        wirelessDebuggingAutomator = SelfArmWirelessDebuggingAutomator(this, main)
        RelayHudController.setNotificationOverlayYOffset(NotificationOverlaySettings.yOffsetDp(this))
        RelayHudController.setNotificationFontSizeSp(NotificationOverlaySettings.fontSizeSp(this))
        RelayHudController.refreshAccessibility(this)
        RelayHudController.addNotificationShownListener(notificationWakeListener)
        RelayHudController.addStateListener(replyWakeListener)
        RelayBridge.start(this)
        SelfArmController.maybeStart(this, "accessibility_service_connected") {
            RelayBridge.sendSelfArmState(this)
        }
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        AccessibilityWindowRoots.noteEvent(event, packageName)
        wirelessDebuggingAutomator?.onAccessibilityEvent(event)
        maybeAcceptAdbAuthorizationDialog(event)
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val snapshot = RelayHudController.inputSnapshot()
        val relayKey = isRelayControlKey(event.keyCode, snapshot)
        if (event.action == KeyEvent.ACTION_UP && grabbedKeys.remove(event.keyCode)) {
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return (event.keyCode in grabbedKeys) || (snapshot.relayActive && relayKey)
        }
        if (snapshot.relayActive && relayKey) {
            grabbedKeys.add(event.keyCode)
        }

        directionFromKey(event.keyCode)?.let { direction ->
            val decision = inputInterpreter.handleDirectionKey(
                snapshot = snapshot,
                direction = direction,
                nowMs = SystemClock.elapsedRealtime(),
            )
            executeInputActions(decision.actions)
            return decision.consumed
        }

        return when (event.keyCode) {
            in CONFIRM_KEYS -> {
                val decision = inputInterpreter.handleConfirm(
                    snapshot = snapshot,
                    mode = if (snapshot.inboxOpen) {
                        RelayInputInterpreter.ConfirmMode.INBOX_TAP
                    } else {
                        RelayInputInterpreter.ConfirmMode.SERVICE_IMMEDIATE
                    },
                )
                executeInputActions(decision.actions)
                decision.consumed
            }
            KeyEvent.KEYCODE_BACK -> {
                val decision = inputInterpreter.handleBack(
                    snapshot = snapshot,
                    mode = RelayInputInterpreter.BackMode.ACCESSIBILITY,
                )
                executeInputActions(decision.actions)
                decision.consumed
            }
            else -> false
        }
    }

    override fun onDestroy() {
        unregisterSelfArmSettingsObserver()
        hideOverlay()
        wirelessDebuggingAutomator?.stop()
        wirelessDebuggingAutomator = null
        if (activeService === this) activeService = null
        runCatching { unregisterReceiver(twoFingerReceiver) }
        main.removeCallbacks(singleTapRunnable)
        inputInterpreter.resetTransientState()
        clearCommandVolumeRunnable?.let { main.removeCallbacks(it) }
        clearCommandVolumeRunnable = null
        RelayHudController.removeNotificationShownListener(notificationWakeListener)
        RelayHudController.removeStateListener(replyWakeListener)
        releaseReplyWakeLock()
        RelayHudController.refreshAccessibility(this)
        RelayBridge.sendSelfArmState(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterSelfArmSettingsObserver()
        return super.onUnbind(intent)
    }

    private fun registerSelfArmSettingsObserver() {
        if (selfArmSettingsObserverRegistered) return
        val resolver = applicationContext.contentResolver
        runCatching {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                selfArmSettingsObserver,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                selfArmSettingsObserver,
            )
            selfArmSettingsObserverRegistered = true
        }.onFailure {
            runCatching { resolver.unregisterContentObserver(selfArmSettingsObserver) }
            Log.w(TAG, "Self-arm settings observer registration failed: ${it.message}")
        }
    }

    private fun unregisterSelfArmSettingsObserver() {
        if (!selfArmSettingsObserverRegistered) return
        selfArmSettingsObserverRegistered = false
        runCatching {
            applicationContext.contentResolver.unregisterContentObserver(selfArmSettingsObserver)
        }.onFailure {
            Log.w(TAG, "Self-arm settings observer unregistration failed: ${it.message}")
        }
    }

    private fun maybeRepairSelfArmSettings() {
        val appContext = applicationContext
        if (!SelfArmController.isArmed(appContext)) return
        runCatching {
            val resolver = appContext.contentResolver
            val enabledServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val accessibilityEnabled = Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            )
            if (SelfArmController.accessibilityRepairNeeded(enabledServices, accessibilityEnabled)) {
                SelfArmController.maybeStart(appContext, "settings_changed")
            }
        }.onFailure {
            Log.w(TAG, "Self-arm settings observer check failed: ${it.message}")
        }
    }

    private fun onTwoFinger(direction: RelayDirection) {
        executeInputActions(
            inputInterpreter.handleTwoFinger(
                snapshot = RelayHudController.inputSnapshot(),
                direction = direction,
                nowMs = SystemClock.elapsedRealtime(),
            ).actions,
        )
    }

    private fun notificationWakeDuration(popupDurationMs: Long): Long =
        if (popupDurationMs > 0L) {
            (popupDurationMs + NOTIFICATION_WAKE_MARGIN_MS).coerceAtLeast(MIN_NOTIFICATION_WAKE_MS)
        } else {
            NOTIFICATION_VISIBLE_WAKE_MS
        }

    private fun directionFromKey(keyCode: Int): RelayDirection? =
        RelayDirectionKeyMapper.directionFromKey(keyCode)

    private fun isRelayControlKey(
        keyCode: Int,
        snapshot: RelayInputInterpreter.Snapshot,
    ): Boolean =
        (directionFromKey(keyCode) != null && snapshot.directionKeysEnabled) ||
            isConfirmKey(keyCode) ||
            keyCode == KeyEvent.KEYCODE_BACK

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode in CONFIRM_KEYS

    private fun executeInputActions(actions: List<RelayInputInterpreter.Action>) {
        actions.forEach { action ->
            when (action) {
                RelayInputInterpreter.Action.StartVoice -> RelayBridge.startVoice()
                RelayInputInterpreter.Action.CancelVoice -> RelayBridge.cancelVoice()
                RelayInputInterpreter.Action.OpenInbox -> RelayHudController.openInbox()
                RelayInputInterpreter.Action.OpenInboxDetail -> RelayHudController.openInboxDetail()
                RelayInputInterpreter.Action.BackInInbox -> RelayHudController.backInInbox()
                RelayInputInterpreter.Action.HideNotification -> RelayBridge.hideNotification()
                RelayInputInterpreter.Action.OpenAccessibilitySettings -> Unit
                RelayInputInterpreter.Action.RestoreCommandVolumeSoon -> restoreCommandVolumeSoon()
                RelayInputInterpreter.Action.ScheduleCommandVolumeClear -> scheduleCommandVolumeClear()
                RelayInputInterpreter.Action.CancelSingleTap -> main.removeCallbacks(singleTapRunnable)
                is RelayInputInterpreter.Action.NavigateInbox -> RelayHudController.navigateInbox(action.delta)
                is RelayInputInterpreter.Action.PageInboxDetail -> RelayHudController.pageInboxDetail(action.delta)
                is RelayInputInterpreter.Action.PageNotification -> RelayHudController.pageNotification(action.delta)
                is RelayInputInterpreter.Action.KeepReplyScreenOn -> keepReplyScreenOn(action.durationMs)
                is RelayInputInterpreter.Action.CaptureCommandVolume -> captureCommandVolume(action.replaceExisting)
                is RelayInputInterpreter.Action.ScheduleSingleTap -> {
                    main.removeCallbacks(singleTapRunnable)
                    main.postDelayed(singleTapRunnable, action.delayMs)
                }
            }
        }
    }

    private fun captureCommandVolume(replaceExisting: Boolean) {
        if (!replaceExisting && commandVolume != null) return
        commandVolume = VolumeSnapshot.capture(audioManager)
    }

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

    private fun maybeAcceptAdbAuthorizationDialog(event: AccessibilityEvent) {
        if (!SelfArmController.adbAuthorizationPromptAllowed(this)) return
        val root = rootInActiveWindow ?: return
        if (!isSystemAdbPromptWindow(event, root)) return
        val text = collectNodeText(root).joinToString(" ").lowercase(Locale.US)
        val isAdbDialog = listOf(
            "usb debugging",
            "rsa key fingerprint",
            "debogage usb",
            "débogage usb",
            "empreinte rsa",
        ).any { it in text }
        if (!isAdbDialog) return
        if (!SelfArmController.adbAuthorizationPromptMatchesExpectedKey(this, text)) return

        clickFirstMatching(root) { node ->
            val nodeText = node.nodeText()
            node.isCheckable && (
                "always allow" in nodeText ||
                    "toujours autoriser" in nodeText ||
                    "always trust" in nodeText
                )
        }
        val clicked = clickFirstMatching(root) { node ->
            val nodeText = node.nodeText()
            node.isClickable && (
                nodeText == "ok" ||
                    nodeText == "allow" ||
                    nodeText == "autoriser" ||
                    nodeText == "yes"
                )
        }
        if (clicked) Log.i(TAG, "accepted ADB authorization dialog")
    }

    private fun isSystemAdbPromptWindow(event: AccessibilityEvent, root: AccessibilityNodeInfo): Boolean {
        val packages = listOfNotNull(
            event.packageName?.toString(),
            root.packageName?.toString(),
        ).map { it.lowercase(Locale.US) }
        if (packages.none { it in ADB_AUTH_PROMPT_PACKAGES }) return false

        val classes = listOfNotNull(
            event.className?.toString(),
            root.className?.toString(),
        ).map { it.lowercase(Locale.US) }
        if (classes.isEmpty()) return true
        return classes.any { className ->
            "usbdebugging" in className ||
                "alertdialog" in className ||
                "dialog" in className ||
                "systemui" in className
        }
    }

    private fun collectNodeText(node: AccessibilityNodeInfo): List<String> {
        val values = mutableListOf<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { values += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { values += it }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                values += collectNodeText(child)
            }
        }
        return values
    }

    private fun clickFirstMatching(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        if (predicate(node)) {
            val target = clickableNode(node)
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                if (clickFirstMatching(child, predicate)) return true
            }
        }
        return false
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = current ?: return null
            if (candidate.isClickable) return candidate
            current = candidate.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.nodeText(): String =
        listOfNotNull(text?.toString(), contentDescription?.toString())
            .joinToString(" ")
            .trim()
            .lowercase(Locale.US)

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
        @Volatile private var activeService: RelayAccessibilityService? = null

        fun startSelfArmWirelessSetup(): Boolean {
            val service = activeService
            if (service == null) {
                RelayBridge.sendSelfArmWirelessStatus(
                    setupState = "accessibility_service_needed",
                    wifiIp = "",
                    adbConnectPort = 0,
                )
                return false
            }
            service.main.post {
                service.wirelessDebuggingAutomator?.start()
            }
            return true
        }

        private val ADB_AUTH_PROMPT_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.rokid.settings",
            "com.rokid.systemui",
        )
        private const val ACTION_TWO_FINGER_SWIPE_FORWARD =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        private const val ACTION_TWO_FINGER_SWIPE_BACK =
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
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
