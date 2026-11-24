package com.gesturemouse.wear

import com.gesturemouse.wear.mode.HidAction
import com.gesturemouse.wear.network.WifiProtocolCodec
import com.gesturemouse.wear.spatial.ProximityDecision
import com.gesturemouse.wear.spatial.ProximityHostSwitcher
import com.gesturemouse.wear.spatial.RangedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAndUwbTest {

    @Test
    fun wifiUdpMousePacketEncodesHeaderPayloadAndXorChecksum() {
        val packet = WifiProtocolCodec.encodeAction(
            action = HidAction.MouseState(buttons = 0x01, dx = 18, dy = -12, wheel = 2, pan = -1),
            seq = 42
        )

        assertEquals(WifiProtocolCodec.PACKET_SIZE, packet.size)
        assertEquals(WifiProtocolCodec.MAGIC_0, packet[0])
        assertEquals(WifiProtocolCodec.MAGIC_1, packet[1])
        assertEquals(42.toByte(), packet[2])
        assertEquals(0.toByte(), packet[3])
        assertEquals(WifiProtocolCodec.TYPE_MOUSE, packet[4])
        assertEquals(1.toByte(), packet[5])
        assertEquals(18.toByte(), packet[6])
        assertEquals((-12).toByte(), packet[7])
        assertEquals(2.toByte(), packet[8])
        assertEquals((-1).toByte(), packet[9])

        var expectedXor = 0
        for (i in 0 until WifiProtocolCodec.PACKET_SIZE - 1) {
            expectedXor = expectedXor xor (packet[i].toInt() and 0xFF)
        }
        assertEquals(expectedXor.toByte(), packet[WifiProtocolCodec.PACKET_SIZE - 1])
    }

    @Test
    fun wifiWebSocketMacroJsonParsesAppNameAndButtons() {
        val json = """{"app":"Figma","macros":[{"id":"undo","label":"Undo"},{"id":"zoom","label":"Zoom 100%"}]}"""
        val macros = WifiProtocolCodec.parseMacroSyncJson(json)

        assertEquals(2, macros.size)
        assertEquals("Figma", macros[0].appName)
        assertEquals("undo", macros[0].id)
        assertEquals("Undo", macros[0].label)
        assertEquals("zoom", macros[1].id)
        assertEquals("Zoom 100%", macros[1].label)
    }

    @Test
    fun uwbProximityAutoWakesWhenPausedAndSwitchesHostWithHysteresis() {
        val switcher = ProximityHostSwitcher(
            wakeDistanceMeters = 1.2f,
            switchMarginMeters = 0.35f,
            switchDwellMs = 800L
        )

        val macBook = RangedHost("AA:BB:CC:00:00:01", "MacBook Pro", distanceMeters = 0.8f, timestampMs = 1_000L)
        val wakeDecision = switcher.onRangingUpdate(macBook, activeHostAddress = macBook.address, isPaused = true)
        assertTrue("Expected AutoWake when within 1.2m while paused", wakeDecision is ProximityDecision.AutoWake)

        // Move closer to iPad (0.4m vs MacBook 1.1m -> clearly closer by > 0.35m)
        val macFar = macBook.copy(distanceMeters = 1.1f, timestampMs = 2_000L)
        assertNull(switcher.onRangingUpdate(macFar, activeHostAddress = macBook.address, isPaused = false))

        val ipadFirst = RangedHost("AA:BB:CC:00:00:02", "iPad Pro", distanceMeters = 0.4f, timestampMs = 2_100L)
        // Before 800 ms dwell time, must not switch yet
        assertNull(switcher.onRangingUpdate(ipadFirst, activeHostAddress = macBook.address, isPaused = false))
        assertNull(
            switcher.onRangingUpdate(
                ipadFirst.copy(timestampMs = 2_600L),
                activeHostAddress = macBook.address,
                isPaused = false
            )
        )

        // After >= 800 ms sustained proximity, switch to iPad Pro
        val switchDecision = switcher.onRangingUpdate(
            ipadFirst.copy(timestampMs = 3_000L),
            activeHostAddress = macBook.address,
            isPaused = false
        )
        assertTrue("Expected SwitchHost after dwell", switchDecision is ProximityDecision.SwitchHost)
        assertEquals("AA:BB:CC:00:00:02", (switchDecision as ProximityDecision.SwitchHost).targetHost.address)
    }

    @Test
    fun wifiTeleportPacketEncodesMonitorIndexCountAndChecksum() {
        val packet = WifiProtocolCodec.encodeTeleport(monitorIndex = 2, monitorCount = 3, seq = 7)
        assertEquals(WifiProtocolCodec.PACKET_SIZE, packet.size)
        assertEquals(WifiProtocolCodec.TYPE_TELEPORT, packet[4])
        assertEquals(2.toByte(), packet[5])
        assertEquals(3.toByte(), packet[6])

        var expectedXor = 0
        for (i in 0 until WifiProtocolCodec.PACKET_SIZE - 1) {
            expectedXor = expectedXor xor (packet[i].toInt() and 0xFF)
        }
        assertEquals(expectedXor.toByte(), packet[WifiProtocolCodec.PACKET_SIZE - 1])
    }

    @Test
    fun appProfileAndHandoffJsonParseCleanly() {
        val profileJson = """{"type":"profile","app":"Spotify","category":"media"}"""
        val profile = WifiProtocolCodec.parseAppProfileJson(profileJson)
        assertTrue(profile != null)
        assertEquals("Spotify", profile!!.appName)
        assertEquals(com.gesturemouse.wear.model.CrownAction.VOLUME, profile.recommendedCrown)
        assertTrue("Expected default media macros when none explicitly provided", profile.macros.isNotEmpty())

        val handoffJson = """{"type":"handoff","kind":"url","text":"https://developer.android.com"}"""
        val handoff = WifiProtocolCodec.parseHandoffJson(handoffJson)
        assertTrue(handoff != null)
        assertEquals("url", handoff!!.kind)
        assertEquals("https://developer.android.com", handoff.text)
    }
}
