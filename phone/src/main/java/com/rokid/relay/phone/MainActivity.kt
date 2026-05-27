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
    private lateinit var statusList: LinearLayout
    private lateinit var activityText: TextView
    private lateinit var sttSummary: TextView
    private lateinit var openAiKeyInput: EditText

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
        RelayStarter.startIfReady(this, "app_open")
        renderStatus()
        handler.post(pollStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(pollStatus)
        super.onPause()
    }

    @Deprecated("Hi Rokid still returns authorization through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Constants.AUTH_REQUEST_CODE) return

        val notice = when (val result = CxrLAuth.parseAuthorizationResult(resultCode, data)) {
            is CxrLAuth.Result.Success -> {
                prefs().edit().putString(Constants.PREF_AUTH_TOKEN, result.token).apply()
                RelayStarter.start(this, result.token, "authorization")
                null
            }
            is CxrLAuth.Result.Fail -> "Authorization failed: ${result.reason}"
            is CxrLAuth.Result.Cancel -> "Authorization cancelled"
        }
        renderStatus()
        notice?.let(::toastLine)
    }

    private fun buildContent(): ScrollView {
        val horizontalPadding = dp(20)
        val topPadding = dp(18)
        val bottomPadding = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            applySystemBarPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
        }

        root.addView(TextView(this).apply {
            text = "Rokid Relay"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(COLOR_TEXT)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Phone notifications, glasses overlay, voice replies"
            textSize = 14f
            includeFontPadding = false
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(6), 0, dp(4))
        }, matchWrap())

        root.addView(section("Relay status") {
            statusList = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(statusList, matchWrap(top = 2))
            addView(rule(), matchWrap(top = 12))
            addView(TextView(this@MainActivity).apply {
                text = "Last activity"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(12), 0, dp(6))
            }, matchWrap())
            activityText = TextView(this@MainActivity).apply {
                textSize = 13f
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(COLOR_TEXT)
            }
            addView(activityText, matchWrap())
        })

        root.addView(section("Controls") {
            addView(buttonRow(
                actionButton("Start relay", ButtonTone.Primary) {
                    val started = RelayStarter.startIfReady(this@MainActivity, "manual")
                    renderStatus()
                    if (!started) toastLine("Authorize Hi Rokid before starting relay")
                },
                actionButton("Stop relay", ButtonTone.Danger) {
                    RelayStarter.stop(this@MainActivity)
                    renderStatus()
                },
            ))
            addView(actionButton("Authorize Hi Rokid", ButtonTone.Secondary) {
                if (!CxrLAuth.isGlobalHiRokidInstalled(this@MainActivity)) {
                    toastLine("Hi Rokid Global is not visible to Rokid Relay")
                } else {
                    val error = CxrLAuth.requestAuthorization(this@MainActivity, Constants.AUTH_REQUEST_CODE)
                    if (error is CxrLAuth.Result.Fail) {
                        toastLine("Authorization failed to open: ${error.reason}")
                    }
                }
            })
            addView(actionButton("Notification access", ButtonTone.Secondary) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            addView(buttonRow(
                actionButton("Test notification", ButtonTone.Secondary) {
                    TestNotificationReceiver.postTestNotification(this@MainActivity)
                    renderStatus()
                },
                actionButton("Long test", ButtonTone.Secondary) {
                    TestNotificationReceiver.postTestNotification(
                        this@MainActivity,
                        "Long message de test pour Rokid Relay. Il doit rester lisible sur les lunettes sans prendre tout l'ecran: " +
                            "on garde quelques lignes utiles, puis le reste est tronque proprement. Cette phrase ajoute volontairement " +
                            "du contenu pour verifier l'ellipse, la hauteur maximale et le confort en notification reelle.",
                    )
                    renderStatus()
                },
            ))
        })

        root.addView(section("Speech to text") {
            addView(TextView(this@MainActivity).apply {
                text = "OpenAI API key"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(COLOR_TEXT)
            }, matchWrap())
            sttSummary = TextView(this@MainActivity).apply {
                textSize = 13f
                includeFontPadding = false
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(6), 0, dp(8))
            }
            addView(sttSummary, matchWrap())
            openAiKeyInput = EditText(this@MainActivity).apply {
                hint = "sk-..."
                textSize = 15f
                setSingleLine(true)
                includeFontPadding = false
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
                typeface = Typeface.MONOSPACE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSelectAllOnFocus(true)
                setPadding(dp(12), 0, dp(12), 0)
                minHeight = dp(48)
                background = inputBackground()
            }
            addView(openAiKeyInput, matchWrap(top = 2))
            addView(buttonRow(
                actionButton("Save key", ButtonTone.Primary) {
                    val notice = runCatching {
                        SttCredentialStore(this@MainActivity).saveOpenAiApiKey(openAiKeyInput.text.toString())
                    }.fold(
                        onSuccess = {
                            openAiKeyInput.text.clear()
                            "OpenAI STT key saved"
                        },
                        onFailure = {
                            "Failed to save STT key: ${it.message}"
                        },
                    )
                    renderStatus()
                    toastLine(notice)
                },
                actionButton("Clear key", ButtonTone.Secondary) {
                    SttCredentialStore(this@MainActivity).clearOpenAiApiKey()
                    openAiKeyInput.text.clear()
                    renderStatus()
                    toastLine("OpenAI STT key cleared")
                },
            ))
        })

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_APP_BG)
            isFillViewport = true
            addView(root, matchWrap())
        }
    }

    private fun section(title: String, build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedRect(COLOR_PANEL, COLOR_STROKE, radius = 8)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(COLOR_TEXT)
                setPadding(0, 0, 0, dp(10))
            }, matchWrap())
            build()
            layoutParams = matchWrap(top = 14)
        }

    private fun actionButton(label: String, tone: ButtonTone, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isAllCaps = false
            minHeight = dp(48)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(buttonTextColor(tone))
            background = buttonBackground(tone)
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { onClick() }
            layoutParams = matchWrap(top = 10)
        }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index > 0) leftMargin = dp(10)
                })
            }
            layoutParams = matchWrap(top = 10)
        }

    private fun renderStatus() {
        val snap = RelayBridge.snapshot()
        val hiRokid = CxrLAuth.isGlobalHiRokidInstalled(this)
        val notifications = notificationAccessEnabled()
        val authSaved = !savedToken().isNullOrBlank()
        val stt = SttCredentialStore(this)
        val sttLabel = stt.openAiAccountLabel()

        if (::sttSummary.isInitialized) {
            sttSummary.text = if (sttLabel.isNullOrBlank()) {
                "No key saved. Voice replies need STT."
            } else {
                "Stored securely as $sttLabel"
            }
            sttSummary.setTextColor(if (sttLabel.isNullOrBlank()) COLOR_MUTED else COLOR_PHOSPHOR_DARK)
        }

        if (::statusList.isInitialized) {
            setStatusRows(
                listOf(
                    StatusLine("Hi Rokid", if (hiRokid) "Installed" else "Not ready", if (hiRokid) StatusTone.Ready else StatusTone.Waiting),
                    StatusLine("Authorization", if (authSaved) "Saved" else "Missing", if (authSaved) StatusTone.Ready else StatusTone.Waiting),
                    StatusLine("Notifications", if (notifications) "Enabled" else "Disabled", if (notifications) StatusTone.Ready else StatusTone.Waiting),
                    StatusLine("Speech to text", if (sttLabel.isNullOrBlank()) "Missing key" else "Ready", if (sttLabel.isNullOrBlank()) StatusTone.Waiting else StatusTone.Ready),
                    StatusLine("Relay service", if (RelayService.running) "Running" else "Stopped", if (RelayService.running) StatusTone.Ready else StatusTone.Neutral),
                    StatusLine("CXR-L", if (snap.cxrConnected) "Connected" else "Disconnected", if (snap.cxrConnected) StatusTone.Ready else StatusTone.Waiting),
                    StatusLine("Glasses BT", if (snap.glassConnected) "Connected" else "Waiting", if (snap.glassConnected) StatusTone.Ready else StatusTone.Waiting),
                    StatusLine("Glasses app", snap.bootstrapState, StatusTone.Neutral),
                ),
            )
        }

        if (::activityText.isInitialized) {
            activityText.setTextColor(COLOR_TEXT)
            activityText.text = buildString {
                appendLine("Event: ${snap.lastStatus}")
                appendLine("Sent: ${displayMessage(snap.lastOutgoingReply)}")
                append("Received: ${displayMessage(snap.lastDeliveredReply)}")
            }
        }
    }

    private fun setStatusRows(rows: List<StatusLine>) {
        statusList.removeAllViews()
        rows.forEachIndexed { index, row ->
            statusList.addView(statusRow(row), matchWrap(top = if (index == 0) 0 else 8))
        }
    }

    private fun statusRow(row: StatusLine): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(26)
            addView(View(this@MainActivity).apply {
                background = dot(statusColor(row.tone))
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(TextView(this@MainActivity).apply {
                text = row.label
                textSize = 13f
                includeFontPadding = false
                setTextColor(COLOR_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, wrap(), 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(12)
            })
            addView(TextView(this@MainActivity).apply {
                text = row.value
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(statusColor(row.tone))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, wrap(), 0.95f))
        }

    private fun buttonBackground(tone: ButtonTone): StateListDrawable {
        val defaultFill: Int
        val pressedFill: Int
        val stroke: Int
        when (tone) {
            ButtonTone.Primary -> {
                defaultFill = COLOR_PHOSPHOR
                pressedFill = COLOR_PHOSPHOR_SOFT
                stroke = COLOR_PHOSPHOR_DARK
            }
            ButtonTone.Secondary -> {
                defaultFill = COLOR_PANEL
                pressedFill = COLOR_PHOSPHOR_WASH
                stroke = COLOR_STROKE
            }
            ButtonTone.Danger -> {
                defaultFill = COLOR_ALERT_WASH
                pressedFill = COLOR_PANEL
                stroke = COLOR_ALERT
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), roundedRect(COLOR_DISABLED, COLOR_STROKE, radius = 8))
            addState(intArrayOf(android.R.attr.state_pressed), roundedRect(pressedFill, stroke, radius = 8))
            addState(intArrayOf(android.R.attr.state_focused), roundedRect(pressedFill, COLOR_PHOSPHOR_DARK, radius = 8, strokeWidth = 2))
            addState(intArrayOf(), roundedRect(defaultFill, stroke, radius = 8))
        }
    }

    private fun buttonTextColor(tone: ButtonTone): Int =
        when (tone) {
            ButtonTone.Danger -> COLOR_ALERT
            else -> COLOR_TEXT
        }

    private fun inputBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundedRect(COLOR_PANEL, COLOR_PHOSPHOR_DARK, radius = 8, strokeWidth = 2))
            addState(intArrayOf(), roundedRect(COLOR_PANEL, COLOR_STROKE, radius = 8))
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
            StatusTone.Ready -> COLOR_PHOSPHOR_DARK
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 42)
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
        if (::activityText.isInitialized) {
            activityText.setTextColor(COLOR_ALERT)
            activityText.text = text
        }
    }

    private fun displayMessage(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (oneLine.isBlank()) return "-"
        return if (oneLine.length <= 160) oneLine else oneLine.take(157) + "..."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private data class StatusLine(
        val label: String,
        val value: String,
        val tone: StatusTone,
    )

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

    private companion object {
        val COLOR_APP_BG: Int = Color.rgb(244, 249, 241)
        val COLOR_PANEL: Int = Color.rgb(250, 253, 247)
        val COLOR_DISABLED: Int = Color.rgb(230, 237, 227)
        val COLOR_PHOSPHOR_WASH: Int = Color.rgb(229, 248, 232)
        val COLOR_PHOSPHOR_SOFT: Int = Color.rgb(185, 244, 199)
        val COLOR_PHOSPHOR: Int = Color.rgb(82, 238, 122)
        val COLOR_PHOSPHOR_DARK: Int = Color.rgb(29, 135, 68)
        val COLOR_STROKE: Int = Color.rgb(184, 211, 191)
        val COLOR_TEXT: Int = Color.rgb(12, 30, 20)
        val COLOR_MUTED: Int = Color.rgb(77, 98, 84)
        val COLOR_AMBER: Int = Color.rgb(131, 96, 39)
        val COLOR_ALERT: Int = Color.rgb(148, 56, 52)
        val COLOR_ALERT_WASH: Int = Color.rgb(252, 238, 235)
    }
}
