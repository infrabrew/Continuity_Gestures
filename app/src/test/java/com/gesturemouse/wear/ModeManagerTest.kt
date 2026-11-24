package com.gesturemouse.wear

import com.gesturemouse.wear.hid.HidDescriptors
import com.gesturemouse.wear.mode.HidAction
import com.gesturemouse.wear.mode.ModeManager
import com.gesturemouse.wear.model.CursorDelta
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.WristTwistMediaTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModeManagerTest {

    @Test
    fun mouseModeMapsPinchesDragAndFlickScroll() {
        val manager = ModeManager(InputMode.MOUSE)

        assertEquals(
            listOf(HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_LEFT)),
            manager.mapGesture(GestureEvent.SinglePinch(100L))
        )
        assertEquals(
            listOf(HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_RIGHT)),
            manager.mapGesture(GestureEvent.DoublePinch(200L))
        )
        assertEquals(
            listOf(HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_MIDDLE)),
            manager.mapGesture(GestureEvent.PalmUpPinch(300L))
        )

        // Hold drag latches left button while moving cursor
        manager.mapGesture(GestureEvent.PinchHoldStart(400L))
        val moveAction = manager.mapCursorDelta(CursorDelta(dx = 10, dy = -5))
        assertEquals(
            HidAction.MouseState(buttons = HidDescriptors.MOUSE_BUTTON_LEFT, dx = 10, dy = -5),
            moveAction
        )

        // Switching mode releases held buttons cleanly
        val cleanup = manager.setMode(InputMode.ARROW_KEYS)
        assertEquals(listOf(HidAction.MouseState(buttons = HidDescriptors.MOUSE_BUTTON_NONE)), cleanup)
        assertEquals(HidDescriptors.MOUSE_BUTTON_NONE, manager.heldMouseButtons)
    }

    @Test
    fun arrowKeysModeMapsNavigationAndSuppressesCursorDrift() {
        val manager = ModeManager(InputMode.ARROW_KEYS)

        assertNull(manager.mapCursorDelta(CursorDelta(dx = 15, dy = 12)))
        assertEquals(
            listOf(HidAction.KeyTap(HidDescriptors.KeyCode.ENTER)),
            manager.mapGesture(GestureEvent.SinglePinch(100L))
        )
        assertEquals(
            listOf(HidAction.KeyTap(HidDescriptors.KeyCode.ESCAPE)),
            manager.mapGesture(GestureEvent.PalmUpPinch(200L))
        )
        assertEquals(
            listOf(HidAction.KeyTap(HidDescriptors.KeyCode.RIGHT_ARROW)),
            manager.mapGesture(GestureEvent.FlickRight(300L))
        )
    }

    @Test
    fun mediaModeMapsPlayPauseTracksAndTwistVolume() {
        val manager = ModeManager(InputMode.MEDIA_CONTROLS)

        assertNull(manager.mapCursorDelta(CursorDelta(dx = 8, dy = 4)))
        assertEquals(
            listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.PLAY_PAUSE)),
            manager.mapGesture(GestureEvent.SinglePinch(100L))
        )
        assertEquals(
            listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.NEXT_TRACK)),
            manager.mapGesture(GestureEvent.FlickRight(200L))
        )
        assertEquals(
            listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.VOLUME_UP)),
            manager.mapGesture(GestureEvent.WristTwist(300L, deltaDegrees = 20f))
        )
        assertEquals(
            listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.VOLUME_DOWN)),
            manager.mapGesture(GestureEvent.WristTwist(400L, deltaDegrees = -20f))
        )
    }

    @Test
    fun presenterModeAllowsLaserCursorAndSlideNavigation() {
        val manager = ModeManager(InputMode.PRESENTER)

        assertNotNull(manager.mapCursorDelta(CursorDelta(dx = 4, dy = -3)))
        assertEquals(
            listOf(HidAction.KeyTap(HidDescriptors.KeyCode.RIGHT_ARROW)),
            manager.mapGesture(GestureEvent.SinglePinch(100L))
        )
        assertEquals(
            listOf(HidAction.KeyTap(HidDescriptors.KeyCode.LEFT_ARROW)),
            manager.mapGesture(GestureEvent.PalmUpPinch(200L))
        )
        assertEquals(
            listOf(HidAction.KeyTap(HidDescriptors.KeyCode.KEY_B)),
            manager.mapGesture(GestureEvent.DoublePinch(300L))
        )
    }

    @Test
    fun mediaModeMapsWristTwistToBrightnessWhenConfigured() {
        val manager = ModeManager(
            initialMode = InputMode.MEDIA_CONTROLS,
            initialTwistMediaTarget = WristTwistMediaTarget.BRIGHTNESS
        )
        assertEquals(
            listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.BRIGHTNESS_UP)),
            manager.mapGesture(GestureEvent.WristTwist(100L, deltaDegrees = 22f))
        )
        assertEquals(
            listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.BRIGHTNESS_DOWN)),
            manager.mapGesture(GestureEvent.WristTwist(200L, deltaDegrees = -22f))
        )
    }

    @Test
    fun previousModeCyclesBackwardAndReleasesHeldButtons() {
        val manager = ModeManager(InputMode.MOUSE)
        manager.mapGesture(GestureEvent.PinchHoldStart(100L))

        val (prevMode, cleanup) = manager.previousMode()
        assertEquals(InputMode.PRESENTER, prevMode)
        assertEquals(listOf(HidAction.MouseState(buttons = HidDescriptors.MOUSE_BUTTON_NONE)), cleanup)
        assertEquals(HidDescriptors.MOUSE_BUTTON_NONE, manager.heldMouseButtons)
    }
}
