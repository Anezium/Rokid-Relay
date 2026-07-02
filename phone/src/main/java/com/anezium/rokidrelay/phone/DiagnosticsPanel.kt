package com.anezium.rokidrelay.phone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class DiagnosticsPanel(
    context: Context,
    private val onStatusChanged: () -> Unit,
    private val onNotice: (String) -> Unit,
) : LinearLayout(context) {
    private lateinit var toggleButton: Button
    private lateinit var container: LinearLayout
    private lateinit var threadInput: EditText
    private lateinit var messageCountInput: EditText
    private lateinit var activityText: TextView
    private var diagnosticsVisible = false

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = roundedRect(COLOR_PANEL, COLOR_STROKE, radius = 10)

        addView(TextView(context).apply {
            text = "Diagnostics"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(COLOR_TEXT)
        }, matchWrap())
        addView(View(context), LayoutParams(match(), dp(8)))

        toggleButton = textButton("Show diagnostics") {
            diagnosticsVisible = !diagnosticsVisible
            updateDiagnosticsVisibility()
        }
        addView(toggleButton, matchWrap())

        container = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
        }
        container.addView(bodyText().apply {
            text = "Create separate replyable Android notifications, append messages after a reply, and stress long-message paging."
        }, matchWrap(top = 10))
        container.addView(threadFields(), matchWrap(top = 14))
        container.addView(buttonRow(
            smallButton("New thread", ButtonTone.Secondary) {
                createNextThread()
            },
            smallButton("Reset thread", ButtonTone.Secondary) {
                postThread(reset = true, longMessages = false)
            },
        ), matchWrap(top = 12))
        container.addView(buttonRow(
            smallButton("Add messages", ButtonTone.Secondary) {
                postThread(reset = false, longMessages = false)
            },
            smallButton("Add long", ButtonTone.Secondary) {
                postThread(reset = false, longMessages = true)
            },
        ), matchWrap(top = 8))
        container.addView(buttonRow(
            smallButton("Clear thread", ButtonTone.Danger) {
                clearThread()
            },
            smallButton("Clear all", ButtonTone.Danger) {
                clearAllThreads()
            },
        ), matchWrap(top = 8))
        container.addView(buttonRow(
            smallButton("Single test", ButtonTone.Secondary) {
                TestNotificationHarness.postTestNotification(context)
                onStatusChanged()
            },
            smallButton("Image test", ButtonTone.Secondary) {
                TestNotificationHarness.postImageTestNotification(context)
                onStatusChanged()
                onNotice("Image test notification posted")
            },
        ), matchWrap(top = 8))
        container.addView(rule(), matchWrap(top = 14))
        container.addView(label("Last activity"), matchWrap(top = 12))
        activityText = bodyText().apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        container.addView(activityText, matchWrap(top = 8))
        addView(container, matchWrap())
        updateDiagnosticsVisibility()
    }

    fun render(snapshot: RelayBridge.Snapshot) {
        if (!::activityText.isInitialized) return
        activityText.setTextColor(COLOR_TEXT)
        activityText.text = buildString {
            appendLine("Event: ${snapshot.lastStatus}")
            appendLine("Voice: ${snapshot.voiceRoute} / ${snapshot.sttEngine}")
            appendLine("CXR-L: ${if (snapshot.cxrConnected) "connected" else "disconnected"}")
            appendLine("Glasses BT: ${if (snapshot.glassConnected) "connected" else "waiting"}")
            appendLine("Glasses app: ${snapshot.bootstrapState}")
            appendLine("Self-arm: ${snapshot.selfArmStatus} key=${snapshot.selfArmKeyPresent}")
            appendLine("Mic foreground: ${if (RelayService.microphoneForegroundActive) "active" else "off"}")
            if (!RelayService.microphoneForegroundActive && RelayService.lastMicrophoneForegroundError.isNotBlank()) {
                appendLine("Mic foreground error: ${RelayService.lastMicrophoneForegroundError}")
            }
            appendLine("CXR audio: ${displayBytes(snapshot.cxrAudioBytes)} avg=${snapshot.vadAverageAbs} peak=${snapshot.vadPeakAbs} speech=${snapshot.vadSpeechDetected}")
            if (snapshot.lastVoiceError.isNotBlank()) appendLine("Voice error: ${snapshot.lastVoiceError}")
            appendLine("Sent: ${displayMessage(snapshot.lastOutgoingReply)}")
            append("Received: ${displayMessage(snapshot.lastDeliveredReply)}")
        }
    }

    fun showNotice(text: String) {
        if (!::activityText.isInitialized) return
        activityText.setTextColor(COLOR_DANGER)
        activityText.text = text
    }

    private fun createNextThread() {
        val count = readMessageCount() ?: return
        val threadIndex = TestNotificationHarness.nextThreadIndex(context)
        threadInput.setText(threadIndex.toString())
        TestNotificationHarness.postThreadTestNotification(
            context = context,
            threadIndex = threadIndex,
            addCount = count,
            reset = true,
            longMessages = false,
        )
        onStatusChanged()
        onNotice("Test thread $threadIndex created with $count messages")
    }

    private fun postThread(reset: Boolean, longMessages: Boolean) {
        val threadIndex = readThreadIndex() ?: return
        val count = readMessageCount() ?: return
        TestNotificationHarness.postThreadTestNotification(
            context = context,
            threadIndex = threadIndex,
            addCount = count,
            reset = reset,
            longMessages = longMessages,
        )
        onStatusChanged()
        onNotice(
            when {
                reset -> "Test thread $threadIndex reset with $count messages"
                longMessages -> "Added $count long messages to test thread $threadIndex"
                else -> "Added $count messages to test thread $threadIndex"
            },
        )
    }

    private fun clearThread() {
        val threadIndex = readThreadIndex() ?: return
        TestNotificationHarness.clearTestThread(context, threadIndex)
        threadInput.setText(TestNotificationHarness.nextThreadIndex(context).toString())
        onStatusChanged()
        onNotice("Test thread $threadIndex cleared")
    }

    private fun clearAllThreads() {
        TestNotificationHarness.clearAllTestThreads(context)
        threadInput.setText(TestNotificationHarness.nextThreadIndex(context).toString())
        onStatusChanged()
        onNotice("All test threads cleared. The next thread will start at 1.")
    }

    private fun readThreadIndex(): Int? {
        val value = threadInput.text.toString().trim().toIntOrNull()
        if (value == null || value < 1) {
            onNotice("Enter a thread number of 1 or higher.")
            return null
        }
        val clamped = value.coerceAtMost(TestNotificationHarness.MAX_TEST_THREAD_INDEX)
        if (clamped != value) threadInput.setText(clamped.toString())
        return clamped
    }

    private fun readMessageCount(): Int? {
        val value = messageCountInput.text.toString().trim().toIntOrNull()
        if (value == null || value < 1) {
            onNotice("Enter a message count of 1 or higher.")
            return null
        }
        val clamped = value.coerceAtMost(TestNotificationHarness.MAX_TEST_THREAD_MESSAGES_PER_POST)
        if (clamped != value) messageCountInput.setText(clamped.toString())
        return clamped
    }

    private fun threadFields(): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            threadInput = numberInput("1").apply {
                setText(TestNotificationHarness.nextThreadIndex(context).toString())
            }
            messageCountInput = numberInput(TestNotificationHarness.DEFAULT_TEST_THREAD_MESSAGE_COUNT.toString()).apply {
                setText(TestNotificationHarness.DEFAULT_TEST_THREAD_MESSAGE_COUNT.toString())
            }
            addView(numberBlock("Thread", threadInput), LayoutParams(0, wrap(), 1f))
            addView(numberBlock("Messages", messageCountInput), LayoutParams(0, wrap(), 1f).apply {
                leftMargin = dp(10)
            })
        }

    private fun numberBlock(title: String, input: EditText): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label(title), matchWrap())
            addView(input, LayoutParams(match(), dp(42)).apply {
                topMargin = dp(8)
            })
        }

    private fun updateDiagnosticsVisibility() {
        container.visibility = if (diagnosticsVisible) VISIBLE else GONE
        toggleButton.text = if (diagnosticsVisible) "Hide diagnostics" else "Show diagnostics"
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { index, button ->
                addView(button, LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }

    private fun smallButton(label: String, tone: ButtonTone, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
        TextView(context).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(COLOR_MUTED)
        }

    private fun bodyText(): TextView =
        TextView(context).apply {
            textSize = 12.5f
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(COLOR_MUTED)
        }

    private fun numberInput(hintText: String): EditText =
        EditText(context).apply {
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

    private fun rule(): View =
        View(context).apply {
            setBackgroundColor(COLOR_STROKE)
            layoutParams = LayoutParams(match(), dp(1))
        }

    private fun inputBackground(): GradientDrawable =
        roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8)

    private fun buttonBackground(tone: ButtonTone): StateListDrawable {
        val pressed = when (tone) {
            ButtonTone.Primary -> roundedRect(COLOR_ACTION_PRESSED, COLOR_PHOSPHOR_DIM, radius = 8)
            ButtonTone.Secondary -> roundedRect(COLOR_PANEL_ALT, COLOR_PHOSPHOR_DIM, radius = 8)
            ButtonTone.Danger -> roundedRect(COLOR_PANEL_ALT, COLOR_DANGER, radius = 8)
        }
        val normal = when (tone) {
            ButtonTone.Primary -> roundedRect(COLOR_ACTION, COLOR_PHOSPHOR_DIM, radius = 8)
            ButtonTone.Secondary -> roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8)
            ButtonTone.Danger -> roundedRect(COLOR_FIELD, COLOR_DANGER, radius = 8)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun buttonTextColor(tone: ButtonTone): Int =
        when (tone) {
            ButtonTone.Primary -> COLOR_PHOSPHOR
            ButtonTone.Danger -> COLOR_DANGER
            ButtonTone.Secondary -> COLOR_TEXT
        }

    private fun roundedRect(color: Int, stroke: Int, radius: Int, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            setStroke(dp(strokeWidth), stroke)
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

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun matchWrap(top: Int = 0) = LayoutParams(match(), wrap()).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class ButtonTone {
        Primary,
        Secondary,
        Danger,
    }

    private companion object {
        val COLOR_PANEL: Int = Color.rgb(8, 18, 11)
        val COLOR_PANEL_ALT: Int = Color.rgb(11, 29, 16)
        val COLOR_FIELD: Int = Color.rgb(5, 13, 8)
        val COLOR_ACTION: Int = Color.rgb(12, 44, 22)
        val COLOR_ACTION_PRESSED: Int = Color.rgb(17, 61, 31)
        val COLOR_TEXT: Int = Color.rgb(224, 245, 226)
        val COLOR_MUTED: Int = Color.rgb(141, 162, 145)
        val COLOR_DIM: Int = Color.rgb(85, 106, 90)
        val COLOR_STROKE: Int = Color.rgb(32, 52, 38)
        val COLOR_PHOSPHOR: Int = Color.rgb(113, 255, 151)
        val COLOR_PHOSPHOR_DIM: Int = Color.rgb(72, 156, 93)
        val COLOR_DANGER: Int = Color.rgb(255, 112, 112)
    }
}
