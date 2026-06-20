package com.anezium.rokidrelay.phone

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RelayAutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            -> {
                if (!RelayStarter.isRelayEnabled(context)) {
                    RelayBridge.setStatus("relay not started: disabled")
                    return
                }
                CompanionDeviceCoordinator.startObserving(context)
                RelayBridge.setStatus("relay armed after ${action.substringAfterLast('.')}")
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
                ) {
                    if (RelayStarter.isRelayEnabled(context)) {
                        RelayBridge.setStatus("relay armed after BLUETOOTH_ON")
                    }
                }
            }
        }
    }
}
