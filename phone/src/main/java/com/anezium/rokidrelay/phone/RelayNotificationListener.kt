package com.anezium.rokidrelay.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RelayNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationControl.attach(this)
        RelayBridge.setStatus("notification listener connected")
        RelayStarter.startIfReady(this, "notification_listener")
        syncActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val capture = ReplyRepository.capture(this, sbn) ?: return
        Log.i(
            TAG,
            "posted pkg=${sbn.packageName} id=${capture.reply.id.take(8)} show=${capture.shouldShowNow}",
        )
        if (capture.shouldShowNow) {
            RelayBridge.setStatus("replyable notification from ${capture.reply.appLabel}")
            RelayBridge.sendNotification(capture.reply)
        } else {
            RelayBridge.sendInbox()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        Log.i(TAG, "removed pkg=${sbn.packageName}")
        ReplyRepository.forgetStatusBarNotification(sbn)
        RelayBridge.sendInbox()
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
