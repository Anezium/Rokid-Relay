package com.anezium.rokidrelay.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.rokid.cxr.Caps
import org.json.JSONArray
import org.json.JSONObject

object RelayBridge {
    data class Snapshot(
        val cxrConnected: Boolean,
        val glassConnected: Boolean,
        val bootstrapState: String,
        val lastStatus: String,
        val lastOutgoingReply: String,
        val lastDeliveredReply: String,
        val sttEngine: String,
        val voiceRoute: String,
        val cxrAudioBytes: Long,
        val vadAverageAbs: Int,
        val vadPeakAbs: Int,
        val vadSpeechDetected: Boolean,
        val lastVoiceError: String,
    )

    private const val TAG = "RokidRelayBridge"
    private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
    private const val RECONNECT_MAX_DELAY_MS = 15_000L
    private const val FOREGROUND_OPEN_WINDOW_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cxrConnected = false
    @Volatile private var glassConnected = false
    @Volatile private var bootstrapState = "idle"
    @Volatile private var lastStatus = "idle"
    @Volatile private var lastOutgoingReply = ""
    @Volatile private var lastDeliveredReply = ""
    @Volatile private var sttEngine = "not selected"
    @Volatile private var voiceRoute = "idle"
    @Volatile private var cxrAudioBytes = 0L
    @Volatile private var vadAverageAbs = 0
    @Volatile private var vadPeakAbs = 0
    @Volatile private var vadSpeechDetected = false
    @Volatile private var lastVoiceError = ""
    @Volatile private var bootstrapStarted = false
    @Volatile private var bootstrapInFlight = false
    @Volatile private var bootstrapReadyForMessages = false
    @Volatile private var bootstrapMayOpenClient = false
    @Volatile private var bootstrapOpenClientDeadlineMs = 0L
    @Volatile private var authToken = ""
    @Volatile private var pendingNotificationRetry: ReplyRepository.PendingReply? = null
    @Volatile private var reconnectRunnable: Runnable? = null
    @Volatile private var foregroundOpenRunnable: Runnable? = null
    private var reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS

    private var appContext: Context? = null
    private var link: CXRLink? = null

    fun start(context: Context, token: String, reason: String = "") {
        appContext = context.applicationContext
        authToken = token
        val allowForegroundOpen = allowsGlassesForegroundStart(reason)
        setForegroundOpenRequest(allowForegroundOpen)
        main.post {
            startOnMain(context.applicationContext, token, allowForegroundOpen)
        }
    }

    fun stop() {
        main.post {
            VoiceController.cancel()
            cancelReconnect()
            runCatching { link?.disconnect() }
            link = null
            authToken = ""
            pendingNotificationRetry = null
            cxrConnected = false
            glassConnected = false
            bootstrapStarted = false
            bootstrapInFlight = false
            bootstrapReadyForMessages = false
            clearForegroundOpenRequest()
            bootstrapState = "stopped"
            lastStatus = "stopped"
        }
    }

    fun setStatus(text: String) {
        lastStatus = text
    }

    fun recordOutgoingReply(text: String, sent: Boolean) {
        lastOutgoingReply = text
        lastStatus = if (sent) "reply sent" else "reply send failed"
    }

    fun recordDeliveredReply(text: String) {
        lastDeliveredReply = text
        lastStatus = if (text.isBlank()) "test reply received empty" else "test reply received"
    }

    fun recordVoiceStart(engine: SpeechToTextEngine, route: String) {
        sttEngine = engine.shortLabel
        voiceRoute = route
        cxrAudioBytes = 0L
        vadAverageAbs = 0
        vadPeakAbs = 0
        vadSpeechDetected = false
        lastVoiceError = ""
        lastStatus = "voice listening"
    }

    fun recordVoiceAudio(snapshot: VoiceActivitySnapshot) {
        cxrAudioBytes = snapshot.totalBytes
        vadAverageAbs = snapshot.averageAbs
        vadPeakAbs = snapshot.peakAbs
        vadSpeechDetected = snapshot.speechDetected
    }

    fun recordVoiceError(message: String) {
        lastVoiceError = message
        lastStatus = "voice error"
    }

    fun recordVoiceIdle(status: String = "idle") {
        voiceRoute = status
    }

    fun snapshot(): Snapshot = Snapshot(
        cxrConnected = cxrConnected,
        glassConnected = glassConnected,
        bootstrapState = bootstrapState,
        lastStatus = lastStatus,
        lastOutgoingReply = lastOutgoingReply,
        lastDeliveredReply = lastDeliveredReply,
        sttEngine = sttEngine,
        voiceRoute = voiceRoute,
        cxrAudioBytes = cxrAudioBytes,
        vadAverageAbs = vadAverageAbs,
        vadPeakAbs = vadPeakAbs,
        vadSpeechDetected = vadSpeechDetected,
        lastVoiceError = lastVoiceError,
    )

