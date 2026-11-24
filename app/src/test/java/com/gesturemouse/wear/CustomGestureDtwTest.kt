package com.gesturemouse.wear

import com.gesturemouse.wear.gesture.CustomGestureDtwEngine
import com.gesturemouse.wear.gesture.CustomGestureTemplate
import com.gesturemouse.wear.model.CustomGestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CustomGestureDtwTest {

    @Test
    fun buildTemplateMatchesTimeWarpedRepeatAndRejectsOrthogonalGesture() {
        val sample1 = circleGesture(frameCount = 24, amplitude = 2.6f)
        val sample2 = circleGesture(frameCount = 30, amplitude = 2.9f)
        val sample3 = circleGesture(frameCount = 20, amplitude = 2.3f)

        val template = CustomGestureDtwEngine.buildTemplate(
            id = "g1",
            name = "Circle Copy",
            action = CustomGestureAction.COPY,
            samples = listOf(sample1, sample2, sample3)
        )
        assertNotNull("Expected valid template built from 3 circle samples", template)
        val tpl = template!!
        assertEquals(CustomGestureTemplate.TEMPLATE_POINTS * 3, tpl.points.size)

        // Repeat gesture at slower speed (36 frames) should match cleanly
        val slowRepeat = circleGesture(frameCount = 36, amplitude = 2.5f)
        val matched = CustomGestureDtwEngine.match(slowRepeat, listOf(tpl))
        assertNotNull("Slow time-warped repeat should match template", matched)
        assertEquals("g1", matched!!.id)

        // Completely orthogonal straight Z-axis oscillation should be rejected
        val orthogonal = zWaveGesture(frameCount = 28, amplitude = 2.8f)
        val rejected = CustomGestureDtwEngine.match(orthogonal, listOf(tpl))
        assertNull("Orthogonal gesture should not match circle template", rejected)
    }

    @Test
    fun serializeAndDeserializeRoundTripPreservesTemplatesAndMatching() {
        val tpl1 = CustomGestureDtwEngine.buildTemplate(
            id = "copy_1",
            name = "Circle",
            action = CustomGestureAction.COPY,
            samples = listOf(circleGesture(24, 2.5f), circleGesture(26, 2.7f), circleGesture(22, 2.4f))
        )!!
        val tpl2 = CustomGestureDtwEngine.buildTemplate(
            id = "lock_2",
            name = "Z-Shake",
            action = CustomGestureAction.LOCK_SCREEN,
            samples = listOf(zWaveGesture(24, 2.5f), zWaveGesture(28, 2.6f), zWaveGesture(20, 2.4f))
        )!!

        val serialized = CustomGestureDtwEngine.serialize(listOf(tpl1, tpl2))
        val restored = CustomGestureDtwEngine.deserialize(serialized)

        assertEquals(2, restored.size)
        assertEquals("copy_1", restored[0].id)
        assertEquals(CustomGestureAction.COPY, restored[0].action)
        assertEquals("lock_2", restored[1].id)
        assertEquals(CustomGestureAction.LOCK_SCREEN, restored[1].action)

        val match = CustomGestureDtwEngine.match(zWaveGesture(30, 2.7f), restored)
        assertNotNull(match)
        assertEquals("lock_2", match!!.id)
        assertTrue(restored[0].points.size == CustomGestureTemplate.TEMPLATE_POINTS * 3)
    }

    private fun circleGesture(frameCount: Int, amplitude: Float): List<FloatArray> =
        List(frameCount) { i ->
            val theta = (2.0 * PI * i) / (frameCount - 1)
            floatArrayOf(
                (amplitude * cos(theta)).toFloat(),
                (amplitude * sin(theta)).toFloat(),
                0.08f
            )
        }

    private fun zWaveGesture(frameCount: Int, amplitude: Float): List<FloatArray> =
        List(frameCount) { i ->
            val theta = (2.0 * PI * i) / (frameCount - 1)
            floatArrayOf(
                0.05f,
                -0.05f,
                (amplitude * sin(theta)).toFloat()
            )
        }
}
