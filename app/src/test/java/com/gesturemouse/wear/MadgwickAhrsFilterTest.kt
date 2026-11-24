package com.gesturemouse.wear

import com.gesturemouse.wear.sensor.MadgwickAhrsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class MadgwickAhrsFilterTest {

    @Test
    fun nineDofFilterMaintainsUnitQuaternionAndTracksTiltedGravity() {
        val filter = MadgwickAhrsFilter(beta = 0.15f)
        val invSqrt2 = 0.70710677f

        // Hold watch stationary tilted 45° around X-axis with realistic Earth magnetic field (~45 µT)
        repeat(400) {
            filter.update(
                gx = 0f, gy = 0f, gz = 0f,
                ax = 0f, ay = 9.81f * invSqrt2, az = 9.81f * invSqrt2,
                mx = 22f, my = 15f, mz = -36f,
                dtSec = 0.01f
            )
        }

        val qNorm = sqrt(
            filter.qw * filter.qw +
                filter.qx * filter.qx +
                filter.qy * filter.qy +
                filter.qz * filter.qz
        )
        assertEquals("Quaternion must remain normalized", 1f, qNorm, 0.001f)
        assertEquals("Gravity Y should converge to +0.707", invSqrt2, filter.gravityY, 0.08f)
        assertEquals("Gravity Z should converge to +0.707", invSqrt2, filter.gravityZ, 0.08f)
    }

    @Test
    fun magneticInterferenceFallsBackCleanlyToSixDofWithoutNan() {
        val filter = MadgwickAhrsFilter()

        // Strong magnet distortion (250 µT — well above 90 µT guard)
        repeat(200) {
            filter.update(
                gx = 0.2f, gy = -0.1f, gz = 0.3f,
                ax = 0f, ay = 0f, az = 9.81f,
                mx = 180f, my = -160f, mz = 50f,
                dtSec = 0.01f
            )
        }

        assertTrue("qw must be finite", filter.qw.isFinite())
        assertTrue("qx must be finite", filter.qx.isFinite())
        assertTrue("qy must be finite", filter.qy.isFinite())
        assertTrue("qz must be finite", filter.qz.isFinite())

        val qNorm = sqrt(
            filter.qw * filter.qw +
                filter.qx * filter.qx +
                filter.qy * filter.qy +
                filter.qz * filter.qz
        )
        assertEquals("Unit quaternion preserved during 6-DoF fallback", 1f, qNorm, 0.001f)
    }
}