    fun sendNotification(reply: ReplyRepository.PendingReply) {
        if (notificationForwardingPaused()) {
            if (pendingNotificationRetry?.id == reply.id) pendingNotificationRetry = null
            lastStatus = "notification forwarding paused: phone screen on"
            Log.i(TAG, "notification forwarding paused id=${reply.id.take(8)}")
            sendInbox()
            return
        }
        if (!canSendNotificationEvent(bootstrapReadyForMessages, cxrConnected, glassConnected, link?.isServiceConnected() == true)) {
            pendingNotificationRetry = reply
            lastStatus = "notification queued: glasses app not ready"
            Log.i(TAG, "notification queued id=${reply.id.take(8)} reason=glasses app not ready")
            return
        }
        val json = JSONObject()
            .put("version", Constants.PROTOCOL_VERSION)
            .put("type", "notification")
            .put("source", "phone")
            .put("notificationId", reply.id)
            .put("appPackage", reply.packageName)
            .put("appLabel", reply.appLabel)
            .put("title", reply.title)
            .put("text", reply.text)
            .put("canReply", true)
            .appendImageMetadata(reply.imagePreview)
            .appendUserSettings()
        if (sendJson(Constants.KEY_EVENT, json)) {
            if (pendingNotificationRetry?.id == reply.id) pendingNotificationRetry = null
            sendNotificationImage(reply)
        } else {
            pendingNotificationRetry = reply
        }
        sendInbox()
    }

    fun sendSettings() {
        sendJson(
            Constants.KEY_EVENT,
            JSONObject()
                .put("version", Constants.PROTOCOL_VERSION)
                .put("type", "settings")
                .put("source", "phone")
                .appendUserSettings(),
        )
    }

    fun saveNotificationOverlayPosition(yOffsetDp: Int): Boolean {
        val cleanOffset = NotificationOverlayPositionStore.sanitizeYOffsetDp(yOffsetDp)
        return sendJson(
            Constants.KEY_EVENT,
            JSONObject()
                .put("version", Constants.PROTOCOL_VERSION)
                .put("type", "save_notification_overlay_position")
                .put("source", "phone")
                .put("overlayYOffsetDp", cleanOffset),
        )
    }

    fun sendInbox() {
        val settings = appContext?.let { NotificationSettingsStore(it) }
        val forwardingPaused = notificationForwardingPaused()
        val items = if (forwardingPaused) {
            emptyList()
        } else {
            ReplyRepository.listPending(
                settings?.inboxEntryLimit() ?: NotificationSettingsStore.DEFAULT_INBOX_ENTRY_LIMIT,
            )
        }
        val notifications = JSONArray()
        items.forEach { reply ->
            notifications.put(
                JSONObject()
                    .put("notificationId", reply.id)
                    .put("appPackage", reply.packageName)
                    .put("appLabel", reply.appLabel)
                    .put("title", reply.title)
                    .put("text", reply.text)
                    .put("canReply", true)
                    .appendImageMetadata(reply.imagePreview),
            )
        }
        sendJson(
            Constants.KEY_EVENT,
            JSONObject()
                .put("version", Constants.PROTOCOL_VERSION)
                .put("type", "inbox")
                .put("source", "phone")
                .put("notifications", notifications),
        )
    }

    fun sendVoiceState(
        state: String,
        partial: String = "",
        countdownMs: Long = 0L,
        countdownTotalMs: Long = 0L,
    ) {
        sendJson(
            Constants.KEY_EVENT,
            JSONObject()
                .put("version", Constants.PROTOCOL_VERSION)
                .put("type", "voice_state")
                .put("source", "phone")
                .put("state", state)
                .put("partial", partial)
                .put("countdownMs", countdownMs)
                .put("countdownTotalMs", countdownTotalMs),
        )
    }

    fun sendReplyResult(notificationId: String, ok: Boolean, message: String) {
        sendJson(
            Constants.KEY_EVENT,
            JSONObject()
                .put("version", Constants.PROTOCOL_VERSION)
                .put("type", "reply_result")
                .put("source", "phone")
                .put("notificationId", notificationId)
                .put("ok", ok)
                .put("message", message),
        )
    }

