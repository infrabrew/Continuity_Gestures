package com.gesturemouse.wear.sensor

import kotlin.math.sqrt

/**
 * Pure-Kotlin 9-DoF Madgwick AHRS sensor fusion filter combining 3-axis gyroscope,
 * 3-axis accelerometer, and 3-axis magnetometer into a drift-stabilized unit quaternion.
 *
 * ### Magnetic Interference Guard
 * Laptops, desks, and watch straps can distort local magnetic fields. Whenever the measured
 * magnetometer magnitude falls outside Earth's plausible geomagnetic range (`15..90 µT`) or no
 * magnetometer is present, this filter automatically degrades to its 6-DoF IMU gradient step
 * for that frame so yaw never snaps or glitches near speakers/magnets.
 */
class MadgwickAhrsFilter(
    private val beta: Float = DEFAULT_BETA
) {
    var qw: Float = 1f
        private set
    var qx: Float = 0f
        private set
    var qy: Float = 0f
        private set
    var qz: Float = 0f
        private set

    /** Normalized world-up (gravity reaction) vector in watch local coordinates. */
    val gravityX: Float
        get() = 2f * (qx * qz - qw * qy)

    val gravityY: Float
        get() = 2f * (qw * qx + qy * qz)

    val gravityZ: Float
        get() = qw * qw - qx * qx - qy * qy + qz * qz

    fun reset() {
        qw = 1f
        qx = 0f
        qy = 0f
        qz = 0f
    }

    /**
     * Advances orientation by [dtSec] seconds using calibrated gyroscope (`rad/s`),
     * accelerometer (`m/s²`), and optional magnetometer (`µT`).
     */
    fun update(
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
        mx: Float = 0f,
        my: Float = 0f,
        mz: Float = 0f,
        dtSec: Float
    ) {
        if (dtSec <= 0f || dtSec > 0.25f) return

        val magNorm = sqrt(mx * mx + my * my + mz * mz)
        val useMagnetometer = magNorm in MIN_VALID_MAG_UT..MAX_VALID_MAG_UT

        if (!useMagnetometer) {
            updateImu6Dof(gx, gy, gz, ax, ay, az, dtSec)
            return
        }

        // Rate of change of quaternion from gyroscope
        var qDot1 = 0.5f * (-qx * gx - qy * gy - qz * gz)
        var qDot2 = 0.5f * (qw * gx + qy * gz - qz * gy)
        var qDot3 = 0.5f * (qw * gy - qx * gz + qz * gx)
        var qDot4 = 0.5f * (qw * gz + qx * gy - qy * gx)

        val accNorm = sqrt(ax * ax + ay * ay + az * az)
        if (accNorm > 0.001f) {
            val axn = ax / accNorm
            val ayn = ay / accNorm
            val azn = az / accNorm
            val mxn = mx / magNorm
            val myn = my / magNorm
            val mzn = mz / magNorm

            val _2q0mx = 2f * qw * mxn
            val _2q0my = 2f * qw * myn
            val _2q0mz = 2f * qw * mzn
            val _2q1mx = 2f * qx * mxn
            val _2q0 = 2f * qw
            val _2q1 = 2f * qx
            val _2q2 = 2f * qy
            val _2q3 = 2f * qz
            val _2q0q2 = 2f * qw * qy
            val _2q2q3 = 2f * qy * qz
            val q0q0 = qw * qw
            val q0q1 = qw * qx
            val q0q2 = qw * qy
            val q0q3 = qw * qz
            val q1q1 = qx * qx
            val q1q2 = qx * qy
            val q1q3 = qx * qz
            val q2q2 = qy * qy
            val q2q3 = qy * qz
            val q3q3 = qz * qz

            // Reference direction of Earth's magnetic field
            val hx = mxn * q0q0 - _2q0my * qz + _2q0mz * qy + mxn * q1q1 +
                _2q1 * myn * qy + _2q1 * mzn * qz - mxn * q2q2 - mxn * q3q3
            val hy = _2q0mx * qz + myn * q0q0 - _2q0mz * qx + _2q1mx * qy -
                myn * q1q1 + myn * q2q2 + _2q2 * mzn * qz - myn * q3q3
            val _2bx = sqrt(hx * hx + hy * hy)
            val _2bz = -_2q0mx * qy + _2q0my * qx + mzn * q0q0 + _2q1mx * qz -
                mzn * q1q1 + _2q2 * myn * qz - mzn * q2q2 + mzn * q3q3
            val _4bx = 2f * _2bx
            val _4bz = 2f * _2bz

            // Gradient descent corrective step
            var s0 = -_2q2 * (2f * q1q3 - _2q0q2 - axn) +
                _2q1 * (2f * q0q1 + _2q2q3 - ayn) -
                _2bz * qy * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) +
                (-_2bx * qz + _2bz * qx) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) +
                _2bx * qy * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)
            var s1 = _2q3 * (2f * q1q3 - _2q0q2 - axn) +
                _2q0 * (2f * q0q1 + _2q2q3 - ayn) -
                4f * qx * (1f - 2f * q1q1 - 2f * q2q2 - azn) +
                _2bz * qz * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) +
                (_2bx * qy + _2bz * qw) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) +
                (_2bx * qz - _4bz * qx) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)
            var s2 = -_2q0 * (2f * q1q3 - _2q0q2 - axn) +
                _2q3 * (2f * q0q1 + _2q2q3 - ayn) -
                4f * qy * (1f - 2f * q1q1 - 2f * q2q2 - azn) +
                (-_4bx * qy - _2bz * qw) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) +
                (_2bx * qx + _2bz * qz) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) +
                (_2bx * qw - _4bz * qy) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)
            var s3 = _2q1 * (2f * q1q3 - _2q0q2 - axn) +
                _2q2 * (2f * q0q1 + _2q2q3 - ayn) +
                (-_4bx * qz + _2bz * qx) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) +
                (-_2bx * qw + _2bz * qy) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) +
                _2bx * qx * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)

            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-6f) {
                val invSNorm = 1f / sNorm
                s0 *= invSNorm
                s1 *= invSNorm
                s2 *= invSNorm
                s3 *= invSNorm
                qDot1 -= beta * s0
                qDot2 -= beta * s1
                qDot3 -= beta * s2
                qDot4 -= beta * s3
            }
        }

        integrateAndNormalize(qDot1, qDot2, qDot3, qDot4, dtSec)
    }

    private fun updateImu6Dof(
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
        dtSec: Float
    ) {
        var qDot1 = 0.5f * (-qx * gx - qy * gy - qz * gz)
        var qDot2 = 0.5f * (qw * gx + qy * gz - qz * gy)
        var qDot3 = 0.5f * (qw * gy - qx * gz + qz * gx)
        var qDot4 = 0.5f * (qw * gz + qx * gy - qy * gx)

        val accNorm = sqrt(ax * ax + ay * ay + az * az)
        if (accNorm > 0.001f) {
            val axn = ax / accNorm
            val ayn = ay / accNorm
            val azn = az / accNorm

            val _2q0 = 2f * qw
            val _2q1 = 2f * qx
            val _2q2 = 2f * qy
            val _2q3 = 2f * qz
            val _4q0 = 4f * qw
            val _4q1 = 4f * qx
            val _4q2 = 4f * qy
            val _8q1 = 8f * qx
            val _8q2 = 8f * qy
            val q0q0 = qw * qw
            val q1q1 = qx * qx
            val q2q2 = qy * qy
            val q3q3 = qz * qz

            var s0 = _4q0 * q2q2 + _2q2 * axn + _4q0 * q1q1 - _2q1 * ayn
            var s1 = _4q1 * q3q3 - _2q3 * axn + 4f * q0q0 * qx - _2q0 * ayn -
                _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * azn
            var s2 = 4f * q0q0 * qy + _2q0 * axn + _4q2 * q3q3 - _2q3 * ayn -
                _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * azn
            var s3 = 4f * q1q1 * qz - _2q1 * axn + 4f * q2q2 * qz - _2q2 * ayn

            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-6f) {
                val invSNorm = 1f / sNorm
                s0 *= invSNorm
                s1 *= invSNorm
                s2 *= invSNorm
                s3 *= invSNorm
                qDot1 -= beta * s0
                qDot2 -= beta * s1
                qDot3 -= beta * s2
                qDot4 -= beta * s3
            }
        }

        integrateAndNormalize(qDot1, qDot2, qDot3, qDot4, dtSec)
    }

    private fun integrateAndNormalize(
        qDot1: Float,
        qDot2: Float,
        qDot3: Float,
        qDot4: Float,
        dtSec: Float
    ) {
        qw += qDot1 * dtSec
        qx += qDot2 * dtSec
        qy += qDot3 * dtSec
        qz += qDot4 * dtSec

        val norm = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (norm > 1e-6f) {
            val inv = 1f / norm
            qw *= inv
            qx *= inv
            qy *= inv
            qz *= inv
        } else {
            reset()
        }
    }

    companion object {
        const val DEFAULT_BETA = 0.08f
        private const val MIN_VALID_MAG_UT = 15f
        private const val MAX_VALID_MAG_UT = 90f
    }
}
