package com.anezium.rokidrelay.phone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class SttModelOptionRow(
    context: Context,
    val engine: SpeechToTextEngine,
    onClick: (SpeechToTextEngine) -> Unit,
) : LinearLayout(context) {
    private val titleView = TextView(context).apply {
        text = engine.choiceLabel()
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(COLOR_TEXT)
    }
    private val descriptionView = bodyText().apply {
        text = engine.choiceDescription
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val badgeViews = engine.choiceBadges.map(::modelBadge)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minimumHeight = dp(82)
        background = roundedRect(COLOR_FIELD, COLOR_STROKE, radius = 8)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick(engine) }

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, wrap(), 1f))
        }, matchWrap())

        addView(descriptionView, matchWrap(top = 6))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            badgeViews.forEachIndexed { index, badge ->
                addView(badge, LinearLayout.LayoutParams(wrap(), dp(24)).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        }, matchWrap(top = 8))
    }

    fun bindSelected(selected: Boolean) {
        background = roundedRect(
            if (selected) COLOR_SELECTED else COLOR_FIELD,
            if (selected) COLOR_PHOSPHOR_DIM else COLOR_STROKE,
            radius = 8,
            strokeWidth = if (selected) 2 else 1,
        )
        titleView.setTextColor(if (selected) COLOR_PHOSPHOR else COLOR_TEXT)
        descriptionView.setTextColor(if (selected) COLOR_TEXT else COLOR_MUTED)
        badgeViews.forEach { badge ->
            badge.setTextColor(if (selected) COLOR_PHOSPHOR else COLOR_MUTED)
            badge.background = roundedRect(
                if (selected) COLOR_PANEL_ALT else COLOR_APP_BG,
                if (selected) COLOR_PHOSPHOR_DIM else COLOR_STROKE,
                radius = 8,
            )
        }
    }

    private fun bodyText(): TextView =
        TextView(context).apply {
            textSize = 12.5f
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(COLOR_MUTED)
        }

    private fun modelBadge(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(COLOR_MUTED)
            background = roundedRect(COLOR_APP_BG, COLOR_STROKE, radius = 8)
        }

    private fun SpeechToTextEngine.choiceLabel(): String =
        displayName.removePrefix("${provider.displayName} ")

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

    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val COLOR_APP_BG: Int = Color.rgb(4, 10, 6)
        val COLOR_FIELD: Int = Color.rgb(5, 13, 8)
        val COLOR_SELECTED: Int = Color.rgb(10, 35, 18)
        val COLOR_PANEL_ALT: Int = Color.rgb(11, 29, 16)
        val COLOR_PHOSPHOR: Int = Color.rgb(113, 255, 151)
        val COLOR_PHOSPHOR_DIM: Int = Color.rgb(42, 122, 62)
        val COLOR_STROKE: Int = Color.rgb(24, 67, 36)
        val COLOR_TEXT: Int = Color.rgb(224, 255, 232)
        val COLOR_MUTED: Int = Color.rgb(132, 178, 145)
    }
}
