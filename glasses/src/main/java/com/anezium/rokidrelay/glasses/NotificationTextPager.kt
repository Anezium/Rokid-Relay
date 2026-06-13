package com.anezium.rokidrelay.glasses

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

object NotificationTextPager {
    data class LayoutSpec(
        val widthPx: Int,
        val maxLines: Int = DEFAULT_MAX_LINES_PER_PAGE,
        val lineSpacingAddPx: Float = DEFAULT_LINE_SPACING_ADD_PX,
        val lineSpacingMultiplier: Float = DEFAULT_LINE_SPACING_MULTIPLIER,
    )

    fun pages(
        text: String,
        fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
    ): List<String> =
        pages(
            text = text,
            paint = defaultPaint(fontSizeSp),
            spec = defaultLayoutSpec(fontSizeSp),
        )

    fun pages(
        text: String,
        fontSizeSp: Float,
        maxLines: Int,
    ): List<String> =
        pages(
            text = text,
            paint = defaultPaint(fontSizeSp),
            spec = defaultLayoutSpec(fontSizeSp).copy(maxLines = maxLines.coerceAtLeast(1)),
        )

    fun pages(
        text: String,
        paint: TextPaint,
        spec: LayoutSpec,
    ): List<String> {
        val cleanText = normalize(text)
        if (cleanText.isBlank()) return listOf("")

        val layout = layout(cleanText, paint, spec)
        if (layout.lineCount <= spec.maxLines) return listOf(cleanText)

        val pages = ArrayList<String>()
        var startLine = 0
        while (startLine < layout.lineCount) {
            val endLine = (startLine + spec.maxLines - 1).coerceAtMost(layout.lineCount - 1)
            val start = layout.getLineStart(startLine)
            val end = layout.getLineEnd(endLine).coerceAtMost(cleanText.length)
            val page = cleanText.substring(start, end).trim()
            if (page.isNotBlank()) pages += page
            startLine = endLine + 1
        }
        return pages.ifEmpty { listOf("") }
    }

    fun page(
        text: String,
        index: Int,
        fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
    ): String {
        val pages = pages(text, fontSizeSp)
        return pages[index.coerceIn(0, pages.lastIndex)]
    }

    fun page(
        text: String,
        index: Int,
        paint: TextPaint,
        spec: LayoutSpec,
    ): String {
        val pages = pages(text, paint, spec)
        return pages[index.coerceIn(0, pages.lastIndex)]
    }

    fun pageCount(
        text: String,
        fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
    ): Int = pages(text, fontSizeSp).size

    fun pageCount(
        text: String,
        fontSizeSp: Float,
        maxLines: Int,
    ): Int = pages(text, fontSizeSp, maxLines).size

    fun pageCount(
        text: String,
        paint: TextPaint,
        spec: LayoutSpec,
    ): Int = pages(text, paint, spec).size

    fun measuredLineCount(
        text: String,
        paint: TextPaint,
        spec: LayoutSpec,
    ): Int = layout(normalize(text), paint, spec).lineCount.coerceAtLeast(1)

    fun defaultPaint(fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = NotificationOverlaySettings.sanitizeFontSizeSp(fontSizeSp) *
                ROKID_FALLBACK_SCALED_DENSITY
        }

    fun defaultLayoutSpec(fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP): LayoutSpec =
        LayoutSpec(
            widthPx = DEFAULT_CONTENT_WIDTH_PX,
            maxLines = DEFAULT_MAX_LINES_PER_PAGE,
        )

    private fun layout(text: String, paint: TextPaint, spec: LayoutSpec): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, spec.widthPx.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(spec.lineSpacingAddPx, spec.lineSpacingMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()

    private fun normalize(text: String): String =
        text
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private const val DEFAULT_MAX_LINES_PER_PAGE = 9
    private const val DEFAULT_CONTENT_WIDTH_PX = 414
    private const val ROKID_FALLBACK_SCALED_DENSITY = 1.5f
    private const val DEFAULT_LINE_SPACING_ADD_PX = 1f
    private const val DEFAULT_LINE_SPACING_MULTIPLIER = 1.02f
}
