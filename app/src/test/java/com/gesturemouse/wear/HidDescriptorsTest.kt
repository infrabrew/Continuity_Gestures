package com.gesturemouse.wear

import com.gesturemouse.wear.hid.HidDescriptors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidDescriptorsTest {

    @Test
    fun compositeDescriptorHasBalancedCollections() {
        val bytes = HidDescriptors.COMPOSITE_DESCRIPTOR
        var collectionDepth = 0
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt() and 0xFF
            val sizeCode = tag and 0x03
            val dataLen = if (sizeCode == 3) 4 else sizeCode
            when (tag) {
                0xA1 -> collectionDepth++ // Collection
                0xC0 -> collectionDepth-- // End Collection
            }
            assertTrue("Collection depth must never go negative", collectionDepth >= 0)
            i += 1 + dataLen
        }
        assertEquals("Every HID Collection must have a matching End Collection", 0, collectionDepth)
    }

    @Test
    fun mouseReportClampsSigned8BitValuesAndMasksButtons() {
        val report = HidDescriptors.buildMouseReport(
            buttons = HidDescriptors.MOUSE_BUTTON_LEFT or HidDescriptors.MOUSE_BUTTON_RIGHT,
            dx = 250,
            dy = -300,
            wheel = 5,
            pan = -2
        )
        assertEquals(5, report.size)
        assertArrayEquals(
            byteArrayOf(0x03, 127, (-127).toByte(), 5, (-2).toByte()),
            report
        )
    }

    @Test
    fun keyboardAndConsumerReportsHaveStandardLengths() {
        val kbReport = HidDescriptors.buildKeyboardReport(HidDescriptors.KeyCode.ENTER)
        assertEquals(8, kbReport.size)
        assertEquals(HidDescriptors.KeyCode.ENTER, kbReport[2])

        val consumerReport = HidDescriptors.buildConsumerReport(HidDescriptors.ConsumerBit.PLAY_PAUSE)
        assertEquals(2, consumerReport.size)
        assertArrayEquals(byteArrayOf(0x01, 0x00), consumerReport)

        val brightUp = HidDescriptors.buildConsumerReport(HidDescriptors.ConsumerBit.BRIGHTNESS_UP)
        assertArrayEquals(byteArrayOf(0x00, 0x01), brightUp)

        val brightDown = HidDescriptors.buildConsumerReport(HidDescriptors.ConsumerBit.BRIGHTNESS_DOWN)
        assertArrayEquals(byteArrayOf(0x00, 0x02), brightDown)
    }
}
