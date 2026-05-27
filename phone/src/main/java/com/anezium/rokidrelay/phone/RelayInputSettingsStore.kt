package com.anezium.rokidrelay.phone

import android.content.Context

class RelayInputSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun inputCombo(): String =
        normalizeCombo(prefs.getString(Constants.PREF_INPUT_COMBO, DEFAULT_COMBO)) ?: DEFAULT_COMBO

    fun swipeMode(): String =
        sanitizeSwipeMode(prefs.getString(Constants.PREF_SWIPE_MODE, DEFAULT_SWIPE_MODE))

    fun saveInputCombo(raw: String): String? {
        val combo = normalizeCombo(raw) ?: return null
        prefs.edit().putString(Constants.PREF_INPUT_COMBO, combo).apply()
        return combo
    }

    fun saveSwipeMode(mode: String) {
        prefs.edit().putString(Constants.PREF_SWIPE_MODE, sanitizeSwipeMode(mode)).apply()
    }

    companion object {
        const val DEFAULT_COMBO = "LLRR"
        const val SWIPE_MODE_NORMAL = "normal"
        const val SWIPE_MODE_TWO_FINGER = "two_finger"
        const val DEFAULT_SWIPE_MODE = SWIPE_MODE_NORMAL
        const val MIN_COMBO_LENGTH = 2
        const val MAX_COMBO_LENGTH = 8

        fun normalizeCombo(raw: String?): String? {
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
            return combo.takeIf { it.length in MIN_COMBO_LENGTH..MAX_COMBO_LENGTH }
        }

        fun sanitizeSwipeMode(raw: String?): String =
            if (raw == SWIPE_MODE_TWO_FINGER) SWIPE_MODE_TWO_FINGER else SWIPE_MODE_NORMAL

        fun displayCombo(combo: String): String =
            normalizeCombo(combo)
                ?.map { it.toString() }
                ?.joinToString(" ")
                ?: DEFAULT_COMBO.map { it.toString() }.joinToString(" ")
    }
}
