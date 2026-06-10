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
        RelayDirectionKeyMapper.directionFromKey(keyCode)?.let { direction ->
            if (!RelayHudController.directionKeysEnabled()) return super.onKeyDown(keyCode, event)
            return handleDirection(direction)
        }
        return when (keyCode) {
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
        if (!directionDebouncer.accept(direction, SystemClock.elapsedRealtime())) return true
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
        RelayHudController.pageInboxDetail(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun pageNotification(direction: RelayDirection): Boolean {
        if (!RelayHudController.hasPagedNotification()) return false
        return RelayHudController.pageNotification(if (direction == RelayDirection.LEFT) -1 else 1)
    }

    private fun addToCombo(direction: RelayDirection): Boolean {
        return comboBuffer.add(
            nowMs = SystemClock.elapsedRealtime(),
            direction = direction,
            combo = RelayHudController.inputCombo(),
        ).matched
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

    private val comboBuffer = RelayInputComboBuffer()
    private val directionDebouncer = RelayDirectionDebouncer()
}
