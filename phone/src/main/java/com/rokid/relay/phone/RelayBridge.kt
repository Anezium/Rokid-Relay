package com.rokid.relay.phone

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

    private var appContext: Context? = null
    private var link: CXRLink? = null

    fun start(context: Context, token: String) {
        appContext = context.applicationContext
        main.post { startOnMain(context.applicationContext, token) }
    }

    fun stop() {
        main.post {
            VoiceController.cancel()
            runCatching { link?.disconnect() }
            link = null
            cxrConnected = false
            glassConnected = false
            bootstrapStarted = false
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
        sendJson(Constants.KEY_EVENT, json)
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
        if (link == null) {
            link = CXRLink(context).apply {
                configCXRSession(
                    CxrDefs.CXRSession(
                        CxrDefs.CXRSessionType.CUSTOMAPP,
                        Constants.CLIENT_PACKAGE,
                    ),
                )
                setCXRLinkCbk(linkCallback)
                setCXRCustomCmdCbk(commandCallback)
            }
        }
        bootstrapState = "waiting for glasses"
        lastStatus = "binding Hi Rokid service"
        val bound = runCatching { link?.connect(token) == true }.getOrDefault(false)
        if (!bound) {
            lastStatus = "Hi Rokid bind failed"
            bootstrapState = "not connected"
        }
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            lastStatus = if (connected) "CXR-L connected" else "CXR-L disconnected"
            if (connected) sendState()
            maybeBootstrap()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassConnected = connected
            lastStatus = if (connected) "glasses connected" else "glasses disconnected"
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

    private fun maybeBootstrap() {
        val context = appContext ?: return
        val localLink = link ?: return
        if (!cxrConnected || !glassConnected || bootstrapStarted) return
        bootstrapStarted = true
        bootstrapState = "starting glasses app"
        Thread {
            val result = ClientBootstrap(context, localLink).ensureRunning()
            bootstrapState = result
            lastStatus = result
            sendState()
        }.apply {
            name = "RokidRelayBootstrap"
            start()
        }
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
            put("inboxEntryLimit", NotificationSettingsStore.DEFAULT_INBOX_ENTRY_LIMIT)
            put("threadMessageLimit", NotificationSettingsStore.DEFAULT_THREAD_MESSAGE_LIMIT)
            put("inputCombo", RelayInputSettingsStore.DEFAULT_COMBO)
            put("swipeMode", RelayInputSettingsStore.DEFAULT_SWIPE_MODE)
            return this
        }
        val notificationStore = NotificationSettingsStore(context)
        val inputStore = RelayInputSettingsStore(context)
        put("notificationPopupDurationMs", notificationStore.popupDurationMs())
        put("inboxEntryLimit", notificationStore.inboxEntryLimit())
        put("threadMessageLimit", notificationStore.threadMessageLimit())
        put("inputCombo", inputStore.inputCombo())
        put("swipeMode", inputStore.swipeMode())
        return this
    }

    private fun sendJson(key: String, json: JSONObject) {
        val localLink = link ?: return
        runCatching {
            val bytes = Caps().apply { write(json.toString()) }.serialize()
            localLink.sendCustomCmd(key, bytes)
        }.onFailure {
            Log.w(TAG, "send failed: ${it.message}")
            lastStatus = "send failed"
        }
    }

    private fun payloadToJson(payload: ByteArray): JSONObject? =
        runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() == 0) return@runCatching null
            JSONObject(caps.at(0).string)
        }.getOrNull()
}
