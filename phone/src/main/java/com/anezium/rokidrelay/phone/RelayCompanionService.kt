package com.anezium.rokidrelay.phone

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log

/**
 * Bound by the system while the associated glasses are in range. While this binding is
 * active the app holds companion-device exemptions, so this is the right moment to (re)start
 * the relay and re-acquire the microphone foreground type that background starts cannot get.
 *
 * The framework picks one callback depending on the platform version: Android 16+ delivers
 * DevicePresenceEvent, 13-15 the AssociationInfo variant, and older builds the plain address.
 * Overriding a variant stops the default forwarding chain, so all of them are implemented
 * and routed to the same idempotent handler.
 */
class RelayCompanionService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            -> glassesPresent("presence event ${event.event}")
            else -> Log.i(TAG, "glasses presence event ${event.event}")
        }
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        glassesPresent("association ${associationInfo.id}")
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "glasses out of range: association ${associationInfo.id}")
    }

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceAppeared(address: String) {
        glassesPresent(address)
    }

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "glasses out of range: $address")
    }

    private fun glassesPresent(source: String) {
        Log.i(TAG, "glasses present: $source")
        if (RelayStarter.isRelayEnabled(this)) {
            RelayBridge.setStatus("glasses present: relay armed")
        }
    }

    companion object {
        private const val TAG = "RelayCompanion"
    }
}