    private fun startOnMain(context: Context, token: String, allowForegroundOpen: Boolean = false) {
        authToken = token
        val localLink = ensureLink(context)
        bootstrapState = "waiting for glasses"
        lastStatus = "binding Hi Rokid service"
        val bound = runCatching { localLink.connect(token) }.getOrDefault(false)
        if (!bound) {
            lastStatus = "Hi Rokid bind failed"
            bootstrapState = "not connected"
            scheduleReconnect("bind failed")
        } else if (localLink.isServiceConnected()) {
            cxrConnected = true
            glassConnected = localLink.isGlassBtConnected()
            maybeBootstrap(allowForegroundOpen = allowForegroundOpen)
        }
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            if (connected) {
                lastStatus = "CXR-L connected"
                if (glassConnected) sendState()
            } else {
                glassConnected = false
                bootstrapStarted = false
                bootstrapInFlight = false
                bootstrapReadyForMessages = false
                clearForegroundOpenRequest()
                bootstrapState = "not connected"
                lastStatus = "CXR-L disconnected"
                scheduleReconnect("CXR-L disconnected")
            }
            maybeBootstrap()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassConnected = connected
            lastStatus = if (connected) "glasses connected" else "glasses disconnected"
            if (connected) {
                cancelReconnect()
                sendState()
            } else {
                bootstrapStarted = false
                bootstrapInFlight = false
                bootstrapReadyForMessages = false
                clearForegroundOpenRequest()
                bootstrapState = if (cxrConnected) "waiting for glasses" else "not connected"
            }
            maybeBootstrap()
        }

        override fun onGlassAiAssistStart() {
            lastStatus = "AI key down"
        }

