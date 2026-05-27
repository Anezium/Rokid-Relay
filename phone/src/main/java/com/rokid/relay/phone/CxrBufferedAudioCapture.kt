package com.rokid.relay.phone

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import java.io.ByteArrayOutputStream
import kotlin.math.abs

data class CxrCapturedAudio(
    val pcm16Mono: ByteArray,
    val sampleRate: Int,
    val closeReason: String,
)

class CxrBufferedAudioCapture(
    private val link: CXRLink,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audioLock = Any()
    private var vadRunnable: Runnable? = null
    private var audioBuffer = ByteArrayOutputStream()
    private var audioSourceActive = false
    private var audioFinished = false
    private var audioStartedAtMs = 0L
    private var audioLastVoiceAtMs = 0L
    private var audioBytes = 0L
    private var audioSpeechDetected = false
    private var audioAverageAbs = 0
    private var audioPeakAbs = 0
    private var onSpeechStarted: (() -> Unit)? = null
    private var onCaptureFinished: ((CxrCapturedAudio) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun start(
        onSpeechStarted: () -> Unit,
        onCaptureFinished: (CxrCapturedAudio) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        this.onSpeechStarted = onSpeechStarted
        this.onCaptureFinished = onCaptureFinished
        this.onError = onError
        return runCatching {
            synchronized(audioLock) {
                audioBuffer = ByteArrayOutputStream()
                audioSourceActive = true
                audioFinished = false
                audioStartedAtMs = SystemClock.elapsedRealtime()
                audioLastVoiceAtMs = 0L
                audioBytes = 0L
                audioSpeechDetected = false
                audioAverageAbs = 0
                audioPeakAbs = 0
            }
            link.setCXRAudioCbk(audioCallback)
            val started = link.startAudioStream(CXR_AUDIO_PCM)
            Log.i(TAG, "CXR audio stream start=$started")
            if (!started) {
                stop()
                false
            } else {
                startVadMonitor()
                true
            }
        }.getOrElse {
            Log.w(TAG, "Failed to start CXR audio source", it)
            stop()
            false
        }
    }

    fun stop() {
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = null
        stopAudioSource()
        synchronized(audioLock) {
            audioBuffer.reset()
            audioStartedAtMs = 0L
            audioLastVoiceAtMs = 0L
            audioBytes = 0L
            audioSpeechDetected = false
            audioAverageAbs = 0
            audioPeakAbs = 0
            audioFinished = true
        }
        onSpeechStarted = null
        onCaptureFinished = null
        onError = null
    }

    private val audioCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
            bufferCxrAudio(data, offset, length)
        }

        override fun onAudioError(code: Int, msg: String?) {
            Log.w(TAG, "CXR audio stream error=$code ${msg.orEmpty()}")
            failCapture("Glasses audio stream error $code")
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            Log.i(TAG, "CXR audio stream state started=$started")
        }
    }

    private fun bufferCxrAudio(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        var voiceStarted = false
        synchronized(audioLock) {
            if (!audioSourceActive || audioFinished) return
            val safeOffset = offset.coerceIn(0, data.size)
            val safeLength = length.coerceAtMost(data.size - safeOffset)
            if (safeLength <= 0) return
            audioBuffer.write(data, safeOffset, safeLength)
            val activity = detectPcm16Le(data, safeOffset, safeLength)
            val hadSpeech = audioSpeechDetected
            audioBytes += safeLength
            audioAverageAbs = activity.averageAbs
            audioPeakAbs = activity.peakAbs
            if (activity.isVoice) {
                audioSpeechDetected = true
                audioLastVoiceAtMs = SystemClock.elapsedRealtime()
            }
            voiceStarted = activity.isVoice && !hadSpeech
            if (audioBytes <= CXR_AUDIO_LOG_BYTES || audioBytes % CXR_AUDIO_LOG_BYTES < safeLength) {
                Log.i(
                    TAG,
                    "CXR audio bytes=$audioBytes level=${activity.averageAbs} peak=${activity.peakAbs} voice=$audioSpeechDetected",
                )
            }
        }
        if (voiceStarted) main.post { onSpeechStarted?.invoke() }
    }

    private fun startVadMonitor() {
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = object : Runnable {
            override fun run() {
                val reason = audioCloseReason()
                if (reason != null) {
                    Log.i(TAG, "Closing CXR audio input reason=$reason")
                    finishCapture(reason)
                    return
                }
                main.postDelayed(this, VAD_CHECK_INTERVAL_MS)
            }
        }.also { main.postDelayed(it, VAD_CHECK_INTERVAL_MS) }
    }

    private fun audioCloseReason(): String? =
        synchronized(audioLock) {
            if (!audioSourceActive || audioFinished) return@synchronized null
            val now = SystemClock.elapsedRealtime()
            val activeForMs = (now - audioStartedAtMs).coerceAtLeast(0L)
            val silenceForMs = if (audioLastVoiceAtMs == 0L) 0L else (now - audioLastVoiceAtMs).coerceAtLeast(0L)
            when {
                audioSpeechDetected &&
                    activeForMs >= SPEECH_INPUT_MINIMUM_LENGTH_MS &&
                    silenceForMs >= SPEECH_POSSIBLY_COMPLETE_SILENCE_MS ->
                    "silence-after-speech activeForMs=$activeForMs silenceForMs=$silenceForMs"
                !audioSpeechDetected &&
                    activeForMs >= CXR_AUDIO_FIRST_BYTE_TIMEOUT_MS &&
                    audioBytes == 0L ->
                    "no-audio-bytes activeForMs=$activeForMs"
                !audioSpeechDetected &&
                    activeForMs >= 8_000L &&
                    audioBytes > 0L ->
                    "no-vad-speech-timeout activeForMs=$activeForMs level=$audioAverageAbs peak=$audioPeakAbs"
                activeForMs >= CXR_AUDIO_MAX_CAPTURE_MS ->
                    "safety-max activeForMs=$activeForMs"
                else -> null
            }
        }

    private fun finishCapture(reason: String) {
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = null
        val captured = synchronized(audioLock) {
            if (audioFinished) return
            audioFinished = true
            audioSourceActive = false
            audioBuffer.toByteArray()
        }
        stopAudioSource()
        if (captured.size < MIN_AUDIO_BYTES) {
            main.post { onError?.invoke("No audio captured from glasses") }
            return
        }
        main.post {
            onCaptureFinished?.invoke(
                CxrCapturedAudio(
                    pcm16Mono = captured,
                    sampleRate = SAMPLE_RATE_HZ,
                    closeReason = reason,
                ),
            )
        }
    }

    private fun failCapture(message: String) {
        vadRunnable?.let(main::removeCallbacks)
        vadRunnable = null
        synchronized(audioLock) {
            if (audioFinished) return
            audioFinished = true
            audioSourceActive = false
        }
        stopAudioSource()
        main.post { onError?.invoke(message) }
    }

    private fun stopAudioSource() {
        runCatching { link.stopAudioStream() }
        runCatching { link.setCXRAudioCbk(null) }
    }

    private fun detectPcm16Le(data: ByteArray, offset: Int, length: Int): AudioActivity {
        val safeOffset = offset.coerceIn(0, data.size)
        val end = (safeOffset + length.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end - safeOffset < 2) return AudioActivity(0, 0, false)
        var sumAbs = 0L
        var peakAbs = 0
        var samples = 0
        var index = safeOffset
        while (index + 1 < end) {
            val low = data[index].toInt() and 0xff
            val high = data[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(sample)
            sumAbs += magnitude
            if (magnitude > peakAbs) peakAbs = magnitude
            samples += 1
            index += 2
        }
        val averageAbs = if (samples == 0) 0 else (sumAbs / samples).toInt()
        return AudioActivity(
            averageAbs = averageAbs,
            peakAbs = peakAbs,
            isVoice = averageAbs >= VAD_AVERAGE_ABS_THRESHOLD || peakAbs >= VAD_PEAK_ABS_THRESHOLD,
        )
    }

    private data class AudioActivity(
        val averageAbs: Int,
        val peakAbs: Int,
        val isVoice: Boolean,
    )

    companion object {
        private const val TAG = "RelayCxrAudio"
        private const val CXR_AUDIO_PCM = 1
        private const val SAMPLE_RATE_HZ = 16_000
        private const val MIN_AUDIO_BYTES = 3_200
        private const val CXR_AUDIO_LOG_BYTES = 32_000L
        private const val CXR_AUDIO_FIRST_BYTE_TIMEOUT_MS = 1_800L
        private const val CXR_AUDIO_MAX_CAPTURE_MS = 30_000L
        private const val VAD_CHECK_INTERVAL_MS = 120L
        private const val VAD_AVERAGE_ABS_THRESHOLD = 350
        private const val VAD_PEAK_ABS_THRESHOLD = 2_800
        private const val SPEECH_INPUT_MINIMUM_LENGTH_MS = 2_500L
        private const val SPEECH_POSSIBLY_COMPLETE_SILENCE_MS = 2_500L
    }
}
