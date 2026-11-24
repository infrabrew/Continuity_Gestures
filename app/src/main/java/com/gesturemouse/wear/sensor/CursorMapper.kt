package com.gesturemouse.wear.sensor

import com.gesturemouse.wear.model.AccelerationCurve
import com.gesturemouse.wear.model.CursorDelta
import com.gesturemouse.wear.model.ImuSample
import com.gesturemouse.wear.model.SettingsState
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Pure-Kotlin wrist-motion-to-cursor kinematics, velocity-based acceleration curves, and
 * hybrid micro-trackpad engine.
 *
 * Translates gravity-aligned angular velocities from the watch IMU into smooth, jitter-free
 * relative HID mouse deltas `(dx, dy)` and scroll wheel deltas `(wheel, pan)`.
 *
 * ### Key Feel Optimizations
 * - **Gravity-Aligned Horizon Projection**: Even if the user holds their arm tilted at an
 *   arbitrary roll angle, sweeping the forearm horizontally still moves the cursor horizontally.
 * - **Velocity-Based Acceleration Curves**: Selectable curves ([AccelerationCurve]) combine a
 *   predictable slow/linear micro-aiming band with exponential fast-flick boost across wide
 *   multi-monitor setups.
 * - **Hybrid Air-Mouse + Micro-Trackpad**: Sliding a finger on the watch screen emits 1-pixel
 *   relative deltas simultaneously while gently damping air-gyro tremor during glass contact.
 * - **Adaptive Smoothing (One-Euro Style)** + **Click-Stabilization Freeze** + **Sub-Pixel
 *   Remainder Accumulation**.
 */
