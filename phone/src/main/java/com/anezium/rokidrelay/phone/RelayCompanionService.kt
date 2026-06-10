package com.anezium.rokidrelay.phone

import android.companion.CompanionDeviceService
import android.util.Log

/**
 * Bound by the system while the associated glasses are in range. While this binding is
 * active the app holds companion-device exemptions, so this is the right moment to (re)start
 * the relay and re-acquire the microphone foreground type that background starts cannot get.
 */
class RelayCompanionService : CompanionDeviceService() {
    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "glasses present: $address")
        RelayStarter.startIfReady(this, "glasses_present")
        RelayService.refreshForeground()
    }

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "glasses out of range: $address")
    }

    companion object {
        private const val TAG = "RelayCompanion"
    }
}
