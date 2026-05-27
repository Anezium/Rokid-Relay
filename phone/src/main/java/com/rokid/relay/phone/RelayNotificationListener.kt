package com.rokid.relay.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RelayNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        RelayBridge.setStatus("notification listener connected")
        RelayStarter.startIfReady(this, "notification_listener")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pending = ReplyRepository.capture(this, sbn) ?: return
        RelayBridge.setStatus("replyable notification from ${pending.appLabel}")
        RelayBridge.sendNotification(pending)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        ReplyRepository.forgetStatusBarNotification(sbn)
        RelayBridge.sendInbox()
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "notification listener disconnected")
        RelayBridge.setStatus("notification listener disconnected")
        super.onListenerDisconnected()
    }

    companion object {
        private const val TAG = "RelayNotifListener"
    }
}
