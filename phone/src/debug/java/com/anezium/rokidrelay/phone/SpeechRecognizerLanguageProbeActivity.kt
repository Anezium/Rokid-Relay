package com.anezium.rokidrelay.phone

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeechRecognizerLanguageProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val report = StringBuilder()
    private val pendingSupportSteps = ArrayDeque<SupportStep>()
    private val pendingListenSteps = ArrayDeque<ListenStep>()

    private lateinit var reportView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var supportButton: Button
    private lateinit var micListenButton: Button
    private lateinit var copyButton: Button

    private var running = false
    private var resultCount = 0
    private var currentRecognizer: SpeechRecognizer? = null
    private var currentReadFd: ParcelFileDescriptor? = null
    private var currentWriteFd: ParcelFileDescriptor? = null
    private var currentStopRunnable: Runnable? = null
    private var currentTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        appendHeader()

        if (intent.getStringExtra(EXTRA_MODE) == MODE_LISTEN) {
            main.post { runMicListenTest() }
        }
    }

    override fun onDestroy() {
        pendingSupportSteps.clear()
        pendingListenSteps.clear()
        cleanupCurrentAttempt()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))

            addView(LinearLayout(this@SpeechRecognizerLanguageProbeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                supportButton = Button(this@SpeechRecognizerLanguageProbeActivity).apply {
                    text = "Support probe"
                    setOnClickListener { runSupportProbe() }
                }
                micListenButton = Button(this@SpeechRecognizerLanguageProbeActivity).apply {
                    text = "Mic listen test"
                    setOnClickListener { runMicListenTest() }
                }
                copyButton = Button(this@SpeechRecognizerLanguageProbeActivity).apply {
                    text = "Copy report"
                    setOnClickListener { copyReport() }
                }
                addView(supportButton, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(micListenButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    leftMargin = dp(8)
                })
                addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    leftMargin = dp(8)
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            scrollView = ScrollView(this@SpeechRecognizerLanguageProbeActivity)
            reportView = TextView(this@SpeechRecognizerLanguageProbeActivity).apply {
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, dp(12))
            }
            scrollView.addView(reportView)
            addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

    private fun appendHeader() {
        appendLine("Speech recognition diagnostic")
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("App versionName: ${appVersionName()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        appendLine("System locales: ${systemLocales()}")
        appendLine("Default recognition service: ${defaultRecognitionService().ifBlank { "none" }}")
        appendLine("SpeechRecognizer available: ${SpeechRecognizer.isRecognitionAvailable(this)}")
        appendLine("On-device recognizer available: ${onDeviceRecognitionAvailable()}")
        appendLine("Available recognition services:")
        val services = recognitionServices()
        if (services.isEmpty()) {
            appendLine("  none")
        } else {
            val defaultComponent = defaultRecognitionServiceComponent()
            services.forEach { component ->
                val flags = mutableListOf<String>()
                if (component == defaultComponent) flags += "default"
                if (component.isGoogleRecognitionService()) flags += "Google"
                appendLine("  ${component.flattenToShortString()}${flags.formatFlags()}")
            }
        }
        appendLine("Recognizer targets used by this diagnostic:")
        recognitionTargets(services, includeDefault = true).forEach { target ->
            appendLine("  ${target.reportName}")
        }
        appendLine("")
        appendLine("Tap Support probe or Mic listen test. During Mic listen test, speak Cantonese after each prompt.")
        appendLine("")
    }

    private fun runSupportProbe() {
        if (!startRun("Support probe")) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            appendLine("DONE unsupported-sdk: SpeechRecognizer support probe needs Android 13+.")
            finishRun()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendLine("DONE recognition-unavailable: speech recognition is unavailable on this phone.")
            finishRun()
            return
        }

        resultCount = 0
        pendingSupportSteps.clear()
        recognitionTargets(recognitionServices(), includeDefault = true).forEach { target ->
            LANGUAGE_CASES.forEach { language ->
                pendingSupportSteps += SupportStep(target, language)
            }
        }
        appendLine("SUPPORT_START steps=${pendingSupportSteps.size}")
        runNextSupportStep()
    }

    private fun runNextSupportStep() {
        val step = pendingSupportSteps.removeFirstOrNull()
        if (step == null) {
            appendLine("SUPPORT_DONE results=$resultCount")
            finishRun()
            return
        }

        val recognizer = runCatching { step.target.create(this) }.getOrElse { error ->
            logSupportResult(step, "CREATE_ERROR", detail = error.javaClass.simpleName)
            runNextSupportStepAfterDelay()
            return
        }
        currentRecognizer = recognizer
        val intent = recognitionIntent(step.language.tag)
        var finished = false
        val timeout = Runnable {
            if (!finished) {
                finished = true
                cleanupCurrentAttempt()
                logSupportResult(step, "TIMEOUT")
                runNextSupportStepAfterDelay()
            }
        }
        currentTimeoutRunnable = timeout
        main.postDelayed(timeout, SUPPORT_TIMEOUT_MS)

        runCatching {
            recognizer.checkRecognitionSupport(
                intent,
                mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (finished) return
                        finished = true
                        cleanupCurrentAttempt()
                        logSupportResult(step, "SUPPORT", supportSummary(support))
                        runNextSupportStepAfterDelay()
                    }

                    override fun onError(error: Int) {
                        if (finished) return
                        finished = true
                        cleanupCurrentAttempt()
                        if (step.attempt < MAX_ATTEMPTS && error.isRetryableProbeError()) {
                            logSupportResult(step, "RETRY_${errorName(error)}", detail = "error=$error")
                            pendingSupportSteps.addFirst(step.copy(attempt = step.attempt + 1))
                            runNextSupportStepAfterDelay(RETRY_DELAY_MS)
                            return
                        }
                        logSupportResult(step, errorName(error), detail = "error=$error")
                        runNextSupportStepAfterDelay()
                    }
                },
            )
        }.onFailure { error ->
            if (!finished) {
                finished = true
                cleanupCurrentAttempt()
                logSupportResult(step, "CHECK_ERROR", detail = error.javaClass.simpleName)
                runNextSupportStepAfterDelay()
            }
        }
    }

    private fun runMicListenTest() {
        if (!startRun("Mic listen test")) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            appendLine("MIC_LISTEN_ABORT microphone permission missing; grant it in the app first.")
            finishRun()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendLine("MIC_LISTEN_ABORT speech recognition is unavailable on this phone.")
            finishRun()
            return
        }

        // Include the system-default recognizer: it is what pre-v0.1.15-preview.9 builds used
        // (plain createSpeechRecognizer), and on some phones it is the only target whose online
        // stack supports Cantonese — exactly the working configuration we are trying to find.
        val targets = recognitionTargets(recognitionServices(), includeDefault = true)
        if (targets.isEmpty()) {
            appendLine("MIC_LISTEN_ABORT no recognizer target is available.")
            finishRun()
            return
        }

        resultCount = 0
        pendingListenSteps.clear()
        targets.forEach { target ->
            CANTONESE_LISTEN_CASES.forEach { language ->
                pendingListenSteps += ListenStep(target, language, AudioMode.MIC)
            }
        }
        targets.forEach { target ->
            CANTONESE_LISTEN_CASES.forEach { language ->
                pendingListenSteps += ListenStep(target, language, AudioMode.INJECTED_SILENCE)
            }
        }
        appendLine("MIC_LISTEN_START steps=${pendingListenSteps.size}")
        appendLine("Speak Cantonese now when each MIC prompt appears. Each mic attempt listens for about 6 seconds.")
        runNextListenStep()
    }

    private fun runNextListenStep() {
        val step = pendingListenSteps.removeFirstOrNull()
        if (step == null) {
            appendLine("MIC_LISTEN_DONE results=$resultCount")
            finishRun()
            return
        }

        appendLine(
            "PROMPT audio=${step.audioMode.label} target=${step.target.reportName} " +
                "tag=${step.language.tagLabel}: ${if (step.audioMode == AudioMode.MIC) "Speak Cantonese now." else "Injected silent audio."}",
        )
        if (step.audioMode == AudioMode.MIC) {
            Toast.makeText(this, "Speak Cantonese now", Toast.LENGTH_SHORT).show()
        }

        val recognizer = runCatching { step.target.create(this) }.getOrElse { error ->
            logListenResult(step, "CREATE_ERROR", detail = error.javaClass.simpleName)
            runNextListenStepAfterDelay()
            return
        }
        currentRecognizer = recognizer

        val pipe = if (step.audioMode == AudioMode.INJECTED_SILENCE) {
            runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { error ->
                cleanupCurrentAttempt()
                logListenResult(step, "PIPE_ERROR", detail = error.javaClass.simpleName)
                runNextListenStepAfterDelay()
                return
            }
        } else {
            null
        }
        currentReadFd = pipe?.getOrNull(0)
        currentWriteFd = pipe?.getOrNull(1)

        val listenIntent = recognitionIntent(step.language.tag).apply {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIC_LISTEN_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, MIC_LISTEN_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, MIC_LISTEN_MS)
            if (step.audioMode == AudioMode.INJECTED_SILENCE) {
                currentReadFd?.let { readFd ->
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                }
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }

        var finished = false
        val partials = mutableListOf<String>()
        fun finishStep(outcome: String, detail: String = "") {
            if (finished) return
            finished = true
            cleanupCurrentAttempt()
            logListenResult(step, outcome, detail)
            runNextListenStepAfterDelay()
        }

        val timeout = Runnable {
            finishStep("TIMEOUT", "watchdogMs=$LISTEN_TIMEOUT_MS partials=${partials.formatTexts()}")
        }
        currentTimeoutRunnable = timeout
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS)

        if (step.audioMode == AudioMode.MIC) {
            val stop = Runnable {
                runCatching { recognizer.stopListening() }
                    .onFailure { error -> finishStep("STOP_ERROR", error.javaClass.simpleName) }
            }
            currentStopRunnable = stop
            main.postDelayed(stop, MIC_LISTEN_MS)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                appendLine("READY audio=${step.audioMode.label} target=${step.target.reportName} tag=${step.language.tagLabel}")
            }

            override fun onBeginningOfSpeech() {
                appendLine("BEGIN audio=${step.audioMode.label} target=${step.target.reportName} tag=${step.language.tagLabel}")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                appendLine("END audio=${step.audioMode.label} target=${step.target.reportName} tag=${step.language.tagLabel}")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.bestRecognizerText()
                if (text.isNotBlank()) {
                    partials += text
                    appendLine(
                        "PARTIAL audio=${step.audioMode.label} target=${step.target.reportName} " +
                            "tag=${step.language.tagLabel} text=${text.quoteForReport()}",
                    )
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                finishStep(errorName(error), "code=$error partials=${partials.formatTexts()}")
            }

            override fun onResults(results: Bundle?) {
                val texts = results.recognizerTexts()
                val finalText = texts.firstOrNull().orEmpty()
                val outcome = if (finalText.isBlank()) "RESULTS_EMPTY" else "TRANSCRIPT"
                finishStep(outcome, "final=${texts.formatTexts()} partials=${partials.formatTexts()}")
            }
        })

        runCatching {
            recognizer.startListening(listenIntent)
            if (step.audioMode == AudioMode.INJECTED_SILENCE) {
                currentWriteFd?.let { writeSilenceThenClose(it) }
            }
        }.onFailure { error ->
            finishStep("START_ERROR", error.javaClass.simpleName)
        }
    }

    private fun recognitionIntent(languageTag: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Rokid Relay speech diagnostic")
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            languageTag?.let { tag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
        }

    private fun cleanupCurrentAttempt() {
        currentStopRunnable?.let(main::removeCallbacks)
        currentStopRunnable = null
        currentTimeoutRunnable?.let(main::removeCallbacks)
        currentTimeoutRunnable = null
        runCatching { currentWriteFd?.close() }
        currentWriteFd = null
        runCatching { currentReadFd?.close() }
        currentReadFd = null
        val recognizer = currentRecognizer
        currentRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
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
        val services = runCatching {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        return services.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }
    }

    private fun recognitionTargets(services: List<ComponentName>, includeDefault: Boolean): List<RecognizerTarget> {
        val targets = mutableListOf<RecognizerTarget>()
        services.firstOrNull { it.isGoogleRecognitionService() }?.let { component ->
            targets += RecognizerTarget(
                name = "google",
                reportName = "google:${component.flattenToShortString()}",
                component = component,
            )
        }
        if (onDeviceRecognitionAvailable()) {
            targets += RecognizerTarget(
                name = "on-device",
                reportName = "on-device:${ON_DEVICE_COMPONENT_LABEL}",
                onDevice = true,
            )
        }
        if (includeDefault) {
            targets += RecognizerTarget(name = "default", reportName = "default:${DEFAULT_COMPONENT_LABEL}")
        }
        return targets.distinctBy { it.name }
    }

    private fun onDeviceRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)

    private fun defaultRecognitionService(): String =
        runCatching {
            Settings.Secure.getString(contentResolver, "voice_recognition_service").orEmpty()
        }.getOrElse { error ->
            "unreadable:${error.javaClass.simpleName}"
        }

    private fun defaultRecognitionServiceComponent(): ComponentName? =
        ComponentName.unflattenFromString(defaultRecognitionService())

    private fun appVersionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")

    private fun systemLocales(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales.toLanguageTags()
        } else {
            Locale.getDefault().toLanguageTag()
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

    private fun startRun(name: String): Boolean {
        if (running) {
            appendLine("$name ignored: another probe is still running.")
            return false
        }
        running = true
        setProbeButtonsEnabled(false)
        appendLine("")
        appendLine("== $name ==")
        return true
    }

    private fun finishRun() {
        cleanupCurrentAttempt()
        running = false
        setProbeButtonsEnabled(true)
        appendLine("")
    }

    private fun setProbeButtonsEnabled(enabled: Boolean) {
        supportButton.isEnabled = enabled
        micListenButton.isEnabled = enabled
        copyButton.isEnabled = true
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rokid Relay speech diagnostic", report.toString()))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun appendLine(line: String = "") {
        report.append(line).append('\n')
        reportView.text = report.toString()
        if (line.isNotBlank()) Log.i(TAG, line)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun logSupportResult(step: SupportStep, result: String, detail: String = "") {
        resultCount += 1
        appendLine(
            "RESULT target=${step.target.reportName} case=${step.language.name} " +
                "tag=${step.language.tagLabel} result=$result ${detail.trim()}".trimEnd(),
        )
    }

    private fun logListenResult(step: ListenStep, outcome: String, detail: String = "") {
        resultCount += 1
        appendLine(
            "LISTEN_RESULT audio=${step.audioMode.label} target=${step.target.reportName} " +
                "component=${step.target.componentLabel} case=${step.language.name} " +
                "tag=${step.language.tagLabel} outcome=$outcome ${detail.trim()}".trimEnd(),
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

    private fun runNextSupportStepAfterDelay(delayMs: Long = STEP_COOLDOWN_MS) {
        main.postDelayed({ runNextSupportStep() }, delayMs)
    }

    private fun runNextListenStepAfterDelay(delayMs: Long = LISTEN_COOLDOWN_MS) {
        main.postDelayed({ runNextListenStep() }, delayMs)
    }

    private fun ComponentName.isGoogleRecognitionService(): Boolean =
        packageName.startsWith("com.google.android")

    private fun MutableList<String>.formatFlags(): String =
        if (isEmpty()) "" else joinToString(prefix = " (", postfix = ")")

    private fun Bundle?.recognizerTexts(): List<String> =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun Bundle?.bestRecognizerText(): String =
        recognizerTexts().firstOrNull().orEmpty()

    private fun List<String>.formatTexts(): String =
        if (isEmpty()) "[]" else joinToString(prefix = "[", postfix = "]") { it.quoteForReport() }

    private fun String.quoteForReport(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private enum class AudioMode(val label: String) {
        MIC("mic"),
        INJECTED_SILENCE("injected-silence"),
    }

    private data class ProbeLanguage(
        val name: String,
        val tag: String?,
    ) {
        val tagLabel: String = tag ?: "no-hint"
    }

    private data class SupportStep(
        val target: RecognizerTarget,
        val language: ProbeLanguage,
        val attempt: Int = 1,
    )

    private data class ListenStep(
        val target: RecognizerTarget,
        val language: ProbeLanguage,
        val audioMode: AudioMode,
    )

    private data class RecognizerTarget(
        val name: String,
        val reportName: String,
        val component: ComponentName? = null,
        val onDevice: Boolean = false,
    ) {
        val componentLabel: String =
            component?.flattenToShortString() ?: if (onDevice) ON_DEVICE_COMPONENT_LABEL else DEFAULT_COMPONENT_LABEL

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
        const val SUPPORT_TIMEOUT_MS = 5_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val MIC_LISTEN_MS = 6_000L
        const val LISTEN_COOLDOWN_MS = 800L
        const val STEP_COOLDOWN_MS = 300L
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_ATTEMPTS = 2
        const val MAX_LANGUAGES_IN_LOG = 12
        const val SILENCE_CHUNK_BYTES = 3_200
        const val SILENCE_CHUNKS = 12
        const val SILENCE_CHUNK_DELAY_MS = 100L
        const val ON_DEVICE_COMPONENT_LABEL = "SpeechRecognizer.createOnDeviceSpeechRecognizer"
        const val DEFAULT_COMPONENT_LABEL = "SpeechRecognizer.createSpeechRecognizer(default)"

        val CANTONESE_LISTEN_CASES = listOf(
            ProbeLanguage("cantonese_old_specific", "yue-Hant-HK"),
            ProbeLanguage("cantonese_yue_hk", "yue-HK"),
            ProbeLanguage("cantonese_current", "zh-HK"),
            ProbeLanguage("no_hint", null),
        )

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
