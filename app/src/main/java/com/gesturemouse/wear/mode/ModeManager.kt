package com.gesturemouse.wear.mode

import com.gesturemouse.wear.hid.HidDescriptors
import com.gesturemouse.wear.model.CursorDelta
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.WristTwistMediaTarget

/**
 * Concrete HID actions emitted by [ModeManager] for [com.gesturemouse.wear.hid.BluetoothHidService]
 * to transmit over Bluetooth HID.
 */
sealed interface HidAction {
    /** Immediate mouse state update (movement, scroll, or button hold). */
    data class MouseState(
        val buttons: Int,
        val dx: Int = 0,
        val dy: Int = 0,
        val wheel: Int = 0,
        val pan: Int = 0
    ) : HidAction

    /** Momentary mouse button press followed by automatic release. */
    data class MouseClick(
        val buttonMask: Int
    ) : HidAction

    /** Momentary keyboard keypress followed by automatic release. */
    data class KeyTap(
        val keyCode: Byte,
        val modifiers: Byte = 0x00
    ) : HidAction

    /** Momentary consumer media key press followed by automatic release. */
    data class ConsumerTap(
        val bitmask: Int
    ) : HidAction
}

/**
 * Stateful translator mapping `(InputMode, GestureEvent | CursorDelta)` into [HidAction]s.
 *
 * Pure Kotlin with zero Android dependencies so all mode bindings can be verified with fast
 * JVM unit tests.
 */
