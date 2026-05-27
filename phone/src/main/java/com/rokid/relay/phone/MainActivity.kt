package com.rokid.relay.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val modeButtons = mutableMapOf<SpeechMode, Button>()
    private val providerButtons = mutableMapOf<SpeechToTextProvider, Button>()
    private val modelOptionRows = mutableMapOf<SpeechToTextEngine, SttModelOptionRow>()

    private lateinit var setupRows: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var sttSummary: TextView
    private lateinit var apiChoiceContainer: LinearLayout
    private lateinit var providerHint: TextView
    private lateinit var modelChoiceContainer: LinearLayout
    private lateinit var modelButtonsContainer: LinearLayout
    private lateinit var apiKeysToggleButton: Button
    private lateinit var apiKeysContainer: LinearLayout
    private lateinit var openAiKeyBlock: LinearLayout
    private lateinit var elevenLabsKeyBlock: LinearLayout
    private lateinit var openAiKeyInput: EditText
    private lateinit var elevenLabsKeyInput: EditText
    private lateinit var openAiKeyMeta: TextView
    private lateinit var elevenLabsKeyMeta: TextView
    private lateinit var diagnosticsToggleButton: Button
    private lateinit var diagnosticsContainer: LinearLayout
    private lateinit var activityText: TextView

    private var runtimePermissionRequestInFlight = false
    private var authRequestInFlight = false
    private var autoAuthAttempted = false
    private var autoReauthAttempted = false
    private var openAiKeyVisible = false
    private var elevenLabsKeyVisible = false
    private var apiKeysVisible = false
    private var diagnosticsVisible = false

    private val pollStatus = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        if (!runtimePermissionRequestInFlight) autoStartOrAuthorize("app_open")
        renderStatus()
        handler.post(pollStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(pollStatus)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runtimePermissionRequestInFlight = false
        autoStartOrAuthorize("permissions")
        RelayService.refreshForeground()
        renderStatus()
    }

    @Deprecated("Hi Rokid still returns authorization through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Constants.AUTH_REQUEST_CODE) return
        authRequestInFlight = false

        val notice = when (val result = CxrLAuth.parseAuthorizationResult(resultCode, data)) {
            is CxrLAuth.Result.Success -> {
                prefs().edit().putString(Constants.PREF_AUTH_TOKEN, result.token).apply()
                autoReauthAttempted = false
                RelayStarter.start(this, result.token, "authorization")
                "Hi Rokid authorized"
            }
            is CxrLAuth.Result.Fail -> "Authorization failed: ${result.reason}"
            is CxrLAuth.Result.Cancel -> "Authorization cancelled"
        }
        renderStatus()
        toastLine(notice)
    }

    private fun buildContent(): ScrollView {
        val horizontalPadding = dp(18)
        val topPadding = dp(16)
        val bottomPadding = dp(22)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            applySystemBarPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
        }

        root.addView(header(), matchWrap())

        root.addView(panel("Setup") {
            setupRows = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(setupRows, matchWrap())
            noticeText = bodyText().apply {
                setPadding(0, dp(12), 0, 0)
            }
            addView(noticeText, matchWrap())
        })

        root.addView(panel("Speech") {
            sttSummary = bodyText()
            addView(sttSummary, matchWrap())
            addView(label("Engine"), matchWrap(top = 14))
            addView(modeSelector(), matchWrap(top = 8))

            apiChoiceContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("API"), matchWrap(top = 14))
                addView(providerSelector(), matchWrap(top = 8))
                providerHint = bodyText()
                addView(providerHint, matchWrap(top = 8))
            }
            addView(apiChoiceContainer, matchWrap())

            modelChoiceContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Model"), matchWrap(top = 14))
                modelButtonsContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(modelButtonsContainer, matchWrap(top = 8))
            }
            addView(modelChoiceContainer, matchWrap())

            apiKeysToggleButton = textButton("Manage API keys") {
                apiKeysVisible = !apiKeysVisible
                renderStatus()
            }
            addView(apiKeysToggleButton, matchWrap(top = 12))

            apiKeysContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            openAiKeyBlock = apiKeyBlock(
                title = "OpenAI",
                hint = "sk-...",
                kind = SpeechToTextCredentialKind.OPENAI,
                isVisible = { openAiKeyVisible },
                setVisible = { openAiKeyVisible = it },
                setInput = { openAiKeyInput = it },
                setMeta = { openAiKeyMeta = it },
            )
            elevenLabsKeyBlock = apiKeyBlock(
                title = "ElevenLabs",
                hint = "xi-...",
                kind = SpeechToTextCredentialKind.ELEVENLABS,
                isVisible = { elevenLabsKeyVisible },
                setVisible = { elevenLabsKeyVisible = it },
                setInput = { elevenLabsKeyInput = it },
                setMeta = { elevenLabsKeyMeta = it },
            )
            apiKeysContainer.addView(openAiKeyBlock, matchWrap(top = 12))
            apiKeysContainer.addView(elevenLabsKeyBlock, matchWrap(top = 12))
            addView(apiKeysContainer, matchWrap())
        })

        root.addView(panel("Diagnostics") {
            diagnosticsToggleButton = textButton("Show diagnostics") {
                diagnosticsVisible = !diagnosticsVisible
                updateDiagnosticsVisibility()
            }
            addView(diagnosticsToggleButton, matchWrap(top = 12))

            diagnosticsContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            diagnosticsContainer.addView(buttonRow(
                smallButton("Test notification", ButtonTone.Secondary) {
                    TestNotificationReceiver.postTestNotification(this@MainActivity)
                    renderStatus()
                },
                smallButton("Long test", ButtonTone.Secondary) {
                    TestNotificationReceiver.postTestNotification(
                        this@MainActivity,
                        "Long message de test pour Rokid Relay. Il doit rester lisible sur les lunettes sans prendre tout l'ecran: " +
                            "on garde quelques lignes utiles, puis le reste est tronque proprement. Cette phrase ajoute volontairement " +
                            "du contenu pour verifier l'ellipse, la hauteur maximale et le confort en notification reelle.",
                    )
                    renderStatus()
                },
            ), matchWrap(top = 10))
            diagnosticsContainer.addView(rule(), matchWrap(top = 14))
            diagnosticsContainer.addView(label("Last activity"), matchWrap(top = 12))
            activityText = bodyText().apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
            }
            diagnosticsContainer.addView(activityText, matchWrap(top = 8))
            addView(diagnosticsContainer, matchWrap())
        })

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_APP_BG)
            isFillViewport = true
            addView(root, matchWrap())
        }
    }

    private fun header(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Rokid Relay"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(COLOR_TEXT)
            }, matchWrap())
        }

    private fun panel(title: String, build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = roundedRect(COLOR_PANEL, COLOR_STROKE, radius = 10)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(COLOR_TEXT)
            }, matchWrap())
            build()
            layoutParams = matchWrap(top = 12)
        }

    private fun setupRow(
        title: String,
        value: String,
        tone: StatusTone,
        actionLabel: String,
        actionTone: ButtonTone,
        onClick: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            addView(View(this@MainActivity).apply {
                background = dot(statusColor(tone))
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setTextColor(COLOR_TEXT)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, matchWrap())
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 12f
                    includeFontPadding = false
                    setTextColor(statusColor(tone))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, matchWrap(top = 4))
            }, LinearLayout.LayoutParams(0, wrap(), 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
            })
            addView(smallButton(actionLabel, actionTone, onClick), LinearLayout.LayoutParams(dp(112), dp(38)))
        }

    private fun modeSelector(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            modeButtons.clear()
            SpeechMode.values().forEachIndexed { index, mode ->
                val button = selectorButton(mode.label) {
                    val store = SpeechToTextSettingsStore(this@MainActivity)
                    val current = store.selectedEngine()
                    val next = when (mode) {
                        SpeechMode.ANDROID -> SpeechToTextEngine.ANDROID_CXR
                        SpeechMode.API -> if (current.provider == SpeechToTextProvider.ANDROID) {
                            defaultApiEngine()
                        } else {
                            current
                        }
                    }
                    if (store.selectedEngine() != next) {
                        store.saveSelectedEngine(next)
                        if (next.requiresMicrophonePermission) requestMicrophonePermissionIfNeeded()
                        autoStartOrAuthorize("stt_engine")
                        renderStatus()
                    }
                }
                modeButtons[mode] = button
                addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }

    private fun providerSelector(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            providerButtons.clear()
            listOf(SpeechToTextProvider.OPENAI, SpeechToTextProvider.ELEVENLABS).forEachIndexed { index, provider ->
                val button = selectorButton(provider.displayName) {
                    val next = defaultEngineForProvider(provider)
                    val store = SpeechToTextSettingsStore(this@MainActivity)
                    if (store.selectedEngine() != next) {
                        store.saveSelectedEngine(next)
                        autoStartOrAuthorize("stt_provider")
                        renderStatus()
                    }
                }
                providerButtons[provider] = button
                addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }

    private fun renderModelSelector(provider: SpeechToTextProvider, selected: SpeechToTextEngine) {
        if (!::modelButtonsContainer.isInitialized) return
        modelButtonsContainer.removeAllViews()
        modelOptionRows.clear()
        modelsForProvider(provider).forEachIndexed { rowIndex, engine ->
            val option = SttModelOptionRow(this, engine) { selectedEngine ->
                val store = SpeechToTextSettingsStore(this@MainActivity)
                if (store.selectedEngine() != selectedEngine) {
                    store.saveSelectedEngine(selectedEngine)
                    autoStartOrAuthorize("stt_model")
                    renderStatus()
                }
            }
            modelOptionRows[engine] = option
            modelButtonsContainer.addView(option, matchWrap(top = if (rowIndex == 0) 0 else 8))
        }
        updateModelOptions(selected)
    }

    private fun selectorButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(40)
            stateListAnimator = null
            elevation = 0f
            background = roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8)
            setTextColor(COLOR_TEXT)
            setOnClickListener { onClick() }
        }

    private fun apiKeyBlock(
        title: String,
        hint: String,
        kind: SpeechToTextCredentialKind,
        isVisible: () -> Boolean,
        setVisible: (Boolean) -> Unit,
        setInput: (EditText) -> Unit,
        setMeta: (TextView) -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("$title API key"), LinearLayout.LayoutParams(0, wrap(), 1f))
                val meta = TextView(this@MainActivity).apply {
                    textSize = 12f
                    includeFontPadding = false
                    setTextColor(COLOR_MUTED)
                    gravity = Gravity.END
                }
                setMeta(meta)
                addView(meta, LinearLayout.LayoutParams(0, wrap(), 1f))
            }, matchWrap())

            val input = keyInput(hint)
            setInput(input)
            setApiKeyVisibility(input, isVisible())

            lateinit var toggle: Button
            toggle = smallButton(if (isVisible()) "Hide" else "Show", ButtonTone.Secondary) {
                setVisible(!isVisible())
                setApiKeyVisibility(input, isVisible())
                toggle.text = if (isVisible()) "Hide" else "Show"
            }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(toggle, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                    leftMargin = dp(8)
                })
            }, matchWrap(top = 8))

            val provider = when (kind) {
                SpeechToTextCredentialKind.OPENAI -> "OpenAI"
                SpeechToTextCredentialKind.ELEVENLABS -> "ElevenLabs"
                SpeechToTextCredentialKind.NONE -> "STT"
            }
            addView(buttonRow(
                smallButton("Save", ButtonTone.Primary) {
                    val notice = runCatching {
                        SttCredentialStore(this@MainActivity).saveApiKey(kind, input.text.toString())
                    }.fold(
                        onSuccess = {
                            input.text.clear()
                            "$provider key saved"
                        },
                        onFailure = {
                            "Failed to save $provider key: ${it.message}"
                        },
                    )
                    renderStatus()
                    toastLine(notice)
                },
                smallButton("Clear", ButtonTone.Secondary) {
                    SttCredentialStore(this@MainActivity).clearApiKey(kind)
                    input.text.clear()
                    renderStatus()
                    toastLine("$provider key cleared")
                },
            ), matchWrap(top = 8))
        }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }

    private fun smallButton(label: String, tone: ButtonTone, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(buttonTextColor(tone))
            background = buttonBackground(tone)
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit): Button =
        smallButton(label, ButtonTone.Secondary, onClick)

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(COLOR_MUTED)
        }

    private fun bodyText(): TextView =
        TextView(this).apply {
            textSize = 12.5f
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(COLOR_MUTED)
        }

    private fun keyInput(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 14f
            setSingleLine(true)
            includeFontPadding = false
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_DIM)
            typeface = Typeface.MONOSPACE
            setSelectAllOnFocus(true)
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(42)
            background = inputBackground()
        }

    private fun setApiKeyVisibility(input: EditText, visible: Boolean) {
        input.inputType = InputType.TYPE_CLASS_TEXT or if (visible) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        input.typeface = Typeface.MONOSPACE
        input.setSingleLine(true)
        input.setSelection(input.text.length)
    }

    private fun renderStatus() {
        val snap = RelayBridge.snapshot()
        val hiRokid = CxrLAuth.isGlobalHiRokidInstalled(this)
        val notifications = notificationAccessEnabled()
        val authSaved = !savedToken().isNullOrBlank()
        val selectedEngine = SpeechToTextSettingsStore(this).selectedEngine()
        val stt = SttCredentialStore(this)
        val openAiLabel = stt.accountLabel(SpeechToTextCredentialKind.OPENAI)
        val elevenLabsLabel = stt.accountLabel(SpeechToTextCredentialKind.ELEVENLABS)
        val sttReady = sttReady(selectedEngine, stt)
        val micPermissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        maybeAutoReauthorizeAfterBindFailure(snap, authSaved)

        if (::setupRows.isInitialized) {
            setupRows.removeAllViews()
            setupRows.addView(setupRow(
                title = "Hi Rokid",
                value = if (hiRokid) "Installed" else "Not visible",
                tone = if (hiRokid) StatusTone.Ready else StatusTone.Waiting,
                actionLabel = "Authorize",
                actionTone = if (authSaved) ButtonTone.Secondary else ButtonTone.Primary,
                onClick = { requestHiRokidAuthorization(auto = false, reason = "manual") },
            ))
            setupRows.addView(setupRow(
                title = "Notification access",
                value = if (notifications) "Enabled" else "Disabled",
                tone = if (notifications) StatusTone.Ready else StatusTone.Waiting,
                actionLabel = "Open",
                actionTone = ButtonTone.Secondary,
                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            ), matchWrap(top = 8))
            setupRows.addView(setupRow(
                title = "Microphone",
                value = if (micPermissionGranted) "Granted" else "Needed for Android CXR",
                tone = if (micPermissionGranted) StatusTone.Ready else StatusTone.Waiting,
                actionLabel = "Grant",
                actionTone = ButtonTone.Secondary,
                onClick = {
                    requestMicrophonePermissionIfNeeded()
                    autoStartOrAuthorize("microphone_permission")
                    renderStatus()
                },
            ), matchWrap(top = 8))
            setupRows.addView(setupRow(
                title = "Relay service",
                value = if (RelayService.running) "Forwarding notifications" else "Stopped",
                tone = if (RelayService.running) StatusTone.Ready else StatusTone.Neutral,
                actionLabel = "Stop",
                actionTone = ButtonTone.Danger,
                onClick = {
                    RelayStarter.stop(this)
                    renderStatus()
                },
            ), matchWrap(top = 8))
        }

        if (::noticeText.isInitialized) {
            noticeText.text = when {
                !hiRokid -> "Install or expose Hi Rokid Global first."
                !authSaved -> "Authorize once, then the relay can start automatically."
                !notifications -> "Notification access is still required."
                !sttReady -> "Finish speech-to-text setup for voice replies."
                RelayService.running -> "Ready. Replyable notifications will forward to the glasses."
                else -> "Ready to start."
            }
            noticeText.setTextColor(if (hiRokid && authSaved && notifications && sttReady) COLOR_PHOSPHOR else COLOR_MUTED)
        }

        if (::sttSummary.isInitialized) {
            sttSummary.text = when {
                selectedEngine.requiresMicrophonePermission && !micPermissionGranted ->
                    "${selectedEngine.displayName}. Microphone permission required."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.OPENAI && openAiLabel.isNullOrBlank() ->
                    "${selectedEngine.displayName}. Add an OpenAI key."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.ELEVENLABS && elevenLabsLabel.isNullOrBlank() ->
                    "${selectedEngine.displayName}. Add an ElevenLabs key."
                selectedEngine.requiresMicrophonePermission ->
                    "${selectedEngine.displayName}. Uses glasses PCM through Android recognition."
                selectedEngine.usesRealtime ->
                    "${selectedEngine.displayName}. Streams glasses audio for live transcript updates."
                else ->
                    "${selectedEngine.displayName}. Uses buffered glasses audio."
            }
            sttSummary.setTextColor(if (sttReady) COLOR_TEXT else COLOR_MUTED)
        }

        updateSpeechChoiceButtons(selectedEngine)
        updateApiKeys(selectedEngine, openAiLabel, elevenLabsLabel)

        if (::activityText.isInitialized) {
            activityText.setTextColor(COLOR_TEXT)
            activityText.text = buildString {
                appendLine("Event: ${snap.lastStatus}")
                appendLine("Voice: ${snap.voiceRoute} / ${snap.sttEngine}")
                appendLine("CXR-L: ${if (snap.cxrConnected) "connected" else "disconnected"}")
                appendLine("Glasses BT: ${if (snap.glassConnected) "connected" else "waiting"}")
                appendLine("Glasses app: ${snap.bootstrapState}")
                appendLine("Mic foreground: ${if (RelayService.microphoneForegroundActive) "active" else "off"}")
                appendLine("CXR audio: ${displayBytes(snap.cxrAudioBytes)} avg=${snap.vadAverageAbs} peak=${snap.vadPeakAbs} speech=${snap.vadSpeechDetected}")
                if (snap.lastVoiceError.isNotBlank()) appendLine("Voice error: ${snap.lastVoiceError}")
                appendLine("Sent: ${displayMessage(snap.lastOutgoingReply)}")
                append("Received: ${displayMessage(snap.lastDeliveredReply)}")
            }
        }
    }

    private fun updateSpeechChoiceButtons(selected: SpeechToTextEngine) {
        val mode = if (selected.provider == SpeechToTextProvider.ANDROID) SpeechMode.ANDROID else SpeechMode.API
        modeButtons.forEach { (itemMode, button) ->
            val isSelected = itemMode == mode
            button.setTextColor(if (isSelected) COLOR_PHOSPHOR else COLOR_TEXT)
            button.background = roundedRect(
                if (isSelected) COLOR_SELECTED else COLOR_FIELD,
                if (isSelected) COLOR_PHOSPHOR_DIM else COLOR_STROKE,
                radius = 8,
                strokeWidth = if (isSelected) 2 else 1,
            )
        }

        val apiVisible = mode == SpeechMode.API
        if (::apiChoiceContainer.isInitialized) apiChoiceContainer.visibility = if (apiVisible) View.VISIBLE else View.GONE
        if (::modelChoiceContainer.isInitialized) modelChoiceContainer.visibility = if (apiVisible) View.VISIBLE else View.GONE
        if (!apiVisible) return

        providerButtons.forEach { (provider, button) ->
            val isSelected = provider == selected.provider
            button.setTextColor(if (isSelected) COLOR_PHOSPHOR else COLOR_TEXT)
            button.background = roundedRect(
                if (isSelected) COLOR_SELECTED else COLOR_FIELD,
                if (isSelected) COLOR_PHOSPHOR_DIM else COLOR_STROKE,
                radius = 8,
                strokeWidth = if (isSelected) 2 else 1,
            )
        }
        if (::providerHint.isInitialized) {
            providerHint.text = providerDescription(selected.provider)
        }
        renderModelSelector(selected.provider, selected)
    }

    private fun updateModelOptions(selected: SpeechToTextEngine) {
        modelOptionRows.forEach { (engine, option) ->
            val isSelected = engine == selected
            option.bindSelected(isSelected)
        }
    }

    private fun updateApiKeys(
        selectedEngine: SpeechToTextEngine,
        openAiLabel: String?,
        elevenLabsLabel: String?,
    ) {
        val selectedOpenAi = selectedEngine.credentialKind == SpeechToTextCredentialKind.OPENAI
        val selectedElevenLabs = selectedEngine.credentialKind == SpeechToTextCredentialKind.ELEVENLABS
        val apiSelected = selectedOpenAi || selectedElevenLabs
        val forceOpen = (selectedOpenAi && openAiLabel.isNullOrBlank()) ||
            (selectedElevenLabs && elevenLabsLabel.isNullOrBlank())
        val showKeys = apiKeysVisible || forceOpen

        apiKeysToggleButton.visibility = if (apiSelected) View.VISIBLE else View.GONE
        apiKeysContainer.visibility = if (apiSelected && showKeys) View.VISIBLE else View.GONE
        apiKeysToggleButton.text = if (showKeys && apiKeysVisible) "Hide API keys" else "Manage API keys"

        openAiKeyBlock.visibility = if (apiKeysVisible || selectedOpenAi) View.VISIBLE else View.GONE
        elevenLabsKeyBlock.visibility = if (apiKeysVisible || selectedElevenLabs) View.VISIBLE else View.GONE

        openAiKeyMeta.text = openAiLabel ?: "not saved"
        openAiKeyMeta.setTextColor(if (openAiLabel.isNullOrBlank()) COLOR_MUTED else COLOR_PHOSPHOR)
        elevenLabsKeyMeta.text = elevenLabsLabel ?: "not saved"
        elevenLabsKeyMeta.setTextColor(if (elevenLabsLabel.isNullOrBlank()) COLOR_MUTED else COLOR_PHOSPHOR)
    }

    private fun updateDiagnosticsVisibility() {
        if (!::diagnosticsContainer.isInitialized) return
        diagnosticsContainer.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        if (::diagnosticsToggleButton.isInitialized) {
            diagnosticsToggleButton.text = if (diagnosticsVisible) "Hide diagnostics" else "Show diagnostics"
        }
    }

    private fun defaultApiEngine(): SpeechToTextEngine =
        if (!SttCredentialStore(this).apiKey(SpeechToTextCredentialKind.ELEVENLABS).isNullOrBlank()) {
            SpeechToTextEngine.ELEVENLABS_SCRIBE_V2_REALTIME
        } else {
            SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER
        }

    private fun defaultEngineForProvider(provider: SpeechToTextProvider): SpeechToTextEngine =
        when (provider) {
            SpeechToTextProvider.OPENAI -> SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER
            SpeechToTextProvider.ELEVENLABS -> SpeechToTextEngine.ELEVENLABS_SCRIBE_V2_REALTIME
            SpeechToTextProvider.ANDROID -> SpeechToTextEngine.ANDROID_CXR
        }

    private fun modelsForProvider(provider: SpeechToTextProvider): List<SpeechToTextEngine> =
        SpeechToTextEngine.values()
            .filter { it.provider == provider && it.usesApiAudio }

    private fun providerDescription(provider: SpeechToTextProvider): String =
        when (provider) {
            SpeechToTextProvider.OPENAI -> "OpenAI: strong all-purpose recognition. Good if you already use an OpenAI key."
            SpeechToTextProvider.ELEVENLABS -> "ElevenLabs: voice-focused recognition with simple realtime and buffered choices."
            SpeechToTextProvider.ANDROID -> "Android: local phone recognition. No API key, but needs microphone permission."
        }

    private fun buttonBackground(tone: ButtonTone): StateListDrawable {
        val fill: Int
        val pressed: Int
        val stroke: Int
        when (tone) {
            ButtonTone.Primary -> {
                fill = COLOR_ACTION
                pressed = COLOR_ACTION_PRESSED
                stroke = COLOR_PHOSPHOR_DIM
            }
            ButtonTone.Secondary -> {
                fill = COLOR_FIELD
                pressed = COLOR_PANEL_ALT
                stroke = COLOR_STROKE
            }
            ButtonTone.Danger -> {
                fill = COLOR_DANGER_BG
                pressed = COLOR_DANGER_PRESSED
                stroke = COLOR_DANGER
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), roundedRect(COLOR_DISABLED, COLOR_STROKE, radius = 8))
            addState(intArrayOf(android.R.attr.state_pressed), roundedRect(pressed, stroke, radius = 8))
            addState(intArrayOf(android.R.attr.state_focused), roundedRect(pressed, COLOR_PHOSPHOR, radius = 8, strokeWidth = 2))
            addState(intArrayOf(), roundedRect(fill, stroke, radius = 8))
        }
    }

    private fun buttonTextColor(tone: ButtonTone): Int =
        when (tone) {
            ButtonTone.Primary -> COLOR_PHOSPHOR
            ButtonTone.Danger -> COLOR_DANGER
            ButtonTone.Secondary -> COLOR_TEXT
        }

    private fun inputBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundedRect(COLOR_FIELD, COLOR_PHOSPHOR_DIM, radius = 8, strokeWidth = 2))
            addState(intArrayOf(), roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8))
        }

    private fun roundedRect(color: Int, strokeColor: Int, radius: Int, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            setStroke(dp(strokeWidth), strokeColor)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dot(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun rule(): View =
        View(this).apply {
            setBackgroundColor(COLOR_STROKE)
        }

    private fun statusColor(tone: StatusTone): Int =
        when (tone) {
            StatusTone.Ready -> COLOR_PHOSPHOR
            StatusTone.Waiting -> COLOR_AMBER
            StatusTone.Neutral -> COLOR_MUTED
        }

    private fun View.applySystemBarPadding(left: Int, top: Int, right: Int, bottom: Int) {
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom,
            )
            insets
        }
        requestApplyInsets()
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (SpeechToTextSettingsStore(this).selectedEngine().requiresMicrophonePermission) {
            wanted += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            runtimePermissionRequestInFlight = true
            requestPermissions(missing.toTypedArray(), 42)
        }
    }

    private fun autoStartOrAuthorize(reason: String) {
        if (RelayStarter.startIfReady(this, reason)) return
        if (!autoAuthAttempted) {
            autoAuthAttempted = true
            requestHiRokidAuthorization(auto = true, reason = reason)
        }
    }

    private fun maybeAutoReauthorizeAfterBindFailure(snap: RelayBridge.Snapshot, authSaved: Boolean) {
        if (!authSaved || autoReauthAttempted || authRequestInFlight) return
        if (!RelayService.running || snap.cxrConnected) return
        if (snap.lastStatus != "Hi Rokid bind failed") return
        autoReauthAttempted = true
        requestHiRokidAuthorization(auto = true, reason = "bind_failed")
    }

    private fun requestHiRokidAuthorization(auto: Boolean, reason: String) {
        if (authRequestInFlight) return
        if (!CxrLAuth.isGlobalHiRokidInstalled(this)) {
            toastLine("Hi Rokid Global is not visible to Rokid Relay")
            return
        }
        authRequestInFlight = true
        RelayBridge.setStatus(if (auto) "opening Hi Rokid authorization" else "authorization requested")
        val error = CxrLAuth.requestAuthorization(this, Constants.AUTH_REQUEST_CODE)
        if (error is CxrLAuth.Result.Fail) {
            authRequestInFlight = false
            toastLine("Authorization failed to open: ${error.reason}")
            RelayBridge.setStatus("authorization failed to open: $reason")
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 43)
        }
    }

    private fun sttReady(engine: SpeechToTextEngine, store: SttCredentialStore): Boolean =
        when {
            engine.requiresMicrophonePermission ->
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            engine.requiresCredential -> store.hasCredential(engine)
            else -> true
        }

    private fun notificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.contains(packageName)
    }

    private fun savedToken(): String? =
        prefs().getString(Constants.PREF_AUTH_TOKEN, null)

    private fun prefs() = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    private fun toastLine(text: String) {
        if (::noticeText.isInitialized) {
            noticeText.setTextColor(COLOR_PHOSPHOR)
            noticeText.text = text
        }
        if (::activityText.isInitialized) {
            activityText.setTextColor(COLOR_DANGER)
            activityText.text = text
        }
    }

    private fun displayMessage(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (oneLine.isBlank()) return "-"
        return if (oneLine.length <= 160) oneLine else oneLine.take(157) + "..."
    }

    private fun displayBytes(value: Long): String =
        when {
            value >= 1_000_000L -> "${value / 1_000_000L} MB"
            value >= 1_000L -> "${value / 1_000L} KB"
            else -> "$value B"
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private enum class StatusTone {
        Ready,
        Waiting,
        Neutral,
    }

    private enum class ButtonTone {
        Primary,
        Secondary,
        Danger,
    }

    private enum class SpeechMode(val label: String) {
        ANDROID("Android"),
        API("API"),
    }

    private companion object {
        val COLOR_APP_BG: Int = Color.rgb(4, 10, 6)
        val COLOR_PANEL: Int = Color.rgb(8, 18, 11)
        val COLOR_PANEL_ALT: Int = Color.rgb(11, 29, 16)
        val COLOR_FIELD: Int = Color.rgb(5, 13, 8)
        val COLOR_SELECTED: Int = Color.rgb(10, 35, 18)
        val COLOR_DISABLED: Int = Color.rgb(16, 24, 18)
        val COLOR_ACTION: Int = Color.rgb(12, 44, 22)
        val COLOR_ACTION_PRESSED: Int = Color.rgb(17, 61, 31)
        val COLOR_PHOSPHOR: Int = Color.rgb(113, 255, 151)
        val COLOR_PHOSPHOR_DIM: Int = Color.rgb(42, 122, 62)
        val COLOR_STROKE: Int = Color.rgb(24, 67, 36)
        val COLOR_TEXT: Int = Color.rgb(224, 255, 232)
        val COLOR_MUTED: Int = Color.rgb(132, 178, 145)
        val COLOR_DIM: Int = Color.rgb(76, 111, 86)
        val COLOR_AMBER: Int = Color.rgb(230, 190, 92)
        val COLOR_DANGER: Int = Color.rgb(255, 134, 123)
        val COLOR_DANGER_BG: Int = Color.rgb(37, 16, 15)
        val COLOR_DANGER_PRESSED: Int = Color.rgb(52, 23, 21)
    }
}
