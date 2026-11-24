package com.gesturemouse.wear

import com.gesturemouse.wear.sensor.PalmCoverDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmCoverDetectorTest {

    @Test
    fun sharpDropFromRoomLightTriggersOnceUntilUncovered() {
        val detector = PalmCoverDetector()

        // Establish normal indoor lighting baseline (~120 lux)
        assertFalse(detector.onLuxSample(120f, 0L))
        assertFalse(detector.onLuxSample(115f, 100L))
        assertFalse(detector.onLuxSample(118f, 200L))

        // Hand covers watch face -> lux plummets to 0.5
        assertTrue("Expected palm cover trigger on sharp lux drop", detector.onLuxSample(0.5f, 300L))

        // Staying covered must not re-trigger
        assertFalse(detector.onLuxSample(0.0f, 500L))
        assertFalse(detector.onLuxSample(1.0f, 1_500L))

        // Uncovering re-arms after cooldown
        assertFalse(detector.onLuxSample(95f, 1_700L))
        assertTrue("Second palm cover after uncover + cooldown should trigger", detector.onLuxSample(0.2f, 2_000L))
    }

    @Test
    fun sustainedDarkInDimRoomTriggersAfterHoldDuration() {
        val detector = PalmCoverDetector()

        // Dim room (~6 lux — below sharp-drop baseline)
        assertFalse(detector.onLuxSample(6f, 0L))
        assertFalse(detector.onLuxSample(1.0f, 100L))
        assertFalse(detector.onLuxSample(0.5f, 250L))
        // After >= 280 ms sustained near-zero cover
        assertTrue("Sustained dark palm cover should trigger in dim room", detector.onLuxSample(0.2f, 420L))
    }
}
