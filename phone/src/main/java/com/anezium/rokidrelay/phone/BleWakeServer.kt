package com.anezium.rokidrelay.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID

object BleWakeServer {
    private const val TAG = "RelayBleWake"

    private val serviceUuid = UUID.fromString(Constants.BLE_WAKE_SERVICE_UUID)
    private val characteristicUuid = UUID.fromString(Constants.BLE_WAKE_CHARACTERISTIC_UUID)

    @Volatile private var appContext: Context? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var advertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            RelayBridge.setStatus("BLE wake armed")
            Log.i(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            RelayBridge.setStatus("BLE wake failed: advertise $errorCode")
            Log.w(TAG, "advertising failed code=$errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic?.uuid != characteristicUuid) {
                respond(device, requestId, responseNeeded, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }
            val accepted = handleWakePayload(value)
            respond(
                device,
                requestId,
                responseNeeded,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
            )
        }
    }

    fun ensureStarted(context: Context) {
        val cleanContext = context.applicationContext
        appContext = cleanContext
        if (!RelayStarter.isRelayEnabled(cleanContext)) {
            stop()
            return
        }
        if (!hasBlePermissions(cleanContext)) {
            RelayBridge.setStatus("BLE wake disabled: Bluetooth permission missing")
            return
        }
        val manager = cleanContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            RelayBridge.setStatus("BLE wake disabled: Bluetooth off")
            return
        }
        openGattServer(cleanContext, manager)
        startAdvertising(adapter)
    }

    fun stop() {
        val context = appContext
        val adapter = context?.getSystemService(BluetoothManager::class.java)?.adapter
        runCatching {
            if (hasAdvertisePermission(context)) {
                adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
        }.onFailure {
            Log.w(TAG, "stop advertising failed: ${it.message}")
        }
        advertising = false
        runCatching {
            if (hasConnectPermission(context)) {
                gattServer?.close()
            }
        }.onFailure {
            Log.w(TAG, "close GATT server failed: ${it.message}")
        }
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private fun openGattServer(context: Context, manager: BluetoothManager) {
        if (gattServer != null) return
        val server = manager.openGattServer(context, serverCallback)
        if (server == null) {
            RelayBridge.setStatus("BLE wake failed: GATT server unavailable")
            Log.w(TAG, "openGattServer returned null")
            return
        }
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        if (advertising) return
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            RelayBridge.setStatus("BLE wake failed: advertiser unavailable")
            Log.w(TAG, "bluetoothLeAdvertiser unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        device: BluetoothDevice?,
        requestId: Int,
        responseNeeded: Boolean,
        status: Int,
    ) {
        if (!responseNeeded || device == null) return
        runCatching {
            gattServer?.sendResponse(device, requestId, status, 0, null)
        }.onFailure {
            Log.w(TAG, "send response failed: ${it.message}")
        }
    }

    private fun handleWakePayload(value: ByteArray?): Boolean {
        val context = appContext ?: return false
        val payload = value?.toString(Charsets.UTF_8).orEmpty()
        val json = runCatching { JSONObject(payload) }.onFailure {
            Log.w(TAG, "wake payload parse failed len=${payload.length}: ${it.message}")
        }.getOrNull() ?: return false
        if (json.optString("type") != "wake_reply") return false
        val notificationId = json.optString("notificationId")
        if (notificationId.isBlank()) return false
        Log.i(TAG, "wake request received id=${notificationId.take(8)}")
        NotificationControl.refreshActiveNotificationsNow()
        return RelayStarter.wakeForBleReply(context, notificationId)
    }

    private fun hasBlePermissions(context: Context): Boolean =
        hasAdvertisePermission(context) && hasConnectPermission(context)

    private fun hasAdvertisePermission(context: Context?): Boolean =
        context != null &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            )

    private fun hasConnectPermission(context: Context?): Boolean =
        context != null &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            )
}
