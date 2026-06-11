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
        val snapshot = RelayHudController.inputSnapshot()
        RelayDirectionKeyMapper.directionFromKey(keyCode)?.let { direction ->
            if (!snapshot.directionKeysEnabled) return super.onKeyDown(keyCode, event)
            val decision = inputInterpreter.handleDirectionKey(
                snapshot = snapshot,
                direction = direction,
                nowMs = SystemClock.elapsedRealtime(),
            )
            executeInputActions(decision.actions)
            return decision.consumed
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                val decision = inputInterpreter.handleConfirm(
                    snapshot = snapshot,
                    mode = RelayInputInterpreter.ConfirmMode.ACTIVITY_IMMEDIATE,
                )
                executeInputActions(decision.actions)
                if (decision.consumed) true else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_BACK -> {
                val decision = inputInterpreter.handleBack(
                    snapshot = snapshot,
                    mode = RelayInputInterpreter.BackMode.ACTIVITY,
                )
                executeInputActions(decision.actions)
                if (decision.consumed) true else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun executeInputActions(actions: List<RelayInputInterpreter.Action>) {
        actions.forEach { action ->
            when (action) {
                RelayInputInterpreter.Action.StartVoice -> RelayBridge.startVoice()
                RelayInputInterpreter.Action.CancelVoice -> RelayBridge.cancelVoice()
                RelayInputInterpreter.Action.OpenInbox -> RelayHudController.openInbox()
                RelayInputInterpreter.Action.OpenInboxDetail -> RelayHudController.openInboxDetail()
                RelayInputInterpreter.Action.BackInInbox -> RelayHudController.backInInbox()
                RelayInputInterpreter.Action.HideNotification -> RelayBridge.hideNotification()
                RelayInputInterpreter.Action.OpenAccessibilitySettings -> openAccessibilitySettings()
                is RelayInputInterpreter.Action.NavigateInbox -> RelayHudController.navigateInbox(action.delta)
                is RelayInputInterpreter.Action.PageInboxDetail -> RelayHudController.pageInboxDetail(action.delta)
                is RelayInputInterpreter.Action.PageNotification -> RelayHudController.pageNotification(action.delta)
                is RelayInputInterpreter.Action.KeepReplyScreenOn,
                is RelayInputInterpreter.Action.CaptureCommandVolume,
                RelayInputInterpreter.Action.RestoreCommandVolumeSoon,
                RelayInputInterpreter.Action.ScheduleCommandVolumeClear,
                RelayInputInterpreter.Action.CancelSingleTap,
                is RelayInputInterpreter.Action.ScheduleSingleTap,
                -> Unit
            }
        }
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

    private val inputInterpreter = RelayInputInterpreter()
}
