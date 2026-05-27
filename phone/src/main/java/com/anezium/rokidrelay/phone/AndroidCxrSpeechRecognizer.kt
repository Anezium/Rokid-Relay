package com.anezium.rokidrelay.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class AndroidCxrSpeechRecognizer(
    private val context: Context,
    private val link: CXRLink,
    private val languageTag: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onListening()
        fun onRecognizing(partial: String)
        fun onAudioLevel(snapshot: VoiceActivitySnapshot)
        fun onComplete(transcript: String, reason: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioLock = Any()
    private val voiceActivityDetector = VoiceActivityDetector()

    private var recognizer: SpeechRecognizer? = null
    private var audioPipeRead: ParcelFileDescriptor? = null
    private var audioPipeWrite: ParcelFileDescriptor? = null
    private var audioPipeOutput: FileOutputStream? = null
    private var audioSourceActive = false
    private var finished = false
    private var bestPartialTranscript = ""
    private var lastDiagnosticsAtMs = 0L
    private var firstByteTimeout: Runnable? = null
    private var vadRunnable: Runnable? = null
    private var finalResultTimeout: Runnable? = null
    private var inputClosed = false
    private var inputCloseReason = ""

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            failBeforeStart("Android CXR STT needs Android 13+")
            return false
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failBeforeStart("Microphone permission missing")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            failBeforeStart("Android speech recognition unavailable")
            return false
        }
        RelayService.refreshForeground()
        if (!RelayService.microphoneForegroundActive) {
            failBeforeStart("Microphone foreground unavailable")
            return false
        }
        val readFd = startCxrAudioSource() ?: run {
            failBeforeStart("Glasses audio stream unavailable")
            return false
        }
        val localRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = localRecognizer
        localRecognizer.setRecognitionListener(recognitionListener(localRecognizer))
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SPEECH_INPUT_MINIMUM_LENGTH_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SPEECH_POSSIBLY_COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SPEECH_COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Rokid Relay")
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        return runCatching {
            localRecognizer.startListening(intent)
            listener.onListening()
            true
        }.getOrElse { error ->
            Log.w(TAG, "SpeechRecognizer failed to start", error)
            fail("Speech recognizer failed to start")
            false
        }
    }

    fun cancel() {
        finish {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }

    private fun startCxrAudioSource(): ParcelFileDescriptor? {
        return runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            synchronized(audioLock) {
                audioPipeRead = pipe[0]
                audioPipeWrite = pipe[1]
                audioPipeOutput = FileOutputStream(pipe[1].fileDescriptor)
                audioSourceActive = true
                finished = false
                inputClosed = false
                inputCloseReason = ""
                bestPartialTranscript = ""
                lastDiagnosticsAtMs = 0L
                voiceActivityDetector.reset(SystemClock.elapsedRealtime())
            }
            link.setCXRAudioCbk(audioCallback)
            val started = link.startAudioStream(CXR_AUDIO_PCM)
            Log.i(TAG, "CXR pipe audio stream start=$started")
            if (!started) {
                cleanupCxrAudioSource()
                null
            } else {
                scheduleFirstByteTimeout()
                startVadMonitor()
                pipe[0]
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to start CXR pipe audio", error)
            cleanupCxrAudioSource()
            null
        }
    }

    private val audioCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
            writeCxrAudio(data, offset, length)
        }

        override fun onAudioError(code: Int, msg: String?) {
            Log.w(TAG, "CXR pipe audio error=$code ${msg.orEmpty()}")
            failFromAnyThread("Glasses audio stream error $code")
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            Log.i(TAG, "CXR pipe audio stream state started=$started")
        }
    }

    private fun writeCxrAudio(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        var voiceStarted = false
        var snapshotForDiagnostics: VoiceActivitySnapshot? = null
        synchronized(audioLock) {
            if (!audioSourceActive || finished) return
            val safeOffset = offset.coerceIn(0, data.size)
            val safeLength = length.coerceAtMost(data.size - safeOffset)
            if (safeLength <= 0) return
            try {
                audioPipeOutput?.write(data, safeOffset, safeLength)
                val now = SystemClock.elapsedRealtime()
                val hadSpeech = voiceActivityDetector.speechDetected
                val activity = voiceActivityDetector.acceptPcm16Le(data, safeOffset, safeLength, now)
                voiceStarted = activity.isVoice && !hadSpeech
                if (voiceActivityDetector.totalBytes > 0L) {
                    firstByteTimeout?.let(main::removeCallbacks)
                    firstByteTimeout = null
                }
                if (now - lastDiagnosticsAtMs >= DIAGNOSTICS_UPDATE_MS) {
                    lastDiagnosticsAtMs = now
                    snapshotForDiagnostics = voiceActivityDetector.snapshot(now)
                }
            } catch (error: IOException) {
                Log.w(TAG, "Failed to write CXR audio into SpeechRecognizer pipe", error)
                failFromAnyThread("Glasses audio pipe failed")
            }
        }
        if (voiceStarted) main.post { listener.onRecognizing(bestPartialTranscript) }
        snapshotForDiagnostics?.let { snapshot -> main.post { listener.onAudioLevel(snapshot) } }
    }

    private fun startVadMonitor() {
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = object : Runnable {
            override fun run() {
                val reason = synchronized(audioLock) {
                    if (!audioSourceActive || finished || inputClosed) {
                        null
                    } else {
                        voiceActivityDetector.closeReason(SystemClock.elapsedRealtime())
                    }
                }
                if (reason != null) {
                    Log.i(TAG, "Closing CXR recognizer input reason=$reason")
                    closeRecognizerInput(reason)
                    return
                }
                main.postDelayed(this, VAD_CHECK_INTERVAL_MS)
            }
        }.also { main.postDelayed(it, VAD_CHECK_INTERVAL_MS) }
    }

    private fun recognitionListener(owner: SpeechRecognizer): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isActive(owner)) listener.onListening()
            }

            override fun onBeginningOfSpeech() {
                if (isActive(owner)) listener.onRecognizing(bestPartialTranscript)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (isActive(owner)) listener.onRecognizing(bestPartialTranscript)
            }

            override fun onError(error: Int) {
                if (!isActive(owner)) return
                val partial = bestPartialTranscript.trim()
                if (
                    partial.isNotBlank() &&
                    (inputClosed || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    complete(partial, "${inputCloseReason.ifBlank { "android-partial" }} error=$error")
                } else {
                    fail(error.toVoiceMessage())
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isActive(owner)) return
                val text = bestCompleteTranscript(
                    results.bestRecognizerText(),
                    bestPartialTranscript,
                )
                complete(text, "android-cxr")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isActive(owner)) return
                val partial = partialResults.bestRecognizerText()
                if (partial.isNotBlank()) {
                    bestPartialTranscript = mergeTranscriptWindow(bestPartialTranscript, partial)
                    listener.onRecognizing(bestPartialTranscript)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun isActive(owner: SpeechRecognizer): Boolean =
        !finished && recognizer === owner

    private fun complete(transcript: String, reason: String) {
        val clean = transcript.trim()
        if (clean.isBlank()) {
            fail("No speech recognized")
            return
        }
        finish {
            listener.onComplete(clean, reason)
        }
    }

    private fun failBeforeStart(message: String) {
        listener.onError(message)
    }

    private fun failFromAnyThread(message: String) {
        main.post { fail(message) }
    }

    private fun fail(message: String) {
        finish {
            listener.onError(message)
        }
    }

    private fun finish(afterCleanup: () -> Unit) {
        if (finished) return
        finished = true
        firstByteTimeout?.let(main::removeCallbacks)
        firstByteTimeout = null
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = null
        finalResultTimeout?.let(main::removeCallbacks)
        finalResultTimeout = null
        cleanupCxrAudioSource()
        val localRecognizer = recognizer
        recognizer = null
        runCatching { localRecognizer?.cancel() }
        runCatching { localRecognizer?.destroy() }
        afterCleanup()
    }

    private fun closeRecognizerInput(reason: String) {
        if (finished) return
        val localRecognizer = recognizer
        synchronized(audioLock) {
            if (inputClosed) return
            inputClosed = true
            inputCloseReason = reason
            audioSourceActive = false
            closeCxrWriteSideLocked()
        }
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = null
        firstByteTimeout?.let(main::removeCallbacks)
        firstByteTimeout = null
        runCatching { link.stopAudioStream() }
        runCatching { link.setCXRAudioCbk(null) }
        runCatching { localRecognizer?.stopListening() }
        scheduleFinalResultTimeout(reason)
    }

    private fun scheduleFinalResultTimeout(reason: String) {
        finalResultTimeout?.let(main::removeCallbacks)
        val timeout = Runnable {
            if (finished) return@Runnable
            val partial = bestPartialTranscript.trim()
            if (partial.isNotBlank()) {
                complete(partial, "android-partial-timeout $reason")
            } else {
                fail("No speech recognized")
            }
        }
        finalResultTimeout = timeout
        main.postDelayed(timeout, FINAL_RESULT_TIMEOUT_MS)
    }

    private fun cleanupCxrAudioSource() {
        synchronized(audioLock) {
            audioSourceActive = false
            closeCxrWriteSideLocked()
            runCatching { audioPipeRead?.close() }
            audioPipeRead = null
        }
        runCatching { link.stopAudioStream() }
        runCatching { link.setCXRAudioCbk(null) }
    }

    private fun closeCxrWriteSideLocked() {
        runCatching { audioPipeOutput?.close() }
        runCatching { audioPipeWrite?.close() }
        audioPipeOutput = null
        audioPipeWrite = null
    }

    private fun scheduleFirstByteTimeout() {
        firstByteTimeout?.let(main::removeCallbacks)
        val timeout = Runnable {
            synchronized(audioLock) {
                if (!audioSourceActive || finished || voiceActivityDetector.totalBytes > 0L) return@Runnable
            }
            fail("Glasses audio stream unavailable")
        }
        firstByteTimeout = timeout
        main.postDelayed(timeout, CXR_AUDIO_FIRST_BYTE_TIMEOUT_MS)
    }

    private fun Bundle?.bestRecognizerText(): String {
        val values = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        return values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun bestCompleteTranscript(finalText: String, partialText: String): String {
        val finalClean = finalText.trim()
        val partialClean = partialText.trim()
        if (finalClean.isBlank()) return partialClean
        if (partialClean.isBlank()) return finalClean
        val finalWords = normalizedWords(finalClean)
        val partialWords = normalizedWords(partialClean)
        return if (
            partialWords.size > finalWords.size &&
            containsTokenSequence(partialWords, finalWords)
        ) {
            partialClean
        } else {
            mergeTranscriptWindow(partialClean, finalClean)
        }
    }

    private fun mergeTranscriptWindow(current: String, incoming: String): String {
        val base = current.trim()
        val next = incoming.trim()
        if (base.isBlank()) return next
        if (next.isBlank()) return base

        val baseWords = normalizedWords(base)
        val nextWords = normalizedWords(next)
        if (baseWords.isEmpty() || nextWords.isEmpty()) return "$base $next".trim()
        if (baseWords == nextWords) return if (next.length > base.length) next else base
        if (containsTokenSequence(baseWords, nextWords)) return base
        if (containsTokenSequence(nextWords, baseWords)) return next

        val overlap = longestSuffixPrefixOverlap(baseWords, nextWords)
        val incomingWords = next.split(WORD_SPLIT_REGEX).filter { it.isNotBlank() }
        return if (overlap > 0 && overlap < incomingWords.size) {
            "$base ${incomingWords.drop(overlap).joinToString(" ")}".trim()
        } else {
            "$base $next".trim()
        }
    }

    private fun normalizedWords(text: String): List<String> =
        text.split(WORD_SPLIT_REGEX)
            .map { word ->
                word.lowercase(Locale.ROOT)
                    .trim { char -> !char.isLetterOrDigit() }
            }
            .filter { it.isNotBlank() }

    private fun containsTokenSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            if (haystack.subList(start, start + needle.size) == needle) return true
        }
        return false
    }

    private fun longestSuffixPrefixOverlap(left: List<String>, right: List<String>): Int {
        val max = minOf(left.size, right.size)
        for (size in max downTo 1) {
            if (left.takeLast(size) == right.take(size)) return size
        }
        return 0
    }

    private fun Int.toVoiceMessage(): String =
        when (this) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            SpeechRecognizer.ERROR_AUDIO -> "Audio capture error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Speech language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech language unavailable"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> "Speech network error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
            else -> "Speech recognition error $this"
        }

    private companion object {
        const val TAG = "RelayAndroidCxrStt"
        const val CXR_AUDIO_PCM = 1
        const val SAMPLE_RATE_HZ = 16_000
        const val CXR_AUDIO_FIRST_BYTE_TIMEOUT_MS = 1_800L
        const val SPEECH_INPUT_MINIMUM_LENGTH_MS = 2_500L
        const val SPEECH_POSSIBLY_COMPLETE_SILENCE_MS = 2_500L
        const val SPEECH_COMPLETE_SILENCE_MS = 3_000L
        const val DIAGNOSTICS_UPDATE_MS = 500L
        const val VAD_CHECK_INTERVAL_MS = 120L
        const val FINAL_RESULT_TIMEOUT_MS = 2_500L
        val WORD_SPLIT_REGEX = Regex("\\s+")
    }
}
