package com.anezium.rokidrelay.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val modeButtons = mutableMapOf<SpeechMode, Button>()
    private val providerButtons = mutableMapOf<SpeechToTextProvider, Button>()
    private val modelOptionRows = mutableMapOf<SpeechToTextEngine, SttModelOptionRow>()
    private val languageButtons = mutableMapOf<TranscriptionLanguage, TextView>()

    private lateinit var updateController: PhoneUpdateController
    private lateinit var setupRows: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var selfArmPairingCodeInput: EditText
    private lateinit var notificationForwardingSummary: TextView
    private lateinit var pauseForwardingWhenScreenOnCheckBox: CheckBox
    private lateinit var notificationImagePreviewsCheckBox: CheckBox
    private lateinit var notificationDurationSummary: TextView
    private lateinit var notificationDurationInput: EditText
    private lateinit var notificationFontSizeInput: EditText
    private lateinit var notificationFontSizeDecreaseButton: Button
    private lateinit var notificationFontSizeIncreaseButton: Button
    private lateinit var clearNotificationAfterReplyCheckBox: CheckBox
    private lateinit var notificationLimitsSummary: TextView
    private lateinit var inboxEntryLimitInput: EditText
    private lateinit var threadMessageLimitInput: EditText
    private lateinit var inputSummary: TextView
    private lateinit var inputComboInput: EditText
    private lateinit var normalSwipeButton: Button
    private lateinit var twoFingerSwipeButton: Button
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
    private lateinit var azureKeyBlock: LinearLayout
    private lateinit var azureKeyInput: EditText
    private lateinit var azureRegionInput: EditText
    private lateinit var azureKeyMeta: TextView
    private lateinit var languageHint: TextView
    private lateinit var diagnosticsPanel: DiagnosticsPanel
    private lateinit var homePage: ScrollView
    private lateinit var notificationsPage: ScrollView
    private lateinit var speechPage: ScrollView
    private lateinit var updatePage: ScrollView
    private lateinit var homeTabButton: TextView
    private lateinit var notificationsTabButton: TextView
    private lateinit var speechTabButton: TextView
    private lateinit var updateTabButton: TextView
    private lateinit var updateSummaryText: TextView
    private lateinit var updateCurrentText: TextView
    private lateinit var updateLatestText: TextView
    private lateinit var updateNotesText: TextView
    private lateinit var updateButton: Button
    private lateinit var updateReleaseButton: Button

    private var runtimePermissionRequestInFlight = false
    private var authRequestInFlight = false
    private var autoReauthAttempted = false
    private var armRelayAfterAuth = false
    private var selfArmAfterAuth = false
    private var apiKeysVisible = false
    private var selectedTab = AppTab.HOME
    private var modernBackCallback: OnBackInvokedCallback? = null
    private var updateState = AppUpdateUiState()
    private var notificationFontSizeInputSaving = false
    private var updateNotesExpanded = false
    private var lastRenderedReleaseNotes = ""
    private var lastSelfArmAutoPrepareAtMs = 0L

    private val pollStatus = object : Runnable {
        override fun run() {
            maybeAutoPrepareSelfArmRecovery()
            renderStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateController = PhoneUpdateController(applicationContext, handler) { state ->
            updateState = state
            renderUpdateStatus()
        }
        requestRuntimePermissions()
        val content = buildContent()
        setContentView(content)
        updateController.refreshInstalledUpdateState()
        KeyboardFocusScroller.install(this, content)
        registerModernBackHandler()
    }

    override fun onResume() {
        super.onResume()
        if (!runtimePermissionRequestInFlight) {
            if (RelayStarter.isRelayEnabled(this)) {
                CompanionDeviceCoordinator.startObserving(this)
                BleWakeServer.ensureStarted(this)
                maybeAutoPrepareSelfArmRecovery()
            }
            if (RelayService.running) {
                RelayService.refreshForeground()
                handler.postDelayed({
                    RelayService.refreshForeground()
                    renderStatus()
                }, FOREGROUND_REFRESH_DELAY_MS)
            }
        }
        renderStatus()
        handler.post(pollStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(pollStatus)
        super.onPause()
    }

    @Deprecated("Use legacy Activity back handling because this app does not use AndroidX")
    override fun onBackPressed() {
        if (!navigateBackWithinApp()) super.onBackPressed()
    }

    override fun onDestroy() {
        unregisterModernBackHandler()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runtimePermissionRequestInFlight = false
        syncSettingsAfterUserChange()
        if (RelayStarter.isRelayEnabled(this)) BleWakeServer.ensureStarted(this)
        if (RelayService.running) RelayService.refreshForeground()
        renderStatus()
    }

    @Deprecated("Hi Rokid still returns authorization through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.COMPANION_REQUEST_CODE) {
            val address = CompanionDeviceCoordinator.handleAssociationResult(this, resultCode, data)
            if (address != null) {
                RelayService.refreshForeground()
                toastLine("Glasses linked as companion device")
            } else if (resultCode != Activity.RESULT_CANCELED) {
                toastLine("Companion link failed")
            }
            renderStatus()
            return
        }
        if (requestCode != Constants.AUTH_REQUEST_CODE) return
        authRequestInFlight = false
        val shouldArmRelay = armRelayAfterAuth
        val shouldPrepareSelfArm = selfArmAfterAuth
        armRelayAfterAuth = false
        selfArmAfterAuth = false

        val notice = when (val result = CxrLAuth.parseAuthorizationResult(resultCode, data)) {
            is CxrLAuth.Result.Success -> {
                prefs().edit().putString(Constants.PREF_AUTH_TOKEN, result.token).apply()
                autoReauthAttempted = false
                when {
                    shouldPrepareSelfArm -> {
                        if (prepareSelfArmRecoveryLink()) {
                            "Hi Rokid authorized. Self-arm provisioning started."
                        } else {
                            "Hi Rokid authorized. Self-arm provisioning could not start."
                        }
                    }
                    shouldArmRelay -> {
                        if (armRelayAfterSetupReady()) {
                            "Hi Rokid authorized. Relay armed."
                        } else {
                            "Hi Rokid authorized. ${speechSetupBlocker() ?: "Finish speech setup before Start."}"
                        }
                    }
                    RelayService.running -> {
                        RelayStarter.start(this, result.token, "authorization")
                        "Hi Rokid authorized"
                    }
                    else -> "Hi Rokid authorized"
                }
            }
            is CxrLAuth.Result.Fail -> "Authorization failed: ${result.reason}"
            is CxrLAuth.Result.Cancel -> "Authorization cancelled"
        }
        renderStatus()
        toastLine(notice)
    }

    private fun buildContent(): LinearLayout {
        val contentHost = FrameLayout(this).apply {
            setBackgroundColor(COLOR_APP_BG)
        }
        homePage = page {
            addView(header("Rokid Relay"), matchWrap())
            addSetupPanel()
            addDiagnosticsPanel()
        }
        notificationsPage = page {
            addView(header("Notifications"), matchWrap())
            addNotificationsPanel()
        }
        speechPage = page {
            addView(header("Speech"), matchWrap())
            addSpeechPanel()
        }
        updatePage = page {
            addView(header("Update"), matchWrap())
            addUpdatePanel()
        }
        contentHost.addView(homePage, frameMatch())
        contentHost.addView(notificationsPage, frameMatch())
        contentHost.addView(speechPage, frameMatch())
        contentHost.addView(updatePage, frameMatch())

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_APP_BG)
            applySystemBarPadding(0, 0, 0, 0)
            addView(contentHost, LinearLayout.LayoutParams(match(), 0, 1f))
            addView(bottomNav(), LinearLayout.LayoutParams(match(), wrap()))
            selectTab(AppTab.HOME)
        }
    }

    private fun page(build: LinearLayout.() -> Unit): ScrollView {
        val horizontalPadding = dp(18)
        val topPadding = dp(16)
        val bottomPadding = dp(22)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            build()
        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_APP_BG)
            isFillViewport = true
            addView(content, matchWrap())
        }
    }

    private fun LinearLayout.addSetupPanel() {
        addView(panel("Setup") {
            setupRows = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(setupRows, matchWrap())
            noticeText = bodyText().apply {
                setPadding(0, dp(12), 0, 0)
            }
            addView(noticeText, matchWrap())
        })
    }

    private fun LinearLayout.addNotificationsPanel() {
        addView(NotificationDisplayPanel(this@MainActivity, onNotice = { toastLine(it) }), matchWrap(top = 16))

        addView(panel("Popup") {
            notificationForwardingSummary = bodyText()
            addView(notificationForwardingSummary, matchWrap())
            pauseForwardingWhenScreenOnCheckBox = settingsCheckBox("Pause while phone screen is on") {
                savePauseForwardingWhenScreenOn(it)
            }
            addView(pauseForwardingWhenScreenOnCheckBox, matchWrap(top = 12))
            notificationImagePreviewsCheckBox = settingsCheckBox("Image previews on glasses") {
                saveNotificationImagePreviews(it)
            }
            addView(notificationImagePreviewsCheckBox, matchWrap(top = 8))
            addView(label("Popup duration"), matchWrap(top = 14))
            notificationDurationSummary = bodyText()
            addView(notificationDurationSummary, matchWrap(top = 8))
            addView(notificationDurationEditor(), matchWrap(top = 8))
            addView(label("Font size"), matchWrap(top = 14))
            addView(notificationFontSizeEditor(), matchWrap(top = 8))
            clearNotificationAfterReplyCheckBox = settingsCheckBox("Clear phone notification after reply") {
                saveClearNotificationAfterReply(it)
            }
            addView(clearNotificationAfterReplyCheckBox, matchWrap(top = 12))
            notificationLimitsSummary = bodyText()
            addView(notificationLimitsSummary, matchWrap(top = 16))
            addView(label("Inbox entries"), matchWrap(top = 14))
            addView(inboxEntryLimitEditor(), matchWrap(top = 8))
            addView(label("Messages per thread"), matchWrap(top = 14))
            addView(threadMessageLimitEditor(), matchWrap(top = 8))
        })

        addView(panel("Glasses Input") {
            inputSummary = bodyText()
            addView(inputSummary, matchWrap())
            addView(label("Inbox combo"), matchWrap(top = 14))
            addView(inputComboEditor(), matchWrap(top = 8))
            addView(label("Swipe input"), matchWrap(top = 14))
            addView(swipeModeSelector(), matchWrap(top = 8))
        })
    }

    private fun LinearLayout.addSpeechPanel() {
        addView(panel("Engine") {
            sttSummary = bodyText()
            addView(sttSummary, matchWrap())
            addView(label("Mode"), matchWrap(top = 14))
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

            addView(label("Language"), matchWrap(top = 14))
            addView(languageSelector(), matchWrap(top = 8))
            languageHint = bodyText()
            addView(languageHint, matchWrap(top = 8))
        })

        addView(panel("API Keys") {
            apiKeysToggleButton = textButton("Manage API keys") {
                apiKeysVisible = !apiKeysVisible
                renderStatus()
            }
            addView(apiKeysToggleButton, matchWrap())

            apiKeysContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            openAiKeyBlock = apiKeyBlock(
                title = "OpenAI",
                hint = "sk-...",
                kind = SpeechToTextCredentialKind.OPENAI,
                setInput = { openAiKeyInput = it },
                setMeta = { openAiKeyMeta = it },
            )
            elevenLabsKeyBlock = apiKeyBlock(
                title = "ElevenLabs",
                hint = "xi-...",
                kind = SpeechToTextCredentialKind.ELEVENLABS,
                setInput = { elevenLabsKeyInput = it },
                setMeta = { elevenLabsKeyMeta = it },
            )
            azureKeyBlock = buildAzureKeyBlock()
            apiKeysContainer.addView(openAiKeyBlock, matchWrap(top = 12))
            apiKeysContainer.addView(elevenLabsKeyBlock, matchWrap(top = 12))
            apiKeysContainer.addView(azureKeyBlock, matchWrap(top = 12))
            addView(apiKeysContainer, matchWrap())
        })
    }

    private fun LinearLayout.addUpdatePanel() {
        addView(panel("Release") {
            updateSummaryText = bodyText()
            addView(updateSummaryText, matchWrap())

            addView(label("Installed"), matchWrap(top = 14))
            updateCurrentText = bodyText().apply {
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_TEXT)
            }
            addView(updateCurrentText, matchWrap(top = 6))

            addView(label("Latest"), matchWrap(top = 14))
            updateLatestText = bodyText().apply {
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_TEXT)
            }
            addView(updateLatestText, matchWrap(top = 6))

            updateNotesText = bodyText().apply {
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    if (releaseNotesHasOverflow(updateState.releaseNotes)) {
                        updateNotesExpanded = !updateNotesExpanded
                        renderUpdateStatus()
                    }
                }
            }
            addView(updateNotesText, matchWrap(top = 14))

            updateButton = smallButton("Check", ButtonTone.Primary) {
                updateController.handlePrimaryAction()
            }
            updateReleaseButton = smallButton("Release page", ButtonTone.Secondary) {
                updateController.openReleasePage()
            }
            addView(buttonRow(updateButton, updateReleaseButton), matchWrap(top = 14))
        })

        addView(panel("Packages") {
            addView(bodyText().apply {
                text = "Phone package"
            }, matchWrap())
            addView(packageText(packageName), matchWrap(top = 6))
            addView(bodyText().apply {
                text = "Glasses package"
            }, matchWrap(top = 12))
            addView(packageText(Constants.CLIENT_PACKAGE), matchWrap(top = 6))
        })
    }

    private fun LinearLayout.addDiagnosticsPanel() {
        diagnosticsPanel = DiagnosticsPanel(
            context = this@MainActivity,
            onStatusChanged = { renderStatus() },
            onNotice = { toastLine(it) },
        )
        addView(diagnosticsPanel, matchWrap(top = 16))
    }

    private fun header(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(COLOR_TEXT)
            }, matchWrap())
        }

    private fun bottomNav(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(6))
                background = roundedRect(COLOR_PANEL, COLOR_STROKE, radius = 20)
                homeTabButton = tabButton(AppTab.HOME)
                notificationsTabButton = tabButton(AppTab.NOTIFICATIONS)
                speechTabButton = tabButton(AppTab.SPEECH)
                updateTabButton = tabButton(AppTab.UPDATE)
                listOf(
                    homeTabButton,
                    notificationsTabButton,
                    speechTabButton,
                    updateTabButton,
                ).forEachIndexed { index, button ->
                    addView(button, LinearLayout.LayoutParams(0, dp(TAB_HEIGHT_DP), 1f).apply {
                        if (index > 0) leftMargin = dp(5)
                    })
                }
            }, LinearLayout.LayoutParams(match(), wrap()))
        }

    private fun tabButton(tab: AppTab): TextView =
        TextView(this).apply {
            text = tab.label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(3)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(4), dp(4), dp(3))
            minHeight = dp(TAB_HEIGHT_DP)
            setOnClickListener { selectTab(tab) }
        }

    private fun selectTab(tab: AppTab) {
        selectedTab = tab
        if (::homePage.isInitialized) homePage.visibility = if (tab == AppTab.HOME) View.VISIBLE else View.GONE
        if (::notificationsPage.isInitialized) {
            notificationsPage.visibility = if (tab == AppTab.NOTIFICATIONS) View.VISIBLE else View.GONE
        }
        if (::speechPage.isInitialized) speechPage.visibility = if (tab == AppTab.SPEECH) View.VISIBLE else View.GONE
        if (::updatePage.isInitialized) updatePage.visibility = if (tab == AppTab.UPDATE) View.VISIBLE else View.GONE
        updateTabButtons()
    }

    private fun navigateBackWithinApp(): Boolean {
        if (selectedTab == AppTab.HOME) return false
        selectTab(AppTab.HOME)
        return true
    }

    private fun registerModernBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || modernBackCallback != null) return
        val callback = OnBackInvokedCallback {
            if (!navigateBackWithinApp()) finish()
        }
        modernBackCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback,
        )
    }

    private fun unregisterModernBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        modernBackCallback?.let { callback ->
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
        modernBackCallback = null
    }

    private fun updateTabButtons() {
        if (::homeTabButton.isInitialized) styleTabButton(homeTabButton, AppTab.HOME)
        if (::notificationsTabButton.isInitialized) styleTabButton(notificationsTabButton, AppTab.NOTIFICATIONS)
        if (::speechTabButton.isInitialized) styleTabButton(speechTabButton, AppTab.SPEECH)
        if (::updateTabButton.isInitialized) styleTabButton(updateTabButton, AppTab.UPDATE)
    }

    private fun styleTabButton(button: TextView, tab: AppTab) {
        val selected = selectedTab == tab
        val color = if (selected) COLOR_PHOSPHOR else COLOR_MUTED
        button.setTextColor(color)
        button.background = roundedRect(
            if (selected) COLOR_SELECTED else Color.TRANSPARENT,
            if (selected) COLOR_PHOSPHOR_DIM else Color.TRANSPARENT,
            radius = 14,
            strokeWidth = if (selected) 1 else 0,
        )
        val icon = getDrawable(tab.iconRes)?.mutate()
        icon?.setTint(color)
        icon?.setBounds(0, 0, dp(TAB_ICON_DP), dp(TAB_ICON_DP))
        button.setCompoundDrawables(null, icon, null, null)
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
            addView(View(this@MainActivity), LinearLayout.LayoutParams(match(), dp(8)))
            build()
            layoutParams = matchWrap(top = 16)
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

    private fun selfArmPairingCodeRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val savedCode = if (::selfArmPairingCodeInput.isInitialized) {
                selfArmPairingCodeInput.text.toString()
            } else {
                ""
            }
            selfArmPairingCodeInput = EditText(this@MainActivity).apply {
                hint = "6-digit code from glasses (only if asked)"
                setText(savedCode)
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_DONE
                maxLines = 1
                setSingleLine(true)
                textSize = 13f
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_DIM)
                background = inputBackground()
                setPadding(dp(12), 0, dp(12), 0)
            }
            addView(selfArmPairingCodeInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(smallButton("Pair", ButtonTone.Primary) {
                val accepted = RelayBridge.submitSelfArmPairingCode(
                    this@MainActivity,
                    selfArmPairingCodeInput.text.toString(),
                )
                toastLine(
                    if (accepted) {
                        "Pairing with the glasses…"
                    } else {
                        "Enter the 6-digit code shown on the glasses"
                    },
                )
                renderStatus()
            }, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                leftMargin = dp(8)
            })
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
                        syncSettingsAfterUserChange()
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
            listOf(
                SpeechToTextProvider.OPENAI,
                SpeechToTextProvider.ELEVENLABS,
                SpeechToTextProvider.AZURE,
            ).forEachIndexed { index, provider ->
                val button = selectorButton(provider.displayName) {
                    val next = defaultEngineForProvider(provider)
                    val store = SpeechToTextSettingsStore(this@MainActivity)
                    if (store.selectedEngine() != next) {
                        store.saveSelectedEngine(next)
                        syncSettingsAfterUserChange()
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
                    syncSettingsAfterUserChange()
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
            setApiKeyInputMode(input)
            addView(input, LinearLayout.LayoutParams(match(), dp(42)).apply {
                topMargin = dp(8)
            })

            val provider = when (kind) {
                SpeechToTextCredentialKind.OPENAI -> "OpenAI"
                SpeechToTextCredentialKind.ELEVENLABS -> "ElevenLabs"
                SpeechToTextCredentialKind.AZURE -> "Azure"
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

    private fun buildAzureKeyBlock(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Azure Speech API key"), LinearLayout.LayoutParams(0, wrap(), 1f))
                azureKeyMeta = TextView(this@MainActivity).apply {
                    textSize = 12f
                    includeFontPadding = false
                    setTextColor(COLOR_MUTED)
                    gravity = Gravity.END
                }
                addView(azureKeyMeta, LinearLayout.LayoutParams(0, wrap(), 1f))
            }, matchWrap())

            azureKeyInput = keyInput("Speech resource key")
            setApiKeyInputMode(azureKeyInput)
            addView(azureKeyInput, LinearLayout.LayoutParams(match(), dp(42)).apply {
                topMargin = dp(8)
            })

            addView(label("Azure region"), matchWrap(top = 12))
            azureRegionInput = keyInput("eastasia, westeurope, eastus...")
            addView(azureRegionInput, LinearLayout.LayoutParams(match(), dp(42)).apply {
                topMargin = dp(8)
            })

            addView(buttonRow(
                smallButton("Save", ButtonTone.Primary) {
                    val store = SttCredentialStore(this@MainActivity)
                    val key = azureKeyInput.text.toString().trim()
                    val region = azureRegionInput.text.toString().trim()
                    val notice = runCatching {
                        if (key.isNotBlank()) store.saveApiKey(SpeechToTextCredentialKind.AZURE, key)
                        if (region.isNotBlank()) store.saveAzureRegion(region)
                        require(!store.apiKey(SpeechToTextCredentialKind.AZURE).isNullOrBlank()) { "API key is required" }
                        require(!store.azureRegion().isNullOrBlank()) { "Region is required (e.g. eastasia)" }
                    }.fold(
                        onSuccess = {
                            azureKeyInput.text.clear()
                            "Azure Speech key saved"
                        },
                        onFailure = {
                            "Failed to save Azure key: ${it.message}"
                        },
                    )
                    renderStatus()
                    toastLine(notice)
                },
                smallButton("Clear", ButtonTone.Secondary) {
                    val store = SttCredentialStore(this@MainActivity)
                    store.clearApiKey(SpeechToTextCredentialKind.AZURE)
                    store.clearAzureRegion()
                    azureKeyInput.text.clear()
                    azureRegionInput.text.clear()
                    renderStatus()
                    toastLine("Azure Speech key cleared")
                },
            ), matchWrap(top = 8))
        }

    private fun languageSelector(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            languageButtons.clear()
            TranscriptionLanguage.values().toList().chunked(LANGUAGE_GRID_COLUMNS).forEachIndexed { rowIndex, rowLanguages ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowLanguages.forEachIndexed { index, language ->
                        val chip = languageChip(language.label) {
                            val store = SpeechToTextSettingsStore(this@MainActivity)
                            if (store.selectedEngine() == SpeechToTextEngine.ANDROID_CXR &&
                                language != TranscriptionLanguage.AUTO
                            ) {
                                return@languageChip
                            }
                            if (store.selectedLanguage() != language) {
                                store.saveSelectedLanguage(language)
                                renderStatus()
                            }
                        }
                        languageButtons[language] = chip
                        addView(chip, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                            if (index > 0) leftMargin = dp(8)
                        })
                    }
                    repeat(LANGUAGE_GRID_COLUMNS - rowLanguages.size) {
                        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                            leftMargin = dp(8)
                        })
                    }
                }, matchWrap(top = if (rowIndex == 0) 0 else 8))
            }
        }

    private fun languageChip(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(COLOR_TEXT)
            // Software layer: hardware-accelerated GradientDrawable drops the horizontal
            // stroke segments of rounded-rect chips on some Adreno/One UI devices.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            background = roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun styleLanguageChip(chip: TextView, isSelected: Boolean, isEnabled: Boolean) {
        chip.isEnabled = isEnabled
        chip.isClickable = isEnabled
        chip.isFocusable = isEnabled
        chip.alpha = if (isEnabled) 1f else 0.58f
        chip.setTextColor(
            when {
                isSelected -> COLOR_PHOSPHOR
                isEnabled -> COLOR_TEXT
                else -> COLOR_DIM
            },
        )
        chip.background = roundedRect(
            when {
                isSelected -> COLOR_SELECTED
                isEnabled -> COLOR_FIELD
                else -> COLOR_DISABLED
            },
            when {
                isSelected -> COLOR_PHOSPHOR
                isEnabled -> COLOR_PHOSPHOR_DIM
                else -> COLOR_STROKE
            },
            radius = 8,
            strokeWidth = if (isEnabled || isSelected) 2 else 1,
        )
    }

    private fun updateLanguageButtons(
        selected: TranscriptionLanguage,
        selectedEngine: SpeechToTextEngine,
    ) {
        val androidCxrSelected = selectedEngine == SpeechToTextEngine.ANDROID_CXR
        languageButtons.forEach { (language, chip) ->
            styleLanguageChip(
                chip = chip,
                isSelected = language == selected,
                isEnabled = !androidCxrSelected || language == TranscriptionLanguage.AUTO,
            )
        }
        if (::languageHint.isInitialized) {
            languageHint.text = if (androidCxrSelected) {
                "Android CXR uses SpeechRecognizer auto-detection; explicit language codes are disabled for this engine."
            } else {
                selected.uiNote ?: "${selected.summaryName} is forced for voice replies on every engine."
            }
        }
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

    private fun settingsCheckBox(label: String, onChanged: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            textSize = 12.5f
            includeFontPadding = false
            minHeight = dp(38)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(COLOR_TEXT)
            buttonTintList = ColorStateList.valueOf(COLOR_PHOSPHOR)
            setOnClickListener { onChanged(isChecked) }
        }

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

    private fun packageText(value: String): TextView =
        TextView(this).apply {
            text = value
            textSize = 12.5f
            includeFontPadding = false
            typeface = Typeface.MONOSPACE
            setSingleLine(false)
            setTextColor(COLOR_TEXT)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8)
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

    private fun numberInput(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 14f
            setSingleLine(true)
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_DIM)
            setSelectAllOnFocus(true)
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(42)
            background = inputBackground()
        }

    private fun setApiKeyInputMode(input: EditText) {
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.typeface = Typeface.MONOSPACE
        input.setSingleLine(true)
        input.setSelection(input.text.length)
    }

    private fun renderStatus() {
        val snap = RelayBridge.snapshot(this)
        val hiRokid = CxrLAuth.isGlobalHiRokidInstalled(this)
        val notifications = notificationAccessEnabled()
        val authSaved = !savedToken().isNullOrBlank()
        val batteryUnrestricted = batteryOptimizationsIgnored()
        val speechSettings = SpeechToTextSettingsStore(this)
        val selectedEngine = speechSettings.selectedEngine()
        val selectedLanguage = speechSettings.selectedLanguageForEngine(selectedEngine)
        val stt = SttCredentialStore(this)
        val openAiLabel = stt.accountLabel(SpeechToTextCredentialKind.OPENAI)
        val elevenLabsLabel = stt.accountLabel(SpeechToTextCredentialKind.ELEVENLABS)
        val azureLabel = stt.accountLabel(SpeechToTextCredentialKind.AZURE)
        val azureRegion = stt.azureRegion()
        val sttReady = sttReady(selectedEngine, stt)
        val micPermissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val companionLinked = CompanionDeviceCoordinator.hasAssociation(this)
        val androidCxrTooOld = selectedEngine == SpeechToTextEngine.ANDROID_CXR &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val androidCxrNeedsCompanion = selectedEngine == SpeechToTextEngine.ANDROID_CXR &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !companionLinked
        val androidCxrNeedsMic = selectedEngine.requiresMicrophonePermission && !micPermissionGranted
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
                    syncSettingsAfterUserChange()
                    renderStatus()
                },
            ), matchWrap(top = 8))
            if (speechDiagnosticAvailable()) {
                setupRows.addView(setupRow(
                    title = "Speech diagnostic",
                    value = "Android recognizer report",
                    tone = StatusTone.Neutral,
                    actionLabel = "Run",
                    actionTone = ButtonTone.Secondary,
                    onClick = { openSpeechDiagnostic() },
                ), matchWrap(top = 8))
            }
            setupRows.addView(setupRow(
                title = "Companion link",
                value = if (companionLinked) "Glasses linked" else "Needed for background wake",
                tone = if (companionLinked) StatusTone.Ready else StatusTone.Waiting,
                actionLabel = "Link",
                actionTone = if (companionLinked) ButtonTone.Secondary else ButtonTone.Primary,
                onClick = {
                    CompanionDeviceCoordinator.requestAssociation(this) { message ->
                        handler.post { toastLine(message) }
                    }
                },
            ), matchWrap(top = 8))
            setupRows.addView(setupRow(
                title = "Battery",
                value = if (batteryUnrestricted) "Unrestricted" else "Recommended: set Unrestricted",
                tone = if (batteryUnrestricted) StatusTone.Ready else StatusTone.Waiting,
                actionLabel = "Open",
                actionTone = ButtonTone.Secondary,
                onClick = { openBatterySettings() },
            ), matchWrap(top = 8))
            val relayEnabled = RelayStarter.isRelayEnabled(this)
            val relayRunning = RelayService.running
            val relayOperational = relayRunning &&
                snap.cxrConnected &&
                snap.glassConnected &&
                snap.bootstrapReadyForMessages
            val relayNotificationAccessMissing = relayEnabled && !notifications
            val selfArmProvisioned = SelfArmProvisioner.provisioned(this)
            val selfArmDisablePending = SelfArmProvisioner.disablePending(this)
            val selfArmWireless = SelfArmProvisioner.wirelessBootstrap(this)
            val selfArmRelayEnabledSetupMessage =
                "Connect the glasses to a Wi-Fi network first (any network works — no internet needed), then tap Bootstrap."
            val selfArmRow = selfArmRecoveryRowState(
                selfArmProvisioned = selfArmProvisioned,
                selfArmDisablePending = selfArmDisablePending,
                selfArmWireless = selfArmWireless,
                relayEnabled = relayEnabled,
                glassesState = snap.selfArmGlassesState,
                relayEnabledSetupMessage = selfArmRelayEnabledSetupMessage,
                friendlyWirelessStatus = ::friendlyWirelessStatus,
            )
            setupRows.addView(setupRow(
                title = "Self-arm recovery",
                value = selfArmRow.value,
                tone = selfArmRow.tone.toStatusTone(),
                actionLabel = selfArmRow.actionLabel,
                actionTone = ButtonTone.Secondary,
                onClick = {
                    prepareSelfArmRecoveryOrAuthorize()
                    renderStatus()
                },
            ), matchWrap(top = 8))
            if (
                !selfArmProvisioned &&
                !selfArmWireless.complete &&
                (relayEnabled || selfArmWireless.inProgress || snap.selfArmWirelessInProgress)
            ) {
                setupRows.addView(selfArmPairingCodeRow(), matchWrap(top = 8))
            }
            setupRows.addView(setupRow(
                title = "Relay service",
                value = when {
                    relayNotificationAccessMissing -> "Armed, notification access missing"
                    relayOperational -> "Operational"
                    relayRunning && snap.cxrConnected && snap.glassConnected -> "Connected, preparing glasses app"
                    relayRunning -> "Running, ${snap.bootstrapState}"
                    relayEnabled -> "Armed for notification/reply"
                    else -> "Stopped"
                },
                tone = when {
                    relayOperational -> StatusTone.Ready
                    relayRunning || relayEnabled -> StatusTone.Waiting
                    else -> StatusTone.Neutral
                },
                actionLabel = if (relayRunning || relayEnabled) "Stop" else "Start",
                actionTone = if (relayRunning || relayEnabled) ButtonTone.Danger else ButtonTone.Primary,
                onClick = {
                    if (relayRunning || relayEnabled) {
                        RelayStarter.stop(this)
                    } else {
                        armRelayOrAuthorize()
                    }
                    renderStatus()
                },
            ), matchWrap(top = 8))
            if (relayRunning || relayEnabled) {
                setupRows.addView(setupRow(
                    title = "Relay recovery",
                    value = if (relayOperational) "Bridge is operational" else "Restart service and bridge",
                    tone = if (relayOperational) StatusTone.Neutral else StatusTone.Waiting,
                    actionLabel = "Relaunch",
                    actionTone = if (relayOperational) ButtonTone.Secondary else ButtonTone.Primary,
                    onClick = {
                        relaunchRelayOrAuthorize()
                        renderStatus()
                    },
                ), matchWrap(top = 8))
            }
        }

        if (::noticeText.isInitialized) {
            noticeText.text = when {
                !hiRokid -> "Install or expose Hi Rokid Global first."
                !authSaved -> "Authorize once, then the relay can start automatically."
                RelayStarter.isRelayEnabled(this) && !notifications ->
                    "Armed, but notification access is missing. Open Notification access or notifications cannot wake the relay."
                !notifications -> "Notification access is still required."
                androidCxrTooOld -> "Android CXR needs Android 13+. Choose an API speech engine on this phone."
                androidCxrNeedsMic -> "Grant microphone permission for Android CXR voice replies."
                androidCxrNeedsCompanion -> "Link the glasses as companion device for background reply wake."
                !sttReady -> "Finish speech-to-text setup for voice replies."
                !batteryUnrestricted -> "Ready. Set battery to Unrestricted for best reliability."
                RelayService.running && NotificationForwardingPolicy.isPaused(this) ->
                    "Ready. Forwarding is paused while this screen is on."
                RelayService.running && snap.bootstrapReadyForMessages ->
                    "Operational. Phone bridge and glasses app are ready."
                RelayService.running -> "Awake. Relay will sleep again after the reply window."
                SelfArmProvisioner.provisioned(this) -> "Armed. Self-arm provisioned for glasses recovery."
                RelayStarter.isRelayEnabled(this) &&
                    !SelfArmProvisioner.provisioned(this) &&
                    !SelfArmProvisioner.wirelessBootstrapped(this) ->
                    "Self-arm setup: connect the glasses to a Wi-Fi network (any network works — no internet needed), keep the glasses on, then tap Bootstrap on the Self-arm recovery row."
                SelfArmProvisioner.wirelessBootstrapped(this) ->
                    "Wireless bootstrap is complete. Start Self-arm recovery once to arm direct repair."
                RelayStarter.isRelayEnabled(this) -> "Armed. Relay wakes on replyable notifications or inbox reply attempts."
                else -> "Ready to arm wake-on-notification."
            }
            noticeText.setTextColor(
                when {
                    RelayStarter.isRelayEnabled(this) && !notifications -> COLOR_AMBER
                    hiRokid && authSaved && notifications && sttReady -> COLOR_PHOSPHOR
                    else -> COLOR_MUTED
                },
            )
        }

        if (::sttSummary.isInitialized) {
            sttSummary.text = when {
                androidCxrTooOld ->
                    "${selectedEngine.displayName}. Android 13+ required."
                selectedEngine.requiresMicrophonePermission && !micPermissionGranted ->
                    "${selectedEngine.displayName}. Microphone permission required."
                androidCxrNeedsCompanion ->
                    "${selectedEngine.displayName}. Companion link required for background voice replies."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.OPENAI && openAiLabel.isNullOrBlank() ->
                    "${selectedEngine.displayName}. Add an OpenAI key."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.ELEVENLABS && elevenLabsLabel.isNullOrBlank() ->
                    "${selectedEngine.displayName}. Add an ElevenLabs key."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.AZURE && azureLabel.isNullOrBlank() ->
                    "${selectedEngine.displayName}. Add an Azure Speech key."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.AZURE && azureRegion.isNullOrBlank() ->
                    "${selectedEngine.displayName}. Set the Azure region."
                selectedEngine.credentialKind == SpeechToTextCredentialKind.AZURE ->
                    "${selectedEngine.displayName}. Buffered glasses audio — not realtime, no live text."
                selectedEngine.requiresMicrophonePermission ->
                    "${selectedEngine.displayName}. Uses glasses PCM through Android recognition."
                selectedEngine.usesRealtime ->
                    "${selectedEngine.displayName}. Streams glasses audio for live transcript updates."
                else ->
                    "${selectedEngine.displayName}. Uses buffered glasses audio."
            }
            sttSummary.setTextColor(COLOR_TEXT)
        }

        if (::notificationForwardingSummary.isInitialized) {
            val store = NotificationSettingsStore(this)
            val pauseWhenScreenOn = store.pauseForwardingWhenPhoneScreenOn()
            val pausedNow = NotificationForwardingPolicy.isPaused(this)
            notificationForwardingSummary.text = when {
                pausedNow -> "Forwarding is paused because the phone screen is on."
                pauseWhenScreenOn -> "Forwarding resumes automatically when the phone screen turns off."
                else -> "Replyable notifications forward to the glasses as they arrive."
            }
            notificationForwardingSummary.setTextColor(if (pausedNow) COLOR_AMBER else COLOR_MUTED)
            if (::pauseForwardingWhenScreenOnCheckBox.isInitialized &&
                pauseForwardingWhenScreenOnCheckBox.isChecked != pauseWhenScreenOn
            ) {
                pauseForwardingWhenScreenOnCheckBox.isChecked = pauseWhenScreenOn
            }
            if (::notificationImagePreviewsCheckBox.isInitialized) {
                val imagePreviewsEnabled = store.notificationImagePreviewsEnabled()
                if (notificationImagePreviewsCheckBox.isChecked != imagePreviewsEnabled) {
                    notificationImagePreviewsCheckBox.isChecked = imagePreviewsEnabled
                }
            }
        }

        if (::notificationDurationSummary.isInitialized) {
            val store = NotificationSettingsStore(this)
            val seconds = store.popupDurationSeconds()
            notificationDurationSummary.text = if (seconds == 0L) {
                "Popup stays visible until dismissed. Enter 0 to keep this behavior."
            } else {
                "Popup hides after ${seconds}s. The notification stays available in the inbox."
            }
            notificationDurationSummary.setTextColor(COLOR_MUTED)
            if (::notificationFontSizeInput.isInitialized) {
                val fontSize = store.fontSizeSp()
                if (!notificationFontSizeInput.hasFocus()) {
                    notificationFontSizeInput.setText(formatFontSizeValue(fontSize))
                }
                notificationFontSizeDecreaseButton.isEnabled =
                    fontSize > NotificationSettingsStore.MIN_FONT_SIZE_SP
                notificationFontSizeIncreaseButton.isEnabled =
                    fontSize < NotificationSettingsStore.MAX_FONT_SIZE_SP
            }
            if (::notificationDurationInput.isInitialized && !notificationDurationInput.hasFocus()) {
                notificationDurationInput.setText(seconds.toString())
            }
            if (::clearNotificationAfterReplyCheckBox.isInitialized) {
                val clearAfterReply = NotificationSettingsStore(this).clearPhoneNotificationAfterReply()
                if (clearNotificationAfterReplyCheckBox.isChecked != clearAfterReply) {
                    clearNotificationAfterReplyCheckBox.isChecked = clearAfterReply
                }
            }
        }

        if (::notificationLimitsSummary.isInitialized) {
            val store = NotificationSettingsStore(this)
            val inboxLimit = store.inboxEntryLimit()
            val threadLimit = store.threadMessageLimit()
            notificationLimitsSummary.text =
                "Inbox keeps up to $inboxLimit entries. Threads keep up to $threadLimit messages when Android provides them."
            notificationLimitsSummary.setTextColor(COLOR_MUTED)
            if (::inboxEntryLimitInput.isInitialized && !inboxEntryLimitInput.hasFocus()) {
                inboxEntryLimitInput.setText(inboxLimit.toString())
            }
            if (::threadMessageLimitInput.isInitialized && !threadMessageLimitInput.hasFocus()) {
                threadMessageLimitInput.setText(threadLimit.toString())
            }
        }

        if (::inputSummary.isInitialized) {
            val inputStore = RelayInputSettingsStore(this)
            val combo = inputStore.inputCombo()
            val swipeMode = inputStore.swipeMode()
            inputSummary.text = buildString {
                append("Inbox combo: ${RelayInputSettingsStore.displayCombo(combo)}. ")
                append(
                    if (swipeMode == RelayInputSettingsStore.SWIPE_MODE_TWO_FINGER) {
                        "Two-finger swipe controls notifications and inbox."
                    } else {
                        "Normal swipe controls notifications and inbox."
                    },
                )
            }
            inputSummary.setTextColor(COLOR_MUTED)
            if (::inputComboInput.isInitialized && !inputComboInput.hasFocus()) {
                inputComboInput.setText(combo)
            }
            updateInputChoiceButtons(swipeMode)
        }

        updateSpeechChoiceButtons(selectedEngine)
        updateLanguageButtons(selectedLanguage, selectedEngine)
        updateApiKeys(selectedEngine, openAiLabel, elevenLabsLabel, azureLabel, azureRegion)

        if (::diagnosticsPanel.isInitialized) {
            diagnosticsPanel.render(snap)
        }
        renderUpdateStatus()
    }

    private fun renderUpdateStatus() {
        if (!::updateSummaryText.isInitialized) return
        updateCurrentText.text = "${updateState.currentVersionName} (${updateState.currentVersionCode})"
        updateLatestText.text = if (updateState.latestTag.isBlank()) {
            "Not checked"
        } else {
            buildString {
                append(updateState.latestVersionName.ifBlank { updateState.latestTag })
                updateState.latestVersionCode?.let { append(" ($it)") }
                if (updateState.apkName.isNotBlank()) append("\n${updateState.apkName}")
            }
        }
        updateSummaryText.text = updateState.status.ifBlank {
            "Check GitHub Releases for the newest Rokid Relay phone APK. The phone APK includes the glasses client."
        }
        updateSummaryText.setTextColor(
            when {
                updateState.available -> COLOR_PHOSPHOR
                updateState.status.isBlank() -> COLOR_MUTED
                else -> COLOR_TEXT
            },
        )
        if (lastRenderedReleaseNotes != updateState.releaseNotes) {
            lastRenderedReleaseNotes = updateState.releaseNotes
            updateNotesExpanded = false
        }
        val notes = releaseNotesText(updateState.releaseNotes)
        updateNotesText.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
        updateNotesText.text = notes
        val notesOverflow = releaseNotesHasOverflow(updateState.releaseNotes)
        updateNotesText.maxLines = if (updateNotesExpanded) Int.MAX_VALUE else UPDATE_NOTES_COLLAPSED_LINES
        updateNotesText.ellipsize = if (updateNotesExpanded) null else TextUtils.TruncateAt.END
        updateNotesText.isClickable = notesOverflow
        updateNotesText.isFocusable = notesOverflow
        updateNotesText.setTextColor(if (notesOverflow && updateNotesExpanded) COLOR_TEXT else COLOR_MUTED)

        updateButton.text = when {
            updateState.checking -> "Checking"
            updateState.downloading && updateState.apkPath.isNotBlank() -> "Validating"
            updateState.downloading -> "Downloading"
            updateState.available && updateState.apkPath.isNotBlank() -> "Open Installer"
            updateState.available -> "Install Update"
            else -> "Check"
        }
        updateButton.isEnabled = !updateState.checking && !updateState.downloading
        updateReleaseButton.isEnabled = updateState.releaseUrl.isNotBlank()
        updateReleaseButton.alpha = if (updateReleaseButton.isEnabled) 1f else 0.45f
    }

    private fun releaseNotesText(notes: String): String {
        val clean = cleanReleaseNotes(notes)
        if (clean.isBlank() || updateNotesExpanded || !releaseNotesHasOverflow(notes)) return clean
        return clean
            .lineSequence()
            .take(UPDATE_NOTES_PREVIEW_LINES)
            .joinToString("\n")
            .plus("\n\nShow full changelog")
    }

    private fun releaseNotesHasOverflow(notes: String): Boolean {
        val clean = cleanReleaseNotes(notes)
        if (clean.length > UPDATE_NOTES_COLLAPSED_CHARS) return true
        return clean.lineSequence().count() > UPDATE_NOTES_COLLAPSED_LINES
    }

    private fun cleanReleaseNotes(notes: String): String =
        notes
            .lineSequence()
            .map { line -> line.trim().trimStart('#', '-', '*').trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

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
        azureLabel: String?,
        azureRegion: String?,
    ) {
        val selectedOpenAi = selectedEngine.credentialKind == SpeechToTextCredentialKind.OPENAI
        val selectedElevenLabs = selectedEngine.credentialKind == SpeechToTextCredentialKind.ELEVENLABS
        val selectedAzure = selectedEngine.credentialKind == SpeechToTextCredentialKind.AZURE
        val apiSelected = selectedOpenAi || selectedElevenLabs || selectedAzure
        val forceOpen = (selectedOpenAi && openAiLabel.isNullOrBlank()) ||
            (selectedElevenLabs && elevenLabsLabel.isNullOrBlank()) ||
            (selectedAzure && (azureLabel.isNullOrBlank() || azureRegion.isNullOrBlank()))
        val showKeys = apiKeysVisible || forceOpen

        apiKeysToggleButton.visibility = if (apiSelected) View.VISIBLE else View.GONE
        apiKeysContainer.visibility = if (apiSelected && showKeys) View.VISIBLE else View.GONE
        apiKeysToggleButton.text = if (showKeys && apiKeysVisible) "Hide API keys" else "Manage API keys"

        openAiKeyBlock.visibility = if (apiKeysVisible || selectedOpenAi) View.VISIBLE else View.GONE
        elevenLabsKeyBlock.visibility = if (apiKeysVisible || selectedElevenLabs) View.VISIBLE else View.GONE
        azureKeyBlock.visibility = if (apiKeysVisible || selectedAzure) View.VISIBLE else View.GONE

        openAiKeyMeta.text = openAiLabel ?: "not saved"
        openAiKeyMeta.setTextColor(if (openAiLabel.isNullOrBlank()) COLOR_MUTED else COLOR_PHOSPHOR)
        elevenLabsKeyMeta.text = elevenLabsLabel ?: "not saved"
        elevenLabsKeyMeta.setTextColor(if (elevenLabsLabel.isNullOrBlank()) COLOR_MUTED else COLOR_PHOSPHOR)
        val azureReady = !azureLabel.isNullOrBlank() && !azureRegion.isNullOrBlank()
        azureKeyMeta.text = when {
            azureLabel.isNullOrBlank() -> "not saved"
            azureRegion.isNullOrBlank() -> "$azureLabel · region missing"
            else -> "$azureLabel · $azureRegion"
        }
        azureKeyMeta.setTextColor(if (azureReady) COLOR_PHOSPHOR else COLOR_MUTED)
    }

    private fun notificationDurationEditor(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            notificationDurationInput = numberInput("5").apply {
                setText(NotificationSettingsStore(this@MainActivity).popupDurationSeconds().toString())
            }
            addView(notificationDurationInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(smallButton("OK", ButtonTone.Primary) {
                saveNotificationDuration()
            }, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                leftMargin = dp(8)
            })
        }

    private fun notificationFontSizeEditor(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            notificationFontSizeInput = fontSizeInput()
            addView(notificationFontSizeInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            notificationFontSizeDecreaseButton = fontSizeStepButton("-") {
                adjustNotificationFontSize(-1.0f)
            }
            addView(notificationFontSizeDecreaseButton, LinearLayout.LayoutParams(dp(46), dp(42)).apply {
                leftMargin = dp(8)
            })
            notificationFontSizeIncreaseButton = fontSizeStepButton("+") {
                adjustNotificationFontSize(1.0f)
            }
            addView(notificationFontSizeIncreaseButton, LinearLayout.LayoutParams(dp(46), dp(42)).apply {
                leftMargin = dp(8)
            })
        }

    private fun fontSizeStepButton(label: String, onClick: () -> Unit): Button =
        smallButton(label, ButtonTone.Secondary, onClick).apply {
            textSize = 21f
            setPadding(0, 0, 0, dp(2))
        }

    private fun fontSizeInput(): EditText =
        EditText(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(true)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_DIM)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = dp(42)
            background = inputBackground()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveNotificationFontSizeFromInput()
                    true
                } else {
                    false
                }
            }
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) saveNotificationFontSizeFromInput()
            }
        }

    private fun saveNotificationDuration() {
        val raw = notificationDurationInput.text.toString().trim()
        val seconds = raw.toLongOrNull()
        if (seconds == null || seconds < 0L) {
            toastLine("Enter a duration in seconds. Use 0 to keep the popup visible.")
            return
        }
        val store = NotificationSettingsStore(this)
        val clampedSeconds = seconds.coerceAtMost(NotificationSettingsStore.MAX_POPUP_DURATION_MS / 1_000L)
        store.savePopupDurationSeconds(clampedSeconds)
        notificationDurationInput.setText(clampedSeconds.toString())
        notificationDurationInput.clearFocus()
        RelayBridge.sendSettings()
        renderStatus()
        toastLine(if (clampedSeconds == 0L) "Popup stays visible" else "Popup duration saved: ${clampedSeconds}s")
    }

    private fun adjustNotificationFontSize(deltaSp: Float) {
        val store = NotificationSettingsStore(this)
        val current = store.fontSizeSp()
        val next = store.saveFontSizeSp(current + deltaSp)
        RelayBridge.sendSettings()
        renderStatus()
        toastLine("Popup font size: ${formatFontSizeSp(next)}")
    }

    private fun saveNotificationFontSizeFromInput() {
        if (notificationFontSizeInputSaving) return
        if (!::notificationFontSizeInput.isInitialized) return
        notificationFontSizeInputSaving = true
        try {
            val raw = notificationFontSizeInput.text.toString().trim()
            val requested = raw.toFloatOrNull()
            if (requested == null) {
                renderStatus()
                toastLine(
                    "Enter a font size from ${formatFontSizeSp(NotificationSettingsStore.MIN_FONT_SIZE_SP)} " +
                        "to ${formatFontSizeSp(NotificationSettingsStore.MAX_FONT_SIZE_SP)}",
                )
                return
            }
            val next = NotificationSettingsStore(this).saveFontSizeSp(requested)
            notificationFontSizeInput.setText(formatFontSizeValue(next))
            notificationFontSizeInput.clearFocus()
            RelayBridge.sendSettings()
            renderStatus()
            toastLine("Popup font size: ${formatFontSizeSp(next)}")
        } finally {
            notificationFontSizeInputSaving = false
        }
    }

    private fun formatFontSizeValue(value: Float): String =
        if (kotlin.math.abs(value - value.toInt()) < 0.01f) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.ROOT, "%.1f", value)
        }

    private fun formatFontSizeSp(value: Float): String =
        "${formatFontSizeValue(value)}sp"

    private fun saveClearNotificationAfterReply(enabled: Boolean) {
        NotificationSettingsStore(this).saveClearPhoneNotificationAfterReply(enabled)
        renderStatus()
        toastLine(
            if (enabled) {
                "Phone notifications will be cleared after replies"
            } else {
                "Phone notifications stay after replies"
            },
        )
    }

    private fun savePauseForwardingWhenScreenOn(enabled: Boolean) {
        NotificationSettingsStore(this).savePauseForwardingWhenPhoneScreenOn(enabled)
        if (enabled) {
            RelayBridge.sendInbox()
        } else {
            NotificationControl.refreshActiveNotifications()
        }
        renderStatus()
        toastLine(
            if (enabled) {
                "Forwarding pauses while the phone screen is on"
            } else {
                "Forwarding stays active while the phone screen is on"
            },
        )
    }

    private fun saveNotificationImagePreviews(enabled: Boolean) {
        NotificationSettingsStore(this).saveNotificationImagePreviewsEnabled(enabled)
        if (enabled) {
            NotificationControl.refreshActiveNotifications()
        } else {
            RelayBridge.sendInbox()
        }
        renderStatus()
        toastLine(
            if (enabled) {
                "Image previews will appear on the glasses when Android exposes them"
            } else {
                "Image previews are off"
            },
        )
    }

    private fun inboxEntryLimitEditor(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            inboxEntryLimitInput = numberInput(NotificationSettingsStore.DEFAULT_INBOX_ENTRY_LIMIT.toString()).apply {
                setText(NotificationSettingsStore(this@MainActivity).inboxEntryLimit().toString())
            }
            addView(inboxEntryLimitInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(smallButton("OK", ButtonTone.Primary) {
                saveInboxEntryLimit()
            }, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                leftMargin = dp(8)
            })
        }

    private fun threadMessageLimitEditor(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            threadMessageLimitInput = numberInput(NotificationSettingsStore.DEFAULT_THREAD_MESSAGE_LIMIT.toString()).apply {
                setText(NotificationSettingsStore(this@MainActivity).threadMessageLimit().toString())
            }
            addView(threadMessageLimitInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(smallButton("OK", ButtonTone.Primary) {
                saveThreadMessageLimit()
            }, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                leftMargin = dp(8)
            })
        }

    private fun saveInboxEntryLimit() {
        val raw = inboxEntryLimitInput.text.toString().trim()
        val limit = raw.toIntOrNull()
        if (limit == null) {
            toastLine("Enter an inbox limit from ${NotificationSettingsStore.MIN_INBOX_ENTRY_LIMIT} to ${NotificationSettingsStore.MAX_INBOX_ENTRY_LIMIT}.")
            return
        }
        val clamped = limit.coerceIn(
            NotificationSettingsStore.MIN_INBOX_ENTRY_LIMIT,
            NotificationSettingsStore.MAX_INBOX_ENTRY_LIMIT,
        )
        NotificationSettingsStore(this).saveInboxEntryLimit(clamped)
        inboxEntryLimitInput.setText(clamped.toString())
        inboxEntryLimitInput.clearFocus()
        RelayBridge.sendSettings()
        RelayBridge.sendInbox()
        renderStatus()
        toastLine("Inbox limit saved: $clamped")
    }

    private fun saveThreadMessageLimit() {
        val raw = threadMessageLimitInput.text.toString().trim()
        val limit = raw.toIntOrNull()
        if (limit == null) {
            toastLine("Enter a message limit from ${NotificationSettingsStore.MIN_THREAD_MESSAGE_LIMIT} to ${NotificationSettingsStore.MAX_THREAD_MESSAGE_LIMIT}.")
            return
        }
        val clamped = limit.coerceIn(
            NotificationSettingsStore.MIN_THREAD_MESSAGE_LIMIT,
            NotificationSettingsStore.MAX_THREAD_MESSAGE_LIMIT,
        )
        NotificationSettingsStore(this).saveThreadMessageLimit(clamped)
        threadMessageLimitInput.setText(clamped.toString())
        threadMessageLimitInput.clearFocus()
        RelayBridge.sendSettings()
        NotificationControl.refreshActiveNotifications()
        renderStatus()
        toastLine("Thread message limit saved: $clamped")
    }

    private fun inputComboEditor(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            inputComboInput = keyInput(RelayInputSettingsStore.DEFAULT_COMBO).apply {
                setText(RelayInputSettingsStore(this@MainActivity).inputCombo())
            }
            addView(inputComboInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(smallButton("OK", ButtonTone.Primary) {
                saveInputCombo()
            }, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                leftMargin = dp(8)
            })
        }

    private fun swipeModeSelector(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            normalSwipeButton = selectorButton("Normal") {
                saveSwipeMode(RelayInputSettingsStore.SWIPE_MODE_NORMAL)
            }
            twoFingerSwipeButton = selectorButton("Two-finger") {
                saveSwipeMode(RelayInputSettingsStore.SWIPE_MODE_TWO_FINGER)
            }
            addView(normalSwipeButton, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(twoFingerSwipeButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(8)
            })
        }

    private fun saveInputCombo() {
        val store = RelayInputSettingsStore(this)
        val combo = store.saveInputCombo(inputComboInput.text.toString())
        if (combo == null) {
            toastLine("Use 2 to 8 steps with L/R or G/D.")
            return
        }
        inputComboInput.setText(combo)
        inputComboInput.clearFocus()
        RelayBridge.sendSettings()
        renderStatus()
        toastLine("Combo saved: ${RelayInputSettingsStore.displayCombo(combo)}")
    }

    private fun saveSwipeMode(mode: String) {
        val cleanMode = RelayInputSettingsStore.sanitizeSwipeMode(mode)
        RelayInputSettingsStore(this).saveSwipeMode(cleanMode)
        RelayBridge.sendSettings()
        renderStatus()
        toastLine(
            if (cleanMode == RelayInputSettingsStore.SWIPE_MODE_TWO_FINGER) {
                "Two-finger swipe selected"
            } else {
                "Normal swipe selected"
            },
        )
    }

    private fun updateInputChoiceButtons(swipeMode: String) {
        if (!::normalSwipeButton.isInitialized || !::twoFingerSwipeButton.isInitialized) return
        styleChoiceButton(normalSwipeButton, swipeMode == RelayInputSettingsStore.SWIPE_MODE_NORMAL)
        styleChoiceButton(twoFingerSwipeButton, swipeMode == RelayInputSettingsStore.SWIPE_MODE_TWO_FINGER)
    }

    private fun styleChoiceButton(button: Button, isSelected: Boolean) {
        button.setTextColor(if (isSelected) COLOR_PHOSPHOR else COLOR_TEXT)
        button.background = roundedRect(
            if (isSelected) COLOR_SELECTED else COLOR_FIELD,
            if (isSelected) COLOR_PHOSPHOR_DIM else COLOR_STROKE,
            radius = 8,
            strokeWidth = if (isSelected) 2 else 1,
        )
    }

    private fun defaultApiEngine(): SpeechToTextEngine {
        val store = SttCredentialStore(this)
        return when {
            !store.apiKey(SpeechToTextCredentialKind.ELEVENLABS).isNullOrBlank() ->
                SpeechToTextEngine.ELEVENLABS_SCRIBE_V2_REALTIME
            store.hasOpenAiApiKey() -> SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER
            !store.apiKey(SpeechToTextCredentialKind.AZURE).isNullOrBlank() ->
                SpeechToTextEngine.AZURE_SPEECH
            else -> SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER
        }
    }

    private fun defaultEngineForProvider(provider: SpeechToTextProvider): SpeechToTextEngine =
        when (provider) {
            SpeechToTextProvider.OPENAI -> SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER
            SpeechToTextProvider.ELEVENLABS -> SpeechToTextEngine.ELEVENLABS_SCRIBE_V2_REALTIME
            SpeechToTextProvider.AZURE -> SpeechToTextEngine.AZURE_SPEECH
            SpeechToTextProvider.ANDROID -> SpeechToTextEngine.ANDROID_CXR
        }

    private fun modelsForProvider(provider: SpeechToTextProvider): List<SpeechToTextEngine> =
        SpeechToTextEngine.values()
            .filter { it.provider == provider && it.usesApiAudio }

    private fun providerDescription(provider: SpeechToTextProvider): String =
        when (provider) {
            SpeechToTextProvider.OPENAI -> "OpenAI: strong all-purpose recognition. Good if you already use an OpenAI key."
            SpeechToTextProvider.ELEVENLABS -> "ElevenLabs: voice-focused recognition with simple realtime and buffered choices."
            SpeechToTextProvider.AZURE -> "Azure: free 5 h/month tier with wide language coverage. Buffered only — no realtime, text appears after you stop speaking."
            SpeechToTextProvider.ANDROID -> "Android: local phone recognition with the CXR audio pipe. No API key, but Android still requires microphone permission."
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

    private fun SelfArmRecoveryTone.toStatusTone(): StatusTone =
        when (this) {
            SelfArmRecoveryTone.Ready -> StatusTone.Ready
            SelfArmRecoveryTone.Waiting -> StatusTone.Waiting
            SelfArmRecoveryTone.Neutral -> StatusTone.Neutral
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
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
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

    private fun armRelayOrAuthorize() {
        if (savedAuthToken().isNullOrBlank()) {
            armRelayAfterAuth = true
            requestHiRokidAuthorization(auto = false, reason = RelayStarter.START_REASON_MANUAL)
            return
        }
        val blocker = speechSetupBlocker()
        if (blocker != null) {
            if (
                SpeechToTextSettingsStore(this).selectedEngine().requiresMicrophonePermission &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                requestMicrophonePermissionIfNeeded()
            }
            RelayBridge.setStatus(blocker)
            toastLine(blocker)
            renderStatus()
            return
        }
        armRelayAfterSetupReady()
    }

    private fun armRelayAfterSetupReady(): Boolean {
        if (speechSetupBlocker() != null) return false
        RelayStarter.armAndPrepare(this, RelayStarter.START_REASON_MANUAL)
        CompanionDeviceCoordinator.startObserving(this)
        BleWakeServer.ensureStarted(this)
        return true
    }

    private fun friendlyWirelessStatus(raw: String): String {
        val text = raw.trim()
        val value = text.lowercase(Locale.US)
        return when {
            value.contains("complete") || value.contains("bootstrap ready") ->
                "Glasses recovery is ready"
            value.contains("updating glasses helper") ||
                value.contains("installing glasses helper") ||
                value.contains("glasses helper update failed") ->
                text
            value.contains("same wi-fi") || value.contains("same network") || value.contains("reach") ->
                text
            value.contains("wifi_enable_timeout") ->
                "Connect the glasses to a Wi-Fi network first (any network works — no internet needed), then tap Bootstrap."
            value.contains("pairing wireless") ||
                value.contains("pairing ready") ||
                value.contains("pairing…") ||
                value.contains("pairing with") ->
                "Pairing with the glasses…"
            value.contains("pairing code read") ||
                value.contains("waiting_for_pairing") ||
                value.contains("searching_pairing") ||
                value.contains("opening_pairing") ->
                "Reading the pairing code from the glasses…"
            value.contains("wifi") ||
                value.contains("developer") ||
                value.contains("wireless_debugging") ||
                value.contains("starting_wireless") ||
                value.contains("wireless debugging open") ||
                value.contains("wifi_on") ->
                "Setting up the glasses…"
            value.contains("timeout") || value.contains("manual_step") ->
                "Couldn't finish on the glasses. Keep the glasses on and tap Bootstrap again."
            value.contains("expired") ->
                "The pairing code expired. Tap Bootstrap to try again."
            value.contains("failed") -> {
                val failedPrefix = "failed:"
                val failedIndex = value.indexOf(failedPrefix)
                if (failedIndex >= 0) {
                    text.substring(failedIndex + failedPrefix.length).trim().ifBlank {
                        "Bootstrap failed. Keep both on the same Wi-Fi and tap Bootstrap again."
                    }
                } else {
                    "Bootstrap failed. Keep both on the same Wi-Fi and tap Bootstrap again."
                }
            }
            else -> "Setting up glasses recovery…"
        }
    }

    private fun relaunchRelayOrAuthorize(): Boolean {
        if (savedAuthToken().isNullOrBlank()) {
            armRelayAfterAuth = true
            requestHiRokidAuthorization(auto = false, reason = RelayStarter.START_REASON_MANUAL)
            return false
        }
        val started = RelayStarter.relaunch(this)
        if (started) {
            CompanionDeviceCoordinator.startObserving(this)
            BleWakeServer.ensureStarted(this)
        }
        return started
    }

    private fun prepareSelfArmRecoveryOrAuthorize() {
        if (savedAuthToken().isNullOrBlank()) {
            selfArmAfterAuth = true
            requestHiRokidAuthorization(auto = false, reason = RelayStarter.START_REASON_SELF_ARM)
            return
        }
        if (prepareSelfArmRecoveryLink()) {
            toastLine("Self-arm provisioning started")
        } else {
            toastLine("Self-arm provisioning could not start")
        }
    }

    private fun prepareSelfArmRecoveryLink(): Boolean {
        Log.i(TAG_SELF_ARM, "starting self-arm provisioning link")
        if (!RelayStarter.armAndPrepare(this, RelayStarter.START_REASON_SELF_ARM)) {
            Log.w(TAG_SELF_ARM, "self-arm provisioning link could not start")
            return false
        }
        CompanionDeviceCoordinator.startObserving(this)
        BleWakeServer.ensureStarted(this)
        RelayBridge.setStatus("Self-arm provisioning: opening glasses link")
        if (!SelfArmProvisioner.wirelessBootstrapped(this)) {
            RelayBridge.requestSelfArmWirelessBootstrap(this)
        }
        lastSelfArmAutoPrepareAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG_SELF_ARM, "self-arm provisioning link started")
        return true
    }

    private fun maybeAutoPrepareSelfArmRecovery() {
        if (!RelayStarter.isRelayEnabled(this)) return
        if (SelfArmProvisioner.provisioned(this) || SelfArmProvisioner.disablePending(this)) return
        if (!SelfArmProvisioner.wirelessBootstrapped(this)) return
        if (savedAuthToken().isNullOrBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (
            lastSelfArmAutoPrepareAtMs > 0L &&
            now - lastSelfArmAutoPrepareAtMs < SELF_ARM_AUTO_PREPARE_INTERVAL_MS
        ) return
        prepareSelfArmRecoveryLink()
    }

    private fun syncSettingsAfterUserChange() {
        if (RelayService.running) {
            RelayBridge.sendSettings()
        } else if (RelayStarter.isRelayEnabled(this)) {
            RelayBridge.setStatus("settings saved: will sync on notification wake")
        }
    }

    private fun savedAuthToken(): String? =
        prefs().getString(Constants.PREF_AUTH_TOKEN, null)

    private fun maybeAutoReauthorizeAfterBindFailure(snap: RelayBridge.Snapshot, authSaved: Boolean) {
        if (!authSaved || autoReauthAttempted || authRequestInFlight) return
        if (!RelayService.running || snap.cxrConnected) return
        if (snap.lastStatus != "Hi Rokid bind failed") return
        autoReauthAttempted = true
        requestHiRokidAuthorization(auto = true, reason = "bind_failed")
    }

    private fun requestHiRokidAuthorization(auto: Boolean, reason: String): Boolean {
        if (authRequestInFlight) {
            clearPendingAuthAction(reason)
            return false
        }
        if (!CxrLAuth.isGlobalHiRokidInstalled(this)) {
            clearPendingAuthAction(reason)
            toastLine("Hi Rokid Global is not visible to Rokid Relay")
            return false
        }
        authRequestInFlight = true
        RelayBridge.setStatus(if (auto) "opening Hi Rokid authorization" else "authorization requested")
        val error = CxrLAuth.requestAuthorization(this, Constants.AUTH_REQUEST_CODE)
        if (error is CxrLAuth.Result.Fail) {
            authRequestInFlight = false
            clearPendingAuthAction(reason)
            toastLine("Authorization failed to open: ${error.reason}")
            RelayBridge.setStatus("authorization failed to open: $reason")
            return false
        }
        return true
    }

    private fun clearPendingAuthAction(reason: String) {
        when (reason) {
            RelayStarter.START_REASON_MANUAL -> armRelayAfterAuth = false
            RelayStarter.START_REASON_SELF_ARM -> selfArmAfterAuth = false
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 43)
        }
    }

    private fun sttReady(engine: SpeechToTextEngine, store: SttCredentialStore): Boolean =
        speechSetupBlocker(engine, store, CompanionDeviceCoordinator.hasAssociation(this)) == null

    private fun speechSetupBlocker(): String? =
        speechSetupBlocker(
            SpeechToTextSettingsStore(this).selectedEngine(),
            SttCredentialStore(this),
            CompanionDeviceCoordinator.hasAssociation(this),
        )

    private fun speechSetupBlocker(
        engine: SpeechToTextEngine,
        store: SttCredentialStore,
        companionLinked: Boolean,
    ): String? =
        when {
            engine == SpeechToTextEngine.ANDROID_CXR &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                "Android CXR needs Android 13+"
            engine.requiresMicrophonePermission &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Grant microphone permission for Android CXR"
            engine == SpeechToTextEngine.ANDROID_CXR &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !companionLinked ->
                "Link the glasses as companion device for Android CXR"
            engine.credentialKind == SpeechToTextCredentialKind.AZURE &&
                !store.hasCredential(engine) ->
                "Add an Azure Speech key"
            engine.credentialKind == SpeechToTextCredentialKind.AZURE &&
                store.azureRegion().isNullOrBlank() ->
                "Set the Azure Speech region"
            engine.requiresCredential && !store.hasCredential(engine) ->
                "Add a ${engine.provider.displayName} STT key"
            else -> null
        }

    private fun notificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.contains(packageName)
    }

    private fun batteryOptimizationsIgnored(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(packageName)

    private fun openBatterySettings() {
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching {
            startActivity(appSettings)
        }.recoverCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }.onFailure {
            toastLine("Battery settings unavailable on this device.")
        }
    }

    private fun speechDiagnosticIntent(): Intent =
        Intent().setClassName(packageName, SPEECH_DIAGNOSTIC_ACTIVITY)

    private fun speechDiagnosticAvailable(): Boolean =
        runCatching {
            packageManager.resolveActivity(speechDiagnosticIntent(), 0) != null
        }.getOrDefault(false)

    private fun openSpeechDiagnostic() {
        runCatching {
            startActivity(speechDiagnosticIntent())
        }.onFailure {
            toastLine("Speech diagnostic unavailable in this build.")
        }
    }

    private fun savedToken(): String? =
        prefs().getString(Constants.PREF_AUTH_TOKEN, null)

    private fun prefs() = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    private fun toastLine(text: String) {
        if (::noticeText.isInitialized) {
            noticeText.setTextColor(COLOR_PHOSPHOR)
            noticeText.text = text
        }
        if (::diagnosticsPanel.isInitialized) {
            diagnosticsPanel.showNotice(text)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun frameMatch() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

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

    private enum class AppTab(val label: String, val iconRes: Int) {
        HOME("Home", R.drawable.ic_tab_home),
        NOTIFICATIONS("Notif", R.drawable.ic_tab_notifications),
        SPEECH("Speech", R.drawable.ic_tab_speech),
        UPDATE("Update", R.drawable.ic_tab_update),
    }

    private companion object {
        private const val TAG_SELF_ARM = "RokidRelaySelfArm"
        private const val FOREGROUND_REFRESH_DELAY_MS = 250L
        private const val SELF_ARM_AUTO_PREPARE_INTERVAL_MS = 30_000L
        private const val LANGUAGE_GRID_COLUMNS = 3
        private const val SPEECH_DIAGNOSTIC_ACTIVITY =
            "com.anezium.rokidrelay.phone.SpeechRecognizerLanguageProbeActivity"
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
        const val TAB_HEIGHT_DP = 52
        const val TAB_ICON_DP = 18
        const val UPDATE_NOTES_PREVIEW_LINES = 8
        const val UPDATE_NOTES_COLLAPSED_LINES = 10
        const val UPDATE_NOTES_COLLAPSED_CHARS = 520
    }
}
