package com.rokid.relay.phone

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import java.io.ByteArrayOutputStream

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
    private val voiceActivityDetector = VoiceActivityDetector()
    private var vadRunnable: Runnable? = null
    private var audioBuffer = ByteArrayOutputStream()
    private var audioSourceActive = false
    private var audioFinished = false
    private var lastDiagnosticsAtMs = 0L
    private var onSpeechStarted: (() -> Unit)? = null
    private var onCaptureFinished: ((CxrCapturedAudio) -> Unit)? = null
    private var onAudioLevel: ((VoiceActivitySnapshot) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun start(
        onSpeechStarted: () -> Unit,
        onCaptureFinished: (CxrCapturedAudio) -> Unit,
        onAudioLevel: (VoiceActivitySnapshot) -> Unit = {},
        onError: (String) -> Unit,
    ): Boolean {
        this.onSpeechStarted = onSpeechStarted
        this.onCaptureFinished = onCaptureFinished
        this.onAudioLevel = onAudioLevel
        this.onError = onError
        return runCatching {
            synchronized(audioLock) {
                audioBuffer = ByteArrayOutputStream()
                audioSourceActive = true
                audioFinished = false
                lastDiagnosticsAtMs = 0L
                voiceActivityDetector.reset(SystemClock.elapsedRealtime())
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
            lastDiagnosticsAtMs = 0L
            voiceActivityDetector.reset(0L)
            audioFinished = true
        }
        onSpeechStarted = null
        onCaptureFinished = null
        onAudioLevel = null
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
        var snapshotForDiagnostics: VoiceActivitySnapshot? = null
        synchronized(audioLock) {
            if (!audioSourceActive || audioFinished) return
            val safeOffset = offset.coerceIn(0, data.size)
            val safeLength = length.coerceAtMost(data.size - safeOffset)
            if (safeLength <= 0) return
            audioBuffer.write(data, safeOffset, safeLength)
            val now = SystemClock.elapsedRealtime()
            val hadSpeech = voiceActivityDetector.speechDetected
            val activity = voiceActivityDetector.acceptPcm16Le(data, safeOffset, safeLength, now)
            voiceStarted = activity.isVoice && !hadSpeech
            val totalBytes = voiceActivityDetector.totalBytes
            if (now - lastDiagnosticsAtMs >= DIAGNOSTICS_UPDATE_MS) {
                lastDiagnosticsAtMs = now
                snapshotForDiagnostics = voiceActivityDetector.snapshot(now)
            }
            if (totalBytes <= CXR_AUDIO_LOG_BYTES || totalBytes % CXR_AUDIO_LOG_BYTES < safeLength) {
                Log.i(
                    TAG,
                    "CXR audio bytes=$totalBytes level=${activity.averageAbs} peak=${activity.peakAbs} voice=${voiceActivityDetector.speechDetected}",
                )
            }
        }
        if (voiceStarted) main.post { onSpeechStarted?.invoke() }
        snapshotForDiagnostics?.let { snapshot -> main.post { onAudioLevel?.invoke(snapshot) } }
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
            voiceActivityDetector.closeReason(SystemClock.elapsedRealtime())
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

    companion object {
        private const val TAG = "RelayCxrAudio"
        private const val CXR_AUDIO_PCM = 1
        private const val SAMPLE_RATE_HZ = 16_000
        private const val MIN_AUDIO_BYTES = 3_200
        private const val CXR_AUDIO_LOG_BYTES = 32_000L
        private const val VAD_CHECK_INTERVAL_MS = 120L
        private const val DIAGNOSTICS_UPDATE_MS = 500L
    }
}
