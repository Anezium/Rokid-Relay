package com.anezium.rokidrelay.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.TextView
import java.io.FileOutputStream

class SpeechRecognizerLanguageProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val pendingSteps = ArrayDeque<ProbeStep>()
    private lateinit var status: TextView
    private var resultCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Running SpeechRecognizer language probe. Watch logcat tag $TAG."
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        setContentView(status)
        if (intent.getStringExtra(EXTRA_MODE) == MODE_LISTEN) {
            runListenProbe()
        } else {
            runProbe()
        }
    }

    override fun onDestroy() {
        pendingSteps.clear()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun runProbe() {
        Log.i(TAG, "START sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "DONE unsupported-sdk")
            status.text = "SpeechRecognizer support probe needs Android 13+."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.i(TAG, "DONE recognition-unavailable")
            status.text = "Speech recognition is unavailable on this phone."
            return
        }

        val services = recognitionServices()
        Log.i(TAG, "services=${services.joinToString { it.flattenToShortString() }}")
        recognizerTargets(services).forEach { target ->
            LANGUAGE_CASES.forEach { language ->
                pendingSteps += ProbeStep(target, language)
            }
        }
        runNextStep()
    }

    private fun runListenProbe() {
        Log.i(TAG, "LISTEN_START sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "LISTEN_DONE unsupported-sdk")
            status.text = "SpeechRecognizer listen probe needs Android 13+."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.i(TAG, "LISTEN_DONE recognition-unavailable")
            status.text = "Speech recognition is unavailable on this phone."
            return
        }
        val target = recognizerTargets(recognitionServices()).firstOrNull { it.name.startsWith("google:") }
            ?: RecognizerTarget("default")
        Log.i(TAG, "LISTEN_TARGET ${target.name}")
        LANGUAGE_CASES.forEach { language ->
            pendingSteps += ProbeStep(target, language)
        }
        runNextListenStep()
    }

    private fun runNextListenStep() {
        val step = pendingSteps.removeFirstOrNull()
        if (step == null) {
            Log.i(TAG, "LISTEN_DONE results=$resultCount")
            status.text = "SpeechRecognizer listen probe complete. Results: $resultCount"
            finishAfterDelay()
            return
        }

        val recognizer = runCatching { step.target.create(this) }.getOrElse { error ->
            logListenResult(step, "CREATE_ERROR", detail = error.javaClass.simpleName)
            runNextListenStepAfterDelay()
            return
        }
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { error ->
            runCatching { recognizer.destroy() }
            logListenResult(step, "PIPE_ERROR", detail = error.javaClass.simpleName)
            runNextListenStepAfterDelay()
            return
        }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val listenIntent = recognitionIntent(step.language.tag).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        var finished = false
        fun finishStep(result: String, detail: String = "") {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(LISTEN_TIMEOUT_TOKEN)
            runCatching { writeFd.close() }
            runCatching { readFd.close() }
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
            logListenResult(step, result, detail)
            runNextListenStepAfterDelay()
        }
        val timeout = Runnable {
            finishStep("TIMEOUT")
        }
        main.postAtTime(timeout, LISTEN_TIMEOUT_TOKEN, SystemClock.uptimeMillis() + LISTEN_TIMEOUT_MS)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "LISTEN_READY case=${step.language.name} tag=${step.language.tag ?: "AUTO"}")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                finishStep(errorName(error), "error=$error")
            }

            override fun onResults(results: Bundle?) {
                finishStep("RESULTS")
            }
        })
        runCatching {
            recognizer.startListening(listenIntent)
            writeSilenceThenClose(writeFd)
        }.onFailure { error ->
            finishStep("START_ERROR", error.javaClass.simpleName)
        }
    }

    private fun runNextStep() {
        val step = pendingSteps.removeFirstOrNull()
        if (step == null) {
            Log.i(TAG, "DONE results=$resultCount")
            status.text = "SpeechRecognizer language probe complete. Results: $resultCount"
            finishAfterDelay()
            return
        }

        val recognizer = runCatching { step.target.create(this) }.getOrElse { error ->
            logResult(step, "CREATE_ERROR", detail = error.javaClass.simpleName)
            runNextStepAfterDelay()
            return
        }
        val intent = recognitionIntent(step.language.tag)
        var finished = false
        val timeout = Runnable {
            if (!finished) {
                finished = true
                runCatching { recognizer.destroy() }
                logResult(step, "TIMEOUT")
                runNextStepAfterDelay()
            }
        }
        main.postDelayed(timeout, STEP_TIMEOUT_MS)

        recognizer.checkRecognitionSupport(
            intent,
            mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (finished) return
                    finished = true
                    main.removeCallbacks(timeout)
                    runCatching { recognizer.destroy() }
                    logResult(step, "SUPPORT", supportSummary(support))
                    runNextStepAfterDelay()
                }

                override fun onError(error: Int) {
                    if (finished) return
                    finished = true
                    main.removeCallbacks(timeout)
                    runCatching { recognizer.destroy() }
                    if (step.attempt < MAX_ATTEMPTS && error.isRetryableProbeError()) {
                        logResult(step, "RETRY_${errorName(error)}", detail = "error=$error")
                        pendingSteps.addFirst(step.copy(attempt = step.attempt + 1))
                        runNextStepAfterDelay(RETRY_DELAY_MS)
                        return
                    }
                    logResult(step, errorName(error), detail = "error=$error")
                    runNextStepAfterDelay()
                }
            },
        )
    }

    private fun recognitionIntent(languageTag: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            languageTag?.let { tag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
        }

    private fun writeSilenceThenClose(writeFd: ParcelFileDescriptor) {
        Thread {
            runCatching {
                FileOutputStream(writeFd.fileDescriptor).use { output ->
                    val chunk = ByteArray(SILENCE_CHUNK_BYTES)
                    repeat(SILENCE_CHUNKS) {
                        output.write(chunk)
                        output.flush()
                        Thread.sleep(SILENCE_CHUNK_DELAY_MS)
                    }
                }
            }
            runCatching { writeFd.close() }
        }.start()
    }

    private fun recognitionServices(): List<ComponentName> {
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        return services.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }
    }

    private fun recognizerTargets(services: List<ComponentName>): List<RecognizerTarget> {
        val targets = mutableListOf<RecognizerTarget>()
        services.firstOrNull { it.packageName.startsWith("com.google.android") }?.let { component ->
            targets += RecognizerTarget("google:${component.flattenToShortString()}", component = component)
        }
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            targets += RecognizerTarget("on-device", onDevice = true)
        }
        targets += RecognizerTarget("default")
        return targets.distinctBy { it.name }
    }

    private fun supportSummary(support: RecognitionSupport): String {
        val online = support.getOnlineLanguages().sorted()
        val onDevice = support.getSupportedOnDeviceLanguages().sorted()
        val installed = support.getInstalledOnDeviceLanguages().sorted()
        val pending = support.getPendingOnDeviceLanguages().sorted()
        return "online=${online.shortList()} onDevice=${onDevice.shortList()} " +
            "installed=${installed.shortList()} pending=${pending.shortList()}"
    }

    private fun List<String>.shortList(): String =
        if (size <= MAX_LANGUAGES_IN_LOG) {
            joinToString(prefix = "[", postfix = "]")
        } else {
            take(MAX_LANGUAGES_IN_LOG).joinToString(prefix = "[", postfix = ", ... +${size - MAX_LANGUAGES_IN_LOG}]")
        }

    private fun logResult(step: ProbeStep, result: String, detail: String = "") {
        resultCount += 1
        Log.i(
            TAG,
            "RESULT target=${step.target.name} case=${step.language.name} " +
                "tag=${step.language.tag ?: "AUTO"} result=$result $detail",
        )
    }

    private fun logListenResult(step: ProbeStep, result: String, detail: String = "") {
        resultCount += 1
        Log.i(
            TAG,
            "LISTEN_RESULT target=${step.target.name} case=${step.language.name} " +
                "tag=${step.language.tag ?: "AUTO"} result=$result $detail",
        )
    }

    private fun errorName(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            else -> "ERROR_$error"
        }

    private fun Int.isRetryableProbeError(): Boolean =
        this == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
            this == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT

    private fun runNextStepAfterDelay(delayMs: Long = STEP_COOLDOWN_MS) {
        main.postDelayed({ runNextStep() }, delayMs)
    }

    private fun runNextListenStepAfterDelay(delayMs: Long = LISTEN_COOLDOWN_MS) {
        main.postDelayed({ runNextListenStep() }, delayMs)
    }

    private fun finishAfterDelay() {
        main.postDelayed({ finish() }, FINISH_DELAY_MS)
    }

    private data class ProbeLanguage(
        val name: String,
        val tag: String?,
    )

    private data class ProbeStep(
        val target: RecognizerTarget,
        val language: ProbeLanguage,
        val attempt: Int = 1,
    )

    private data class RecognizerTarget(
        val name: String,
        val component: ComponentName? = null,
        val onDevice: Boolean = false,
    ) {
        fun create(activity: Activity): SpeechRecognizer =
            when {
                onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                component != null -> SpeechRecognizer.createSpeechRecognizer(activity, component)
                else -> SpeechRecognizer.createSpeechRecognizer(activity)
            }
    }

    private companion object {
        const val TAG = "RelaySpeechLangProbe"
        const val EXTRA_MODE = "mode"
        const val MODE_LISTEN = "listen"
        const val STEP_TIMEOUT_MS = 5_000L
        val LISTEN_TIMEOUT_TOKEN = Any()
        const val LISTEN_TIMEOUT_MS = 8_000L
        const val LISTEN_COOLDOWN_MS = 600L
        const val STEP_COOLDOWN_MS = 300L
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_ATTEMPTS = 2
        const val FINISH_DELAY_MS = 1_000L
        const val MAX_LANGUAGES_IN_LOG = 12
        const val SILENCE_CHUNK_BYTES = 3_200
        const val SILENCE_CHUNKS = 12
        const val SILENCE_CHUNK_DELAY_MS = 100L

        val LANGUAGE_CASES = listOf(
            ProbeLanguage("auto", null),
            ProbeLanguage("english_current", "en-US"),
            ProbeLanguage("french_current", "fr-FR"),
            ProbeLanguage("german_current", "de-DE"),
            ProbeLanguage("spanish_current", "es-ES"),
            ProbeLanguage("italian_current", "it-IT"),
            ProbeLanguage("portuguese_current", "pt-BR"),
            ProbeLanguage("japanese_current", "ja-JP"),
            ProbeLanguage("korean_current", "ko-KR"),
            ProbeLanguage("cantonese_current", "zh-HK"),
            ProbeLanguage("cantonese_old_specific", "yue-Hant-HK"),
            ProbeLanguage("cantonese_yue_hk", "yue-HK"),
            ProbeLanguage("cantonese_iso", "yue"),
            ProbeLanguage("chinese_generic", "zh"),
            ProbeLanguage("chinese_hant_script", "zh-Hant"),
            ProbeLanguage("chinese_hans_script", "zh-Hans"),
            ProbeLanguage("traditional_current", "zh-TW"),
            ProbeLanguage("traditional_old_specific", "zh-Hant-TW"),
            ProbeLanguage("traditional_cmn", "cmn-Hant-TW"),
            ProbeLanguage("traditional_cmn_hk", "cmn-Hant-HK"),
            ProbeLanguage("simplified_current", "zh-CN"),
            ProbeLanguage("simplified_old_specific", "zh-Hans-CN"),
            ProbeLanguage("simplified_cmn", "cmn-Hans-CN"),
            ProbeLanguage("invalid_control", "zz-ZZ"),
        )
    }
}
