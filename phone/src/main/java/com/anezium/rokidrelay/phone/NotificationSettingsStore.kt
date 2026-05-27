package com.anezium.rokidrelay.phone

import android.content.Context

class NotificationSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun popupDurationMs(): Long =
        sanitize(prefs.getLong(Constants.PREF_NOTIFICATION_POPUP_DURATION_MS, DEFAULT_POPUP_DURATION_MS))

    fun popupDurationSeconds(): Long =
        popupDurationMs() / 1_000L

    fun savePopupDurationMs(durationMs: Long) {
        prefs.edit().putLong(Constants.PREF_NOTIFICATION_POPUP_DURATION_MS, sanitize(durationMs)).apply()
    }

    fun savePopupDurationSeconds(seconds: Long) {
        savePopupDurationMs(seconds.coerceIn(0L, MAX_POPUP_DURATION_MS / 1_000L) * 1_000L)
    }

    fun clearPhoneNotificationAfterReply(): Boolean =
        prefs.getBoolean(
            Constants.PREF_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY,
            DEFAULT_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY,
        )

    fun saveClearPhoneNotificationAfterReply(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY, enabled).apply()
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
        const val DEFAULT_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY = true
        const val DEFAULT_INBOX_ENTRY_LIMIT = 16
        const val MIN_INBOX_ENTRY_LIMIT = 4
        const val MAX_INBOX_ENTRY_LIMIT = 32
        const val DEFAULT_THREAD_MESSAGE_LIMIT = 20
        const val MIN_THREAD_MESSAGE_LIMIT = 4
        const val MAX_THREAD_MESSAGE_LIMIT = 40
    }
}
