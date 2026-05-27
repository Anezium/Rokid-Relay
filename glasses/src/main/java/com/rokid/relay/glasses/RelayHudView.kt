package com.rokid.relay.glasses

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

class RelayHudView(
    context: Context,
    private val overlayMode: Boolean = false,
) : LinearLayout(context) {
    data class NotificationModel(
        val id: String,
        val app: String,
        val title: String,
        val text: String,
    )

    private val header = label(20f, ACCENT)
    private val connectionLabel = label(15f, DIM)
    private val appLabel = label(if (overlayMode) 12f else 18f, ACCENT)
    private val titleLabel = label(if (overlayMode) 16f else 24f, TEXT)
    private val messageLabel = label(if (overlayMode) 15f else 20f, TEXT)
    private val hintLabel = label(if (overlayMode) 13f else 17f, DIM)
    private val accessibilityLabel = label(16f, ACCENT)
    private val inboxRows = List(3) {
        label(14f, TEXT).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private var connection = "connecting"
    private var notification: NotificationModel? = null
    private var inbox: List<NotificationModel> = emptyList()
    private var inboxVisible = false
    private var inboxDetail = false
    private var inboxIndex = 0
    private var voiceState = "idle"
    private var voicePartial = ""
    private var resultLine = ""
    private var replyOk = false
    private var replyEventId = 0L
    private var lastAnimatedReplyEventId = 0L
    private var sentAnimator: AnimatorSet? = null
    private var transientLine = ""
    private var accessibilityEnabled = false

    init {
        orientation = VERTICAL
        if (overlayMode) {
            buildPopupLayout()
        } else {
            buildSetupLayout()
        }
        render()
    }

    fun applyState(state: RelayHudController.State) {
        connection = state.connection
        notification = state.notification
        inbox = state.inbox
        inboxVisible = state.inboxVisible
        inboxDetail = state.inboxDetail
        inboxIndex = state.inboxIndex
        voiceState = state.voiceState
        voicePartial = state.voicePartial
        resultLine = state.resultLine
        replyOk = state.replyOk
        replyEventId = state.replyEventId
        transientLine = state.transientLine
        accessibilityEnabled = state.accessibilityEnabled
        render()
        if (overlayMode && replyOk && replyEventId != lastAnimatedReplyEventId) {
            lastAnimatedReplyEventId = replyEventId
            playSentAnimation()
        }
    }

    fun setConnection(value: String) {
        connection = value
        render()
    }

    fun showNotification(model: NotificationModel) {
        notification = model
        voiceState = "idle"
        voicePartial = ""
        resultLine = ""
        transientLine = ""
        render()
    }

    fun clearNotification() {
        notification = null
        voiceState = "idle"
        voicePartial = ""
        resultLine = ""
        transientLine = ""
        render()
    }

    fun setVoice(state: String, partial: String) {
        voiceState = state.ifBlank { "idle" }
        voicePartial = partial
        transientLine = ""
        render()
    }

    fun showReplyResult(ok: Boolean, message: String) {
        resultLine = if (ok) message.ifBlank { "Reply sent" } else message.ifBlank { "Reply failed" }
        replyOk = ok
        replyEventId += 1L
        voiceState = "idle"
        voicePartial = ""
        render()
        if (overlayMode && ok) playSentAnimation()
    }

    fun showTransient(message: String) {
        transientLine = message
        render()
    }

    fun hasNotification(): Boolean = notification != null

    fun currentNotificationId(): String = notification?.id.orEmpty()

    private fun buildSetupLayout() {
        gravity = Gravity.START
        setBackgroundColor(Color.BLACK)
        setPadding(dp(24), dp(22), dp(24), dp(18))

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(connectionLabel, LinearLayout.LayoutParams(wrap(), wrap()))
        }
        addView(top, matchWrap())
        addRule()
        addView(appLabel, matchWrap(top = 24))
        addView(titleLabel, matchWrap(top = 6))
        addView(messageLabel, LinearLayout.LayoutParams(match(), 0, 1f).apply {
            topMargin = dp(18)
        })
        addRule()
        addView(hintLabel, matchWrap(top = 12))
        addView(accessibilityLabel.apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outline()
        }, matchWrap(top = 12))
    }

    private fun buildPopupLayout() {
        gravity = Gravity.START
        visibility = INVISIBLE
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = outline()

        titleLabel.maxLines = 1
        titleLabel.ellipsize = TextUtils.TruncateAt.END
        messageLabel.maxLines = 4
        messageLabel.maxHeight = dp(96)
        messageLabel.ellipsize = TextUtils.TruncateAt.END
        hintLabel.maxLines = 1
        hintLabel.ellipsize = TextUtils.TruncateAt.END

        addView(appLabel, matchWrap())
        addView(titleLabel, matchWrap(top = 1))
        addView(messageLabel, matchWrap(top = 4))
        inboxRows.forEach { row ->
            row.visibility = GONE
            addView(row, matchWrap(top = 5))
        }
        addView(hintLabel, matchWrap(top = 5))
    }

    private fun render() {
        if (overlayMode) {
            renderPopup()
        } else {
            renderSetup()
        }
    }

    private fun renderSetup() {
        visibility = VISIBLE
        header.text = "ROKID RELAY"
        connectionLabel.text = connection.uppercase()
        appLabel.text = "PHONE LINK"
        titleLabel.text = "Notification relay"
        messageLabel.text = "Enable Accessibility once. Replyable phone notifications will appear as a compact green overlay above other glasses apps."
        hintLabel.text = transientLine.ifBlank { "Tap: open Accessibility settings" }
        hintLabel.setTextColor(DIM)
        accessibilityLabel.visibility = VISIBLE
        accessibilityLabel.text = if (accessibilityEnabled) {
            "ACCESSIBILITY ON"
        } else {
            "ENABLE ACCESSIBILITY"
        }
        accessibilityLabel.setTextColor(if (accessibilityEnabled) ACCENT else TEXT)
    }

    private fun renderPopup() {
        if (replyOk && resultLine.isNotBlank()) {
            renderSentState()
            return
        }
        if (inboxVisible) {
            renderInbox()
            return
        }
        val model = notification
        if (model == null) {
            visibility = INVISIBLE
            return
        }
        visibility = VISIBLE
        alpha = 1f
        hideInboxRows()
        messageLabel.visibility = VISIBLE
        titleLabel.visibility = VISIBLE
        titleLabel.gravity = Gravity.START
        titleLabel.alpha = 1f
        titleLabel.scaleX = 1f
        titleLabel.scaleY = 1f
        titleLabel.translationY = 0f
        titleLabel.setTextColor(TEXT)
        appLabel.text = model.app.ifBlank { "Message" }
        titleLabel.text = model.title.ifBlank { "Replyable notification" }
        val hasVoiceTranscript = renderMessageBody(model.text)
        renderStatus(hasVoiceTranscript)
    }

    private fun renderSentState() {
        visibility = VISIBLE
        hideInboxRows()
        appLabel.text = "Rokid Relay"
        appLabel.visibility = VISIBLE
        titleLabel.visibility = VISIBLE
        titleLabel.text = "SENT"
        titleLabel.gravity = Gravity.CENTER
        titleLabel.setTextColor(ACCENT)
        messageLabel.visibility = GONE
        hintLabel.visibility = GONE
    }

    private fun renderInbox() {
        visibility = VISIBLE
        alpha = 1f
        titleLabel.gravity = Gravity.START
        titleLabel.alpha = 1f
        titleLabel.scaleX = 1f
        titleLabel.scaleY = 1f
        titleLabel.translationY = 0f
        titleLabel.setTextColor(TEXT)
        appLabel.text = "Rokid Relay"

        if (inbox.isEmpty()) {
            hideInboxRows()
            titleLabel.text = "Inbox"
            messageLabel.visibility = VISIBLE
            messageLabel.maxLines = 2
            messageLabel.text = "No replyable notifications"
            hintLabel.visibility = GONE
            return
        }

        val selectedIndex = inboxIndex.coerceIn(0, inbox.lastIndex)
        val selected = inbox[selectedIndex]

        if (inboxDetail) {
            hideInboxRows()
            messageLabel.visibility = VISIBLE
            appLabel.text = selected.app.ifBlank { "Message" }
            titleLabel.text = selected.title.ifBlank { "Replyable notification" }
            val hasVoiceTranscript = renderMessageBody(selected.text)
            renderStatus(hasVoiceTranscript)
            return
        }

        titleLabel.text = "Inbox ${selectedIndex + 1}/${inbox.size}"
        messageLabel.visibility = GONE
        hintLabel.visibility = GONE

        val start = when {
            inbox.size <= inboxRows.size -> 0
            selectedIndex == 0 -> 0
            selectedIndex == inbox.lastIndex -> inbox.size - inboxRows.size
            else -> selectedIndex - 1
        }
        inboxRows.forEachIndexed { rowIndex, row ->
            val item = inbox.getOrNull(start + rowIndex)
            if (item == null) {
                row.visibility = GONE
            } else {
                val selectedRow = start + rowIndex == selectedIndex
                row.visibility = VISIBLE
                row.setTextColor(if (selectedRow) ACCENT else TEXT)
                row.text = "${if (selectedRow) ">" else " "} ${item.app.ifBlank { "Message" }}: " +
                    item.title.ifBlank { oneLine(item.text).ifBlank { "Replyable notification" } } +
                    "  ${oneLine(item.text)}"
            }
        }
    }

    private fun playSentAnimation() {
        sentAnimator?.cancel()
        renderSentState()
        titleLabel.alpha = 0f
        titleLabel.translationY = dp(5).toFloat()
        titleLabel.scaleX = 0.92f
        titleLabel.scaleY = 0.92f

        val titleAlpha = ObjectAnimator.ofFloat(titleLabel, View.ALPHA, 0f, 1f)
        val titleSlide = ObjectAnimator.ofFloat(titleLabel, View.TRANSLATION_Y, dp(5).toFloat(), 0f)
        val titleScaleX = ObjectAnimator.ofFloat(titleLabel, View.SCALE_X, 0.92f, 1f)
        val titleScaleY = ObjectAnimator.ofFloat(titleLabel, View.SCALE_Y, 0.92f, 1f)
        val popupAlpha = ObjectAnimator.ofFloat(this, View.ALPHA, 0.92f, 1f)
        sentAnimator = AnimatorSet().apply {
            duration = 190L
            interpolator = DecelerateInterpolator()
            playTogether(titleAlpha, titleSlide, titleScaleX, titleScaleY, popupAlpha)
            start()
        }
    }

    private fun popupMessageLines(text: String): Int {
        val lineBreaks = text.count { it == '\n' }
        return when {
            text.length > 180 || lineBreaks >= 3 -> 4
            text.length > 90 || lineBreaks >= 1 -> 3
            else -> 2
        }
    }

    private fun renderMessageBody(notificationText: String): Boolean {
        val transcript = activeVoiceTranscript()
        val hasVoiceTranscript = transcript.isNotBlank()
        val body = if (hasVoiceTranscript) {
            transcript
        } else {
            notificationText.ifBlank { "(no preview)" }
        }
        messageLabel.text = body
        messageLabel.maxLines = if (hasVoiceTranscript) {
            popupVoiceLines(body)
        } else {
            popupMessageLines(notificationText)
        }
        messageLabel.maxHeight = if (hasVoiceTranscript) dp(178) else dp(96)
        return hasVoiceTranscript
    }

    private fun renderStatus(hasVoiceTranscript: Boolean) {
        val statusText = when (voiceState) {
            "listening" -> "Listening..."
            "recognizing" -> if (hasVoiceTranscript) "" else "Recognizing..."
            "processing" -> "Processing..."
            "error" -> "Voice error"
            else -> if (replyOk && resultLine.isNotBlank()) "SENT" else resultLine
        }
        hintLabel.text = statusText
        hintLabel.visibility = if (statusText.isBlank()) GONE else VISIBLE
        hintLabel.alpha = 1f
        hintLabel.translationY = 0f
        hintLabel.setTextColor(if (voiceState == "idle" && !replyOk) DIM else ACCENT)
    }

    private fun activeVoiceTranscript(): String =
        if (
            (voiceState == "recognizing" || voiceState == "processing") &&
            voicePartial.isNotBlank()
        ) {
            voicePartial.trim()
        } else {
            ""
        }

    private fun popupVoiceLines(text: String): Int {
        val lineBreaks = text.count { it == '\n' }
        return when {
            text.length > 280 || lineBreaks >= 5 -> 6
            text.length > 210 || lineBreaks >= 4 -> 5
            text.length > 120 || lineBreaks >= 2 -> 4
            text.length > 60 || lineBreaks >= 1 -> 3
            else -> 2
        }
    }

    private fun hideInboxRows() {
        inboxRows.forEach { it.visibility = GONE }
    }

    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim()

    private fun label(size: Float, color: Int): TextView =
        TextView(context).apply {
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(0f, 1.02f)
        }

    private fun addRule() {
        addView(View(context).apply {
            setBackgroundColor(RULE)
        }, LinearLayout.LayoutParams(match(), dp(1)).apply {
            topMargin = dp(10)
        })
    }

    private fun outline(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(1), ACCENT)
            cornerRadius = dp(3).toFloat()
        }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    companion object {
        private val ACCENT = Color.rgb(92, 255, 136)
        private val TEXT = Color.rgb(210, 255, 222)
        private val DIM = Color.rgb(105, 148, 118)
        private val RULE = Color.rgb(40, 78, 50)
    }
}