        override fun onGlassAiAssistStop() {
            lastStatus = "AI key up"
        }
    }

    private val commandCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String, payload: ByteArray) {
            if (key != Constants.KEY_COMMAND) return
            val json = payloadToJson(payload) ?: return
            handleCommand(json)
        }
    }

    private fun ensureLink(context: Context): CXRLink =
        link ?: CXRLink(context).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    Constants.CLIENT_PACKAGE,
                ),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(commandCallback)
        }.also { link = it }

    private fun scheduleReconnect(reason: String) {
        if (appContext == null) return
        if (authToken.isBlank()) return
        if (reconnectRunnable != null) return
        val delayMs = reconnectDelayMs
        Log.i(TAG, "schedule CXR-L reconnect in ${delayMs}ms: $reason")
        lastStatus = "CXR-L reconnect scheduled"
        val runnable = Runnable {
            reconnectRunnable = null
            val currentContext = appContext ?: return@Runnable
            val token = authToken
            if (token.isBlank()) return@Runnable
            if (cxrConnected && link?.isServiceConnected() == true) {
                reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
                return@Runnable
            }
            bootstrapStarted = false
            bootstrapReadyForMessages = false
            clearForegroundOpenRequest()
            bootstrapState = "reconnecting"
            lastStatus = "reconnecting CXR-L"
            reconnectDelayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            startOnMain(currentContext, token)
            if (!cxrConnected) scheduleReconnect("CXR-L still disconnected")
        }
        reconnectRunnable = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { main.removeCallbacks(it) }
        reconnectRunnable = null
        reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
    }

    private fun scheduleForegroundOpenAttempt(delayMs: Long = 0L) {
        if (!foregroundOpenRequestActive()) return
        if (foregroundOpenRunnable != null) return
        val runnable = Runnable {
            foregroundOpenRunnable = null
            if (!foregroundOpenRequestActive()) return@Runnable
            if (!cxrConnected || !glassConnected || bootstrapInFlight) {
                scheduleForegroundOpenAttempt(delayMs = 500L)
                return@Runnable
            }
            maybeBootstrap(allowForegroundOpen = true)
        }
        foregroundOpenRunnable = runnable
        if (delayMs > 0L) {
            main.postDelayed(runnable, delayMs)
        } else {
            main.post(runnable)
        }
    }

    private fun maybeBootstrap(allowForegroundOpen: Boolean = false) {
        val context = appContext ?: return
        val localLink = link ?: return
        if (!cxrConnected || !glassConnected) return
        if (bootstrapInFlight) return
        val foregroundOpenActive = allowForegroundOpen && foregroundOpenRequestActive()
        if (bootstrapStarted) {
            if (foregroundOpenActive) {
                bootstrapStarted = false
                bootstrapReadyForMessages = false
            } else if (bootstrapReadyForMessages) {
                flushPendingNotification()
                return
            } else {
                if (foregroundOpenRequestActive()) scheduleForegroundOpenAttempt()
                return
            }
        }
        if (!foregroundOpenActive && foregroundOpenRequestActive()) {
            scheduleForegroundOpenAttempt()
            return
        }
        bootstrapStarted = true
        bootstrapInFlight = true
        bootstrapReadyForMessages = false
        bootstrapState = "preparing glasses app"
        val openAfterInstall = foregroundOpenActive
        Thread {
            val result = ClientBootstrap(context, localLink).ensureReady(openAfterInstall = openAfterInstall)
            val rerunForForegroundOpen = !openAfterInstall && foregroundOpenRequestActive() && result.success
            bootstrapInFlight = false
            if (openAfterInstall) clearForegroundOpenRequest()
            bootstrapReadyForMessages = result.readyForMessages
            if (rerunForForegroundOpen) {
                bootstrapStarted = false
                bootstrapReadyForMessages = false
            }
            bootstrapState = result.status
            lastStatus = result.status
            sendState()
            if (rerunForForegroundOpen) {
                main.post { maybeBootstrap(allowForegroundOpen = true) }
            } else if (result.success && result.readyForMessages) {
                flushPendingNotification()
            }
        }.apply {
            name = "RokidRelayBootstrap"
            start()
        }
    }

    private fun flushPendingNotification() {
        val pending = pendingNotificationRetry ?: return
        if (!cxrConnected || !glassConnected) return
        pendingNotificationRetry = null
        Log.i(TAG, "retrying pending notification id=${pending.id.take(8)}")
        sendNotification(pending)
    }

    private fun notificationForwardingPaused(): Boolean =
        appContext?.let { NotificationForwardingPolicy.isPaused(it) } == true

    private fun handleCommand(json: JSONObject) {
        val type = json.optString("type")
        val context = appContext ?: return
        when (type) {
            "request_state" -> sendState()
            "start_voice" -> {
                val id = json.optString("notificationId")
                val localLink = link
                if (id.isBlank() || localLink == null) {
                    sendReplyResult(id, false, "No active notification")
                } else {
                    VoiceController.start(context, localLink, id)
                }
            }
            "retry_voice" -> {
                val id = json.optString("notificationId")
                val localLink = link
                if (id.isBlank() || localLink == null) {
                    sendReplyResult(id, false, "No active notification")
                } else {
                    VoiceController.retry(context, localLink, id)
                }
            }
            "cancel_voice" -> VoiceController.cancel()
            "dismiss_notification" -> {
                val id = json.optString("notificationId")
                ReplyRepository.forget(id)
                sendJson(
                    Constants.KEY_EVENT,
                    JSONObject()
                        .put("version", Constants.PROTOCOL_VERSION)
                        .put("type", "notification_cleared")
                        .put("source", "phone"),
                )
                sendInbox()
            }
        }
    }

    private fun sendState() {
        sendJson(
            Constants.KEY_EVENT,
            JSONObject()
                .put("version", Constants.PROTOCOL_VERSION)
                .put("type", "state")
                .put("source", "phone")
                .put("cxrConnected", cxrConnected)
                .put("glassConnected", glassConnected)
                .put("bootstrapState", bootstrapState)
                .appendUserSettings(),
        )
        sendInbox()
    }

    private fun JSONObject.appendUserSettings(): JSONObject {
        val context = appContext
        if (context == null) {
            put("notificationPopupDurationMs", NotificationSettingsStore.DEFAULT_POPUP_DURATION_MS)
            put("notificationFontSizeSp", NotificationSettingsStore.DEFAULT_FONT_SIZE_SP)
            put("inboxEntryLimit", NotificationSettingsStore.DEFAULT_INBOX_ENTRY_LIMIT)
            put("threadMessageLimit", NotificationSettingsStore.DEFAULT_THREAD_MESSAGE_LIMIT)
            put("inputCombo", RelayInputSettingsStore.DEFAULT_COMBO)
            put("swipeMode", RelayInputSettingsStore.DEFAULT_SWIPE_MODE)
            return this
        }
        val notificationStore = NotificationSettingsStore(context)
        val inputStore = RelayInputSettingsStore(context)
        put("notificationPopupDurationMs", notificationStore.popupDurationMs())
        put("notificationFontSizeSp", notificationStore.fontSizeSp())
        put("inboxEntryLimit", notificationStore.inboxEntryLimit())
        put("threadMessageLimit", notificationStore.threadMessageLimit())
        put("inputCombo", inputStore.inputCombo())
        put("swipeMode", inputStore.swipeMode())
        return this
    }

    private fun JSONObject.appendImageMetadata(preview: NotificationImagePreview?): JSONObject {
        if (preview == null) return this
        put(
            "image",
            JSONObject()
                .put("id", preview.id)
                .put("mimeType", preview.mimeType)
                .put("width", preview.width)
                .put("height", preview.height)
                .put("byteSize", preview.bytes.size)
                .put("source", preview.source),
        )
        return this
    }

    private fun sendNotificationImage(reply: ReplyRepository.PendingReply): Boolean {
        val preview = reply.imagePreview ?: return true
        val localLink = link
        if (localLink == null) {
            markSendUnavailable("link missing", Constants.KEY_MEDIA, JSONObject().put("type", "notification_image"))
            return false
        }
        val json = JSONObject()
            .put("version", Constants.PROTOCOL_VERSION)
            .put("type", "notification_image")
            .put("source", "phone")
            .put("notificationId", reply.id)
            .put("imageId", preview.id)
            .put("mimeType", preview.mimeType)
            .put("width", preview.width)
            .put("height", preview.height)
            .put("byteSize", preview.bytes.size)
        return runCatching {
            val result = localLink.sendCustomCmd(
                Constants.KEY_MEDIA,
                Caps().apply { write(json.toString()) },
                preview.bytes,
            )
            if (result == null || result < 0) {
                markSendUnavailable("media send returned $result", Constants.KEY_MEDIA, json)
                false
            } else {
                Log.i(
                    TAG,
                    "notification image sent id=${reply.id.take(8)} image=${preview.id.take(8)} bytes=${preview.bytes.size} result=$result",
                )
                true
            }
        }.getOrElse {
            Log.w(TAG, "media send failed: ${it.message}")
            markSendUnavailable("media send exception", Constants.KEY_MEDIA, json)
            false
        }
    }

    private fun sendJson(key: String, json: JSONObject): Boolean {
        val localLink = link
        if (localLink == null) {
            markSendUnavailable("link missing", key, json)
            return false
        }
        return runCatching {
            val bytes = Caps().apply { write(json.toString()) }.serialize()
            val result = localLink.sendCustomCmd(key, bytes)
            if (result == null || result < 0) {
                markSendUnavailable("send returned $result", key, json)
                false
            } else {
                true
            }
        }.getOrElse {
            Log.w(TAG, "send failed: ${it.message}")
            markSendUnavailable("send exception", key, json)
            false
        }
    }

    private fun markSendUnavailable(reason: String, key: String, json: JSONObject) {
        Log.w(TAG, "$reason key=$key type=${json.optString("type")}")
        lastStatus = "send failed"
        if (!cxrConnected || link?.isServiceConnected() != true) {
            scheduleReconnect(reason)
        }
    }

    private fun setForegroundOpenRequest(allowed: Boolean) {
        if (allowed) {
            bootstrapMayOpenClient = true
            bootstrapOpenClientDeadlineMs = SystemClock.elapsedRealtime() + FOREGROUND_OPEN_WINDOW_MS
            scheduleForegroundOpenAttempt()
        } else {
            expireForegroundOpenRequest()
        }
    }

    private fun clearForegroundOpenRequest() {
        bootstrapMayOpenClient = false
        bootstrapOpenClientDeadlineMs = 0L
        foregroundOpenRunnable?.let { main.removeCallbacks(it) }
        foregroundOpenRunnable = null
    }

    private fun foregroundOpenRequestActive(): Boolean {
        if (!bootstrapMayOpenClient) return false
        if (SystemClock.elapsedRealtime() <= bootstrapOpenClientDeadlineMs) return true
        clearForegroundOpenRequest()
        return false
    }

    private fun expireForegroundOpenRequest() {
        if (bootstrapMayOpenClient && SystemClock.elapsedRealtime() > bootstrapOpenClientDeadlineMs) {
            clearForegroundOpenRequest()
        }
    }

    private fun payloadToJson(payload: ByteArray): JSONObject? =
        runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() == 0) return@runCatching null
            JSONObject(caps.at(0).string)
        }.getOrNull()
}

internal fun allowsGlassesForegroundStart(reason: String): Boolean =
    when (reason) {
        "app_open",
        "authorization",
        "permissions",
        "stt_engine",
        "stt_provider",
        "stt_model",
        "microphone_permission",
        -> true
        else -> false
    }

internal fun canSendNotificationEvent(
    bootstrapReadyForMessages: Boolean,
    cxrConnected: Boolean,
    glassConnected: Boolean,
    serviceConnected: Boolean,
): Boolean =
    bootstrapReadyForMessages || (cxrConnected && glassConnected && serviceConnected)
