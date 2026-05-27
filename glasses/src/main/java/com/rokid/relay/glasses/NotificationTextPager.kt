package com.rokid.relay.glasses

object NotificationTextPager {
    fun pages(text: String): List<String> {
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
            val messageLines = estimatedVisualLines(message)
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

    fun page(text: String, index: Int): String {
        val pages = pages(text)
        return pages[index.coerceIn(0, pages.lastIndex)]
    }

    fun pageCount(text: String): Int = pages(text).size

    private fun estimatedVisualLines(message: String): Int =
        ((message.length + MAX_CHARS_PER_VISUAL_LINE - 1) / MAX_CHARS_PER_VISUAL_LINE)
            .coerceIn(1, MAX_VISUAL_LINES_PER_PAGE)

    private const val MAX_VISUAL_LINES_PER_PAGE = 4
    private const val MAX_CHARS_PER_VISUAL_LINE = 46
}
