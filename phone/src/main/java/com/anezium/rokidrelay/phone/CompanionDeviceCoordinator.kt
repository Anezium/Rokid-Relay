package com.anezium.rokidrelay.phone

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.util.Log
import java.util.regex.Pattern

/**
 * Companion Device Manager association with the glasses.
 *
 * Android 14+ treats FOREGROUND_SERVICE_TYPE_MICROPHONE as a while-in-use type: the relay
 * service cannot (re)acquire it while the app is in the background, which breaks the Android
 * CXR speech engine after every reboot or background restart. A CDM association (with device
 * presence observation) exempts the app from those background-start restrictions, so the mic
 * foreground type can be acquired even when the voice reply is triggered from the glasses.
 */
object CompanionDeviceCoordinator {
    private const val TAG = "RelayCompanion"
    private val GLASSES_NAME_PATTERN = Pattern.compile("rokid", Pattern.CASE_INSENSITIVE)

    fun hasAssociation(context: Context): Boolean = associatedAddresses(context).isNotEmpty()

    fun associatedAddresses(context: Context): List<String> {
        val manager = manager(context) ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.toList()
            }
        }.getOrElse {
            Log.w(TAG, "read associations failed: ${it.message}")
            emptyList()
        }
    }

    fun requestAssociation(activity: Activity, onFailure: (String) -> Unit) {
        val manager = manager(activity) ?: run {
            onFailure("Companion device service unavailable")
            return
        }
        val request = AssociationRequest.Builder().apply {
            addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setNamePattern(GLASSES_NAME_PATTERN)
                    .build(),
            )
            addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(GLASSES_NAME_PATTERN)
                    .build(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setDeviceProfile(AssociationRequest.DEVICE_PROFILE_GLASSES)
            }
        }.build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                launchChooser(activity, intentSender, onFailure)
            }

            @Deprecated("Android 12L delivers the chooser through onDeviceFound")
            override fun onDeviceFound(intentSender: IntentSender) {
                launchChooser(activity, intentSender, onFailure)
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
                onFailure(error?.toString().orEmpty().ifBlank { "Association failed" })
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, null)
        }.onFailure {
            Log.w(TAG, "associate call failed", it)
            onFailure(it.message ?: "Association failed")
        }
    }

    /** Returns the associated device address, or null when the result is not a success. */
    fun handleAssociationResult(context: Context, resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val address = extractAddress(data) ?: return null
        startObserving(context)
        Log.i(TAG, "associated with $address")
        return address
    }

    /** Idempotent; safe to call on every app open and after each association. */
    fun startObserving(context: Context) {
        val manager = manager(context) ?: return
        associatedAddresses(context).forEach { address ->
            runCatching {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }.onFailure {
                Log.w(TAG, "observe presence failed for $address: ${it.message}")
            }
        }
    }

    private fun launchChooser(activity: Activity, intentSender: IntentSender, onFailure: (String) -> Unit) {
        runCatching {
            activity.startIntentSenderForResult(
                intentSender,
                Constants.COMPANION_REQUEST_CODE,
                null,
                0,
                0,
                0,
            )
        }.onFailure {
            Log.w(TAG, "chooser launch failed", it)
            onFailure(it.message ?: "Could not open device chooser")
        }
    }

    private fun extractAddress(data: Intent?): String? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val association = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
            association?.deviceMacAddress?.let { return it.toString() }
        }
        @Suppress("DEPRECATION")
        return when (val device = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> device.address
            is ScanResult -> device.device?.address
            else -> null
        }
    }

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)
}
