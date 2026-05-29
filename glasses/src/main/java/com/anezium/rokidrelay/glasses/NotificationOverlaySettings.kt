package com.anezium.rokidrelay.glasses

import android.content.Context

object NotificationOverlaySettings {
    const val DEFAULT_Y_OFFSET_DP = 14
    const val MIN_Y_OFFSET_DP = 0
    const val MAX_Y_OFFSET_DP = 360

    private const val PREFS = "rokid_relay_notification_overlay"
    private const val PREF_Y_OFFSET_DP = "y_offset_dp"

    fun yOffsetDp(context: Context): Int =
        sanitizeYOffsetDp(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PREF_Y_OFFSET_DP, DEFAULT_Y_OFFSET_DP),
        )

    fun saveYOffsetDp(context: Context, value: Int): Int {
        val cleanValue = sanitizeYOffsetDp(value)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_Y_OFFSET_DP, cleanValue)
            .apply()
        return cleanValue
    }

    fun sanitizeYOffsetDp(value: Int): Int =
        value.coerceIn(MIN_Y_OFFSET_DP, MAX_Y_OFFSET_DP)
}
