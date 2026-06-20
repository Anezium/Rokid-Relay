package com.anezium.rokidrelay.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RelayNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationControl.attach(this)
        RelayBridge.setStatus("notification listener connected")
        if (RelayStarter.isRelayEnabled(this) && RelayService.running) {
            syncActiveNotifications()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!RelayStarter.isRelayEnabled(this)) return
        if (NotificationForwardingPolicy.isPaused(this)) {
            Log.i(TAG, "posted pkg=${sbn.packageName} skipped=phone_screen_on")
            RelayBridge.setStatus("notification forwarding paused: phone screen on")
            if (RelayService.running) RelayBridge.sendInbox()
            return
        }
        val capture = ReplyRepository.capture(this, sbn) ?: return
        Log.i(
            TAG,
            "posted pkg=${sbn.packageName} id=${capture.reply.id.take(8)} show=${capture.shouldShowNow}",
        )
        if (capture.shouldShowNow) {
            RelayBridge.setStatus("notification wake: ${capture.reply.appLabel}")
            RelayBridge.sendNotification(capture.reply)
            RelayStarter.wakeForNotification(this)
        } else {
            if (RelayService.running) RelayBridge.sendInbox()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!RelayStarter.isRelayEnabled(this)) return
        Log.i(TAG, "removed pkg=${sbn.packageName}")
        ReplyRepository.forgetStatusBarNotification(sbn)
        if (RelayService.running) RelayBridge.sendInbox()
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "notification listener disconnected")
        NotificationControl.detach(this)
        RelayBridge.setStatus("notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NotificationControl.detach(this)
        super.onDestroy()
    }

    private fun syncActiveNotifications() {
        if (NotificationForwardingPolicy.isPaused(this)) {
            Log.i(TAG, "active notification sync skipped: phone screen on")
            RelayBridge.sendInbox()
            return
        }
        val count = runCatching {
            activeNotifications.orEmpty().count { sbn ->
                ReplyRepository.capture(this, sbn) != null
            }
        }.onFailure {
            Log.w(TAG, "active notification sync failed: ${it.message}")
        }.getOrDefault(0)
        if (count > 0) {
            Log.i(TAG, "active notification sync count=$count")
            RelayBridge.sendInbox()
        }
    }

    companion object {
        private const val TAG = "RelayNotifListener"
    }
}
