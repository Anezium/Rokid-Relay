package com.anezium.rokidrelay.phone

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
    private const val REVIEW_MIN_MS = 2_000L
    private const val REVIEW_MAX_MS = 6_500L
    private const val REVIEW_MS_PER_WORD = 180L
    private const val REVIEW_TICK_MS = 1_000L
    private const val REALTIME_READY_TIMEOUT_MS = 12_000L

    private val main = Handler(Looper.getMainLooper())
    private val transcriptionExecutor = Executors.newSingleThreadExecutor()

    private var activeNotificationId: String = ""
    private var activeLink: CXRLink? = null
    private var appContext: Context? = null
    private var voiceActive = false
    private var activeCapture: CxrBufferedAudioCapture? = null
    private var activeEngine: CompletedAudioSpeechToTextEngine? = null
    private var activeRealtimeSession: RealtimeSpeechToTextSession? = null
    private var activeRecognizer: AndroidCxrSpeechRecognizer? = null
    private var pendingReply: PendingVoiceReply? = null
    private var reviewRunnable: Runnable? = null
    private var lastStartAtMs = 0L

    fun start(context: Context, link: CXRLink, notificationId: String) {
        main.post {
            if (notificationId.isBlank()) {
                RelayBridge.sendReplyResult(notificationId, false, "No active notification")
                return@post
            }
            val appContext = context.applicationContext
            val selectedEngine = SpeechToTextSettingsStore(appContext).selectedEngine()
            val credentials = SttCredentialStore(appContext)
            if (selectedEngine.requiresCredential && !credentials.hasCredential(selectedEngine)) {
                RelayBridge.sendReplyResult(notificationId, false, "${selectedEngine.provider.displayName} STT key missing")
                return@post
            }

            val now = SystemClock.elapsedRealtime()
            if (voiceActive) {
                if (notificationId == activeNotificationId) {
                    if (pendingReply != null) {
                        retryOnMain(appContext, link, notificationId)
                        return@post
                    }
                    RelayBridge.sendVoiceState("recognizing")
                    return@post
                }
                cancelOnMain(sendIdle = false)
            } else if (now - lastStartAtMs < START_DEBOUNCE_MS) {
                Log.i(TAG, "Ignoring duplicate voice start")
                return@post
            }
            lastStartAtMs = now
            this.appContext = appContext
            activeLink = link
            activeNotificationId = notificationId
            voiceActive = true

            val routedToGlasses = runCatching { link.setCommunicationDevice() }.getOrDefault(false)
            // Keep the Hi Rokid AI assistant from grabbing the shared glasses audio stream mid-capture.
            val aiWakeSuppressed = runCatching { link.setInterruptAiWake(true) }.getOrDefault(false)
            Log.i(
                TAG,
                "Starting voice capture engine=${selectedEngine.id} routedToGlasses=$routedToGlasses aiWakeSuppressed=$aiWakeSuppressed",
            )
            RelayBridge.sendVoiceState("listening")
            RelayBridge.recordVoiceStart(
                selectedEngine,
                when {
                    selectedEngine == SpeechToTextEngine.ANDROID_CXR -> "Android SpeechRecognizer / CXR pipe"
                    selectedEngine.usesRealtime -> "CXR stream / ${selectedEngine.shortLabel}"
                    else -> "CXR buffer / ${selectedEngine.shortLabel}"
                },
            )

            if (selectedEngine == SpeechToTextEngine.ANDROID_CXR) {
                startAndroidCxrRecognizer(appContext, link, notificationId)
                return@post
            }

            if (selectedEngine.usesRealtime) {
                startRealtimeCxrTranscription(appContext, link, notificationId, selectedEngine)
                return@post
            }

            val capture = CxrBufferedAudioCapture(link)
            activeCapture = capture
            val started = capture.start(
                onSpeechStarted = {
                    if (voiceActive && activeCapture === capture) RelayBridge.sendVoiceState("recognizing")
                },
                onCaptureFinished = { audio ->
                    if (voiceActive && activeCapture === capture) {
                        RelayBridge.sendVoiceState("processing")
                        transcribeCapturedAudio(appContext, selectedEngine, audio)
                    }
                },
                onAudioLevel = { snapshot ->
                    if (voiceActive && activeCapture === capture) RelayBridge.recordVoiceAudio(snapshot)
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

    private fun startAndroidCxrRecognizer(context: Context, link: CXRLink, notificationId: String) {
        var recognizerRef: AndroidCxrSpeechRecognizer? = null
        val recognizer = AndroidCxrSpeechRecognizer(
            context = context,
            link = link,
            languageTag = Locale.getDefault().toLanguageTag(),
            listener = object : AndroidCxrSpeechRecognizer.Listener {
                override fun onListening() {
                    if (voiceActive && activeRecognizer === recognizerRef && activeNotificationId == notificationId) {
                        RelayBridge.sendVoiceState("listening")
                    }
                }

                override fun onRecognizing(partial: String) {
                    if (voiceActive && activeRecognizer === recognizerRef && activeNotificationId == notificationId) {
                        RelayBridge.sendVoiceState("recognizing", partial)
                    }
                }

                override fun onAudioLevel(snapshot: VoiceActivitySnapshot) {
                    if (voiceActive && activeRecognizer === recognizerRef && activeNotificationId == notificationId) {
                        RelayBridge.recordVoiceAudio(snapshot)
                    }
                }

                override fun onComplete(transcript: String, reason: String) {
                    if (voiceActive && activeRecognizer === recognizerRef && activeNotificationId == notificationId) {
                        RelayBridge.sendVoiceState("processing")
                        completeVoiceRecognition(transcript, reason)
                    }
                }

                override fun onError(message: String) {
                    if (voiceActive && activeRecognizer === recognizerRef && activeNotificationId == notificationId) {
                        failVoiceRecognition(message)
                    }
                }
            },
        )
        recognizerRef = recognizer
        activeRecognizer = recognizer
        val started = recognizer.start()
        if (!started && activeRecognizer === recognizer) activeRecognizer = null
    }

    private fun startRealtimeCxrTranscription(
        context: Context,
        link: CXRLink,
        notificationId: String,
        selectedEngine: SpeechToTextEngine,
    ) {
        var realtimeText = ""
        var localSession: RealtimeSpeechToTextSession? = null
        var readyTimeout: Runnable? = null
        val session = runCatching {
            ApiRealtimeSpeechToText.create(
                SttCredentialStore(context),
                selectedEngine,
                object : RealtimeSpeechToTextListener {
                    override fun onReady() {
                        main.post {
                            val sessionRef = localSession
                            readyTimeout?.let(main::removeCallbacks)
                            readyTimeout = null
                            if (sessionRef != null && voiceActive && activeRealtimeSession === sessionRef && activeNotificationId == notificationId) {
                                startRealtimeCapture(link, notificationId, selectedEngine, sessionRef)
                            }
                        }
                    }

                    override fun onPartial(text: String) {
                        main.post {
                            val sessionRef = localSession
                            if (sessionRef != null && voiceActive && activeRealtimeSession === sessionRef && activeNotificationId == notificationId) {
                                realtimeText = mergeRealtimeText(realtimeText, text, selectedEngine)
                                if (realtimeText.isNotBlank()) RelayBridge.sendVoiceState("recognizing", realtimeText)
                            }
                        }
                    }

                    override fun onFinal(text: String) {
                        main.post {
                            val sessionRef = localSession
                            readyTimeout?.let(main::removeCallbacks)
                            readyTimeout = null
                            if (sessionRef != null && voiceActive && activeRealtimeSession === sessionRef && activeNotificationId == notificationId) {
                                val finalText = text.trim().ifBlank { realtimeText.trim() }
                                completeVoiceRecognition(finalText, "${selectedEngine.id} realtime")
                            }
                        }
                    }

                    override fun onError(message: String) {
                        main.post {
                            val sessionRef = localSession
                            readyTimeout?.let(main::removeCallbacks)
                            readyTimeout = null
                            if (sessionRef != null && voiceActive && activeRealtimeSession === sessionRef && activeNotificationId == notificationId) {
                                failVoiceRecognition(message)
                            }
                        }
                    }
                },
            )
        }.getOrElse { error ->
            failVoiceRecognition(error.voiceMessage())
            return
        }
        localSession = session
        activeRealtimeSession = session
        session.start(Locale.getDefault().toLanguageTag(), CXR_SAMPLE_RATE_HZ)
        readyTimeout = Runnable {
            if (voiceActive && activeRealtimeSession === session && activeNotificationId == notificationId) {
                failVoiceRecognition("${selectedEngine.displayName} did not become ready")
            }
        }.also { main.postDelayed(it, REALTIME_READY_TIMEOUT_MS) }
    }

    private fun startRealtimeCapture(
        link: CXRLink,
        notificationId: String,
        selectedEngine: SpeechToTextEngine,
        session: RealtimeSpeechToTextSession,
    ) {
        val capture = CxrBufferedAudioCapture(link)
        activeCapture = capture
        val started = capture.start(
            onSpeechStarted = {
                if (voiceActive && activeCapture === capture && activeNotificationId == notificationId) {
                    RelayBridge.sendVoiceState("recognizing")
                }
            },
            onCaptureFinished = { _ ->
                if (voiceActive && activeCapture === capture && activeRealtimeSession === session) {
                    RelayBridge.sendVoiceState("processing")
                    session.finishAudio()
                }
            },
            bufferAudio = false,
            onAudioLevel = { snapshot ->
                if (voiceActive && activeCapture === capture && activeNotificationId == notificationId) {
                    RelayBridge.recordVoiceAudio(snapshot)
                }
            },
            onAudioChunk = { data, offset, length ->
                if (voiceActive && activeCapture === capture && activeRealtimeSession === session) {
                    session.sendAudio(data, offset, length)
                }
            },
            onError = { message ->
                if (voiceActive && activeCapture === capture && activeNotificationId == notificationId) {
                    failVoiceRecognition(message)
                }
            },
        )
        if (!started) {
            if (activeCapture === capture) activeCapture = null
            failActiveVoice("${selectedEngine.displayName}: glasses microphone unavailable")
        }
    }

    fun cancel() {
        main.post { cancelOnMain(sendIdle = true) }
    }

    fun retry(context: Context, link: CXRLink, notificationId: String) {
        main.post {
            val retryId = notificationId.ifBlank { pendingReply?.notificationId ?: activeNotificationId }
            if (retryId.isBlank()) {
                RelayBridge.sendReplyResult(notificationId, false, "No active notification")
                return@post
            }
            retryOnMain(context.applicationContext, link, retryId)
        }
    }

    private fun completeVoiceRecognition(transcript: String, reason: String) {
        val spoken = transcript.trim()
        if (spoken.isBlank()) {
            failVoiceRecognition("No speech recognized")
            return
        }
        val id = activeNotificationId
        Log.i(TAG, "Voice recognition complete reason=$reason length=${spoken.length}")
        stopVoiceInputForReview()
        beginReviewCountdown(id, spoken)
    }

    private fun transcribeCapturedAudio(context: Context, selectedEngine: SpeechToTextEngine, audio: CxrCapturedAudio) {
        val speechEngine = ApiCompletedAudioSpeechToTextEngine(SttCredentialStore(context), selectedEngine)
        activeEngine = speechEngine
        val notificationId = activeNotificationId
        transcriptionExecutor.execute {
            val result = runCatching {
                speechEngine.transcribe(
                    CompletedAudioSpeechToTextInput(
                        pcm16Mono = audio.pcm16Mono,
                        sampleRate = audio.sampleRate,
                        languageTag = Locale.getDefault().toLanguageTag(),
                    ),
                )
            }
            main.post {
                if (voiceActive && activeEngine === speechEngine && activeNotificationId == notificationId) {
                    result
                        .onSuccess { transcript ->
                            completeVoiceRecognition(transcript, "${selectedEngine.id} ${audio.closeReason}")
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

    private fun beginReviewCountdown(notificationId: String, spoken: String) {
        val totalMs = reviewDelayMs(spoken)
        val startedAt = SystemClock.elapsedRealtime()
        pendingReply = PendingVoiceReply(notificationId, spoken, startedAt, totalMs)
        sendReviewState(spoken, remainingMs = totalMs, totalMs = totalMs)
        reviewRunnable?.let(main::removeCallbacks)
        reviewRunnable = object : Runnable {
            override fun run() {
                val pending = pendingReply ?: return
                val remaining = (pending.totalMs - (SystemClock.elapsedRealtime() - pending.startedAtMs))
                    .coerceAtLeast(0L)
                if (remaining <= 0L) {
                    sendPendingReply()
                } else {
                    sendReviewState(pending.text, remaining, pending.totalMs)
                    main.postDelayed(this, minOf(REVIEW_TICK_MS, remaining))
                }
            }
        }.also { main.postDelayed(it, REVIEW_TICK_MS) }
    }

    private fun sendReviewState(spoken: String, remainingMs: Long, totalMs: Long) {
        RelayBridge.sendVoiceState(
            state = "reviewing",
            partial = spoken,
            countdownMs = remainingMs,
            countdownTotalMs = totalMs,
        )
    }

    private fun sendPendingReply() {
        val pending = pendingReply ?: return
        val context = appContext
        clearReviewCountdown()
        voiceActive = false
        activeNotificationId = ""
        val ok = if (context != null) ReplyRepository.sendReply(context, pending.notificationId, pending.text) else false
        RelayBridge.recordOutgoingReply(pending.text, ok)
        RelayBridge.recordVoiceIdle("done")
        RelayBridge.sendReplyResult(
            pending.notificationId,
            ok,
            if (ok) "Reply sent" else "Reply failed",
        )
        RelayBridge.sendInbox()
    }

    private fun stopVoiceInputForReview() {
        activeEngine = null
        activeRecognizer = null
        activeRealtimeSession?.cancel()
        activeRealtimeSession = null
        activeCapture?.stop()
        activeCapture = null
        runCatching { activeLink?.setInterruptAiWake(false) }
        runCatching { activeLink?.clearCommunicationDevice() }
        activeLink = null
        RelayBridge.recordVoiceIdle("reviewing")
    }

    private fun retryOnMain(context: Context, link: CXRLink, notificationId: String) {
        clearReviewCountdown()
        finishVoiceCapture(sendIdle = false, cancelListening = true)
        lastStartAtMs = 0L
        start(context, link, notificationId)
    }

    private fun clearReviewCountdown() {
        reviewRunnable?.let(main::removeCallbacks)
        reviewRunnable = null
        pendingReply = null
    }

    private fun reviewDelayMs(text: String): Long {
        val words = wordCount(text)
        val scaled = REVIEW_MIN_MS + (words * REVIEW_MS_PER_WORD)
        return scaled.coerceIn(REVIEW_MIN_MS, REVIEW_MAX_MS)
    }

    private fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        text.forEach { char ->
            if (char.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                count += 1
                inWord = true
            }
        }
        return count
    }

    private fun mergeRealtimeText(current: String, incoming: String, selectedEngine: SpeechToTextEngine): String {
        val clean = incoming.trim()
        if (clean.isBlank()) return current
        return if (selectedEngine.provider == SpeechToTextProvider.OPENAI) {
            appendTranscriptDelta(current, incoming)
        } else {
            clean
        }
    }

    private fun appendTranscriptDelta(current: String, incoming: String): String {
        val base = current.trim()
        val clean = incoming.trim()
        if (base.isBlank()) return clean
        val overlap = longestTextOverlap(base, clean)
        val suffix = clean.drop(overlap)
        if (suffix.isBlank()) return base
        val separator = if (base.last().isWhitespace() || suffix.first().isWhitespace()) "" else " "
        return (base + separator + suffix).trim()
    }

    private fun longestTextOverlap(left: String, right: String): Int {
        val max = minOf(left.length, right.length)
        for (length in max downTo 1) {
            if (left.regionMatches(
                    thisOffset = left.length - length,
                    other = right,
                    otherOffset = 0,
                    length = length,
                    ignoreCase = true,
                )
            ) {
                return length
            }
        }
        return 0
    }

    private fun failVoiceRecognition(message: String) {
        val id = activeNotificationId
        RelayBridge.recordVoiceError(message)
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
        clearReviewCountdown()
        voiceActive = false
        if (cancelListening) {
            activeEngine?.cancel()
            activeRecognizer?.cancel()
            activeRealtimeSession?.cancel()
        }
        activeEngine = null
        activeRecognizer = null
        activeRealtimeSession = null
        activeCapture?.stop()
        activeCapture = null
        runCatching { activeLink?.setInterruptAiWake(false) }
        runCatching { activeLink?.clearCommunicationDevice() }
        activeLink = null
        activeNotificationId = ""
        RelayBridge.recordVoiceIdle(if (sendIdle) "idle" else "done")
        if (sendIdle) RelayBridge.sendVoiceState("idle")
    }

    private fun Throwable.voiceMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private data class PendingVoiceReply(
        val notificationId: String,
        val text: String,
        val startedAtMs: Long,
        val totalMs: Long,
    )

    private const val CXR_SAMPLE_RATE_HZ = 16_000
}
