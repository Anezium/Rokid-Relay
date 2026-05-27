package com.rokid.relay.phone

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

object NotificationControl {
    private const val TAG = "RelayNotifControl"
    private const val CANCEL_AFTER_REPLY_DELAY_MS = 450L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: NotificationListenerService? = null

    fun attach(service: NotificationListenerService) {
        listener = service
    }

    fun detach(service: NotificationListenerService) {
        if (listener === service) listener = null
    }

    fun cancelAfterReply(key: String) {
        if (key.isBlank()) return
        main.postDelayed({
            val service = listener
            if (service == null) {
                Log.w(TAG, "Cannot clear replied notification: listener unavailable")
                return@postDelayed
            }
            runCatching {
                service.cancelNotification(key)
                RelayBridge.setStatus("phone notification cleared after reply")
            }.onFailure {
                Log.w(TAG, "Clear replied notification failed: ${it.message}")
            }
        }, CANCEL_AFTER_REPLY_DELAY_MS)
    }
}
