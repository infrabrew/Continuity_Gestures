package com.gesturemouse.wear.sensor

import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.ImuSample
import com.gesturemouse.wear.model.SettingsState
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure-Kotlin real-time IMU gesture recognition engine.
 *
 * Detects finger pinches, double pinches, sustained pinch-holds (drag), palm-up pinches,
 * four-way wrist flicks, and continuous forearm roll twists directly from watch IMU streams
 * ([ImuSample]) at 50–200 Hz.
 *
 * ### Signal-Processing Pipeline
 * 1. **Pinch Detection (Tendon Snap Impulse)**
 *    Pinching the thumb and index finger fires the flexor tendons across the wrist, producing a
 *    short, sharp linear-acceleration impulse (~15–40 ms) dominated by dorsal/palmar Z and
 *    forearm X/Y components with high jerk (Δa/Δt), while overall macro angular velocity stays
 *    low. We isolate this with a first-order high-pass filter and reject macro arm swings via a
 *    gyroscope ceiling gate.
 * 2. **Palm-Up Context Gate**
 *    When the user rotates their palm skyward, the watch face points toward the floor so
 *    `gravityZ < -0.45`. Pinches performed in this posture emit [GestureEvent.PalmUpPinch]
 *    immediately for reverse/secondary actions (e.g., Esc, previous slide, or mode switch).
 * 3. **Single Pinch vs. Double Pinch vs. Pinch-Hold**
 *    After an initial pinch onset, a short disambiguation window checks for:
 *    - A second pinch impulse → [GestureEvent.DoublePinch]
 *    - Sustained isometric muscle micro-tremor → [GestureEvent.PinchHoldStart] ... [GestureEvent.PinchHoldEnd]
 *    - Otherwise → [GestureEvent.SinglePinch]
 * 4. **Directional Flicks with Rebound Rejection**
 *    Gyroscope velocity is projected into a gravity-aligned ergonomic frame (yaw = left/right,
 *    pitch = up/down). Exceeding [SettingsState.flickThreshold] emits a directional flick and
 *    engages a rebound suppression cooldown so returning the wrist to neutral does not trigger
 *    the opposite direction.
 * 5. **Wrist Twist (Forearm Roll)**
 *    Rotation around the watch X-axis (forearm axis) integrates smoothly into incremental
 *    [GestureEvent.WristTwist] events when exceeding a deadzone, ideal for volume or scrolling.
 */