class ModeManager(
    initialMode: InputMode = InputMode.MOUSE,
    initialTwistMediaTarget: WristTwistMediaTarget = WristTwistMediaTarget.VOLUME
) {
    var currentMode: InputMode = initialMode
        private set

    /** Controls whether wrist rotation adjusts Volume or Display Brightness in Media mode. */
    var wristTwistMediaTarget: WristTwistMediaTarget = initialTwistMediaTarget
        private set

    /** Tracks currently held mouse buttons (e.g., left button held during PinchHold drag). */
    var heldMouseButtons: Int = HidDescriptors.MOUSE_BUTTON_NONE
        private set

    fun setWristTwistMediaTarget(target: WristTwistMediaTarget) {
        wristTwistMediaTarget = target
    }

    /**
     * Switches the active [InputMode] and releases any held mouse buttons to prevent stuck drags.
     */
    fun setMode(mode: InputMode): List<HidAction> {
        val cleanup = releaseHeldButtons()
        currentMode = mode
        return cleanup
    }

    fun cycleMode(): Pair<InputMode, List<HidAction>> {
        val next = currentMode.next()
        val cleanup = setMode(next)
        return next to cleanup
    }

    fun previousMode(): Pair<InputMode, List<HidAction>> {
        val prev = currentMode.previous()
        val cleanup = setMode(prev)
        return prev to cleanup
    }

    /**
     * Releases any latched mouse buttons (called on disconnect, pause, or mode switch).
     */
    fun releaseHeldButtons(): List<HidAction> {
        if (heldMouseButtons == HidDescriptors.MOUSE_BUTTON_NONE) return emptyList()
        heldMouseButtons = HidDescriptors.MOUSE_BUTTON_NONE
        return listOf(HidAction.MouseState(buttons = HidDescriptors.MOUSE_BUTTON_NONE))
    }

    /**
     * Converts continuous wrist cursor deltas into a mouse report when the active mode uses cursor pointing.
     */
    fun mapCursorDelta(delta: CursorDelta): HidAction? {
        if (delta.isZero) return null
        return when (currentMode) {
            InputMode.MOUSE,
            InputMode.PRESENTER -> HidAction.MouseState(
                buttons = heldMouseButtons,
                dx = delta.dx,
                dy = delta.dy,
                wheel = delta.wheel,
                pan = delta.pan
            )
            // Arrow keys and media modes intentionally suppress cursor drift
            InputMode.ARROW_KEYS,
            InputMode.MEDIA_CONTROLS -> null
        }
    }

    /**
     * Translates a discrete [GestureEvent] into zero or more [HidAction]s according to [currentMode].
     */
    fun mapGesture(event: GestureEvent): List<HidAction> = when (currentMode) {
        InputMode.MOUSE -> mapMouseMode(event)
        InputMode.ARROW_KEYS -> mapArrowKeysMode(event)
        InputMode.MEDIA_CONTROLS -> mapMediaControlsMode(event)
        InputMode.PRESENTER -> mapPresenterMode(event)
    }

    private fun mapMouseMode(event: GestureEvent): List<HidAction> = when (event) {
        is GestureEvent.SinglePinch -> listOf(
            HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_LEFT)
        )
        is GestureEvent.DoublePinch -> listOf(
            HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_RIGHT)
        )
        is GestureEvent.PinchHoldStart -> {
            heldMouseButtons = heldMouseButtons or HidDescriptors.MOUSE_BUTTON_LEFT
            listOf(HidAction.MouseState(buttons = heldMouseButtons))
        }
        is GestureEvent.PinchHoldEnd -> {
            heldMouseButtons = heldMouseButtons and HidDescriptors.MOUSE_BUTTON_LEFT.inv()
            listOf(HidAction.MouseState(buttons = heldMouseButtons))
        }
        is GestureEvent.PalmUpPinch -> listOf(
            HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_MIDDLE)
        )
        is GestureEvent.FlickUp -> listOf(
            HidAction.MouseState(buttons = heldMouseButtons, wheel = SCROLL_FLICK_STEP)
        )
        is GestureEvent.FlickDown -> listOf(
            HidAction.MouseState(buttons = heldMouseButtons, wheel = -SCROLL_FLICK_STEP)
        )
        is GestureEvent.FlickLeft -> listOf(
            HidAction.MouseState(buttons = heldMouseButtons, pan = -PAN_FLICK_STEP)
        )
        is GestureEvent.FlickRight -> listOf(
            HidAction.MouseState(buttons = heldMouseButtons, pan = PAN_FLICK_STEP)
        )
        is GestureEvent.WristTwist -> {
            val tick = if (event.deltaDegrees > 0f) -1 else 1
            listOf(HidAction.MouseState(buttons = heldMouseButtons, wheel = tick))
        }
        is GestureEvent.ModeSwitch -> emptyList()
    }

    private fun mapArrowKeysMode(event: GestureEvent): List<HidAction> = when (event) {
        is GestureEvent.SinglePinch -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.ENTER))
        is GestureEvent.DoublePinch -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.SPACE))
        is GestureEvent.PalmUpPinch -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.ESCAPE))
        is GestureEvent.FlickUp -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.UP_ARROW))
        is GestureEvent.FlickDown -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.DOWN_ARROW))
        is GestureEvent.FlickLeft -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.LEFT_ARROW))
        is GestureEvent.FlickRight -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.RIGHT_ARROW))
        is GestureEvent.WristTwist -> listOf(
            HidAction.KeyTap(
                if (event.deltaDegrees > 0f) HidDescriptors.KeyCode.DOWN_ARROW
                else HidDescriptors.KeyCode.UP_ARROW
            )
        )
        is GestureEvent.PinchHoldStart,
        is GestureEvent.PinchHoldEnd,
        is GestureEvent.ModeSwitch -> emptyList()
    }

    private fun mapMediaControlsMode(event: GestureEvent): List<HidAction> = when (event) {
        is GestureEvent.SinglePinch -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.PLAY_PAUSE))
        is GestureEvent.DoublePinch -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.MUTE))
        is GestureEvent.PalmUpPinch -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.PREV_TRACK))
        is GestureEvent.FlickRight -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.NEXT_TRACK))
        is GestureEvent.FlickLeft -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.PREV_TRACK))
        is GestureEvent.FlickUp -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.VOLUME_UP))
        is GestureEvent.FlickDown -> listOf(HidAction.ConsumerTap(HidDescriptors.ConsumerBit.VOLUME_DOWN))
        is GestureEvent.WristTwist -> {
            val bit = when (wristTwistMediaTarget) {
                WristTwistMediaTarget.VOLUME ->
                    if (event.deltaDegrees > 0f) HidDescriptors.ConsumerBit.VOLUME_UP
                    else HidDescriptors.ConsumerBit.VOLUME_DOWN
                WristTwistMediaTarget.BRIGHTNESS ->
                    if (event.deltaDegrees > 0f) HidDescriptors.ConsumerBit.BRIGHTNESS_UP
                    else HidDescriptors.ConsumerBit.BRIGHTNESS_DOWN
            }
            listOf(HidAction.ConsumerTap(bit))
        }
        is GestureEvent.PinchHoldStart,
        is GestureEvent.PinchHoldEnd,
        is GestureEvent.ModeSwitch -> emptyList()
    }

    private fun mapPresenterMode(event: GestureEvent): List<HidAction> = when (event) {
        is GestureEvent.SinglePinch,
        is GestureEvent.FlickRight -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.RIGHT_ARROW))
        is GestureEvent.PalmUpPinch,
        is GestureEvent.FlickLeft -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.LEFT_ARROW))
        is GestureEvent.DoublePinch -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.KEY_B))
        is GestureEvent.FlickUp -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.F5))
        is GestureEvent.FlickDown -> listOf(HidAction.KeyTap(HidDescriptors.KeyCode.ESCAPE))
        is GestureEvent.PinchHoldStart,
        is GestureEvent.PinchHoldEnd,
        is GestureEvent.WristTwist,
        is GestureEvent.ModeSwitch -> emptyList()
    }

    companion object {
        private const val SCROLL_FLICK_STEP = 4
        private const val PAN_FLICK_STEP = 3
    }
}
