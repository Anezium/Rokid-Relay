package com.rokid.relay.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.cxrglobal.CXRLink
import java.util.Locale
import java.util.concurrent.Executors

object VoiceController {
    private const val TAG = "RelayVoice"
    private const val START_DEBOUNCE_MS = 900L

    private val main = Handler(Looper.getMainLooper())
    private val transcriptionExecutor = Executors.newSingleThreadExecutor()

    private var activeNotificationId: String = ""
    private var activeLink: CXRLink? = null
    private var appContext: Context? = null
    private var voiceActive = false
    private var activeCapture: CxrBufferedAudioCapture? = null
    private var activeEngine: CompletedAudioSpeechToTextEngine? = null
    private var lastStartAtMs = 0L

    fun start(context: Context, link: CXRLink, notificationId: String) {
        main.post {
            if (notificationId.isBlank()) {
                RelayBridge.sendReplyResult(notificationId, false, "No active notification")
                return@post
            }
            if (!SttCredentialStore(context.applicationContext).hasOpenAiApiKey()) {
                RelayBridge.sendReplyResult(notificationId, false, "OpenAI STT key missing")
                return@post
            }

            val now = SystemClock.elapsedRealtime()
            if (voiceActive) {
                if (notificationId == activeNotificationId) {
                    RelayBridge.sendVoiceState("recognizing")
                    return@post
                }
                cancelOnMain(sendIdle = false)
            } else if (now - lastStartAtMs < START_DEBOUNCE_MS) {
                Log.i(TAG, "Ignoring duplicate voice start")
                return@post
            }
            lastStartAtMs = now
            appContext = context.applicationContext
            activeLink = link
            activeNotificationId = notificationId
            voiceActive = true

            val routedToGlasses = runCatching { link.setCommunicationDevice() }.getOrDefault(false)
            Log.i(TAG, "Starting voice capture routedToGlasses=$routedToGlasses")
            RelayBridge.sendVoiceState("listening")

            val capture = CxrBufferedAudioCapture(link)
            activeCapture = capture
            val started = capture.start(
                onSpeechStarted = {
                    if (voiceActive && activeCapture === capture) RelayBridge.sendVoiceState("recognizing")
                },
                onCaptureFinished = { audio ->
                    if (voiceActive && activeCapture === capture) {
                        RelayBridge.sendVoiceState("processing")
                        transcribeCapturedAudio(context.applicationContext, audio)
                    }
                },
                onError = { message ->
                    if (voiceActive && activeCapture === capture) failVoiceRecognition(message)
                },
            )
            if (!started) {
                if (activeCapture === capture) activeCapture = null
                failActiveVoice("Glasses microphone unavailable")
                return@post
            }
        }
    }

    fun cancel() {
        main.post { cancelOnMain(sendIdle = true) }
    }

    private fun completeVoiceRecognition(transcript: String, reason: String) {
        val spoken = transcript.trim()
        if (spoken.isBlank()) {
            failVoiceRecognition("No speech recognized")
            return
        }
        val id = activeNotificationId
        val context = appContext
        Log.i(TAG, "Voice recognition complete reason=$reason length=${spoken.length}")
        finishVoiceCapture(sendIdle = false, cancelListening = false)
        val ok = if (context != null) ReplyRepository.sendReply(context, id, spoken) else false
        if (ok) ReplyRepository.forget(id)
        RelayBridge.recordOutgoingReply(spoken, ok)
        RelayBridge.sendReplyResult(
            id,
            ok,
            if (ok) "Reply sent" else "Reply failed",
        )
        RelayBridge.sendInbox()
    }

    private fun transcribeCapturedAudio(context: Context, audio: CxrCapturedAudio) {
        val engine = OpenAiCompletedAudioSpeechToTextEngine(SttCredentialStore(context))
        activeEngine = engine
        val notificationId = activeNotificationId
        transcriptionExecutor.execute {
            val result = runCatching {
                engine.transcribe(
                    CompletedAudioSpeechToTextInput(
                        pcm16Mono = audio.pcm16Mono,
                        sampleRate = audio.sampleRate,
                        languageTag = Locale.getDefault().toLanguageTag(),
                    ),
                )
            }
            main.post {
                if (voiceActive && activeEngine === engine && activeNotificationId == notificationId) {
                    result
                        .onSuccess { transcript ->
                            completeVoiceRecognition(transcript, "openai ${audio.closeReason}")
                        }
                        .onFailure { error ->
                            val message = error.voiceMessage()
                            Log.w(TAG, message, error)
                            failVoiceRecognition(message)
                        }
                }
            }
        }
    }

    private fun failVoiceRecognition(message: String) {
        val id = activeNotificationId
        finishVoiceCapture(sendIdle = false, cancelListening = true)
        RelayBridge.sendReplyResult(id, false, message)
    }

    private fun failActiveVoice(message: String) {
        failVoiceRecognition(message)
    }

    private fun cancelOnMain(sendIdle: Boolean) {
        finishVoiceCapture(sendIdle = sendIdle, cancelListening = true)
    }

    private fun finishVoiceCapture(sendIdle: Boolean, cancelListening: Boolean) {
        voiceActive = false
        if (cancelListening) {
            activeEngine?.cancel()
        }
        activeEngine = null
        activeCapture?.stop()
        activeCapture = null
        runCatching { activeLink?.clearCommunicationDevice() }
        activeLink = null
        activeNotificationId = ""
        if (sendIdle) RelayBridge.sendVoiceState("idle")
    }

    private fun Throwable.voiceMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