class CursorMapper(
    private var config: SettingsState = SettingsState()
) {
    private var filteredYaw = 0f
    private var filteredPitch = 0f
    private var remainderX = 0f
    private var remainderY = 0f
    private var touchRemainderX = 0f
    private var touchRemainderY = 0f
    private var lastTimestampMs = 0L
    private var hasLastSample = false

    /** Timestamp until which cursor motion is gently stabilized after a pinch click. */
    private var freezeUntilMs = 0L

    /** Timestamp until which air-gyro motion is damped while finger slides on micro-trackpad. */
    private var microTrackpadDampUntilMs = 0L

    /** When true, vertical wrist pitch maps to smooth scroll wheel instead of cursor Y. */
    var isScrollClutchActive: Boolean = false
        private set

    /** Physical side-button clutch: freezes cursor movement while repositioning your arm. */
    var isClutchFrozen: Boolean = false
        private set

    /** Physical side-button precision mode: scales cursor speed down to 0.30x for micro-aim. */
    var isPrecisionModeActive: Boolean = false
        private set

    private var scrollAccumulator = 0f

    fun updateSettings(settings: SettingsState) {
        config = settings
    }

    fun reset() {
        filteredYaw = 0f
        filteredPitch = 0f
        remainderX = 0f
        remainderY = 0f
        touchRemainderX = 0f
        touchRemainderY = 0f
        scrollAccumulator = 0f
        lastTimestampMs = 0L
        hasLastSample = false
        freezeUntilMs = 0L
        microTrackpadDampUntilMs = 0L
        isScrollClutchActive = false
        isClutchFrozen = false
        isPrecisionModeActive = false
    }

    /**
     * Engages brief click stabilization so pinch finger recoil does not jitter the pointer.
     */
    fun triggerClickStabilization(nowMs: Long, durationMs: Long = DEFAULT_CLICK_FREEZE_MS) {
        freezeUntilMs = maxOf(freezeUntilMs, nowMs + durationMs)
        remainderX = 0f
        remainderY = 0f
    }

    fun setScrollClutch(active: Boolean) {
        isScrollClutchActive = active
        if (!active) {
            scrollAccumulator = 0f
        }
    }

    fun setClutchFrozen(frozen: Boolean) {
        isClutchFrozen = frozen
        if (frozen) {
            filteredYaw = 0f
            filteredPitch = 0f
            remainderX = 0f
            remainderY = 0f
        }
    }

    fun setPrecisionMode(active: Boolean) {
        isPrecisionModeActive = active
    }

    /**
     * Maps fine finger slide deltas (`dxPx`, `dyPx`) on the watch touchscreen into precise
     * relative HID cursor nudges for Hybrid Air-Mouse + Micro-Trackpad aiming.
     */
    fun mapMicroTrackpadDrag(dxPx: Float, dyPx: Float, nowMs: Long): CursorDelta {
        microTrackpadDampUntilMs = maxOf(microTrackpadDampUntilMs, nowMs + MICRO_TRACKPAD_DAMP_MS)
        val xSign = if (config.invertX) -1f else 1f
        val ySign = if (config.invertY) -1f else 1f
        val scale = if (isPrecisionModeActive) MICRO_TRACKPAD_GAIN * 0.55f else MICRO_TRACKPAD_GAIN

        val floatDx = dxPx * xSign * scale + touchRemainderX
        val floatDy = dyPx * ySign * scale + touchRemainderY

        val intDx = floatDx.toInt().coerceIn(-MAX_HID_DELTA, MAX_HID_DELTA)
        val intDy = floatDy.toInt().coerceIn(-MAX_HID_DELTA, MAX_HID_DELTA)

        touchRemainderX = (floatDx - intDx).coerceIn(-1f, 1f)
        touchRemainderY = (floatDy - intDy).coerceIn(-1f, 1f)

        return CursorDelta(dx = intDx, dy = intDy)
    }

    /**
     * Computes the velocity-based acceleration multiplier for [speedRadPerSec] using the
     * selected [AccelerationCurve] and [SettingsState.pointerAcceleration].
     */
    fun computeAccelerationMultiplier(speedRadPerSec: Float): Float {
        val strength = BASE_ACCEL_STRENGTH * config.pointerAcceleration.coerceIn(0f, 2.5f)
        return when (config.accelerationCurve) {
            AccelerationCurve.LINEAR -> 1f

            AccelerationCurve.PRECISION -> {
                if (speedRadPerSec <= PRECISION_KNEE_RAD_S) {
                    PRECISION_LOW_GAIN
                } else {
                    val excess = speedRadPerSec - PRECISION_KNEE_RAD_S
                    (PRECISION_LOW_GAIN + excess.pow(1.45f) * strength * 0.90f)
                        .coerceAtMost(MAX_ACCEL_BOOST)
                }
            }

            AccelerationCurve.BALANCED -> {
                if (speedRadPerSec <= BALANCED_KNEE_RAD_S) {
                    1f
                } else {
                    val excess = speedRadPerSec - BALANCED_KNEE_RAD_S
                    (1f + excess.pow(ACCEL_EXPONENT) * strength)
                        .coerceAtMost(MAX_ACCEL_BOOST)
                }
            }

            AccelerationCurve.DYNAMIC_EXPONENTIAL -> {
                (1f + speedRadPerSec.pow(DYNAMIC_EXPO_POWER) * strength * 1.35f)
                    .coerceAtMost(MAX_DYNAMIC_ACCEL_BOOST)
            }
        }
    }

    /**
     * Converts an [ImuSample] into a quantized [CursorDelta] ready for a Bluetooth HID Mouse report.
     */
    fun mapSample(sample: ImuSample): CursorDelta {
        val nowMs = sample.timestampMs
        val dtSec = if (hasLastSample) {
            ((nowMs - lastTimestampMs).coerceIn(1L, 80L)) / 1000f
        } else {
            hasLastSample = true
            lastTimestampMs = nowMs
            return CursorDelta()
        }
        lastTimestampMs = nowMs

        if (isClutchFrozen) {
            filteredYaw = 0f
            filteredPitch = 0f
            remainderX = 0f
            remainderY = 0f
            return CursorDelta()
        }

        // Normalize gravity direction in watch local coordinates
        val gNorm = sqrt(
            sample.gravityX * sample.gravityX +
                sample.gravityY * sample.gravityY +
                sample.gravityZ * sample.gravityZ
        ).coerceAtLeast(0.001f)
        val gx = sample.gravityX / gNorm
        val gy = sample.gravityY / gNorm
        val gz = sample.gravityZ / gNorm

        // 1. Project angular velocity onto the ergonomic pointing basis
        val rawYaw = -(sample.gyroX * gx + sample.gyroY * gy + sample.gyroZ * gz)

        val forearmX = config.handedness.axisSign
        val pitchAxisY = -forearmX * gz
        val pitchAxisZ = forearmX * gy
        val pitchAxisNorm = sqrt(pitchAxisY * pitchAxisY + pitchAxisZ * pitchAxisZ)
        val rawPitchUp = if (pitchAxisNorm > 0.25f) {
            (sample.gyroY * (pitchAxisY / pitchAxisNorm) +
                sample.gyroZ * (pitchAxisZ / pitchAxisNorm))
        } else {
            -sample.gyroY * forearmX
        }

        // 2. Deadzone rejection to eliminate stationary gyro bias & micro-tremor
        val yawAfterDeadzone = applyDeadzone(rawYaw, GYRO_DEADZONE_RAD_PER_SEC)
        val pitchAfterDeadzone = applyDeadzone(rawPitchUp, GYRO_DEADZONE_RAD_PER_SEC)

        // 3. Adaptive smoothing (One-Euro inspired)
        val speed = sqrt(yawAfterDeadzone * yawAfterDeadzone + pitchAfterDeadzone * pitchAfterDeadzone)
        val baseSmooth = config.smoothingFactor.coerceIn(0f, 0.85f)
        val speedFactor = (speed / HIGH_SPEED_RAD_PER_SEC).coerceIn(0f, 1f)
        val dynamicSmooth = baseSmooth * (1f - 0.70f * speedFactor)
        val alpha = (1f - dynamicSmooth).coerceIn(0.15f, 1f)

        filteredYaw += alpha * (yawAfterDeadzone - filteredYaw)
        filteredPitch += alpha * (pitchAfterDeadzone - filteredPitch)

        // 4. Stabilization damping during tap recoil or active micro-trackpad finger touch
        val freezeScale = when {
            nowMs < freezeUntilMs -> 0.15f
            nowMs < microTrackpadDampUntilMs -> 0.12f
            else -> 1f
        }
        val xSign = if (config.invertX) -1f else 1f
        val ySign = if (config.invertY) -1f else 1f
        val sensitivity = config.cursorSensitivity.coerceIn(0.4f, 5.0f)
        val xScale = config.horizontalSpeedScale.coerceIn(0.5f, 2.5f)
        val yScale = config.verticalSpeedScale.coerceIn(0.5f, 2.5f)

        // 5. Scroll clutch mode: map pitch directly to scroll wheel ticks
        if (isScrollClutchActive) {
            scrollAccumulator += (filteredPitch * ySign) * dtSec * SCROLL_GAIN * sensitivity * yScale
            val wheelTicks = scrollAccumulator.toInt().coerceIn(-MAX_WHEEL_DELTA, MAX_WHEEL_DELTA)
            if (wheelTicks != 0) {
                scrollAccumulator -= wheelTicks
            }
            return CursorDelta(wheel = wheelTicks)
        }

        // 6. Velocity-based acceleration curve
        val accelMultiplier = computeAccelerationMultiplier(speed)
        val precisionScale = if (isPrecisionModeActive) PRECISION_MODE_SCALE else 1f
        val baseGain = BASE_PIXELS_PER_RAD * sensitivity * accelMultiplier * freezeScale * precisionScale
        val gainX = baseGain * xScale
        val gainY = baseGain * yScale

        // HID Mouse coordinates: +dx is right, +dy is DOWN (negate pitchUp so hand up -> cursor up)
        val floatDx = filteredYaw * xSign * dtSec * gainX + remainderX
        val floatDy = -filteredPitch * ySign * dtSec * gainY + remainderY

        val intDx = floatDx.toInt().coerceIn(-MAX_HID_DELTA, MAX_HID_DELTA)
        val intDy = floatDy.toInt().coerceIn(-MAX_HID_DELTA, MAX_HID_DELTA)

        remainderX = (floatDx - intDx).coerceIn(-1f, 1f)
        remainderY = (floatDy - intDy).coerceIn(-1f, 1f)

        return CursorDelta(dx = intDx, dy = intDy)
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= deadzone) return 0f
        return sign(value) * (magnitude - deadzone)
    }

    companion object {
        private const val GYRO_DEADZONE_RAD_PER_SEC = 0.035f
        private const val BASE_PIXELS_PER_RAD = 900f
        private const val HIGH_SPEED_RAD_PER_SEC = 2.6f
        private const val PRECISION_KNEE_RAD_S = 0.85f
        private const val PRECISION_LOW_GAIN = 0.78f
        private const val BALANCED_KNEE_RAD_S = 0.55f
        private const val ACCEL_EXPONENT = 1.35f
        private const val DYNAMIC_EXPO_POWER = 1.65f
        private const val BASE_ACCEL_STRENGTH = 0.65f
        private const val MAX_ACCEL_BOOST = 3.5f
        private const val MAX_DYNAMIC_ACCEL_BOOST = 4.6f
        private const val SCROLL_GAIN = 14f
        private const val DEFAULT_CLICK_FREEZE_MS = 90L
        private const val MICRO_TRACKPAD_DAMP_MS = 160L
        private const val MICRO_TRACKPAD_GAIN = 0.65f
        private const val PRECISION_MODE_SCALE = 0.30f
        private const val MAX_HID_DELTA = 127
        private const val MAX_WHEEL_DELTA = 12
    }
}
