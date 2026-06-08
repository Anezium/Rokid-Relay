package com.anezium.rokidrelay.glasses

object NotificationTextPager {
    fun pages(
        text: String,
        fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
    ): List<String> {
        val messages = text
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (messages.isEmpty()) return listOf("")

        val chunks = mutableListOf<String>()
        val current = ArrayDeque<String>()
        var currentLines = 0

        messages.forEach { message ->
            val messageLines = estimatedVisualLines(message, fontSizeSp)
            if (current.isNotEmpty() && currentLines + messageLines > MAX_VISUAL_LINES_PER_PAGE) {
                chunks += current.joinToString("\n")
                current.clear()
                currentLines = 0
            }
            current.addLast(message)
            currentLines += messageLines
            if (currentLines >= MAX_VISUAL_LINES_PER_PAGE) {
                chunks += current.joinToString("\n")
                current.clear()
                currentLines = 0
            }
        }
        if (current.isNotEmpty()) chunks += current.joinToString("\n")
        return chunks
    }

    fun page(
        text: String,
        index: Int,
        fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
    ): String {
        val pages = pages(text, fontSizeSp)
        return pages[index.coerceIn(0, pages.lastIndex)]
    }

    fun pageCount(
        text: String,
        fontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
    ): Int = pages(text, fontSizeSp).size

    private fun estimatedVisualLines(message: String, fontSizeSp: Float): Int {
        val charsPerLine = charsPerVisualLine(fontSizeSp)
        return ((message.length + charsPerLine - 1) / charsPerLine)
            .coerceIn(1, MAX_VISUAL_LINES_PER_PAGE)
    }

    private fun charsPerVisualLine(fontSizeSp: Float): Int {
        val safeSp = NotificationOverlaySettings.sanitizeFontSizeSp(fontSizeSp)
        return Math.round(BASE_CHARS_PER_VISUAL_LINE * BASE_FONT_SIZE_SP / safeSp)
            .coerceIn(MIN_CHARS_PER_VISUAL_LINE, MAX_CHARS_PER_VISUAL_LINE)
    }

    private const val MAX_VISUAL_LINES_PER_PAGE = 6
    private const val BASE_FONT_SIZE_SP = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP
    private const val BASE_CHARS_PER_VISUAL_LINE = 46f
    private const val MIN_CHARS_PER_VISUAL_LINE = 28
    private const val MAX_CHARS_PER_VISUAL_LINE = 58
}
