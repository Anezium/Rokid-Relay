package com.anezium.rokidrelay.glasses

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
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
        val imageId: String = "",
        val imageMimeType: String = "",
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) {
        val hasImage: Boolean
            get() = imageId.isNotBlank()

        fun textPageMaxLines(): Int =
            if (hasImage) IMAGE_TEXT_MAX_LINES else DEFAULT_TEXT_MAX_LINES

        companion object {
            const val DEFAULT_TEXT_MAX_LINES = 9
            const val IMAGE_TEXT_MAX_LINES = 5
        }
    }

    private enum class MessageBodyMode {
        NOTIFICATION_PAGE,
        EMPTY_INBOX,
        VOICE_TRANSCRIPT,
    }

    private val header = label(20f, ACCENT)
    private val connectionLabel = label(15f, DIM)
    private val appLabel = label(if (overlayMode) 12f else 18f, ACCENT)
    private val titleLabel = label(if (overlayMode) 16f else 24f, TEXT)
    private val imagePreviewView = ImageView(context).apply {
        visibility = GONE
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
        setBackgroundColor(Color.rgb(1, 3, 2))
    }
    private val messageLabel = label(if (overlayMode) 15f else 20f, TEXT)
    private val hintLabel = label(if (overlayMode) 13f else 17f, DIM)
    private val countdownRing = CountdownRingView(context)
    private val reviewLabel = label(if (overlayMode) 13f else 17f, ACCENT)
    private val reviewRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        addView(countdownRing, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(reviewLabel, LinearLayout.LayoutParams(0, wrap(), 1f).apply {
            leftMargin = dp(9)
        })
    }
    private val accessibilityLabel = label(16f, ACCENT)
    private val inboxRows = List(3) {
        label(14f, TEXT).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private var connection = "connecting"
    private var notification: NotificationModel? = null
    private var notificationPage = 0
    private var inbox: List<NotificationModel> = emptyList()
    private var inboxVisible = false
    private var inboxDetail = false
    private var inboxIndex = 0
    private var inboxDetailPage = 0
    private var voiceState = "idle"
    private var voicePartial = ""
    private var countdownMs = 0L
    private var countdownTotalMs = 0L
    private var resultLine = ""
    private var replyOk = false
    private var replyEventId = 0L
    private var lastAnimatedReplyEventId = 0L
    private var sentAnimator: AnimatorSet? = null
    private var transientLine = ""
    private var accessibilityEnabled = false
    private var notificationFontSizeSp = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP

    init {
        orientation = VERTICAL
        if (overlayMode) {
            buildPopupLayout()
            applyPopupFontSize()
        } else {
            buildSetupLayout()
        }
        render()
    }

    fun applyState(state: RelayHudController.State) {
        connection = state.connection
        notification = state.notification
        notificationPage = state.notificationPage
        inbox = state.inbox
        inboxVisible = state.inboxVisible
        inboxDetail = state.inboxDetail
        inboxIndex = state.inboxIndex
        inboxDetailPage = state.inboxDetailPage
        voiceState = state.voiceState
        voicePartial = state.voicePartial
        countdownMs = state.countdownMs
        countdownTotalMs = state.countdownTotalMs
        resultLine = state.resultLine
        replyOk = state.replyOk
        replyEventId = state.replyEventId
        transientLine = state.transientLine
        accessibilityEnabled = state.accessibilityEnabled
        val fontChanged = Math.abs(notificationFontSizeSp - state.notificationFontSizeSp) > 0.01f
        notificationFontSizeSp = state.notificationFontSizeSp
        if (overlayMode && fontChanged) applyPopupFontSize()
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
        countdownMs = 0L
        countdownTotalMs = 0L
        resultLine = ""
        transientLine = ""
        render()
    }

    fun clearNotification() {
        notification = null
        voiceState = "idle"
        voicePartial = ""
        countdownMs = 0L
        countdownTotalMs = 0L
        resultLine = ""
        transientLine = ""
        render()
    }

    fun setVoice(state: String, partial: String) {
        voiceState = state.ifBlank { "idle" }
        voicePartial = partial
        countdownMs = 0L
        countdownTotalMs = 0L
        transientLine = ""
        render()
    }

    fun showReplyResult(ok: Boolean, message: String) {
        resultLine = if (ok) message.ifBlank { "Reply sent" } else message.ifBlank { "Reply failed" }
        replyOk = ok
        replyEventId += 1L
        voiceState = "idle"
        voicePartial = ""
        countdownMs = 0L
        countdownTotalMs = 0L
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
        messageLabel.maxLines = NOTIFICATION_PAGE_MAX_LINES
        messageLabel.ellipsize = TextUtils.TruncateAt.END
        hintLabel.maxLines = 1
        hintLabel.ellipsize = TextUtils.TruncateAt.END

        addView(appLabel, matchWrap())
        addView(titleLabel, matchWrap(top = 1))
        addView(imagePreviewView, LinearLayout.LayoutParams(match(), dp(124)).apply {
            topMargin = dp(5)
        })
        addView(messageLabel, matchWrap(top = 4))
        addView(reviewRow, matchWrap(top = 7))
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
            hideImagePreview()
            visibility = INVISIBLE
            return
        }
        visibility = VISIBLE
        alpha = 1f
        hideInboxRows()
        reviewRow.visibility = GONE
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
        renderImagePreview(model)
        val maxLines = model.textPageMaxLines()
        val pages = notificationPages(model.text, maxLines)
        val pageIndex = notificationPage.coerceIn(0, pages.lastIndex)
        val hasVoiceTranscript = renderMessageBody(pages[pageIndex], maxLines)
        renderStatus(hasVoiceTranscript)
        renderPageHint(pageIndex, pages.size)
    }

    private fun renderSentState() {
        visibility = VISIBLE
        hideInboxRows()
        hideImagePreview()
        reviewRow.visibility = GONE
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
        reviewRow.visibility = GONE
        hideImagePreview()
        appLabel.text = "Rokid Relay"

        if (inbox.isEmpty()) {
            hideInboxRows()
            titleLabel.text = "Inbox"
            applyMessageText(
                text = transientLine.ifBlank {
                    if (connection == "sleeping") {
                        "Phone sleeping. Wait for next notification."
                    } else {
                        "No replyable notifications"
                    }
                },
                mode = MessageBodyMode.EMPTY_INBOX,
                textSizeSp = notificationFontSizeSp,
            )
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
            renderImagePreview(selected)
            val maxLines = selected.textPageMaxLines()
            val pages = notificationPages(selected.text, maxLines)
            val pageIndex = inboxDetailPage.coerceIn(0, pages.lastIndex)
            val hasVoiceTranscript = renderMessageBody(pages[pageIndex], maxLines)
            renderStatus(hasVoiceTranscript)
            renderPageHint(pageIndex, pages.size)
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
                val preview = oneLine(NotificationTextPager.page(item.text, 0, notificationTextPaint(), notificationLayoutSpec(item.textPageMaxLines())))
                val imagePrefix = if (item.hasImage) "[img] " else ""
                row.text = "${if (selectedRow) ">" else " "} ${item.app.ifBlank { "Message" }}: " +
                    imagePrefix + item.title.ifBlank { preview.ifBlank { "Replyable notification" } } +
                    "  $preview"
            }
        }
    }

    private fun renderPageHint(pageIndex: Int, pageCount: Int) {
        if (voiceState != "idle" || resultLine.isNotBlank()) return
        val hint = transientLine.ifBlank {
            if (pageCount > 1) "Page ${pageIndex + 1}/${pageCount}" else ""
        }
        hintLabel.text = hint
        hintLabel.visibility = if (hint.isBlank()) GONE else VISIBLE
        hintLabel.alpha = 1f
        hintLabel.translationY = 0f
        hintLabel.setTextColor(DIM)
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

    private fun renderMessageBody(
        notificationText: String,
        maxLines: Int = NotificationModel.DEFAULT_TEXT_MAX_LINES,
    ): Boolean {
        val transcript = activeVoiceTranscript()
        val hasVoiceTranscript = transcript.isNotBlank()
        val body = if (hasVoiceTranscript) {
            transcript
        } else {
            notificationText.ifBlank { "(no preview)" }
        }
        val bodyTextSize = popupBodyTextSize(body, hasVoiceTranscript)
        applyMessageText(
            text = body,
            mode = if (hasVoiceTranscript) {
                MessageBodyMode.VOICE_TRANSCRIPT
            } else {
                MessageBodyMode.NOTIFICATION_PAGE
            },
            textSizeSp = bodyTextSize,
            notificationMaxLines = maxLines,
        )
        return hasVoiceTranscript
    }

    private fun applyMessageText(
        text: String,
        mode: MessageBodyMode,
        textSizeSp: Float,
        notificationMaxLines: Int = NotificationModel.DEFAULT_TEXT_MAX_LINES,
    ) {
        messageLabel.visibility = VISIBLE
        messageLabel.text = text
        messageLabel.textSize = textSizeSp
        messageLabel.setLineSpacing(
            if (mode == MessageBodyMode.VOICE_TRANSCRIPT) dp(1).toFloat() else 1f,
            if (mode == MessageBodyMode.VOICE_TRANSCRIPT) 1.08f else 1.02f,
        )
        messageLabel.maxLines = when (mode) {
            MessageBodyMode.NOTIFICATION_PAGE -> notificationMaxLines.coerceAtLeast(1)
            MessageBodyMode.EMPTY_INBOX -> 2
            MessageBodyMode.VOICE_TRANSCRIPT -> popupVoiceLines(text)
        }
        messageLabel.maxHeight = if (mode == MessageBodyMode.VOICE_TRANSCRIPT) {
            popupVoiceMaxHeight(text, textSizeSp)
        } else {
            Int.MAX_VALUE
        }
    }

    private fun notificationPages(
        text: String,
        maxLines: Int = NotificationModel.DEFAULT_TEXT_MAX_LINES,
    ): List<String> =
        NotificationTextPager.pages(text, notificationTextPaint(), notificationLayoutSpec(maxLines))

    private fun notificationTextPaint(): TextPaint =
        TextPaint(messageLabel.paint).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                notificationFontSizeSp,
                resources.displayMetrics,
            )
        }

    private fun notificationLayoutSpec(
        maxLines: Int = NotificationModel.DEFAULT_TEXT_MAX_LINES,
    ): NotificationTextPager.LayoutSpec =
        NotificationTextPager.LayoutSpec(
            widthPx = messageTextWidthPx(),
            maxLines = maxLines.coerceAtLeast(1),
            lineSpacingAddPx = 1f,
            lineSpacingMultiplier = 1.02f,
        )

    private fun renderImagePreview(model: NotificationModel): Boolean {
        if (!model.hasImage || voiceState != "idle" || resultLine.isNotBlank()) {
            hideImagePreview()
            return false
        }
        val bitmap = RelayHudController.notificationImage(model.imageId)
        if (bitmap == null) {
            hideImagePreview()
            return false
        }
        imagePreviewView.setImageBitmap(bitmap)
        imagePreviewView.visibility = VISIBLE
        return true
    }

    private fun hideImagePreview() {
        imagePreviewView.setImageDrawable(null)
        imagePreviewView.visibility = GONE
    }

    private fun messageTextWidthPx(): Int {
        val labelWidth = messageLabel.width - messageLabel.paddingLeft - messageLabel.paddingRight
        if (labelWidth > 0) return labelWidth
        val parentWidth = width - paddingLeft - paddingRight
        if (parentWidth > 0) return parentWidth
        val screenWidth = resources.displayMetrics.widthPixels
        val popupWidth = (screenWidth - dp(20))
            .coerceAtLeast((screenWidth * 0.9f).toInt())
            .coerceAtMost(screenWidth - dp(8))
        return (popupWidth - paddingLeft - paddingRight).coerceAtLeast(dp(160))
    }

    private fun renderStatus(hasVoiceTranscript: Boolean) {
        if (voiceState == "reviewing") {
            val total = countdownTotalMs.takeIf { it > 0L } ?: 1L
            val remaining = countdownMs.coerceIn(0L, total)
            val seconds = ((remaining + 999L) / 1000L).coerceAtLeast(1L)
            reviewRow.visibility = VISIBLE
            countdownRing.setCountdown(remaining, total)
            reviewLabel.text = "Sending in ${seconds}s"
            reviewLabel.setTextColor(ACCENT)
            hintLabel.text = "OK: retry"
            hintLabel.visibility = VISIBLE
            hintLabel.alpha = 1f
            hintLabel.translationY = 0f
            hintLabel.setTextColor(DIM)
            return
        }
        reviewRow.visibility = GONE
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
            (voiceState == "recognizing" || voiceState == "processing" || voiceState == "reviewing") &&
            voicePartial.isNotBlank()
        ) {
            voicePartial.trim()
        } else {
            ""
        }

    private fun popupVoiceLines(text: String): Int {
        val lineBreaks = text.count { it == '\n' }
        return when {
            text.length > 420 || lineBreaks >= 8 -> 11
            text.length > 320 || lineBreaks >= 6 -> 10
            text.length > 240 || lineBreaks >= 5 -> 9
            text.length > 160 || lineBreaks >= 3 -> 8
            text.length > 90 || lineBreaks >= 2 -> 6
            text.length > 45 || lineBreaks >= 1 -> 4
            else -> 3
        }
    }

    private fun popupVoiceMaxHeight(text: String, textSizeSp: Float): Int {
        val baseDp = when {
            text.length > 420 -> 326
            text.length > 300 -> 296
            text.length > 180 -> 252
            text.length > 90 -> 214
            else -> 172
        }
        val scale = (textSizeSp / NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP).coerceIn(0.8f, 1.45f)
        return dp(Math.round(baseDp * scale).coerceAtMost(360))
    }

    private fun popupBodyTextSize(text: String, hasVoiceTranscript: Boolean): Float {
        val baseSp = if (overlayMode) notificationFontSizeSp else 20f
        if (!hasVoiceTranscript) return baseSp
        return when {
            text.length > 420 -> (baseSp - 2f).coerceAtLeast(NotificationOverlaySettings.MIN_FONT_SIZE_SP)
            text.length > 260 -> (baseSp - 1f).coerceAtLeast(NotificationOverlaySettings.MIN_FONT_SIZE_SP)
            else -> baseSp
        }
    }

    private fun applyPopupFontSize() {
        if (!overlayMode) return
        val safeSp = NotificationOverlaySettings.sanitizeFontSizeSp(notificationFontSizeSp)
        notificationFontSizeSp = safeSp
        val horizontalPaddingDp = when {
            safeSp <= 13.5f -> 10
            safeSp <= 17.0f -> 12
            else -> 14
        }
        val verticalPaddingDp = when {
            safeSp <= 13.5f -> 8
            safeSp <= 17.0f -> 9
            else -> 11
        }
        setPadding(
            dp(horizontalPaddingDp),
            dp(verticalPaddingDp),
            dp(horizontalPaddingDp),
            dp(verticalPaddingDp),
        )
        appLabel.textSize = (safeSp - 4.0f).coerceAtLeast(9.5f)
        titleLabel.textSize = safeSp + 1.5f
        hintLabel.textSize = (safeSp - 3.0f).coerceAtLeast(10.0f)
        reviewLabel.textSize = (safeSp - 3.0f).coerceAtLeast(10.0f)
        inboxRows.forEach { row ->
            row.textSize = (safeSp - 1.0f).coerceAtLeast(11.0f)
            row.setLineSpacing(0f, if (safeSp >= 18.0f) 1.0f else 1.02f)
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

    private class CountdownRingView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.1f * density
            color = RULE
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.8f * density
            color = ACCENT
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textAlign = Paint.Align.CENTER
            textSize = 12f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val arc = RectF()
        private var remainingMs = 0L
        private var totalMs = 1L
        private var endsAtMs = 0L

        fun setCountdown(remaining: Long, total: Long) {
            remainingMs = remaining.coerceAtLeast(0L)
            totalMs = total.coerceAtLeast(1L)
            endsAtMs = SystemClock.uptimeMillis() + remainingMs
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = progressPaint.strokeWidth / 2f + 1f
            arc.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(arc, -90f, 360f, false, trackPaint)
            val liveRemaining = if (endsAtMs > 0L) {
                (endsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            } else {
                remainingMs
            }
            val progress = (liveRemaining.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
            canvas.drawArc(arc, -90f, 360f * progress, false, progressPaint)
            val seconds = ((liveRemaining + 999L) / 1000L).coerceAtLeast(1L).toString()
            val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(seconds, width / 2f, baseline, textPaint)
            if (liveRemaining > 0L) postInvalidateOnAnimation()
        }
    }

    companion object {
        private val ACCENT = Color.rgb(92, 255, 136)
        private val TEXT = Color.rgb(210, 255, 222)
        private val DIM = Color.rgb(105, 148, 118)
        private val RULE = Color.rgb(40, 78, 50)

        private const val NOTIFICATION_PAGE_MAX_LINES = NotificationModel.DEFAULT_TEXT_MAX_LINES
    }
}
