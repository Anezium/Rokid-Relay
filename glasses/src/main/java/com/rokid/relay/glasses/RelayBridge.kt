package com.rokid.relay.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject

object RelayBridge {
    private const val TAG = "RelayBridge"
    private const val VOICE_COMMAND_DEBOUNCE_MS = 900L

    private val main = Handler(Looper.getMainLooper())
    private var bridge: CXRServiceBridge? = null
    private var lastVoiceCommandAtMs = 0L

    fun start() {
        if (bridge != null) {
            RelayHudController.setConnection("connected")
            requestState()
            return
        }
        val cxr = CXRServiceBridge()
        bridge = cxr
        RelayHudController.setConnection("connecting")
        cxr.setStatusListener(statusListener)
        val result = cxr.subscribe(Constants.KEY_EVENT, msgCallback)
        Log.d(TAG, "subscribe result=$result")
        requestState()
    }

    fun stop() {
        bridge = null
    }

    fun startVoice() {
        val notification = RelayHudController.currentNotificationId()
        if (notification.isBlank()) {
            RelayHudController.showTransient("No replyable notification")
            return
        }
        if (RelayHudController.isVoiceReviewing()) {
            sendCommand("retry_voice") {
                put("notificationId", notification)
            }
            RelayHudController.setVoice("listening", "")
            return
        }
        if (RelayHudController.isVoiceActive()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceCommandAtMs < VOICE_COMMAND_DEBOUNCE_MS) return
        lastVoiceCommandAtMs = now
        sendCommand("start_voice") {
            put("notificationId", notification)
        }
        RelayHudController.setVoice("listening", "")
    }

    fun dismiss() {
        val notification = RelayHudController.currentNotificationId()
        sendCommand("dismiss_notification") {
            put("notificationId", notification)
        }
        RelayHudController.clearNotification()
    }

    fun cancelVoice() {
        sendCommand("cancel_voice")
        RelayHudController.setVoice("idle", "")
    }

    private fun requestState() {
        sendCommand("request_state")
    }

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, mac: String?, deviceType: Int) {
            main.post { RelayHudController.setConnection("connected") }
        }

        override fun onDisconnected() {
            main.post { RelayHudController.setConnection("disconnected") }
        }

        override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
            main.post { RelayHudController.setConnection("connecting") }
        }

        override fun onARTCStatus(latency: Float, connected: Boolean) {
            if (connected) main.post { RelayHudController.setConnection("connected") }
        }

        override fun onRokidAccountChanged(account: String?) = Unit
    }

    private val msgCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
            val payload = when {
                data != null && data.isNotEmpty() -> String(data, Charsets.UTF_8)
                caps != null && caps.size() > 0 -> runCatching { caps.at(0).string }.getOrDefault("")
                else -> ""
            }
            if (payload.isBlank()) return
            main.post { handleEvent(payload) }
        }
    }

    private fun handleEvent(payload: String) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "state" -> {
                RelayHudController.setConnection(
                    if (obj.optBoolean("glassConnected")) "connected" else "waiting",
                )
            }
            "notification" -> {
                RelayHudController.showNotification(
                    RelayHudView.NotificationModel(
                        id = obj.optString("notificationId"),
                        app = obj.optString("appLabel", obj.optString("appPackage")),
                        title = obj.optString("title"),
                        text = obj.optString("text"),
                    ),
                )
            }
            "inbox" -> {
                val notifications = obj.optJSONArray("notifications")
                val items = mutableListOf<RelayHudView.NotificationModel>()
                if (notifications != null) {
                    for (index in 0 until notifications.length()) {
                        val item = notifications.optJSONObject(index) ?: continue
                        items += RelayHudView.NotificationModel(
                            id = item.optString("notificationId"),
                            app = item.optString("appLabel", item.optString("appPackage")),
                            title = item.optString("title"),
                            text = item.optString("text"),
                        )
                    }
                }
                RelayHudController.setInbox(items)
            }
            "voice_state" -> {
                RelayHudController.setVoice(
                    stateName = obj.optString("state"),
                    partial = obj.optString("partial"),
                    countdownMs = obj.optLong("countdownMs", 0L),
                    countdownTotalMs = obj.optLong("countdownTotalMs", 0L),
                )
            }
            "reply_result" -> {
                RelayHudController.showReplyResult(
                    ok = obj.optBoolean("ok"),
                    message = obj.optString("message"),
                )
            }
            "notification_cleared" -> RelayHudController.clearNotification()
        }
    }

    private fun sendCommand(type: String, block: JSONObject.() -> Unit = {}) {
        val obj = JSONObject()
            .put("version", Constants.PROTOCOL_VERSION)
            .put("type", type)
            .put("source", "glasses")
            .apply(block)
        runCatching {
            bridge?.sendMessage(Constants.KEY_COMMAND, Caps().apply { write(obj.toString()) })
        }.onFailure {
            Log.w(TAG, "send command failed: ${it.message}")
            RelayHudController.showTransient("Phone link not ready")
        }
    }

}
