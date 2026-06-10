package com.anezium.rokidrelay.glasses

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var hud: RelayHudView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )

        hud = RelayHudView(this)
        setContentView(hud)
        RelayHudController.attach(hud)
        RelayHudController.setNotificationOverlayYOffset(NotificationOverlaySettings.yOffsetDp(this))
        RelayHudController.setNotificationFontSizeSp(NotificationOverlaySettings.fontSizeSp(this))
        RelayHudController.refreshAccessibility(this)
        RelayBridge.start(this)
    }

    override fun onResume() {
        super.onResume()
        RelayHudController.refreshAccessibility(this)
    }

    override fun onDestroy() {
        RelayHudController.detach(hud)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount ?: 0 > 0) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KEYCODE_SWIPE_FORWARD,
            KEYCODE_SWIPE_BACK,
            -> {
                // Direction keys all come from single-finger swipes; in two-finger mode
                // they are ignored so only the two-finger broadcasts drive the relay.
                if (!RelayHudController.isInputSourceEnabled(RelayInputSource.NORMAL)) return true
                handleDirection(directionFromKey(keyCode) ?: return false)
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                when {
                    RelayHudController.isVoiceReviewing() -> RelayBridge.startVoice()
                    RelayHudController.isVoiceActive() -> RelayBridge.cancelVoice()
                    RelayHudController.isInboxDetailOpen() -> RelayBridge.startVoice()
                    RelayHudController.isInboxOpen() -> RelayHudController.openInboxDetail()
                    RelayHudController.hasNotification() -> RelayBridge.startVoice()
                    else -> openAccessibilitySettings()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (RelayHudController.isInboxOpen()) {
                    if (RelayHudController.isVoiceActive()) RelayBridge.cancelVoice()
                    RelayHudController.backInInbox()
                    true
                } else if (RelayHudController.hasNotification()) {
                    RelayBridge.hideNotification()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleDirection(direction: RelayDirection): Boolean {
        if (RelayHudController.isVoiceActive()) return true
        if (RelayHudController.isInboxDetailOpen()) {
            pageInboxDetail(direction)
            return true
        }
        if (RelayHudController.isInboxOpen()) {
            RelayHudController.navigateInbox(if (direction == RelayDirection.LEFT) -1 else 1)
            return true
        }
        if (RelayHudController.hasNotification()) {
            if (!pageNotification(direction)) RelayBridge.startVoice()
            return true
        }
        return if (addToCombo(direction)) {
            RelayHudController.openInbox()
            comboBuffer.clear()
            true
        } else {
            false
        }
    }

    private fun pageInboxDetail(direction: RelayDirection) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInboxPageAtMs < INBOX_PAGE_DEBOUNCE_MS) return
        lastInboxPageAtMs = now
        RelayHudController.pageInboxDetail(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun pageNotification(direction: RelayDirection): Boolean {
        if (!RelayHudController.hasPagedNotification()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastInboxPageAtMs < INBOX_PAGE_DEBOUNCE_MS) return true
        lastInboxPageAtMs = now
        return RelayHudController.pageNotification(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun addToCombo(direction: RelayDirection): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastComboInputAt > COMBO_TIMEOUT_MS) comboBuffer.clear()
        lastComboInputAt = now
        comboBuffer.add(direction)
        while (comboBuffer.size > RelayInputSettings.MAX_COMBO_LENGTH) comboBuffer.removeAt(0)
        return RelayInputSettings.matchesCombo(comboBuffer, RelayHudController.inputCombo())
    }

    private fun directionFromKey(keyCode: Int): RelayDirection? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KEYCODE_SWIPE_BACK,
            -> RelayDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KEYCODE_SWIPE_FORWARD,
            -> RelayDirection.RIGHT
            else -> null
        }

    private fun openAccessibilitySettings() {
        RelayHudController.showTransient("Enable Rokid Relay accessibility")
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    companion object {
        private const val KEYCODE_SWIPE_FORWARD = 183
        private const val KEYCODE_SWIPE_BACK = 184
        private const val COMBO_TIMEOUT_MS = 2_200L
        private const val INBOX_PAGE_DEBOUNCE_MS = 480L
    }

    private val comboBuffer = ArrayList<RelayDirection>(RelayInputSettings.MAX_COMBO_LENGTH)
    private var lastComboInputAt = 0L
    private var lastInboxPageAtMs = 0L
}
