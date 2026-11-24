package com.gesturemouse.wear.spatial

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of a UWB-ranged paired host (computer, tablet, TV, or spatial receiver).
 */
data class RangedHost(
    val address: String,
    val name: String,
    val distanceMeters: Float,
    val azimuthDegrees: Float = 0f,
    val timestampMs: Long = 0L
)

/**
 * Outcome emitted by [ProximityHostSwitcher] when UWB/RSSI proximity indicates waking mouse
 * mode, seamlessly switching to a nearer paired host, or auto-locking the host on walk-away.
 */
sealed interface ProximityDecision {
    data class AutoWake(val host: RangedHost) : ProximityDecision
    data class SwitchHost(val targetHost: RangedHost) : ProximityDecision
    data class WalkAwayLock(val hostAddress: String, val reason: String) : ProximityDecision
}

/**
 * Pure-Kotlin UWB proximity auto-wake, multi-host auto-switch, and walk-away auto-lock state
 * machine with spatial hysteresis and dwell gating.
 */
class ProximityHostSwitcher(
    private val wakeDistanceMeters: Float = DEFAULT_WAKE_DISTANCE_M,
    private val switchMarginMeters: Float = DEFAULT_SWITCH_MARGIN_M,
    private val switchDwellMs: Long = DEFAULT_SWITCH_DWELL_MS,
    private val walkAwayDistanceMeters: Float = DEFAULT_WALK_AWAY_DISTANCE_M,
    private val walkAwayDwellMs: Long = DEFAULT_WALK_AWAY_DWELL_MS
) {
    private val hostDistances = mutableMapOf<String, RangedHost>()
    private var candidateAddress: String? = null
    private var candidateSinceMs: Long = 0L

    private var wasNearActiveHost: Boolean = false
    private var farSinceMs: Long = -1L
    private var weakRssiSinceMs: Long = -1L
    private var hasFiredWalkAwayLock: Boolean = false

    fun reset() {
        hostDistances.clear()
        candidateAddress = null
        candidateSinceMs = 0L
        wasNearActiveHost = false
        farSinceMs = -1L
        weakRssiSinceMs = -1L
        hasFiredWalkAwayLock = false
    }

    /**
     * Evaluates a fresh UWB ranging sample against [activeHostAddress], pause state, and
     * whether [walkAwayLockEnabled] is armed.
     */
    fun onRangingUpdate(
        sample: RangedHost,
        activeHostAddress: String?,
        isPaused: Boolean,
        walkAwayLockEnabled: Boolean = false
    ): ProximityDecision? {
        hostDistances[sample.address] = sample

        // Drop stale peers (> 5 seconds without ranging packet)
        val staleCutoff = sample.timestampMs - STALE_PEER_MS
        hostDistances.entries.removeAll { it.value.timestampMs < staleCutoff }

        // Check Walk-Away Auto-Lock for the currently active host
        if (!activeHostAddress.isNullOrBlank() && sample.address == activeHostAddress) {
            if (sample.distanceMeters <= NEAR_ARM_DISTANCE_M) {
                wasNearActiveHost = true
                farSinceMs = -1L
                hasFiredWalkAwayLock = false
            } else if (walkAwayLockEnabled &&
                wasNearActiveHost &&
                !hasFiredWalkAwayLock &&
                sample.distanceMeters >= walkAwayDistanceMeters
            ) {
                if (farSinceMs < 0L) {
                    farSinceMs = sample.timestampMs
                } else if (sample.timestampMs - farSinceMs >= walkAwayDwellMs) {
                    hasFiredWalkAwayLock = true
                    farSinceMs = -1L
                    return ProximityDecision.WalkAwayLock(
                        hostAddress = activeHostAddress,
                        reason = "UWB distance ${"%.1f".format(sample.distanceMeters)}m"
                    )
                }
            } else {
                farSinceMs = -1L
            }
        }

        val nearest = hostDistances.values.minByOrNull { it.distanceMeters } ?: return null

        // 1. Auto-wake if user brings wrist within wake distance of a paired host while paused
        if (isPaused && nearest.distanceMeters <= wakeDistanceMeters) {
            candidateAddress = null
            return ProximityDecision.AutoWake(nearest)
        }

        // 2. If no active host yet, connect to nearest host within wake zone
        if (activeHostAddress.isNullOrBlank()) {
            if (nearest.distanceMeters <= wakeDistanceMeters) {
                if (candidateAddress != nearest.address) {
                    candidateAddress = nearest.address
                    candidateSinceMs = sample.timestampMs
                } else if (sample.timestampMs - candidateSinceMs >= switchDwellMs) {
                    candidateAddress = null
                    return ProximityDecision.SwitchHost(nearest)
                }
            }
            return null
        }

        // 3. Hysteresis-protected switch when a different host is significantly closer
        if (nearest.address == activeHostAddress) {
            candidateAddress = null
            return null
        }

        val currentHost = hostDistances[activeHostAddress]
        val clearlyCloser = currentHost == null ||
            (nearest.distanceMeters + switchMarginMeters) < currentHost.distanceMeters

        if (clearlyCloser && nearest.distanceMeters <= wakeDistanceMeters * 1.5f) {
            if (candidateAddress != nearest.address) {
                candidateAddress = nearest.address
                candidateSinceMs = sample.timestampMs
            } else if (sample.timestampMs - candidateSinceMs >= switchDwellMs) {
                candidateAddress = null
                return ProximityDecision.SwitchHost(nearest)
            }
        } else {
            candidateAddress = null
        }

        return null
    }

    /**
     * Evaluates Bluetooth RSSI drop for walk-away auto-lock on watches without UWB hardware.
     */
    fun onBluetoothRssiUpdate(
        hostAddress: String,
        rssiDbm: Int,
        timestampMs: Long,
        walkAwayLockEnabled: Boolean
    ): ProximityDecision.WalkAwayLock? {
        if (!walkAwayLockEnabled || hostAddress.isBlank()) return null

        if (rssiDbm >= STRONG_RSSI_ARM_DBM) {
            wasNearActiveHost = true
            weakRssiSinceMs = -1L
            hasFiredWalkAwayLock = false
            return null
        }

        if (wasNearActiveHost && !hasFiredWalkAwayLock && rssiDbm <= WALK_AWAY_RSSI_DBM) {
            if (weakRssiSinceMs < 0L) {
                weakRssiSinceMs = timestampMs
            } else if (timestampMs - weakRssiSinceMs >= walkAwayDwellMs) {
                hasFiredWalkAwayLock = true
                weakRssiSinceMs = -1L
                return ProximityDecision.WalkAwayLock(
                    hostAddress = hostAddress,
                    reason = "BT RSSI ${rssiDbm} dBm"
                )
            }
        } else {
            weakRssiSinceMs = -1L
        }
        return null
    }

    companion object {
        const val DEFAULT_WAKE_DISTANCE_M = 1.2f
        const val DEFAULT_SWITCH_MARGIN_M = 0.35f
        const val DEFAULT_SWITCH_DWELL_MS = 800L
        const val DEFAULT_WALK_AWAY_DISTANCE_M = 3.0f
        const val DEFAULT_WALK_AWAY_DWELL_MS = 2_000L
        private const val NEAR_ARM_DISTANCE_M = 1.8f
        private const val STRONG_RSSI_ARM_DBM = -68
        private const val WALK_AWAY_RSSI_DBM = -86
        private const val STALE_PEER_MS = 5_000L
    }
}

