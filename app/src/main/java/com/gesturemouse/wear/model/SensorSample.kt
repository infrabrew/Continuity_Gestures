package com.gesturemouse.wear.model

/**
 * Platform-independent IMU snapshot passed into [com.gesturemouse.wear.sensor.GestureDetector]
 * and [com.gesturemouse.wear.sensor.CursorMapper].
 *
 * Keeping sensor frames as pure Kotlin data classes allows deterministic, repeatable
 * unit tests on the JVM without requiring Android framework mocks or physical hardware.
 *
 * Coordinate convention (standard Android Watch sensor frame, watch worn on wrist):
 * - +X: right across the watch face (toward fingers on left wrist, toward elbow on right wrist)
 * - +Y: up across the watch face (12 o'clock)
 * - +Z: perpendicular out of the watch screen toward the sky when viewing the watch
 */
data class ImuSample(
    /** Monotonic sample timestamp in milliseconds. */
    val timestampMs: Long,
    /** Gravity-compensated linear acceleration (m/s²) along watch X, Y, Z axes. */
    val linearAccX: Float,
    val linearAccY: Float,
    val linearAccZ: Float,
    /** Calibrated angular velocity (rad/s) around watch X, Y, Z axes. */
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    /** Normalized gravity direction vector in watch local coordinates (length ~1.0). */
    val gravityX: Float = 0f,
    val gravityY: Float = 0f,
    val gravityZ: Float = 1f,
    /** Unit orientation quaternion (x, y, z, w) from TYPE_GAME_ROTATION_VECTOR or complementary filter. */
    val quatX: Float = 0f,
    val quatY: Float = 0f,
    val quatZ: Float = 0f,
    val quatW: Float = 1f
)

/**
 * Relative 2D cursor and scroll delta output produced by [com.gesturemouse.wear.sensor.CursorMapper].
 */
data class CursorDelta(
    val dx: Int = 0,
    val dy: Int = 0,
    val wheel: Int = 0,
    val pan: Int = 0
) {
    val isZero: Boolean
        get() = dx == 0 && dy == 0 && wheel == 0 && pan == 0
}
