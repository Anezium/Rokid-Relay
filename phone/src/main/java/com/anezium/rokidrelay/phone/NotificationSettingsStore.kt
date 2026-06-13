package com.anezium.rokidrelay.phone

import android.content.Context

class NotificationSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun popupDurationMs(): Long =
        sanitize(prefs.getLong(Constants.PREF_NOTIFICATION_POPUP_DURATION_MS, DEFAULT_POPUP_DURATION_MS))

    fun popupDurationSeconds(): Long =
        popupDurationMs() / 1_000L

    fun fontSizeSp(): Float =
        sanitizeFontSizeSp(
            prefs.getFloat(
                Constants.PREF_NOTIFICATION_FONT_SIZE_SP,
                DEFAULT_FONT_SIZE_SP,
            ),
        )

    fun savePopupDurationMs(durationMs: Long) {
        prefs.edit().putLong(Constants.PREF_NOTIFICATION_POPUP_DURATION_MS, sanitize(durationMs)).apply()
    }

    fun savePopupDurationSeconds(seconds: Long) {
        savePopupDurationMs(seconds.coerceIn(0L, MAX_POPUP_DURATION_MS / 1_000L) * 1_000L)
    }

    fun saveFontSizeSp(value: Float): Float {
        val cleanValue = sanitizeFontSizeSp(value)
        prefs.edit().putFloat(Constants.PREF_NOTIFICATION_FONT_SIZE_SP, cleanValue).apply()
        return cleanValue
    }

    fun clearPhoneNotificationAfterReply(): Boolean =
        prefs.getBoolean(
            Constants.PREF_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY,
            DEFAULT_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY,
        )

    fun saveClearPhoneNotificationAfterReply(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY, enabled).apply()
    }

    fun pauseForwardingWhenPhoneScreenOn(): Boolean =
        prefs.getBoolean(
            Constants.PREF_PAUSE_NOTIFICATION_FORWARDING_WHEN_SCREEN_ON,
            DEFAULT_PAUSE_FORWARDING_WHEN_PHONE_SCREEN_ON,
        )

    fun savePauseForwardingWhenPhoneScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_PAUSE_NOTIFICATION_FORWARDING_WHEN_SCREEN_ON, enabled).apply()
    }

    fun notificationImagePreviewsEnabled(): Boolean =
        prefs.getBoolean(
            Constants.PREF_NOTIFICATION_IMAGE_PREVIEWS_ENABLED,
            DEFAULT_NOTIFICATION_IMAGE_PREVIEWS_ENABLED,
        )

    fun saveNotificationImagePreviewsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_NOTIFICATION_IMAGE_PREVIEWS_ENABLED, enabled).apply()
    }

    fun inboxEntryLimit(): Int =
        sanitizeInboxEntryLimit(prefs.getInt(Constants.PREF_INBOX_ENTRY_LIMIT, DEFAULT_INBOX_ENTRY_LIMIT))

    fun saveInboxEntryLimit(limit: Int) {
        prefs.edit().putInt(Constants.PREF_INBOX_ENTRY_LIMIT, sanitizeInboxEntryLimit(limit)).apply()
    }

    fun threadMessageLimit(): Int =
        sanitizeThreadMessageLimit(prefs.getInt(Constants.PREF_THREAD_MESSAGE_LIMIT, DEFAULT_THREAD_MESSAGE_LIMIT))

    fun saveThreadMessageLimit(limit: Int) {
        prefs.edit().putInt(Constants.PREF_THREAD_MESSAGE_LIMIT, sanitizeThreadMessageLimit(limit)).apply()
    }

    private fun sanitize(durationMs: Long): Long =
        durationMs.coerceIn(0L, MAX_POPUP_DURATION_MS)

    private fun sanitizeInboxEntryLimit(limit: Int): Int =
        limit.coerceIn(MIN_INBOX_ENTRY_LIMIT, MAX_INBOX_ENTRY_LIMIT)

    private fun sanitizeThreadMessageLimit(limit: Int): Int =
        limit.coerceIn(MIN_THREAD_MESSAGE_LIMIT, MAX_THREAD_MESSAGE_LIMIT)

    companion object {
        const val DEFAULT_POPUP_DURATION_MS = 5_000L
        const val MAX_POPUP_DURATION_MS = 300_000L
        const val DEFAULT_FONT_SIZE_SP = 15.0f
        const val MIN_FONT_SIZE_SP = 11.0f
        const val MAX_FONT_SIZE_SP = 24.0f
        const val DEFAULT_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY = true
        const val DEFAULT_PAUSE_FORWARDING_WHEN_PHONE_SCREEN_ON = false
        const val DEFAULT_NOTIFICATION_IMAGE_PREVIEWS_ENABLED = false
        const val DEFAULT_INBOX_ENTRY_LIMIT = 16
        const val MIN_INBOX_ENTRY_LIMIT = 4
        const val MAX_INBOX_ENTRY_LIMIT = 32
        const val DEFAULT_THREAD_MESSAGE_LIMIT = 20
        const val MIN_THREAD_MESSAGE_LIMIT = 4
        const val MAX_THREAD_MESSAGE_LIMIT = 40

        fun sanitizeFontSizeSp(value: Float): Float =
            if (value.isNaN()) {
                DEFAULT_FONT_SIZE_SP
            } else {
                value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
            }
    }
}
