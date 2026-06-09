package com.anezium.rokidrelay.phone

import android.content.Context
import android.os.PowerManager

object NotificationForwardingPolicy {
    fun isPaused(context: Context): Boolean =
        NotificationSettingsStore(context).pauseForwardingWhenPhoneScreenOn() && isPhoneScreenOn(context)

    fun isPhoneScreenOn(context: Context): Boolean {
        val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive == true
    }
}
