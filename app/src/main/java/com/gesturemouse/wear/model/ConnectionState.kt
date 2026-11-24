package com.gesturemouse.wear.model

/**
 * Lifecycle state of the Bluetooth HID device profile connection.
 */
sealed interface ConnectionState {
    /** Bluetooth HID profile registered or idle; no host connected. */
    data object Disconnected : ConnectionState

    /** Watch is actively discoverable so a host (PC/Mac/Android/iPad/TV) can pair. */
    data class Discoverable(val remainingSeconds: Int = 180) : ConnectionState

    /** Initiating connection to a previously bonded host device. */
    data class Connecting(val deviceName: String, val deviceAddress: String) : ConnectionState

    /** Active Bluetooth HID session streaming cursor and gesture reports. */
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState

    /** Recoverable error (e.g., Bluetooth off, missing permission, HID profile unavailable). */
    data class Error(val message: String) : ConnectionState

    val isConnected: Boolean
        get() = this is Connected

    val statusText: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Discoverable -> "Pairing (${remainingSeconds}s)"
            is Connecting -> "Connecting…"
            is Connected -> deviceName.ifBlank { "Connected" }
            is Error -> message
        }
}
