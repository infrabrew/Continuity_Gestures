package com.gesturemouse.wear.mode

import com.gesturemouse.wear.hid.HidDescriptors
import com.gesturemouse.wear.model.AnalogRollAction
import com.gesturemouse.wear.model.CustomGestureAction
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.HostOsPreset

/**
 * Pure-Kotlin mapper translating 3D spatial gestures (Analog Forearm Roll Dial, Modifier + Flick
 * OS window management shortcuts, Custom DTW gesture actions, and Walk-Away Lock Screen) into
 * standard USB-HID keyboard/mouse/consumer actions tailored to Windows/Linux or macOS.
 */
object SpatialShortcutMapper {

    /**
     * Maps a signed analog forearm roll step (`+1` clockwise, `-1` counter-clockwise) while
     * holding the side-button spatial modifier into timeline scrubbing, zoom, or tab cycling.
     */
    fun mapAnalogRollStep(
        direction: Int,
        action: AnalogRollAction,
        os: HostOsPreset,
        heldMouseButtons: Int = HidDescriptors.MOUSE_BUTTON_NONE
    ): HidAction? {
        if (direction == 0 || action == AnalogRollAction.OFF) return null
        val cmdOrCtrl = primaryModifier(os)
        return when (action) {
            AnalogRollAction.TIMELINE_SCRUB ->
                HidAction.MouseState(buttons = heldMouseButtons, pan = direction * 2)

            AnalogRollAction.ZOOM ->
                HidAction.KeyTap(
                    keyCode = if (direction > 0) HidDescriptors.KeyCode.EQUAL_PLUS else HidDescriptors.KeyCode.MINUS,
                    modifiers = cmdOrCtrl
                )

            AnalogRollAction.CYCLE_TABS -> {
                val mods = if (direction > 0) {
                    HidDescriptors.Modifier.LEFT_CTRL
                } else {
                    (HidDescriptors.Modifier.LEFT_CTRL.toInt() or HidDescriptors.Modifier.LEFT_SHIFT.toInt()).toByte()
                }
                HidAction.KeyTap(keyCode = HidDescriptors.KeyCode.TAB, modifiers = mods)
            }

            AnalogRollAction.OFF -> null
        }
    }

    /**
     * Maps a sharp directional flick performed while holding the side-button clutch/modifier
     * into OS-level window management shortcuts (snap left/right, Mission Control / Task View, minimize).
     */
    fun mapModifierFlick(event: GestureEvent, os: HostOsPreset): HidAction? = when (os) {
        HostOsPreset.WINDOWS_LINUX -> when (event) {
            is GestureEvent.FlickLeft ->
                HidAction.KeyTap(HidDescriptors.KeyCode.LEFT_ARROW, HidDescriptors.Modifier.LEFT_GUI)
            is GestureEvent.FlickRight ->
                HidAction.KeyTap(HidDescriptors.KeyCode.RIGHT_ARROW, HidDescriptors.Modifier.LEFT_GUI)
            is GestureEvent.FlickUp ->
                HidAction.KeyTap(HidDescriptors.KeyCode.TAB, HidDescriptors.Modifier.LEFT_GUI)
            is GestureEvent.FlickDown ->
                HidAction.KeyTap(HidDescriptors.KeyCode.KEY_D, HidDescriptors.Modifier.LEFT_GUI)
            else -> null
        }

        HostOsPreset.MACOS -> when (event) {
            is GestureEvent.FlickLeft ->
                HidAction.KeyTap(HidDescriptors.KeyCode.LEFT_ARROW, HidDescriptors.Modifier.LEFT_CTRL)
            is GestureEvent.FlickRight ->
                HidAction.KeyTap(HidDescriptors.KeyCode.RIGHT_ARROW, HidDescriptors.Modifier.LEFT_CTRL)
            is GestureEvent.FlickUp ->
                HidAction.KeyTap(HidDescriptors.KeyCode.UP_ARROW, HidDescriptors.Modifier.LEFT_CTRL)
            is GestureEvent.FlickDown ->
                HidAction.KeyTap(HidDescriptors.KeyCode.KEY_M, HidDescriptors.Modifier.LEFT_GUI)
            else -> null
        }
    }

    /**
     * Maps a user-recorded custom DTW gesture binding into an OS-appropriate [HidAction].
     */
    fun mapCustomAction(action: CustomGestureAction, os: HostOsPreset): HidAction? {
        val cmdOrCtrl = primaryModifier(os)
        return when (action) {
            CustomGestureAction.COPY -> HidAction.KeyTap(HidDescriptors.KeyCode.KEY_C, cmdOrCtrl)
            CustomGestureAction.PASTE -> HidAction.KeyTap(HidDescriptors.KeyCode.KEY_V, cmdOrCtrl)
            CustomGestureAction.UNDO -> HidAction.KeyTap(HidDescriptors.KeyCode.KEY_Z, cmdOrCtrl)
            CustomGestureAction.PLAY_PAUSE -> HidAction.ConsumerTap(HidDescriptors.ConsumerBit.PLAY_PAUSE)
            CustomGestureAction.NEXT_TAB -> HidAction.KeyTap(
                HidDescriptors.KeyCode.TAB,
                HidDescriptors.Modifier.LEFT_CTRL
            )
            CustomGestureAction.PREV_TAB -> HidAction.KeyTap(
                HidDescriptors.KeyCode.TAB,
                (HidDescriptors.Modifier.LEFT_CTRL.toInt() or HidDescriptors.Modifier.LEFT_SHIFT.toInt()).toByte()
            )
            CustomGestureAction.MISSION_CONTROL -> when (os) {
                HostOsPreset.WINDOWS_LINUX ->
                    HidAction.KeyTap(HidDescriptors.KeyCode.TAB, HidDescriptors.Modifier.LEFT_GUI)
                HostOsPreset.MACOS ->
                    HidAction.KeyTap(HidDescriptors.KeyCode.UP_ARROW, HidDescriptors.Modifier.LEFT_CTRL)
            }
            CustomGestureAction.LOCK_SCREEN -> buildLockScreenAction(os)
            CustomGestureAction.MACRO_1 -> null // Handled via WifiTransportClient macro dispatch
        }
    }

    /**
     * Builds the OS Lock Screen shortcut (`Win + L` on Windows/Linux, `Ctrl + Cmd + Q` on macOS)
     * used by Walk-Away Auto-Lock and custom gestures.
     */
    fun buildLockScreenAction(os: HostOsPreset): HidAction = when (os) {
        HostOsPreset.WINDOWS_LINUX -> HidAction.KeyTap(
            keyCode = HidDescriptors.KeyCode.KEY_L,
            modifiers = HidDescriptors.Modifier.LEFT_GUI
        )
        HostOsPreset.MACOS -> HidAction.KeyTap(
            keyCode = HidDescriptors.KeyCode.KEY_Q,
            modifiers = (HidDescriptors.Modifier.LEFT_CTRL.toInt() or HidDescriptors.Modifier.LEFT_GUI.toInt()).toByte()
        )
    }

    private fun primaryModifier(os: HostOsPreset): Byte = when (os) {
        HostOsPreset.WINDOWS_LINUX -> HidDescriptors.Modifier.LEFT_CTRL
        HostOsPreset.MACOS -> HidDescriptors.Modifier.LEFT_GUI
    }
}
