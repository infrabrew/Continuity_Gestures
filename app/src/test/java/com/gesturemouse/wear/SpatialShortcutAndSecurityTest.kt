package com.gesturemouse.wear

import com.gesturemouse.wear.hid.HidDescriptors
import com.gesturemouse.wear.mode.HidAction
import com.gesturemouse.wear.mode.SpatialShortcutMapper
import com.gesturemouse.wear.model.AnalogRollAction
import com.gesturemouse.wear.model.CustomGestureAction
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.HostOsPreset
import com.gesturemouse.wear.spatial.ProximityDecision
import com.gesturemouse.wear.spatial.ProximityHostSwitcher
import com.gesturemouse.wear.spatial.RangedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialShortcutAndSecurityTest {

    @Test
    fun analogRollDialMapsTimelineZoomAndTabsAcrossOsPresets() {
        val panAction = SpatialShortcutMapper.mapAnalogRollStep(
            direction = 1,
            action = AnalogRollAction.TIMELINE_SCRUB,
            os = HostOsPreset.WINDOWS_LINUX
        )
        assertTrue(panAction is HidAction.MouseState)
        assertEquals(2, (panAction as HidAction.MouseState).pan)

        val macZoomIn = SpatialShortcutMapper.mapAnalogRollStep(
            direction = 1,
            action = AnalogRollAction.ZOOM,
            os = HostOsPreset.MACOS
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.EQUAL_PLUS, macZoomIn.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_GUI, macZoomIn.modifiers)

        val winZoomOut = SpatialShortcutMapper.mapAnalogRollStep(
            direction = -1,
            action = AnalogRollAction.ZOOM,
            os = HostOsPreset.WINDOWS_LINUX
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.MINUS, winZoomOut.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_CTRL, winZoomOut.modifiers)

        val prevTab = SpatialShortcutMapper.mapAnalogRollStep(
            direction = -1,
            action = AnalogRollAction.CYCLE_TABS,
            os = HostOsPreset.WINDOWS_LINUX
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.TAB, prevTab.keyCode)
        val expectedCtrlShift = (HidDescriptors.Modifier.LEFT_CTRL.toInt() or
            HidDescriptors.Modifier.LEFT_SHIFT.toInt()).toByte()
        assertEquals(expectedCtrlShift, prevTab.modifiers)
    }

    @Test
    fun modifierFlickMapsWindowsAndMacOsWindowManagementShortcuts() {
        val winSnapLeft = SpatialShortcutMapper.mapModifierFlick(
            GestureEvent.FlickLeft(10L),
            HostOsPreset.WINDOWS_LINUX
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.LEFT_ARROW, winSnapLeft.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_GUI, winSnapLeft.modifiers)

        val winOverview = SpatialShortcutMapper.mapModifierFlick(
            GestureEvent.FlickUp(20L),
            HostOsPreset.WINDOWS_LINUX
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.TAB, winOverview.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_GUI, winOverview.modifiers)

        val macOverview = SpatialShortcutMapper.mapModifierFlick(
            GestureEvent.FlickUp(30L),
            HostOsPreset.MACOS
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.UP_ARROW, macOverview.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_CTRL, macOverview.modifiers)

        val macMinimize = SpatialShortcutMapper.mapModifierFlick(
            GestureEvent.FlickDown(40L),
            HostOsPreset.MACOS
        ) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.KEY_M, macMinimize.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_GUI, macMinimize.modifiers)
    }

    @Test
    fun lockScreenAndCustomActionsMatchHostOsModifiers() {
        val winLock = SpatialShortcutMapper.buildLockScreenAction(HostOsPreset.WINDOWS_LINUX) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.KEY_L, winLock.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_GUI, winLock.modifiers)

        val macLock = SpatialShortcutMapper.buildLockScreenAction(HostOsPreset.MACOS) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.KEY_Q, macLock.keyCode)
        val expectedCtrlCmd = (HidDescriptors.Modifier.LEFT_CTRL.toInt() or
            HidDescriptors.Modifier.LEFT_GUI.toInt()).toByte()
        assertEquals(expectedCtrlCmd, macLock.modifiers)

        val macCopy = SpatialShortcutMapper.mapCustomAction(CustomGestureAction.COPY, HostOsPreset.MACOS) as HidAction.KeyTap
        assertEquals(HidDescriptors.KeyCode.KEY_C, macCopy.keyCode)
        assertEquals(HidDescriptors.Modifier.LEFT_GUI, macCopy.modifiers)
    }

    @Test
    fun walkAwayAutoLockTriggersAfterUwbRangeDwellAndRssiDrop() {
        val switcher = ProximityHostSwitcher(
            walkAwayDistanceMeters = 3.0f,
            walkAwayDwellMs = 2_000L
        )
        val hostAddr = "AA:BB:CC:DD:EE:01"

        // Arm near the desk (0.9 m)
        assertNull(
            switcher.onRangingUpdate(
                sample = RangedHost(hostAddr, "Workstation", distanceMeters = 0.9f, timestampMs = 1_000L),
                activeHostAddress = hostAddr,
                isPaused = false,
                walkAwayLockEnabled = true
            )
        )

        // Step far away (3.5 m) — must wait 2 s dwell before locking
        assertNull(
            switcher.onRangingUpdate(
                sample = RangedHost(hostAddr, "Workstation", distanceMeters = 3.5f, timestampMs = 2_000L),
                activeHostAddress = hostAddr,
                isPaused = false,
                walkAwayLockEnabled = true
            )
        )
        assertNull(
            switcher.onRangingUpdate(
                sample = RangedHost(hostAddr, "Workstation", distanceMeters = 3.6f, timestampMs = 3_500L),
                activeHostAddress = hostAddr,
                isPaused = false,
                walkAwayLockEnabled = true
            )
        )

        val uwbLock = switcher.onRangingUpdate(
            sample = RangedHost(hostAddr, "Workstation", distanceMeters = 3.8f, timestampMs = 4_100L),
            activeHostAddress = hostAddr,
            isPaused = false,
            walkAwayLockEnabled = true
        )
        assertTrue("Expected WalkAwayLock after 2s UWB dwell", uwbLock is ProximityDecision.WalkAwayLock)
        assertEquals(hostAddr, (uwbLock as ProximityDecision.WalkAwayLock).hostAddress)

        // Also verify Bluetooth RSSI walk-away fallback after returning near and walking out
        switcher.reset()
        assertNull(switcher.onBluetoothRssiUpdate(hostAddr, rssiDbm = -58, timestampMs = 10_000L, walkAwayLockEnabled = true))
        assertNull(switcher.onBluetoothRssiUpdate(hostAddr, rssiDbm = -90, timestampMs = 11_000L, walkAwayLockEnabled = true))
        val rssiLock = switcher.onBluetoothRssiUpdate(hostAddr, rssiDbm = -92, timestampMs = 13_200L, walkAwayLockEnabled = true)
        assertTrue("Expected WalkAwayLock after weak BT RSSI dwell", rssiLock is ProximityDecision.WalkAwayLock)
    }

    @Test
    fun fiveNeonThemePalettesCycleCleanlyAndHaveDistinctAccents() {
        val allThemes = com.gesturemouse.wear.model.AppThemeColor.entries
        assertEquals(5, allThemes.size)
        assertEquals(com.gesturemouse.wear.model.AppThemeColor.NEON_GREEN, com.gesturemouse.wear.model.SettingsState().themeColor)

        val visited = mutableListOf<com.gesturemouse.wear.model.AppThemeColor>()
        var current = com.gesturemouse.wear.model.AppThemeColor.NEON_GREEN
        repeat(5) {
            visited += current
            current = current.next()
        }
        assertEquals(com.gesturemouse.wear.model.AppThemeColor.NEON_GREEN, current)
        assertEquals(5, visited.toSet().size)

        val distinctActiveColors = allThemes
            .map { com.gesturemouse.wear.ui.theme.GestureColors.accentFor(it).active }
            .toSet()
        assertEquals("All 5 neon themes should have distinct primary accent colors", 5, distinctActiveColors.size)
    }
}
