package com.anezium.rokidrelay.glasses

enum class RelayDirection {
    LEFT,
    RIGHT,
}

enum class RelayInputSource {
    NORMAL,
    TWO_FINGER,
}

object RelayInputSettings {
    const val DEFAULT_COMBO = "LLRR"
    const val SWIPE_MODE_NORMAL = "normal"
    const val SWIPE_MODE_TWO_FINGER = "two_finger"
    const val DEFAULT_SWIPE_MODE = SWIPE_MODE_NORMAL
    const val MIN_COMBO_LENGTH = 2
    const val MAX_COMBO_LENGTH = 8

    fun sanitizeCombo(raw: String?): String {
        val combo = raw.orEmpty()
            .uppercase()
            .mapNotNull { char ->
                when (char) {
                    'L', 'G', '<' -> 'L'
                    'R', 'D', '>' -> 'R'
                    else -> null
                }
            }
            .joinToString("")
        return if (combo.length in MIN_COMBO_LENGTH..MAX_COMBO_LENGTH) combo else DEFAULT_COMBO
    }

    fun sanitizeSwipeMode(raw: String?): String =
        if (raw == SWIPE_MODE_TWO_FINGER) SWIPE_MODE_TWO_FINGER else SWIPE_MODE_NORMAL

    fun sourceEnabled(mode: String, source: RelayInputSource): Boolean =
        when (sanitizeSwipeMode(mode)) {
            SWIPE_MODE_TWO_FINGER -> source == RelayInputSource.TWO_FINGER
            else -> source == RelayInputSource.NORMAL
        }

    fun matchesCombo(buffer: List<RelayDirection>, combo: String): Boolean {
        val expected = sanitizeCombo(combo)
        if (buffer.size < expected.length) return false
        val offset = buffer.size - expected.length
        return expected.indices.all { index ->
            token(buffer[offset + index]) == expected[index]
        }
    }

    fun token(direction: RelayDirection): Char =
        if (direction == RelayDirection.LEFT) 'L' else 'R'
}
