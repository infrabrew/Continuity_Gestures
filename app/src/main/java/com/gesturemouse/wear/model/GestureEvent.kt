package com.gesturemouse.wear.model

/**
 * High-level semantic gestures emitted by [com.gesturemouse.wear.sensor.GestureDetector].
 *
 * Every event carries a monotonic [timestampMs] so downstream consumers ([com.gesturemouse.wear.mode.ModeManager],
 * UI ring animations, haptics, and TouchSDK BLE notifications) can correlate events cleanly.
 */
sealed class GestureEvent(open val timestampMs: Long) {

    /** Standard index + thumb tap pinch (typically Left Click or Confirm/Play). */
    data class SinglePinch(
        override val timestampMs: Long,
        val confidence: Float = 1f
    ) : GestureEvent(timestampMs)

    /** Two rapid pinches within the double-pinch window (typically Right Click or Double Click). */
    data class DoublePinch(
        override val timestampMs: Long
    ) : GestureEvent(timestampMs)

    /** Sustained pinch hold began (initiates mouse drag or scroll-clutch mode). */
    data class PinchHoldStart(
        override val timestampMs: Long
    ) : GestureEvent(timestampMs)

    /** Sustained pinch hold released (ends mouse drag or scroll-clutch mode). */
    data class PinchHoldEnd(
        override val timestampMs: Long
    ) : GestureEvent(timestampMs)

    /** Pinch performed with the palm rotated skyward (watch face pointing down) for reverse/secondary actions. */
    data class PalmUpPinch(
        override val timestampMs: Long
    ) : GestureEvent(timestampMs)

    /** Sharp wrist flick upward. */
    data class FlickUp(
        override val timestampMs: Long,
        val peakAngularVelocityRadPerSec: Float = 0f
    ) : GestureEvent(timestampMs)

    /** Sharp wrist flick downward. */
    data class FlickDown(
        override val timestampMs: Long,
        val peakAngularVelocityRadPerSec: Float = 0f
    ) : GestureEvent(timestampMs)

    /** Sharp wrist flick to the user's left (handedness-compensated). */
    data class FlickLeft(
        override val timestampMs: Long,
        val peakAngularVelocityRadPerSec: Float = 0f
    ) : GestureEvent(timestampMs)

    /** Sharp wrist flick to the user's right (handedness-compensated). */
    data class FlickRight(
        override val timestampMs: Long,
        val peakAngularVelocityRadPerSec: Float = 0f
    ) : GestureEvent(timestampMs)

    /**
     * Continuous forearm roll rotation (pronation/supination) used for fine-grained
     * volume, brightness, or scroll control.
     *
     * @property deltaDegrees Signed roll delta in degrees; positive = clockwise (handedness-adjusted).
     */
    data class WristTwist(
        override val timestampMs: Long,
        val deltaDegrees: Float
    ) : GestureEvent(timestampMs)

    /**
     * Hands-free mode switch triggered by a crisp horizontal flick while the palm is rotated
     * skyward (watch face pointing downward).
     *
     * @property forward True to advance to the next [InputMode], false for previous.
     */
    data class ModeSwitch(
        override val timestampMs: Long,
        val forward: Boolean = true
    ) : GestureEvent(timestampMs)

    /** Short human-readable label for the watch HUD pill. */
    fun displayLabel(): String = when (this) {
        is SinglePinch -> "Pinch"
        is DoublePinch -> "Double Pinch"
        is PinchHoldStart -> "Hold / Drag"
        is PinchHoldEnd -> "Release"
        is PalmUpPinch -> "Palm-Up Pinch"
        is FlickUp -> "Flick Up"
        is FlickDown -> "Flick Down"
        is FlickLeft -> "Flick Left"
        is FlickRight -> "Flick Right"
        is WristTwist -> if (deltaDegrees >= 0f) "Twist +" else "Twist -"
        is ModeSwitch -> if (forward) "Next Mode" else "Prev Mode"
    }
}
