package com.rokid.relay.phone

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

    private fun sanitize(durationMs: Long): Long =
        durationMs.coerceIn(0L, MAX_POPUP_DURATION_MS)

    companion object {
        const val DEFAULT_POPUP_DURATION_MS = 5_000L
        const val MAX_POPUP_DURATION_MS = 300_000L
        const val DEFAULT_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY = true
    }
}
