package com.anezium.rokidrelay.phone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class NotificationDisplayPanel(
    context: Context,
    private val onNotice: (String) -> Unit,
) : LinearLayout(context) {
    private val summary = bodyText()
    private val preview = NotificationOverlayPositionView(context)
    private var savedYOffsetDp = NotificationOverlayPositionStore.DEFAULT_Y_OFFSET_DP
    private var draftYOffsetDp = NotificationOverlayPositionStore.DEFAULT_Y_OFFSET_DP

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = roundedRect(COLOR_PANEL, COLOR_STROKE, radius = 10)

        addView(TextView(context).apply {
            text = "Display"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(COLOR_TEXT)
        }, matchWrap())
        addView(View(context), LinearLayout.LayoutParams(match(), dp(8)))
        addView(summary, matchWrap())
        addView(preview, matchWrap(top = 12))
        addView(
            buttonRow(
                smallButton("Save", ButtonTone.Primary) { savePosition() },
                smallButton("Reset", ButtonTone.Secondary) { resetPosition() },
            ),
            matchWrap(top = 12),
        )

        savedYOffsetDp = NotificationOverlayPositionStore(context).yOffsetDp()
        draftYOffsetDp = savedYOffsetDp
        preview.yOffsetDp = draftYOffsetDp
        preview.onYOffsetChanged = { value ->
            draftYOffsetDp = value
            updateSummary()
        }
        updateSummary()
    }

    private fun savePosition() {
        val cleanOffset = NotificationOverlayPositionStore.sanitizeYOffsetDp(draftYOffsetDp)
        draftYOffsetDp = cleanOffset
        preview.yOffsetDp = cleanOffset
        if (!RelayBridge.saveNotificationOverlayPosition(cleanOffset)) {
            updateSummary()
            onNotice("Glasses link not ready. Position not saved.")
            return
        }
        savedYOffsetDp = NotificationOverlayPositionStore(context).saveYOffsetDp(cleanOffset)
        updateSummary()
        onNotice("Notification display saved to glasses")
    }

    private fun resetPosition() {
        draftYOffsetDp = NotificationOverlayPositionStore.DEFAULT_Y_OFFSET_DP
        preview.yOffsetDp = draftYOffsetDp
        updateSummary()
        onNotice("Default notification position ready")
    }

    private fun updateSummary() {
        val cleanDraft = NotificationOverlayPositionStore.sanitizeYOffsetDp(draftYOffsetDp)
        val dirty = cleanDraft != savedYOffsetDp
        summary.text = if (dirty) {
            "Notification popup: ${cleanDraft}dp from top. Unsaved."
        } else {
            "Notification popup: ${savedYOffsetDp}dp from top. Saved."
        }
        summary.setTextColor(if (dirty) COLOR_AMBER else COLOR_MUTED)
    }

    private fun bodyText(): TextView =
        TextView(context).apply {
            textSize = 13f
            includeFontPadding = false
            setTextColor(COLOR_MUTED)
            setLineSpacing(0f, 1.1f)
        }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
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
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(buttonTextColor(tone))
            background = buttonBackground(tone)
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { onClick() }
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
            ButtonTone.Secondary -> COLOR_TEXT
        }

    private fun roundedRect(color: Int, strokeColor: Int, radius: Int, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            setStroke(dp(strokeWidth), strokeColor)
            cornerRadius = dp(radius).toFloat()
        }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class ButtonTone {
        Primary,
        Secondary,
    }

    private companion object {
        val COLOR_PANEL: Int = Color.rgb(8, 18, 11)
        val COLOR_PANEL_ALT: Int = Color.rgb(11, 29, 16)
        val COLOR_FIELD: Int = Color.rgb(5, 13, 8)
        val COLOR_DISABLED: Int = Color.rgb(16, 24, 18)
        val COLOR_ACTION: Int = Color.rgb(12, 44, 22)
        val COLOR_ACTION_PRESSED: Int = Color.rgb(17, 61, 31)
        val COLOR_PHOSPHOR: Int = Color.rgb(113, 255, 151)
        val COLOR_PHOSPHOR_DIM: Int = Color.rgb(42, 122, 62)
        val COLOR_STROKE: Int = Color.rgb(24, 67, 36)
        val COLOR_TEXT: Int = Color.rgb(224, 255, 232)
        val COLOR_MUTED: Int = Color.rgb(132, 178, 145)
        val COLOR_AMBER: Int = Color.rgb(230, 190, 92)
    }
}
