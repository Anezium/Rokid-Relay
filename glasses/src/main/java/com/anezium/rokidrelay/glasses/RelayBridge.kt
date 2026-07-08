package com.anezium.rokidrelay.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
    private const val WAKE_REPLY_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private var bridge: CXRServiceBridge? = null
    private var appContext: Context? = null
    private var lastVoiceCommandAtMs = 0L
    private var lastSelfArmStatePayload = ""
    private var pendingWakeReplyNotificationId = ""
    private var pendingWakeReplyExpiresAtMs = 0L
    private val pendingWakeReplyTimeout = Runnable {
        if (pendingWakeReplyNotificationId.isNotBlank()) {
            pendingWakeReplyNotificationId = ""
            pendingWakeReplyExpiresAtMs = 0L
            RelayHudController.showTransient("Phone wake timed out")
            RelayHudController.setVoice("idle", "")
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (bridge != null) {
            if (!RelayHudController.isPhoneConnected()) {
                RelayHudController.setConnection("connecting")
            }
            requestState()
            sendSelfArmState(context, force = false)
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
        sendSelfArmState(context, force = false)
    }

    fun stop() {
        bridge = null
        lastSelfArmStatePayload = ""
        clearPendingWakeReply()
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
        if (!RelayHudController.isPhoneConnected()) {
            requestPhoneWakeForReply(notification)
            return
        }
        sendStartVoice(notification)
    }

    private fun sendStartVoice(notificationId: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceCommandAtMs < VOICE_COMMAND_DEBOUNCE_MS) return
        lastVoiceCommandAtMs = now
        sendCommand("start_voice") {
            put("notificationId", notificationId)
        }
        RelayHudController.setVoice("listening", "")
    }

    private fun requestPhoneWakeForReply(notificationId: String) {
        val context = appContext
        if (context == null) {
            RelayHudController.showTransient("Phone wake unavailable")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (pendingWakeReplyNotificationId == notificationId && now < pendingWakeReplyExpiresAtMs) {
            RelayHudController.showTransient("Waking phone...")
            return
        }
        pendingWakeReplyNotificationId = notificationId
        pendingWakeReplyExpiresAtMs = now + WAKE_REPLY_TIMEOUT_MS
        main.removeCallbacks(pendingWakeReplyTimeout)
        main.postDelayed(pendingWakeReplyTimeout, WAKE_REPLY_TIMEOUT_MS)
        RelayHudController.showTransient("Waking phone...")
        BleWakeClient.requestWake(context, notificationId) { ok, message ->
            if (ok) {
                RelayHudController.showTransient(message.ifBlank { "Phone waking..." })
            } else if (pendingWakeReplyNotificationId == notificationId) {
                clearPendingWakeReply()
                RelayHudController.showTransient(message.ifBlank { "Phone wake failed" })
                RelayHudController.setVoice("idle", "")
            }
        }
    }

    private fun maybeStartPendingWakeReply() {
        val notificationId = pendingWakeReplyNotificationId
        if (notificationId.isBlank()) return
        if (!RelayHudController.isPhoneConnected()) return
        if (SystemClock.elapsedRealtime() > pendingWakeReplyExpiresAtMs) {
            clearPendingWakeReply()
            RelayHudController.showTransient("Phone wake timed out")
            return
        }
        clearPendingWakeReply()
        sendStartVoice(notificationId)
    }

    private fun clearPendingWakeReply() {
        main.removeCallbacks(pendingWakeReplyTimeout)
        pendingWakeReplyNotificationId = ""
        pendingWakeReplyExpiresAtMs = 0L
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
            main.post {
                if (!RelayHudController.isPhoneConnected()) {
                    RelayHudController.setConnection("waiting")
                }
                requestState()
                appContext?.let { sendSelfArmState(it, force = false) }
            }
        }

        override fun onDisconnected() {
            main.post {
                lastSelfArmStatePayload = ""
                RelayHudController.setConnection("disconnected")
            }
        }

        override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
            main.post { RelayHudController.setConnection("connecting") }
        }

        override fun onARTCStatus(latency: Float, connected: Boolean) {
            if (connected) {
                main.post {
                    if (!RelayHudController.isPhoneConnected()) {
                        RelayHudController.setConnection("waiting")
                    }
                    requestState()
                    appContext?.let { sendSelfArmState(it, force = false) }
                }
            }
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
                appContext?.let { sendSelfArmState(it, force = false) }
                maybeStartPendingWakeReply()
            }
            "request_state" -> appContext?.let { sendSelfArmState(it, force = true) }
            "phone_sleeping" -> RelayHudController.phoneSleeping()
            "settings" -> applySettings(obj)
            "self_arm_provision" -> appContext?.let { context ->
                Thread {
                    val result = SelfArmController.provision(context, obj)
                    main.post {
                        sendCommand("self_arm_status") {
                            put("provisioned", result.recoveryStarted)
                            put("accepted", result.accepted)
                            put("disabled", false)
                            put("adbKeyProvisioned", SelfArmController.hasProvisionedKey(context))
                            put("watchdogVersion", Constants.SELF_ARM_WATCHDOG_VERSION)
                        }
                        sendSelfArmState(context, force = false)
                    }
                }.apply {
                    name = "RokidRelaySelfArmProvision"
                    start()
                }
            }
            "self_arm_disable" -> appContext?.let {
                SelfArmController.disable(it) { disabled ->
                    main.post {
                        sendCommand("self_arm_status") {
                            put("provisioned", false)
                            put("disabled", disabled)
                            put("adbKeyProvisioned", SelfArmController.hasProvisionedKey(it))
                            put("watchdogVersion", Constants.SELF_ARM_WATCHDOG_VERSION)
                        }
                        sendSelfArmState(it, force = false)
                    }
                }
            }
            "self_arm_wireless_setup" -> {
                val started = RelayAccessibilityService.startSelfArmWirelessSetup()
                if (!started) {
                    sendSelfArmWirelessStatus(
                        setupState = "accessibility_service_needed",
                        wifiIp = "",
                        adbConnectPort = 0,
                    )
                }
            }
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

    fun sendSelfArmWirelessStatus(
        setupState: String,
        wifiIp: String,
        adbPairCode: String = "",
        adbPairHost: String = "",
        adbPairPort: Int = 0,
        adbConnectPort: Int = 0,
        errorMessage: String = "",
    ) {
        sendCommand("self_arm_wireless_status") {
            put("setupState", setupState)
            put("wifiIp", wifiIp)
            put("wifiConnected", wifiIp.isNotBlank())
            if (adbPairCode.isNotBlank()) put("adbPairCode", adbPairCode)
            if (adbPairHost.isNotBlank()) put("adbPairHost", adbPairHost)
            if (adbPairPort > 0) put("adbPairPort", adbPairPort)
            if (adbConnectPort > 0) {
                put("adbConnectPort", adbConnectPort)
                put("adbPort", adbConnectPort)
            }
            if (errorMessage.isNotBlank()) put("errorMessage", errorMessage)
        }
    }

    fun sendSelfArmState(context: Context? = appContext, force: Boolean = false) {
        val app = context?.applicationContext ?: return
        val payload = JSONObject()
            .put("version", Constants.PROTOCOL_VERSION)
            .put("type", "self_arm_state")
            .put("source", "glasses")
            .put("armed", SelfArmController.isArmed(app))
            .put("keyPresent", SelfArmController.hasProvisionedKey(app))
            .put(
                "writeSecureGranted",
                app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            .put("accessibilityEnabled", RelayHudController.accessibilityEnabled())
            .put("helperVersionCode", helperVersionCode(app))
        val serialized = payload.toString()
        if (!force && serialized == lastSelfArmStatePayload) return
        val localBridge = bridge ?: return
        runCatching {
            val result = localBridge.sendMessage(Constants.KEY_COMMAND, Caps().apply { write(serialized) })
            if (result < 0) {
                Log.w(TAG, "self-arm state send returned $result")
            } else {
                lastSelfArmStatePayload = serialized
            }
        }.onFailure {
            Log.w(TAG, "send self-arm state failed: ${it.message}")
        }
    }

    private fun helperVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

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