class GestureDetector(
    private var config: SettingsState = SettingsState()
) {
    /** Optional callback for live calibration tools to inspect raw pinch impulse scores. */
    var onImpulseTelemetry: ((impulseScore: Float, gyroMagnitude: Float) -> Unit)? = null

    // High-pass filter state for isolating sharp tendon snaps from slow arm motion
    private var prevRawAccX = 0f
    private var prevRawAccY = 0f
    private var prevRawAccZ = 0f
    private var hpAccX = 0f
    private var hpAccY = 0f
    private var hpAccZ = 0f
    private var hasPreviousSample = false
    private var lastSampleTimestampMs = 0L

    // Pinch state machine
    private enum class PinchState {
        IDLE,
        WAITING_SECOND_OR_HOLD,
        HOLDING
    }

    private var pinchState = PinchState.IDLE
    private var firstPinchTimestampMs = 0L
    private var lastPinchTriggerMs = -10_000L
    private var holdTremorAccumMs = 0L
    private var holdQuietAccumMs = 0L
    private var holdEnergyEma = 0f

    // Flick state with rebound suppression
    private var lastFlickTimestampMs = -10_000L

    // Wrist twist accumulator (degrees)
    private var accumulatedTwistDeg = 0f
    private var lastTwistEmitMs = -10_000L

    fun updateSettings(settings: SettingsState) {
        config = settings
    }

    fun reset() {
        prevRawAccX = 0f
        prevRawAccY = 0f
        prevRawAccZ = 0f
        hpAccX = 0f
        hpAccY = 0f
        hpAccZ = 0f
        hasPreviousSample = false
        lastSampleTimestampMs = 0L
        pinchState = PinchState.IDLE
        firstPinchTimestampMs = 0L
        lastPinchTriggerMs = -10_000L
        holdTremorAccumMs = 0L
        holdQuietAccumMs = 0L
        holdEnergyEma = 0f
        lastFlickTimestampMs = -10_000L
        accumulatedTwistDeg = 0f
        lastTwistEmitMs = -10_000L
    }

    /**
     * Ingests a single [ImuSample] and returns zero or more recognized [GestureEvent]s.
     */
    fun process(sample: ImuSample): List<GestureEvent> {
        val events = mutableListOf<GestureEvent>()
        val nowMs = sample.timestampMs
        val dtMs = if (hasPreviousSample) (nowMs - lastSampleTimestampMs).coerceIn(1L, 100L) else 10L
        val dtSec = dtMs / 1000f

        // 1. High-pass filter linear acceleration (alpha ~0.78 isolates >15 Hz tendon impulses)
        if (!hasPreviousSample) {
            prevRawAccX = sample.linearAccX
            prevRawAccY = sample.linearAccY
            prevRawAccZ = sample.linearAccZ
            hasPreviousSample = true
        } else {
            val alpha = HP_FILTER_ALPHA
            hpAccX = alpha * (hpAccX + sample.linearAccX - prevRawAccX)
            hpAccY = alpha * (hpAccY + sample.linearAccY - prevRawAccY)
            hpAccZ = alpha * (hpAccZ + sample.linearAccZ - prevRawAccZ)
            prevRawAccX = sample.linearAccX
            prevRawAccY = sample.linearAccY
            prevRawAccZ = sample.linearAccZ
        }
        lastSampleTimestampMs = nowMs

        // Weight dorsal-palmar Z axis slightly higher because finger tendon snap radiates
        // strongly perpendicular to the watch backplate.
        val hpScore = sqrt(
            hpAccX * hpAccX * 0.85f +
                hpAccY * hpAccY * 0.85f +
                hpAccZ * hpAccZ * 1.25f
        )
        val rawLinearMag = sqrt(
            sample.linearAccX * sample.linearAccX +
                sample.linearAccY * sample.linearAccY +
                sample.linearAccZ * sample.linearAccZ
        )
        // Combine high-pass impulse with raw linear peak so both single-frame synthetic spikes
        // and multi-frame physical tendon snaps score reliably.
        val pinchImpulseScore = maxOf(hpScore, rawLinearMag * 0.85f)

        val gyroMag = sqrt(
            sample.gyroX * sample.gyroX +
                sample.gyroY * sample.gyroY +
                sample.gyroZ * sample.gyroZ
        )

        onImpulseTelemetry?.invoke(pinchImpulseScore, gyroMag)

        // 2. Check directional wrist flicks first (high angular velocity gestures)
        val flickEvent = detectFlick(sample, nowMs)
        if (flickEvent != null) {
            // Cancel any pending tap if the user is actively flicking
            if (pinchState == PinchState.WAITING_SECOND_OR_HOLD) {
                pinchState = PinchState.IDLE
            }
            events.add(flickEvent)
        }

        // 3. Process pinch state machine (gated against high macro angular velocity)
        processPinchStateMachine(
            sample = sample,
            nowMs = nowMs,
            dtMs = dtMs,
            impulseScore = pinchImpulseScore,
            gyroMag = gyroMag,
            outEvents = events
        )

        // 4. Process continuous forearm roll twist (when not flicking)
        if (flickEvent == null) {
            detectWristTwist(sample, nowMs, dtSec)?.let { events.add(it) }
        }

        return events
    }

    /**
     * Flushes a pending [GestureEvent.SinglePinch] if the double-pinch window has elapsed
     * without further sensor input (useful for end-of-stream or low-frequency pauses).
     */
    fun flushPending(nowMs: Long): List<GestureEvent> {
        val events = mutableListOf<GestureEvent>()
        if (pinchState == PinchState.WAITING_SECOND_OR_HOLD &&
            nowMs - firstPinchTimestampMs >= DOUBLE_PINCH_WINDOW_MS
        ) {
            pinchState = PinchState.IDLE
            events.add(GestureEvent.SinglePinch(timestampMs = nowMs))
        }
        return events
    }

    private fun processPinchStateMachine(
        sample: ImuSample,
        nowMs: Long,
        dtMs: Long,
        impulseScore: Float,
        gyroMag: Float,
        outEvents: MutableList<GestureEvent>
    ) {
        val effectivePinchThreshold = maxOf(
            config.pinchThreshold,
            config.calibratedNoiseFloor * 2.6f
        )
        val gyroGate = config.flickThreshold * PINCH_GYRO_GATE_RATIO
        val isPalmUp = sample.gravityZ < PALM_UP_GRAVITY_Z
        val isPinchImpulse = impulseScore >= effectivePinchThreshold && gyroMag < gyroGate
        val debounceElapsed = (nowMs - lastPinchTriggerMs) >= config.debounceMs

        // Smooth tremor envelope used for detecting sustained pinch-hold (isometric muscle tension)
        holdEnergyEma = holdEnergyEma * 0.80f + impulseScore * 0.20f
        val holdTremorFloor = maxOf(config.calibratedNoiseFloor * 1.45f, effectivePinchThreshold * 0.28f)

        when (pinchState) {
            PinchState.IDLE -> {
                if (isPinchImpulse && debounceElapsed) {
                    lastPinchTriggerMs = nowMs
                    if (isPalmUp) {
                        outEvents.add(GestureEvent.PalmUpPinch(timestampMs = nowMs))
                    } else {
                        pinchState = PinchState.WAITING_SECOND_OR_HOLD
                        firstPinchTimestampMs = nowMs
                        holdTremorAccumMs = 0L
                        holdQuietAccumMs = 0L
                    }
                }
            }

            PinchState.WAITING_SECOND_OR_HOLD -> {
                val elapsedSinceFirst = nowMs - firstPinchTimestampMs

                // Check for a distinct second pinch within the double-pinch window.
                // Use a capped inter-tap minimum gap so higher user debounce settings
                // (e.g., 350–500 ms between separate gestures) never break DoublePinch.
                val doubleTapMinGapMs = minOf(config.debounceMs, DOUBLE_PINCH_MIN_GAP_MS)
                if (isPinchImpulse &&
                    elapsedSinceFirst >= doubleTapMinGapMs &&
                    elapsedSinceFirst <= DOUBLE_PINCH_WINDOW_MS
                ) {
                    lastPinchTriggerMs = nowMs
                    pinchState = PinchState.IDLE
                    outEvents.add(GestureEvent.DoublePinch(timestampMs = nowMs))
                    return
                }

                // Track sustained isometric tremor after the initial snap decays (>70 ms)
                if (elapsedSinceFirst > INITIAL_SNAP_SETTLE_MS && gyroMag < gyroGate) {
                    if (holdEnergyEma >= holdTremorFloor) {
                        holdTremorAccumMs += dtMs
                    } else {
                        holdTremorAccumMs = (holdTremorAccumMs - dtMs / 2).coerceAtLeast(0L)
                    }
                }

                if (holdTremorAccumMs >= HOLD_CONFIRM_MS) {
                    pinchState = PinchState.HOLDING
                    holdQuietAccumMs = 0L
                    outEvents.add(GestureEvent.PinchHoldStart(timestampMs = nowMs))
                } else if (elapsedSinceFirst >= DOUBLE_PINCH_WINDOW_MS) {
                    pinchState = PinchState.IDLE
                    val confidence = (impulseScore / effectivePinchThreshold).coerceIn(0.6f, 1f)
                    outEvents.add(GestureEvent.SinglePinch(timestampMs = nowMs, confidence = confidence))
                }
            }

            PinchState.HOLDING -> {
                // Release hold when tremor subsides for HOLD_RELEASE_MS or on an explicit release snap
                if (holdEnergyEma < holdTremorFloor * 0.72f) {
                    holdQuietAccumMs += dtMs
                } else {
                    holdQuietAccumMs = 0L
                }

                if (holdQuietAccumMs >= HOLD_RELEASE_MS ||
                    (isPinchImpulse && (nowMs - firstPinchTimestampMs) > HOLD_MIN_RELEASE_SNAP_MS)
                ) {
                    pinchState = PinchState.IDLE
                    lastPinchTriggerMs = nowMs
                    outEvents.add(GestureEvent.PinchHoldEnd(timestampMs = nowMs))
                }
            }
        }
    }

    private fun detectFlick(sample: ImuSample, nowMs: Long): GestureEvent? {
        if (nowMs - lastFlickTimestampMs < FLICK_REBOUND_COOLDOWN_MS) {
            return null
        }

        // Normalize gravity for roll-invariant horizontal (yaw) projection
        val gNorm = sqrt(
            sample.gravityX * sample.gravityX +
                sample.gravityY * sample.gravityY +
                sample.gravityZ * sample.gravityZ
        ).coerceAtLeast(0.001f)
        val gx = sample.gravityX / gNorm
        val gy = sample.gravityY / gNorm
        val gz = sample.gravityZ / gNorm

        // Yaw rate around world vertical: positive = sweeping arm to user's right
        val yawRightRadPerSec = -(sample.gyroX * gx + sample.gyroY * gy + sample.gyroZ * gz)

        // Pitch rate around watch Y-axis (wrist width): sign inverted by handedness so
        // positive always means tilting fingers UP toward the sky.
        val pitchUpRadPerSec = -sample.gyroY * config.handedness.axisSign

        val absYaw = abs(yawRightRadPerSec)
        val absPitch = abs(pitchUpRadPerSec)
        val threshold = config.flickThreshold

        // Reject forearm roll (gyroX) masquerading as a flick. When Wear OS native gesture
        // guard is active, tighten roll rejection so system Wrist Turn never misfires flicks.
        val absRoll = abs(sample.gyroX)
        val rollRejectRatio = if (config.nativeGestureGuard) 0.85f else 1.15f
        if (absRoll > maxOf(absYaw, absPitch) * rollRejectRatio) {
            return null
        }

        if (absYaw < threshold && absPitch < threshold) {
            return null
        }

        lastFlickTimestampMs = nowMs
        return if (absYaw >= absPitch) {
            // Palm-up horizontal flick switches input modes hands-free without interfering
            // with normal upright scrolling or arrow navigation.
            if (config.gestureModeSwitchEnabled && gz < PALM_UP_MODE_SWITCH_GRAVITY_Z) {
                GestureEvent.ModeSwitch(
                    timestampMs = nowMs,
                    forward = yawRightRadPerSec > 0f
                )
            } else if (yawRightRadPerSec > 0f) {
                GestureEvent.FlickRight(nowMs, absYaw)
            } else {
                GestureEvent.FlickLeft(nowMs, absYaw)
            }
        } else {
            if (pitchUpRadPerSec > 0f) {
                GestureEvent.FlickUp(nowMs, absPitch)
            } else {
                GestureEvent.FlickDown(nowMs, absPitch)
            }
        }
    }

    private fun detectWristTwist(sample: ImuSample, nowMs: Long, dtSec: Float): GestureEvent? {
        // Forearm pronation/supination occurs primarily around the watch X-axis.
        val rollRadPerSec = sample.gyroX * config.handedness.axisSign
        val absRoll = abs(rollRadPerSec)
        val absOther = maxOf(abs(sample.gyroY), abs(sample.gyroZ))

        // Require deliberate roll dominance above deadzone (raised when native Wrist Turn guard is active)
        val twistDeadzone = if (config.nativeGestureGuard) {
            TWIST_DEADZONE_GUARDED_RAD_PER_SEC
        } else {
            TWIST_DEADZONE_RAD_PER_SEC
        }
        if (absRoll < twistDeadzone || absRoll < absOther * 1.25f) {
            // Bleed residual accumulator slowly back to zero
            accumulatedTwistDeg *= 0.85f
            return null
        }

        val deltaDeg = (rollRadPerSec * RAD_TO_DEG) * dtSec
        accumulatedTwistDeg += deltaDeg

        if (abs(accumulatedTwistDeg) >= TWIST_STEP_DEGREES &&
            (nowMs - lastTwistEmitMs) >= TWIST_MIN_INTERVAL_MS
        ) {
            val emitDeg = accumulatedTwistDeg
            accumulatedTwistDeg = 0f
            lastTwistEmitMs = nowMs
            return GestureEvent.WristTwist(timestampMs = nowMs, deltaDegrees = emitDeg)
        }
        return null
    }

    companion object {
        private const val HP_FILTER_ALPHA = 0.78f
        private const val PALM_UP_GRAVITY_Z = -0.45f
        private const val PALM_UP_MODE_SWITCH_GRAVITY_Z = -0.65f
        private const val PINCH_GYRO_GATE_RATIO = 0.75f
        private const val INITIAL_SNAP_SETTLE_MS = 70L
        private const val DOUBLE_PINCH_MIN_GAP_MS = 140L
        private const val DOUBLE_PINCH_WINDOW_MS = 340L
        private const val HOLD_CONFIRM_MS = 220L
        private const val HOLD_RELEASE_MS = 180L
        private const val HOLD_MIN_RELEASE_SNAP_MS = 400L
        private const val FLICK_REBOUND_COOLDOWN_MS = 380L
        private const val TWIST_DEADZONE_RAD_PER_SEC = 1.15f
        private const val TWIST_DEADZONE_GUARDED_RAD_PER_SEC = 1.45f
        private const val TWIST_STEP_DEGREES = 18f
        private const val TWIST_MIN_INTERVAL_MS = 110L
        private const val RAD_TO_DEG = 57.29578f
    }
}
