package com.gesturemouse.wear

import com.gesturemouse.wear.model.AccelerationCurve
import com.gesturemouse.wear.model.Handedness
import com.gesturemouse.wear.model.ImuSample
import com.gesturemouse.wear.model.SettingsState
import com.gesturemouse.wear.sensor.CursorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMapperTest {

    @Test
    fun stationaryMicroNoiseIsRejectedByDeadzone() {
        val mapper = CursorMapper(SettingsState(cursorSensitivity = 1.5f))
        mapper.mapSample(sample(0L, gyroX = 0.01f, gyroY = 0.01f, gyroZ = 0.02f))
        val delta = mapper.mapSample(sample(20L, gyroX = 0.02f, gyroY = -0.02f, gyroZ = 0.02f))

        assertTrue("Tiny gyro bias should remain inside deadzone", delta.isZero)
    }

    @Test
    fun sweepingRightProducesPositiveDx() {
        val mapper = CursorMapper(SettingsState(cursorSensitivity = 1.2f, smoothingFactor = 0.2f))
        mapper.mapSample(sample(0L))

        var totalDx = 0
        for (t in 10L..100L step 10L) {
            // Negative gyroZ around +Z gravity = sweeping forearm clockwise (right)
            totalDx += mapper.mapSample(sample(t, gyroZ = -1.2f)).dx
        }
        assertTrue("Expected positive horizontal movement (dx=$totalDx)", totalDx > 20)
    }

    @Test
    fun tiltingUpProducesNegativeHidDyOnBothWrists() {
        val leftMapper = CursorMapper(SettingsState(handedness = Handedness.LEFT, smoothingFactor = 0.1f))
        leftMapper.mapSample(sample(0L))
        var leftDy = 0
        for (t in 10L..80L step 10L) {
            leftDy += leftMapper.mapSample(sample(t, gyroY = -1.2f)).dy
        }

        val rightMapper = CursorMapper(SettingsState(handedness = Handedness.RIGHT, smoothingFactor = 0.1f))
        rightMapper.mapSample(sample(0L))
        var rightDy = 0
        for (t in 10L..80L step 10L) {
            rightDy += rightMapper.mapSample(sample(t, gyroY = 1.2f)).dy
        }

        assertTrue("Left wrist tilt up should move cursor up (negative dy=$leftDy)", leftDy < -15)
        assertTrue("Right wrist tilt up should move cursor up (negative dy=$rightDy)", rightDy < -15)
    }

    @Test
    fun clickStabilizationDampensCursorDuringRecoil() {
        val normalMapper = CursorMapper(SettingsState(smoothingFactor = 0f))
        val frozenMapper = CursorMapper(SettingsState(smoothingFactor = 0f))

        normalMapper.mapSample(sample(0L))
        frozenMapper.mapSample(sample(0L))
        frozenMapper.triggerClickStabilization(nowMs = 10L, durationMs = 100L)

        val normalDx = normalMapper.mapSample(sample(20L, gyroZ = -1.5f)).dx
        val frozenDx = frozenMapper.mapSample(sample(20L, gyroZ = -1.5f)).dx

        assertTrue("Frozen dx ($frozenDx) should be much smaller than normal dx ($normalDx)", frozenDx < normalDx / 3)
    }

    @Test
    fun scrollClutchMapsPitchToWheelTicks() {
        val mapper = CursorMapper(SettingsState(cursorSensitivity = 1.5f, smoothingFactor = 0.1f))
        mapper.setScrollClutch(true)
        mapper.mapSample(sample(0L))

        var totalWheel = 0
        for (t in 10L..250L step 10L) {
            val delta = mapper.mapSample(sample(t, gyroY = -1.8f))
            assertEquals(0, delta.dx)
            assertEquals(0, delta.dy)
            totalWheel += delta.wheel
        }

        assertTrue("Expected positive vertical scroll wheel ticks ($totalWheel)", totalWheel > 0)
    }

    @Test
    fun rollInvariantHorizonTracksPureHorizontalSweepWhenWristTilted45Degrees() {
        val mapper = CursorMapper(SettingsState(cursorSensitivity = 1.2f, smoothingFactor = 0.1f))
        val invSqrt2 = 0.70710677f
        // Gravity tilted 45° around forearm X axis: g = (0, 0.707, 0.707)
        val tiltedInit = ImuSample(
            timestampMs = 0L,
            linearAccX = 0f, linearAccY = 0f, linearAccZ = 0f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            gravityX = 0f, gravityY = invSqrt2, gravityZ = invSqrt2
        )
        mapper.mapSample(tiltedInit)

        var totalDx = 0
        var totalDy = 0
        for (t in 10L..100L step 10L) {
            // Pure horizontal world yaw around gravity vector -> gyro = -1.5 * g
            val delta = mapper.mapSample(
                tiltedInit.copy(
                    timestampMs = t,
                    gyroY = -1.5f * invSqrt2,
                    gyroZ = -1.5f * invSqrt2
                )
            )
            totalDx += delta.dx
            totalDy += delta.dy
        }

        assertTrue("Expected strong rightward dx ($totalDx)", totalDx > 25)
        assertEquals("Pure horizontal yaw on tilted wrist should produce zero vertical dy", 0, totalDy)
    }

    @Test
    fun invertYReversesVerticalCursorAndScrollDirection() {
        val normalMapper = CursorMapper(
            SettingsState(handedness = Handedness.LEFT, invertY = false, smoothingFactor = 0.1f)
        )
        val invertedMapper = CursorMapper(
            SettingsState(handedness = Handedness.LEFT, invertY = true, smoothingFactor = 0.1f)
        )
        normalMapper.mapSample(sample(0L))
        invertedMapper.mapSample(sample(0L))

        var normalDy = 0
        var invertedDy = 0
        for (t in 10L..80L step 10L) {
            normalDy += normalMapper.mapSample(sample(t, gyroY = -1.2f)).dy
            invertedDy += invertedMapper.mapSample(sample(t, gyroY = -1.2f)).dy
        }

        assertTrue("Normal pitch up should move cursor up (negative dy=$normalDy)", normalDy < -20)
        assertTrue("Inverted Y should flip dy sign (positive dy=$invertedDy)", invertedDy > 20)
        assertEquals(-normalDy, invertedDy)

        normalMapper.reset()
        invertedMapper.reset()
        normalMapper.setScrollClutch(true)
        invertedMapper.setScrollClutch(true)
        normalMapper.mapSample(sample(0L))
        invertedMapper.mapSample(sample(0L))

        var normalWheel = 0
        var invertedWheel = 0
        for (t in 10L..250L step 10L) {
            normalWheel += normalMapper.mapSample(sample(t, gyroY = -1.8f)).wheel
            invertedWheel += invertedMapper.mapSample(sample(t, gyroY = -1.8f)).wheel
        }
        assertTrue("Normal scroll clutch wheel ($normalWheel) should be positive", normalWheel > 0)
        assertEquals("Inverted Y should flip scroll clutch direction", -normalWheel, invertedWheel)
    }

    @Test
    fun invertXReversesHorizontalCursorDirection() {
        val normalMapper = CursorMapper(SettingsState(invertX = false, smoothingFactor = 0.1f))
        val invertedMapper = CursorMapper(SettingsState(invertX = true, smoothingFactor = 0.1f))
        normalMapper.mapSample(sample(0L))
        invertedMapper.mapSample(sample(0L))

        var normalDx = 0
        var invertedDx = 0
        for (t in 10L..80L step 10L) {
            normalDx += normalMapper.mapSample(sample(t, gyroZ = -1.2f)).dx
            invertedDx += invertedMapper.mapSample(sample(t, gyroZ = -1.2f)).dx
        }

        assertTrue("Normal rightward sweep should produce positive dx ($normalDx)", normalDx > 20)
        assertEquals("Inverted X should negate dx", -normalDx, invertedDx)
    }

    @Test
    fun independentAxisSpeedsAndPointerAccelerationScaleCursorTravel() {
        val slowLinearMapper = CursorMapper(
            SettingsState(
                cursorSensitivity = 1.0f,
                horizontalSpeedScale = 1.0f,
                verticalSpeedScale = 1.0f,
                pointerAcceleration = 0.0f,
                smoothingFactor = 0.0f
            )
        )
        val boostedMapper = CursorMapper(
            SettingsState(
                cursorSensitivity = 2.4f,
                horizontalSpeedScale = 1.5f,
                verticalSpeedScale = 2.0f,
                pointerAcceleration = 1.8f,
                smoothingFactor = 0.0f
            )
        )
        slowLinearMapper.mapSample(sample(0L))
        boostedMapper.mapSample(sample(0L))

        var slowDx = 0
        var slowDy = 0
        var fastDx = 0
        var fastDy = 0
        for (t in 10L..100L step 10L) {
            val s = sample(t, gyroY = -1.1f, gyroZ = -1.1f)
            val dSlow = slowLinearMapper.mapSample(s)
            val dFast = boostedMapper.mapSample(s)
            slowDx += dSlow.dx
            slowDy += dSlow.dy
            fastDx += dFast.dx
            fastDy += dFast.dy
        }

        assertTrue("Boosted horizontal travel ($fastDx) should vastly exceed linear ($slowDx)", fastDx > slowDx * 3)
        assertTrue("Boosted vertical travel ($fastDy) should vastly exceed linear ($slowDy)", fastDy < slowDy * 3)
        assertTrue("Higher vertical scale should move faster vertically than horizontally", kotlin.math.abs(fastDy) > fastDx)
    }

    @Test
    fun sideButtonFreezeClutchHaltsCursorCompletely() {
        val mapper = CursorMapper(SettingsState(cursorSensitivity = 2.0f, smoothingFactor = 0.0f))
        mapper.mapSample(sample(0L))
        mapper.setClutchFrozen(true)

        for (t in 10L..100L step 10L) {
            val delta = mapper.mapSample(sample(t, gyroY = -2.0f, gyroZ = -2.0f))
            assertTrue("Frozen clutch must suppress all cursor motion", delta.isZero)
        }

        // Releasing clutch resumes smooth motion without backlog jump
        mapper.setClutchFrozen(false)
        val firstResumed = mapper.mapSample(sample(110L, gyroZ = -1.2f))
        assertTrue("Resuming after clutch should move right smoothly (${firstResumed.dx})", firstResumed.dx in 1..60)
    }

    @Test
    fun sideButtonPrecisionModeScalesTravelToThirtyPercent() {
        val normalMapper = CursorMapper(SettingsState(cursorSensitivity = 1.6f, smoothingFactor = 0.0f))
        val precisionMapper = CursorMapper(SettingsState(cursorSensitivity = 1.6f, smoothingFactor = 0.0f))
        precisionMapper.setPrecisionMode(true)

        normalMapper.mapSample(sample(0L))
        precisionMapper.mapSample(sample(0L))

        var normalDx = 0
        var precisionDx = 0
        for (t in 10L..120L step 10L) {
            val s = sample(t, gyroZ = -1.1f)
            normalDx += normalMapper.mapSample(s).dx
            precisionDx += precisionMapper.mapSample(s).dx
        }

        val ratio = precisionDx.toFloat() / normalDx.toFloat()
        assertTrue("Expected precision ratio ~0.30 (actual=$ratio, normal=$normalDx, precision=$precisionDx)", ratio in 0.24f..0.36f)
    }

    @Test
    fun velocityAccelerationCurvesDistinguishMicroAimFromFastFlicks() {
        val linear = CursorMapper(SettingsState(accelerationCurve = AccelerationCurve.LINEAR, pointerAcceleration = 1.2f))
        val precision = CursorMapper(SettingsState(accelerationCurve = AccelerationCurve.PRECISION, pointerAcceleration = 1.2f))
        val balanced = CursorMapper(SettingsState(accelerationCurve = AccelerationCurve.BALANCED, pointerAcceleration = 1.2f))
        val dynamicExpo = CursorMapper(SettingsState(accelerationCurve = AccelerationCurve.DYNAMIC_EXPONENTIAL, pointerAcceleration = 1.2f))

        assertEquals(1f, linear.computeAccelerationMultiplier(0.25f), 0.001f)
        assertEquals(1f, linear.computeAccelerationMultiplier(2.4f), 0.001f)

        val precisionSlow = precision.computeAccelerationMultiplier(0.25f)
        val balancedSlow = balanced.computeAccelerationMultiplier(0.25f)
        val balancedFast = balanced.computeAccelerationMultiplier(2.4f)
        val expoFast = dynamicExpo.computeAccelerationMultiplier(2.4f)

        assertTrue("Precision curve should damp micro-aim ($precisionSlow < $balancedSlow)", precisionSlow < balancedSlow)
        assertTrue("Balanced curve should exponentially boost fast flicks ($balancedFast > $balancedSlow * 2)", balancedFast > balancedSlow * 2f)
        assertTrue("Dynamic Expo should boost fast flicks even more than Balanced ($expoFast > $balancedFast)", expoFast > balancedFast)
    }

    @Test
    fun hybridMicroTrackpadNudgesPreciselyAndDampsConcurrentAirGyro() {
        val mapper = CursorMapper(
            SettingsState(
                cursorSensitivity = 1.6f,
                hybridMicroTrackpadEnabled = true,
                smoothingFactor = 0.0f
            )
        )
        mapper.mapSample(sample(0L))

        val touchNudge = mapper.mapMicroTrackpadDrag(dxPx = 14f, dyPx = -10f, nowMs = 100L)
        assertTrue("Micro-trackpad slide right should yield positive dx (${touchNudge.dx})", touchNudge.dx > 0)
        assertTrue("Micro-trackpad slide up should yield negative dy (${touchNudge.dy})", touchNudge.dy < 0)

        // Air gyro movement immediately during touch drag (within 160ms damping window) should be heavily damped
        val dampedAir = mapper.mapSample(sample(140L, gyroZ = -1.2f))
        val undampedAir = mapper.mapSample(sample(400L, gyroZ = -1.2f))
        assertTrue(
            "Air gyro during finger slide ($dampedAir) should be heavily damped compared to normal ($undampedAir)",
            dampedAir.dx < undampedAir.dx / 2
        )
    }

    private fun sample(
        t: Long,
        gyroX: Float = 0f,
        gyroY: Float = 0f,
        gyroZ: Float = 0f
    ) = ImuSample(
        timestampMs = t,
        linearAccX = 0f,
        linearAccY = 0f,
        linearAccZ = 0f,
        gyroX = gyroX,
        gyroY = gyroY,
        gyroZ = gyroZ,
        gravityX = 0f,
        gravityY = 0f,
        gravityZ = 1f
    )
}
