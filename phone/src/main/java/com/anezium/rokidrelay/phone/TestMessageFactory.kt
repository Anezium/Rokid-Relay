package com.anezium.rokidrelay.phone

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
    private val SHORT_THREAD_SETS = listOf(
        listOf(
            "Can you check the build when you have a minute?",
            "I pushed a few messages in a row to simulate a busy chat.",
            "The HUD should group recent messages into readable pages.",
            "The inbox should let us move page by page without jumping.",
        ),
        listOf(
            "Lunch moved to 12:45, same place as yesterday.",
            "I left the badge on your desk next to the charger.",
            "Can you bring the small USB cable too?",
            "The elevator is slow today, give yourself two extra minutes.",
        ),
        listOf(
            "The mockup looks cleaner with the compact tab bar.",
            "Try the darker stroke on the notification card.",
            "The title spacing is fixed on my side now.",
            "That bottom padding finally feels like a phone shell.",
        ),
        listOf(
            "Train platform changed again, now it says B.",
            "I found seats near the front window.",
            "Battery is at 38 percent, should be fine for the demo.",
            "Send me the address when you get a chance.",
        ),
        listOf(
            "Support ticket says the reply action got stuck.",
            "They sent three messages after the Android reply.",
            "The notification clear retry should catch that state.",
            "I will keep the thread open for one more test.",
        ),
        listOf(
            "The launch checklist is down to two items.",
            "Screenshots are uploaded in the shared folder.",
            "Can you verify the release notes title?",
            "I am making one final pass on the settings wording.",
        ),
    )
    private val LONG_THREAD_SETS = listOf(
        listOf(
            "Long cafe thread message. We changed the meeting time twice, added a reminder about the charger, and still need the glasses to keep the whole conversation readable across pages.",
            "Another cafe thread entry with enough detail to fill several lines. It mentions the badge, the desk, the cable, and a tiny bit of timing pressure for a realistic notification.",
        ),
        listOf(
            "Long design thread message. This one talks about compact navigation, softer card spacing, and a bottom bar that should feel like it belongs inside a rounded phone frame.",
            "Second design note with extra words on typography, padding, visual weight, and how cramped diagnostic controls should still stay readable when the keyboard appears.",
        ),
        listOf(
            "Long travel thread message. The platform changed, the carriage moved, the battery is lower than expected, and someone still needs the address before the demo starts.",
            "Second travel update with enough text to exercise pagination while sounding clearly different from the support, design, and launch diagnostic threads.",
        ),
        listOf(
            "Long support thread message. Android remote reply can leave the source notification in a weird state, so this diagnostic message is intentionally verbose and reply-focused.",
            "Second support update that describes a stuck notification, a clear retry, and follow-up messages that should arrive in order instead of disappearing after a reply.",
        ),
    )
    private val SENDERS_BY_THREAD = listOf(
        listOf("Mika", "Nina", "Sam", "Alex"),
        listOf("Rae", "Theo", "June", "Iris"),
        listOf("Noah", "Lena", "Omar", "Vera"),
        listOf("Kai", "Maya", "Eli", "Zoe"),
    )

    fun generatedMessages(
        threadIndex: Int,
        startIndex: Int,
        count: Int,
        longMessages: Boolean,
    ): List<TestThreadMessage> {
        val source = messageSource(threadIndex, longMessages)
        val senders = SENDERS_BY_THREAD[positiveMod(threadIndex - 1, SENDERS_BY_THREAD.size)]
        val now = System.currentTimeMillis()
        return (0 until count.coerceIn(1, MAX_MESSAGES_PER_POST)).map { offset ->
            val ordinal = startIndex + offset + 1
            val messageIndex = positiveMod(ordinal - 1, source.size)
            TestThreadMessage(
                text = "T$threadIndex #$ordinal: ${source[messageIndex]}",
                sender = senders[positiveMod(threadIndex + ordinal - 2, senders.size)],
                timestamp = now + offset * 1_000L,
            )
        }
    }

    private fun messageSource(threadIndex: Int, longMessages: Boolean): List<String> {
        if (longMessages) {
            val customSet = LONG_THREAD_SETS[positiveMod(threadIndex - 1, LONG_THREAD_SETS.size)]
            return customSet + LONG_BURST_MESSAGES
        }
        val customSet = SHORT_THREAD_SETS[positiveMod(threadIndex - 1, SHORT_THREAD_SETS.size)]
        return customSet + BURST_MESSAGES
    }

    private fun positiveMod(value: Int, size: Int): Int =
        ((value % size) + size) % size
}
