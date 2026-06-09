package com.anezium.rokidrelay.phone

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

object NotificationControl {
    private const val TAG = "RelayNotifControl"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: NotificationListenerService? = null

    fun attach(service: NotificationListenerService) {
        listener = service
        Log.i(TAG, "notification listener attached")
    }

    fun detach(service: NotificationListenerService) {
        if (listener === service) listener = null
    }

    fun refreshActiveNotifications() {
        main.post {
            val service = listener
            if (service == null) {
                Log.w(TAG, "Cannot refresh active notifications: listener unavailable")
                return@post
            }
            if (NotificationForwardingPolicy.isPaused(service)) {
                Log.i(TAG, "active notification refresh skipped: phone screen on")
                RelayBridge.sendInbox()
                return@post
            }
            val count = runCatching {
                service.activeNotifications.orEmpty().count { sbn ->
                    ReplyRepository.capture(service, sbn) != null
                }
            }.onFailure {
                Log.w(TAG, "Active notification refresh failed: ${it.message}")
            }.getOrDefault(0)
            Log.i(TAG, "active notification refresh count=$count")
            RelayBridge.sendInbox()
        }
    }

    fun cancelAfterReply(key: String) {
        if (key.isBlank()) return
        CANCEL_AFTER_REPLY_DELAYS_MS.forEach { delay ->
            main.postDelayed({
                val service = listener
                if (service == null) {
                    Log.w(TAG, "Cannot clear replied notification: listener unavailable")
                    return@postDelayed
                }
                runCatching {
                    service.cancelNotification(key)
                    RelayBridge.setStatus("phone notification cleared after reply")
                    Log.i(TAG, "clear replied notification requested delayMs=$delay")
                }.onFailure {
                    Log.w(TAG, "Clear replied notification failed: ${it.message}")
                }
            }, delay)
        }
    }

    private val CANCEL_AFTER_REPLY_DELAYS_MS = longArrayOf(250L, 1_000L, 2_500L)
}
