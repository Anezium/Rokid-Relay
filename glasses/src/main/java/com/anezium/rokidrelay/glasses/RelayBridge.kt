package com.anezium.rokidrelay.glasses

import android.content.Context
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
    private var appContext: Context? = null
    private var lastVoiceCommandAtMs = 0L

    fun start(context: Context) {
        appContext = context.applicationContext
        if (bridge != null) {
            RelayHudController.setConnection("connected")
            requestState()
            return
        }
        val cxr = CXRServiceBridge()
        bridge = cxr
        RelayHudController.setConnection("connecting")
        cxr.setStatusListener(statusListener)
        val eventResult = cxr.subscribe(Constants.KEY_EVENT, msgCallback)
        val mediaResult = cxr.subscribe(Constants.KEY_MEDIA, msgCallback)
        Log.d(TAG, "subscribe event=$eventResult media=$mediaResult")
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

    fun hideNotification() {
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

        override fun onAudioNoise(noise: Float) = Unit

        override fun onRokidAccountChanged(account: String?) = Unit
    }

    private val msgCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
            if (msgType == Constants.KEY_MEDIA) {
                handleMedia(caps, data)
                return
            }
            val payload = decodePayload(caps, data)
            Log.d(
                TAG,
                "event received type=$msgType dataBytes=${data?.size ?: 0} capsSize=${caps?.size() ?: 0} payloadLen=${payload.length}",
            )
            if (payload.isBlank()) return
            main.post { handleEvent(payload) }
        }
    }

    private fun decodePayload(caps: Caps?, data: ByteArray?): String {
        if (data != null && data.isNotEmpty()) {
            val raw = String(data, Charsets.UTF_8).trim()
            if (raw.startsWith("{")) return raw
            val serializedCapsPayload = runCatching {
                val parsed = Caps.fromBytes(data)
                if (parsed.size() > 0) parsed.at(0).string else ""
            }.onFailure {
                Log.w(TAG, "serialized event decode failed: ${it.message}")
            }.getOrDefault("")
            if (serializedCapsPayload.isNotBlank()) return serializedCapsPayload
            if (raw.isNotBlank()) return raw
        }
        return if (caps != null && caps.size() > 0) {
            runCatching { caps.at(0).string }.getOrDefault("")
        } else {
            ""
        }
    }

    private fun handleEvent(payload: String) {
        val obj = runCatching { JSONObject(payload) }.onFailure {
            Log.w(TAG, "event JSON parse failed length=${payload.length}: ${it.message}")
        }.getOrNull() ?: return
        val type = obj.optString("type")
        Log.d(TAG, "event json type=$type")
        when (type) {
            "state" -> {
                applySettings(obj)
                RelayHudController.setConnection(
                    if (obj.optBoolean("glassConnected")) "connected" else "waiting",
                )
            }
            "settings" -> applySettings(obj)
            "notification" -> {
                applySettings(obj)
                RelayHudController.showNotification(
                    notificationModelFromJson(obj),
                )
            }
            "inbox" -> {
                val notifications = obj.optJSONArray("notifications")
                val items = mutableListOf<RelayHudView.NotificationModel>()
                if (notifications != null) {
                    for (index in 0 until notifications.length()) {
                        val item = notifications.optJSONObject(index) ?: continue
                        items += notificationModelFromJson(item)
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
            "save_notification_overlay_position" -> {
                val rawOffset = obj.optInt(
                    "overlayYOffsetDp",
                    NotificationOverlaySettings.DEFAULT_Y_OFFSET_DP,
                )
                val savedOffset = appContext?.let { context ->
                    NotificationOverlaySettings.saveYOffsetDp(context, rawOffset)
                } ?: NotificationOverlaySettings.sanitizeYOffsetDp(rawOffset)
                RelayHudController.setNotificationOverlayYOffset(savedOffset)
            }
            "notification_cleared" -> RelayHudController.clearNotification()
        }
    }

    private fun handleMedia(caps: Caps?, data: ByteArray?) {
        val payload = decodeCapsPayload(caps)
        if (payload.isBlank()) {
            Log.w(TAG, "media skipped: missing metadata dataBytes=${data?.size ?: 0}")
            return
        }
        val obj = runCatching { JSONObject(payload) }.onFailure {
            Log.w(TAG, "media JSON parse failed length=${payload.length}: ${it.message}")
        }.getOrNull() ?: return
        if (obj.optString("type") != "notification_image") return
        val imageId = obj.optString("imageId")
        val stream = data?.copyOf()
        if (imageId.isBlank() || stream == null || stream.isEmpty()) {
            Log.w(TAG, "media skipped image=${imageId.take(8)} dataBytes=${data?.size ?: 0}")
            return
        }
        val expectedByteSize = obj.optInt("byteSize", stream.size)
        Thread {
            val stored = RelayNotificationImageCache.putEncoded(imageId, stream, expectedByteSize)
            if (stored) {
                main.post { RelayHudController.notifyImageCacheChanged() }
            }
        }.apply {
            name = "RelayImageDecode"
            start()
        }
    }

    private fun decodeCapsPayload(caps: Caps?): String =
        if (caps != null && caps.size() > 0) {
            runCatching { caps.at(0).string }.getOrDefault("")
        } else {
            ""
        }

    private fun notificationModelFromJson(obj: JSONObject): RelayHudView.NotificationModel {
        val image = obj.optJSONObject("image")
        return RelayHudView.NotificationModel(
            id = obj.optString("notificationId"),
            app = obj.optString("appLabel", obj.optString("appPackage")),
            title = obj.optString("title"),
            text = obj.optString("text"),
            imageId = image?.optString("id").orEmpty(),
            imageMimeType = image?.optString("mimeType").orEmpty(),
            imageWidth = image?.optInt("width", 0) ?: 0,
            imageHeight = image?.optInt("height", 0) ?: 0,
        )
    }

    private fun applySettings(obj: JSONObject) {
        if (obj.has("notificationPopupDurationMs")) {
            RelayHudController.setNotificationPopupDuration(obj.optLong("notificationPopupDurationMs", 5_000L))
        }
        if (obj.has("notificationFontSizeSp")) {
            val rawFontSize = obj.optDouble(
                "notificationFontSizeSp",
                NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP.toDouble(),
            ).toFloat()
            val savedFontSize = appContext?.let { context ->
                NotificationOverlaySettings.saveFontSizeSp(context, rawFontSize)
            } ?: NotificationOverlaySettings.sanitizeFontSizeSp(rawFontSize)
            RelayHudController.setNotificationFontSizeSp(savedFontSize)
        }
        RelayHudController.setInputSettings(
            combo = if (obj.has("inputCombo")) obj.optString("inputCombo") else null,
            swipeMode = if (obj.has("swipeMode")) obj.optString("swipeMode") else null,
        )
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
