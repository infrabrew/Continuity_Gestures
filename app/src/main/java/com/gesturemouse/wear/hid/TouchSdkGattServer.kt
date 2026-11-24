package com.gesturemouse.wear.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.ImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Optional TouchSDK-inspired BLE GATT server for developer integrations (Unity, Python `bleak`,
 * Web Bluetooth, robotics, XR headsets).
 *
 * When enabled in Quick Settings, advertises a custom BLE GATT Service (with the 128-bit service
 * UUID in the primary advertising packet and the device name in the scan response to stay safely
 * within the 31-byte legacy BLE payload limit) exposing two Notify characteristics:
 * - **Gesture Event Characteristic**: 6-byte binary packet `[eventCode(1), extra(1), timestampMsLow32(4)]`
 * - **Orientation + IMU Characteristic**: 16-byte little-endian IEEE-754 float quaternion `[qx, qy, qz, qw]`
 *   throttled to ~30 Hz to preserve BLE bandwidth and battery life.
 */
class TouchSdkGattServer(
    private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gestureChar: BluetoothGattCharacteristic? = null
    private var orientationChar: BluetoothGattCharacteristic? = null

    private val gestureSubscribers = CopyOnWriteArraySet<BluetoothDevice>()
    private val orientationSubscribers = CopyOnWriteArraySet<BluetoothDevice>()

    private val _subscriberCount = MutableStateFlow(0)
    val subscriberCount: StateFlow<Int> = _subscriberCount.asStateFlow()

    @Volatile
    private var isStarted = false
    private var lastOrientationNotifyMs = 0L

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "TouchSDK BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "TouchSDK BLE advertising failed with error=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gestureSubscribers.remove(device)
                orientationSubscribers.remove(device)
                updateSubscriberCount()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (!hasConnectPermission()) return
            if (descriptor.uuid == CCCD_UUID) {
                val targetSet = subscriberSetFor(descriptor.characteristic?.uuid)
                val value = if (targetSet?.contains(device) == true) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (se: SecurityException) {
                    Log.w(TAG, "Missing permission sending CCCD read response", se)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null && (
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    )
                val targetSet = subscriberSetFor(descriptor.characteristic?.uuid)
                if (targetSet != null) {
                    if (enabled) targetSet.add(device) else targetSet.remove(device)
                    updateSubscriberCount()
                }
                if (responseNeeded && hasConnectPermission()) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (se: SecurityException) {
                        Log.w(TAG, "Missing permission sending CCCD response", se)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isStarted) return true
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled || !hasConnectPermission() || !hasAdvertisePermission()) {
            Log.w(TAG, "Cannot start TouchSDK BLE server: BT disabled or permissions missing")
            return false
        }

        return try {
            val server = bluetoothManager?.openGattServer(context, gattCallback) ?: return false
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val gChar = BluetoothGattCharacteristic(
                GESTURE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }

            val oChar = BluetoothGattCharacteristic(
                ORIENTATION_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }

            service.addCharacteristic(gChar)
            service.addCharacteristic(oChar)
            server.addService(service)

            gattServer = server
            gestureChar = gChar
            orientationChar = oChar

            advertiser = adapter.bluetoothLeAdvertiser
            val advSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()
            // Keep primary advertisement <= 31 bytes (flags + 128-bit UUID = 21 bytes);
            // place device name in scanResponse to avoid ADVERTISE_FAILED_DATA_TOO_LARGE.
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            advertiser?.startAdvertising(advSettings, advData, scanResponse, advertiseCallback)

            isStarted = true
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting TouchSDK GATT server", e)
            stop()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TouchSDK GATT server", e)
            stop()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isStarted && gattServer == null) return
        isStarted = false
        gestureSubscribers.clear()
        orientationSubscribers.clear()
        updateSubscriberCount()
        try {
            if (hasAdvertisePermission()) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
            if (hasConnectPermission()) {
                gattServer?.close()
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException while stopping TouchSDK GATT server", se)
        } finally {
            advertiser = null
            gattServer = null
            gestureChar = null
            orientationChar = null
        }
    }

    fun publishGesture(event: GestureEvent) {
        if (!isStarted || gestureSubscribers.isEmpty()) return
        val char = gestureChar ?: return

        val code: Byte = when (event) {
            is GestureEvent.SinglePinch -> 0x01
            is GestureEvent.DoublePinch -> 0x02
            is GestureEvent.PinchHoldStart -> 0x03
            is GestureEvent.PinchHoldEnd -> 0x04
            is GestureEvent.PalmUpPinch -> 0x05
            is GestureEvent.FlickUp -> 0x10
            is GestureEvent.FlickDown -> 0x11
            is GestureEvent.FlickLeft -> 0x12
            is GestureEvent.FlickRight -> 0x13
            is GestureEvent.WristTwist -> 0x20
            is GestureEvent.ModeSwitch -> 0x30
        }
        val extra: Byte = when (event) {
            is GestureEvent.WristTwist -> event.deltaDegrees.toInt().coerceIn(-127, 127).toByte()
            is GestureEvent.ModeSwitch -> if (event.forward) 0x01 else (-1).toByte()
            else -> 0x00
        }

        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(code)
            .put(extra)
            .putInt((event.timestampMs and 0xFFFFFFFFL).toInt())
            .array()

        notifySubscribers(gestureSubscribers, char, payload)
    }

    fun publishOrientation(sample: ImuSample) {
        if (!isStarted || orientationSubscribers.isEmpty()) return
        if (sample.timestampMs - lastOrientationNotifyMs < ORIENTATION_THROTTLE_MS) return
        lastOrientationNotifyMs = sample.timestampMs
        val char = orientationChar ?: return

        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(sample.quatX)
            .putFloat(sample.quatY)
            .putFloat(sample.quatZ)
            .putFloat(sample.quatW)
            .array()

        notifySubscribers(orientationSubscribers, char, payload)
    }

    private fun subscriberSetFor(charUuid: UUID?): CopyOnWriteArraySet<BluetoothDevice>? = when (charUuid) {
        GESTURE_CHAR_UUID -> gestureSubscribers
        ORIENTATION_CHAR_UUID -> orientationSubscribers
        else -> null
    }

    private fun updateSubscriberCount() {
        val unique = HashSet<String>(gestureSubscribers.size + orientationSubscribers.size)
        gestureSubscribers.forEach { unique.add(it.address) }
        orientationSubscribers.forEach { unique.add(it.address) }
        _subscriberCount.value = unique.size
    }

    @SuppressLint("MissingPermission")
    private fun notifySubscribers(
        subscribers: CopyOnWriteArraySet<BluetoothDevice>,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        val server = gattServer ?: return
        if (!hasConnectPermission()) return

        for (device in subscribers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed notifying TouchSDK subscriber ${device.address}", e)
            }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TouchSdkGattServer"
        private const val ORIENTATION_THROTTLE_MS = 33L // ~30 Hz

        val SERVICE_UUID: UUID = UUID.fromString("7B9F0001-4E2A-4D8C-9C1B-A3F100000001")
        val GESTURE_CHAR_UUID: UUID = UUID.fromString("7B9F0002-4E2A-4D8C-9C1B-A3F100000001")
        val ORIENTATION_CHAR_UUID: UUID = UUID.fromString("7B9F0003-4E2A-4D8C-9C1B-A3F100000001")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
