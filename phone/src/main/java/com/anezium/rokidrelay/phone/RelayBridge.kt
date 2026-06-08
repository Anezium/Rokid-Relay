package com.anezium.rokidrelay.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    @Volatile private var clientLaunchInFlight = false
    @Volatile private var clientOpenedForConnection = false
    @Volatile private var launchClientOnBootstrap = false
    @Volatile private var authToken = ""
    @Volatile private var pendingNotificationRetry: ReplyRepository.PendingReply? = null
    @Volatile private var reconnectRunnable: Runnable? = null
    private var reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS

    private var appContext: Context? = null
    private var link: CXRLink? = null

    fun start(context: Context, token: String, reason: String = "") {
        appContext = context.applicationContext
        authToken = token
        main.post {
            if (shouldLaunchClientForReason(reason)) launchClientOnBootstrap = true
            startOnMain(context.applicationContext, token)
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
            clientLaunchInFlight = false
            clientOpenedForConnection = false
            launchClientOnBootstrap = false
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
            .appendUserSettings()
        if (sendJson(Constants.KEY_EVENT, json)) {
            if (pendingNotificationRetry?.id == reply.id) pendingNotificationRetry = null
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
        val items = ReplyRepository.listPending(
            settings?.inboxEntryLimit() ?: NotificationSettingsStore.DEFAULT_INBOX_ENTRY_LIMIT,
        )
        val notifications = JSONArray()
        items.forEach { reply ->
            notifications.put(
                JSONObject()
                    .put("notificationId", reply.id)
                    .put("appPackage", reply.packageName)
                    .put("appLabel", reply.appLabel)
                    .put("title", reply.title)
                    .put("text", reply.text)
                    .put("canReply", true),
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

    private fun startOnMain(context: Context, token: String) {
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
            maybeBootstrap()
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
                clientLaunchInFlight = false
                clientOpenedForConnection = false
                launchClientOnBootstrap = false
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
                clientLaunchInFlight = false
                clientOpenedForConnection = false
                launchClientOnBootstrap = false
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

    private fun maybeBootstrap() {
        val context = appContext ?: return
        val localLink = link ?: return
        if (!cxrConnected || !glassConnected) return
        if (bootstrapInFlight) return
        if (bootstrapStarted) {
            maybeOpenClientForInteractiveStart(context, localLink)
            flushPendingNotification()
            return
        }
        bootstrapStarted = true
        bootstrapInFlight = true
        bootstrapState = if (launchClientOnBootstrap) "starting glasses app" else "preparing glasses app"
        Thread {
            val bootstrapper = ClientBootstrap(context, localLink)
            var result = bootstrapper.ensureReady(openClient = launchClientOnBootstrap)
            if (result.success && launchClientOnBootstrap && !result.openedClient) {
                result = bootstrapper.openClient()
            }
            clientOpenedForConnection = clientOpenedForConnection || result.openedClient
            if (result.openedClient || !result.success) launchClientOnBootstrap = false
            bootstrapInFlight = false
            bootstrapState = result.status
            lastStatus = result.status
            sendState()
            if (result.success) {
                flushPendingNotification()
            }
        }.apply {
            name = "RokidRelayBootstrap"
            start()
        }
    }

    private fun maybeOpenClientForInteractiveStart(context: Context, localLink: CXRLink) {
        if (!launchClientOnBootstrap || clientOpenedForConnection || clientLaunchInFlight) return
        clientLaunchInFlight = true
        bootstrapState = "starting glasses app"
        Thread {
            val result = ClientBootstrap(context, localLink).openClient()
            clientOpenedForConnection = clientOpenedForConnection || result.openedClient
            launchClientOnBootstrap = false
            clientLaunchInFlight = false
            bootstrapState = result.status
            lastStatus = result.status
            sendState()
            if (result.success) flushPendingNotification()
        }.apply {
            name = "RokidRelayClientLaunch"
            start()
        }
    }

    private fun shouldLaunchClientForReason(reason: String): Boolean =
        when (reason) {
            "authorization",
            "app_open",
            "permissions",
            "microphone_permission",
            "stt_engine",
            "stt_provider",
            "stt_model",
            -> true
            else -> false
        }

    private fun flushPendingNotification() {
        val pending = pendingNotificationRetry ?: return
        if (!cxrConnected || !glassConnected) return
        pendingNotificationRetry = null
        Log.i(TAG, "retrying pending notification id=${pending.id.take(8)}")
        sendNotification(pending)
    }

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

    private fun payloadToJson(payload: ByteArray): JSONObject? =
        runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() == 0) return@runCatching null
            JSONObject(caps.at(0).string)
        }.getOrNull()
}
