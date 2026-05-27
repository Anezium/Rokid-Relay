package com.rokid.relay.phone

internal object TestMessageFactory {
    const val DEFAULT_THREAD_MESSAGE_COUNT = 3
    const val MAX_MESSAGES_PER_POST = 12
    const val DEFAULT_TEST_MESSAGE = "Replyable test message from the phone."
    const val LONG_TEST_MESSAGE =
        "Long test message for Rokid Relay. It should stay readable on the glasses without filling the whole view: " +
            "we keep several useful lines, then the rest should be paged or trimmed cleanly. This extra sentence " +
            "exists to stress pagination, height limits, and real notification comfort."

    val burstMessageCount: Int
        get() = BURST_MESSAGES.size

    private val BURST_MESSAGES = listOf(
        "Can you check the build when you have a minute?",
        "I pushed a few messages in a row to simulate a busy chat.",
        "The HUD should group recent messages into readable pages.",
        "The inbox should let us move page by page without jumping.",
        "This one adds enough content for another readable page.",
        "Right swipe: next page, with debounce.",
        "Left swipe: previous page, without weird wrapping.",
        "Tap inside the detail view should keep voice reply available.",
        "Back should return to the notification list.",
        "Message ten checks that older content still stays ordered.",
        "Another short message, like a real burst.",
        "Final message: this should appear after the earlier ones.",
    )
    private val LONG_BURST_MESSAGES = listOf(
        "This is a deliberately long diagnostic message. It should take multiple lines on the glasses, so the pager can prove that it splits content by visible size instead of raw message count.",
        "Second long message in the same thread. After a reply, adding this should create a fresh Android notification while keeping the previous test history intact.",
        "Third long message with enough text to stress the popup. The goal is to make sure a couple of large entries fill the first page and push later entries to the next page.",
        "Fourth long message for inbox testing. It checks that page order remains chronological even when the notification contains several heavy messages.",
    )
    private val SENDERS = listOf("Mika", "Nina", "Sam", "Alex")

    fun generatedMessages(
        threadIndex: Int,
        startIndex: Int,
        count: Int,
        longMessages: Boolean,
    ): List<TestThreadMessage> {
        val source = if (longMessages) LONG_BURST_MESSAGES else BURST_MESSAGES
        val now = System.currentTimeMillis()
        return (0 until count.coerceIn(1, MAX_MESSAGES_PER_POST)).map { offset ->
            val ordinal = startIndex + offset + 1
            TestThreadMessage(
                text = "T$threadIndex #$ordinal: ${source[(ordinal - 1) % source.size]}",
                sender = SENDERS[(threadIndex + ordinal - 2) % SENDERS.size],
                timestamp = now + offset * 1_000L,
            )
        }
    }
}
