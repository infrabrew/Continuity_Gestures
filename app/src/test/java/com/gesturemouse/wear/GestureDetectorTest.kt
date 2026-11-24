package com.gesturemouse.wear

import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.Handedness
import com.gesturemouse.wear.model.ImuSample
import com.gesturemouse.wear.model.SettingsState
import com.gesturemouse.wear.sensor.GestureDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDetectorTest {

    @Test
    fun singlePinchDetectedAfterDisambiguationWindow() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.2f, debounceMs = 200L))
        val emitted = mutableListOf<GestureEvent>()

        // Resting baseline
        for (t in 0L..60L step 20L) {
            emitted += detector.process(restingSample(t))
        }
        assertTrue(emitted.isEmpty())

        // Tendon snap pinch impulse on dorsal Z axis
        emitted += detector.process(
            ImuSample(
                timestampMs = 100L,
                linearAccX = 0.6f,
                linearAccY = 0.4f,
                linearAccZ = 4.6f,
                gyroX = 0.1f,
                gyroY = 0.1f,
                gyroZ = 0.1f
            )
        )

        // Quiet samples until double-pinch window (340 ms) elapses
        for (t in 120L..480L step 20L) {
            emitted += detector.process(restingSample(t))
        }

        assertEquals(1, emitted.size)
        assertTrue(emitted.first() is GestureEvent.SinglePinch)
    }

    @Test
    fun doublePinchDetectedWhenSecondImpulseArrivesWithinWindow() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.2f, debounceMs = 180L))
        val emitted = mutableListOf<GestureEvent>()

        emitted += detector.process(restingSample(0L))
        // First pinch at 100 ms
        emitted += detector.process(pinchSample(100L, accZ = 4.5f))
        for (t in 120L..300L step 20L) {
            emitted += detector.process(restingSample(t))
        }
        // Second pinch at 320 ms (220 ms after first -> past 180 ms debounce, within 340 ms window)
        emitted += detector.process(pinchSample(320L, accZ = 4.5f))
        emitted += detector.process(restingSample(340L))

        assertEquals(1, emitted.size)
        assertTrue(emitted.first() is GestureEvent.DoublePinch)
    }

    @Test
    fun pinchHoldEmitsStartAndEndDuringSustainedIsometricTension() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.2f, calibratedNoiseFloor = 0.4f))
        val emitted = mutableListOf<GestureEvent>()

        emitted += detector.process(restingSample(0L))
        // Initial pinch snap at t = 100 ms
        emitted += detector.process(pinchSample(100L, accZ = 4.8f))

        // Sustained isometric finger micro-tremor (sub-threshold alternating signal)
        var sign = 1f
        for (t in 110L..460L step 10L) {
            sign = -sign
            emitted += detector.process(
                ImuSample(
                    timestampMs = t,
                    linearAccX = 0.3f * sign,
                    linearAccY = 0.3f * sign,
                    linearAccZ = 1.15f * sign,
                    gyroX = 0f,
                    gyroY = 0f,
                    gyroZ = 0f
                )
            )
        }

        assertTrue(
            "Expected PinchHoldStart during sustained tension",
            emitted.any { it is GestureEvent.PinchHoldStart }
        )

        // Relax hand completely -> should emit PinchHoldEnd after release window
        for (t in 480L..760L step 20L) {
            emitted += detector.process(restingSample(t))
        }

        assertTrue(
            "Expected PinchHoldEnd after relaxing grip",
            emitted.any { it is GestureEvent.PinchHoldEnd }
        )
    }

    @Test
    fun palmUpPinchDetectedWhenWatchFacePointsDownward() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.0f))
        detector.process(restingSample(0L, gravityZ = -0.95f))

        val events = detector.process(
            ImuSample(
                timestampMs = 100L,
                linearAccX = 0.2f,
                linearAccY = 0.2f,
                linearAccZ = 4.4f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 0f,
                gravityX = 0f,
                gravityY = 0f,
                gravityZ = -0.95f
            )
        )

        assertEquals(1, events.size)
        assertTrue(events.first() is GestureEvent.PalmUpPinch)
    }

    @Test
    fun pinchIsRejectedDuringFastArmSwingGyroGate() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.0f, flickThreshold = 4.2f))
        detector.process(restingSample(0L))

        val events = detector.process(
            ImuSample(
                timestampMs = 100L,
                linearAccX = 0f,
                linearAccY = 0f,
                linearAccZ = 4.8f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = -3.8f // High macro swing above gyro gate (3.15 rad/s)
            )
        )
        for (t in 120L..500L step 20L) {
            detector.process(restingSample(t))
        }

        assertTrue(events.none { it is GestureEvent.SinglePinch })
    }

    @Test
    fun flickRightAndReboundSuppressionWorkCorrectly() {
        val detector = GestureDetector(SettingsState(flickThreshold = 4.2f))
        detector.process(restingSample(0L))

        // Sweeping arm right -> negative gyroZ around +Z gravity
        val flickEvents = detector.process(
            ImuSample(
                timestampMs = 100L,
                linearAccX = 0f,
                linearAccY = 0f,
                linearAccZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = -5.2f
            )
        )
        assertEquals(1, flickEvents.size)
        assertTrue(flickEvents.first() is GestureEvent.FlickRight)

        // Immediate return swing 120 ms later must be suppressed as rebound
        val reboundEvents = detector.process(
            ImuSample(
                timestampMs = 220L,
                linearAccX = 0f,
                linearAccY = 0f,
                linearAccZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 5.2f
            )
        )
        assertTrue("Rebound flick should be suppressed", reboundEvents.isEmpty())
    }

    @Test
    fun pitchFlickUpMirrorsCorrectlyForRightWrist() {
        val rightWristDetector = GestureDetector(
            SettingsState(handedness = Handedness.RIGHT, flickThreshold = 4.0f)
        )
        rightWristDetector.process(restingSample(0L))

        // On right wrist, tilting fingers UP produces positive gyroY
        val events = rightWristDetector.process(
            ImuSample(
                timestampMs = 100L,
                linearAccX = 0f,
                linearAccY = 0f,
                linearAccZ = 0f,
                gyroX = 0f,
                gyroY = 5.0f,
                gyroZ = 0f
            )
        )
        assertEquals(1, events.size)
        assertTrue(events.first() is GestureEvent.FlickUp)
    }

    @Test
    fun sustainedForearmRollEmitsWristTwist() {
        val detector = GestureDetector(SettingsState(handedness = Handedness.LEFT))
        val emitted = mutableListOf<GestureEvent>()

        for (t in 0L..250L step 10L) {
            emitted += detector.process(
                ImuSample(
                    timestampMs = t,
                    linearAccX = 0f,
                    linearAccY = 0f,
                    linearAccZ = 0f,
                    gyroX = 2.4f, // Deliberate forearm roll (~137 deg/s)
                    gyroY = 0f,
                    gyroZ = 0f
                )
            )
        }

        val twists = emitted.filterIsInstance<GestureEvent.WristTwist>()
        assertTrue("Expected at least one WristTwist event", twists.isNotEmpty())
        assertTrue(twists.first().deltaDegrees > 0f)
    }

    @Test
    fun doublePinchStillWorksWithHighDebounceSetting() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.2f, debounceMs = 450L))
        val emitted = mutableListOf<GestureEvent>()

        emitted += detector.process(restingSample(0L))
        // First pinch at 100 ms
        emitted += detector.process(pinchSample(100L, accZ = 4.6f))
        for (t in 120L..260L step 20L) {
            emitted += detector.process(restingSample(t))
        }
        // Second pinch at 280 ms (180 ms gap: past 140 ms double-tap guard, well under 450 ms inter-gesture debounce)
        emitted += detector.process(pinchSample(280L, accZ = 4.6f))
        emitted += detector.process(restingSample(300L))

        assertEquals(1, emitted.size)
        assertTrue(emitted.first() is GestureEvent.DoublePinch)
    }

    @Test
    fun palmUpHorizontalFlickEmitsHandsFreeModeSwitch() {
        val detector = GestureDetector(SettingsState(flickThreshold = 4.0f, gestureModeSwitchEnabled = true))
        detector.process(restingSample(0L, gravityZ = -0.95f))

        // With gravityZ = -0.95, yawRight = -(gyroZ * -1) = +gyroZ
        val forwardEvents = detector.process(
            ImuSample(
                timestampMs = 100L,
                linearAccX = 0f,
                linearAccY = 0f,
                linearAccZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 5.0f,
                gravityZ = -0.95f
            )
        )
        assertEquals(1, forwardEvents.size)
        val forward = forwardEvents.first() as GestureEvent.ModeSwitch
        assertTrue("Expected forward ModeSwitch", forward.forward)

        // After rebound cooldown (380 ms), palm-up flick left emits backward ModeSwitch
        val backwardEvents = detector.process(
            ImuSample(
                timestampMs = 600L,
                linearAccX = 0f,
                linearAccY = 0f,
                linearAccZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = -5.0f,
                gravityZ = -0.95f
            )
        )
        assertEquals(1, backwardEvents.size)
        val backward = backwardEvents.first() as GestureEvent.ModeSwitch
        assertTrue("Expected backward ModeSwitch", !backward.forward)
    }

    @Test
    fun flushPendingEmitsSinglePinchAfterDoublePinchWindow() {
        val detector = GestureDetector(SettingsState(pinchThreshold = 3.0f))
        detector.process(restingSample(0L))
        detector.process(pinchSample(100L, accZ = 4.5f))

        val flushed = detector.flushPending(500L)
        assertEquals(1, flushed.size)
        assertTrue(flushed.first() is GestureEvent.SinglePinch)
    }

    private fun restingSample(timestampMs: Long, gravityZ: Float = 1f) = ImuSample(
        timestampMs = timestampMs,
        linearAccX = 0f,
        linearAccY = 0f,
        linearAccZ = 0f,
        gyroX = 0f,
        gyroY = 0f,
        gyroZ = 0f,
        gravityZ = gravityZ
    )

    private fun pinchSample(timestampMs: Long, accZ: Float) = ImuSample(
        timestampMs = timestampMs,
        linearAccX = 0.4f,
        linearAccY = 0.3f,
        linearAccZ = accZ,
        gyroX = 0.05f,
        gyroY = 0.05f,
        gyroZ = 0.05f
    )
}
