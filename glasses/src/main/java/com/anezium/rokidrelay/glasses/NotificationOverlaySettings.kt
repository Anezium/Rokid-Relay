package com.anezium.rokidrelay.glasses

import android.content.Context

object NotificationOverlaySettings {
    const val DEFAULT_Y_OFFSET_DP = 14
    const val MIN_Y_OFFSET_DP = 0
    const val MAX_Y_OFFSET_DP = 360
    const val DEFAULT_FONT_SIZE_SP = 15.0f
    const val MIN_FONT_SIZE_SP = 11.0f
    const val MAX_FONT_SIZE_SP = 24.0f

    private const val PREFS = "rokid_relay_notification_overlay"
    private const val PREF_Y_OFFSET_DP = "y_offset_dp"
    private const val PREF_FONT_SIZE_SP = "font_size_sp"

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

    fun fontSizeSp(context: Context): Float =
        sanitizeFontSizeSp(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(PREF_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP),
        )

    fun saveFontSizeSp(context: Context, value: Float): Float {
        val cleanValue = sanitizeFontSizeSp(value)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREF_FONT_SIZE_SP, cleanValue)
            .apply()
        return cleanValue
    }

    fun sanitizeYOffsetDp(value: Int): Int =
        value.coerceIn(MIN_Y_OFFSET_DP, MAX_Y_OFFSET_DP)

    fun sanitizeFontSizeSp(value: Float): Float =
        if (value.isNaN()) {
            DEFAULT_FONT_SIZE_SP
        } else {
            value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        }
}
