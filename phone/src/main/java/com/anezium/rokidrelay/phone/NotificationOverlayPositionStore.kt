package com.anezium.rokidrelay.phone

import android.content.Context

class NotificationOverlayPositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun yOffsetDp(): Int =
        sanitizeYOffsetDp(
            prefs.getInt(
                Constants.PREF_NOTIFICATION_OVERLAY_Y_OFFSET_DP,
                DEFAULT_Y_OFFSET_DP,
            ),
        )

    fun saveYOffsetDp(value: Int): Int {
        val cleanValue = sanitizeYOffsetDp(value)
        prefs.edit()
            .putInt(Constants.PREF_NOTIFICATION_OVERLAY_Y_OFFSET_DP, cleanValue)
            .apply()
        return cleanValue
    }

    companion object {
        const val DEFAULT_Y_OFFSET_DP = 14
        const val MIN_Y_OFFSET_DP = 0
        const val MAX_Y_OFFSET_DP = 360

        fun sanitizeYOffsetDp(value: Int): Int =
            value.coerceIn(MIN_Y_OFFSET_DP, MAX_Y_OFFSET_DP)
    }
}