/**
 * Wear OS Ultra-Wideband (UWB) proximity & walk-away auto-lock controller wrapping hardware
 * capability detection and [ProximityHostSwitcher] for Pixel Watch 5 / UWB-equipped watches.
 */
class UwbProximityManager(
    context: Context,
    private val onDecision: (ProximityDecision) -> Unit
) {
    val isUwbHardwareSupported: Boolean =
        context.packageManager.hasSystemFeature(FEATURE_UWB)

    private val switcher = ProximityHostSwitcher()
    private var enabled: Boolean = false
    private var walkAwayLockEnabled: Boolean = false

    private val _statusText = MutableStateFlow(
        if (isUwbHardwareSupported) "Ready (UWB available)" else "Hardware not present"
    )
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    fun configure(uwbAutoSwitch: Boolean, walkAwayLock: Boolean) {
        enabled = uwbAutoSwitch
        walkAwayLockEnabled = walkAwayLock
        if (!uwbAutoSwitch && !walkAwayLock) {
            switcher.reset()
            _statusText.value = if (isUwbHardwareSupported) "Off" else "Hardware not present"
            return
        }
        _statusText.value = if (isUwbHardwareSupported) {
            if (uwbAutoSwitch) "Scanning UWB hosts (<1.2m)" else "Walk-away armed"
        } else {
            if (walkAwayLock) "No UWB (RSSI/Off-body guard)" else "No UWB radio on this watch"
        }
    }

    fun onHostRanged(
        sample: RangedHost,
        activeHostAddress: String?,
        isPaused: Boolean
    ) {
        if (!enabled && !walkAwayLockEnabled) return
        _statusText.value = "${sample.name} • ${"%.1f".format(sample.distanceMeters)}m"
        val decision = switcher.onRangingUpdate(
            sample = sample,
            activeHostAddress = activeHostAddress,
            isPaused = isPaused,
            walkAwayLockEnabled = walkAwayLockEnabled
        )
        if (decision != null) {
            onDecision(decision)
        }
    }

    fun onBluetoothRssi(hostAddress: String, rssiDbm: Int, timestampMs: Long) {
        val decision = switcher.onBluetoothRssiUpdate(
            hostAddress = hostAddress,
            rssiDbm = rssiDbm,
            timestampMs = timestampMs,
            walkAwayLockEnabled = walkAwayLockEnabled
        )
        if (decision != null) {
            onDecision(decision)
        }
    }

    fun stop() {
        enabled = false
        walkAwayLockEnabled = false
        switcher.reset()
    }

    companion object {
        private const val FEATURE_UWB = "android.hardware.uwb"
    }
}
