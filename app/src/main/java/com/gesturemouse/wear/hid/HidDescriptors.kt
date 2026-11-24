package com.gesturemouse.wear.hid

/**
 * USB-HID 1.11 compliant Composite Report Descriptor and report builders for GestureMouse.
 *
 * Exposes three logical HID collections on a single Bluetooth HID Device profile so that
 * Windows, macOS, Linux, Android, iPadOS, and smart TVs recognize the watch out of the box
 * without custom drivers:
 * - **Report ID 1**: 3-button Mouse + X/Y relative movement + vertical Wheel + horizontal AC Pan
 * - **Report ID 2**: Standard 8-byte Keyboard (arrows, Enter, Esc, Space, presenter shortcuts)
 * - **Report ID 3**: Consumer Control (Play/Pause, Next/Prev Track, Volume Up/Down, Mute, Stop, Brightness Up/Down)
 */
object HidDescriptors {

    const val REPORT_ID_MOUSE: Int = 1
    const val REPORT_ID_KEYBOARD: Int = 2
    const val REPORT_ID_CONSUMER: Int = 3

    // Mouse button bitmasks (Report ID 1, byte 0)
    const val MOUSE_BUTTON_NONE: Int = 0x00
    const val MOUSE_BUTTON_LEFT: Int = 0x01
    const val MOUSE_BUTTON_RIGHT: Int = 0x02
    const val MOUSE_BUTTON_MIDDLE: Int = 0x04

    // Standard USB HID Modifier Bitmasks (Report ID 2, byte 0)
    object Modifier {
        const val NONE: Byte = 0x00
        const val LEFT_CTRL: Byte = 0x01
        const val LEFT_SHIFT: Byte = 0x02
        const val LEFT_ALT: Byte = 0x04
        const val LEFT_GUI: Byte = 0x08 // Windows key / macOS Command key
    }

    // Standard USB HID Usage IDs for Keyboard (Usage Page 0x07)
    object KeyCode {
        const val NONE: Byte = 0x00
        const val KEY_B: Byte = 0x05
        const val KEY_C: Byte = 0x06
        const val KEY_D: Byte = 0x07
        const val KEY_L: Byte = 0x0F
        const val KEY_M: Byte = 0x10
        const val KEY_Q: Byte = 0x14
        const val KEY_T: Byte = 0x17
        const val KEY_V: Byte = 0x19
        const val KEY_W: Byte = 0x1A
        const val KEY_Z: Byte = 0x1D
        const val ENTER: Byte = 0x28
        const val ESCAPE: Byte = 0x29
        const val TAB: Byte = 0x2B
        const val SPACE: Byte = 0x2C
        const val MINUS: Byte = 0x2D
        const val EQUAL_PLUS: Byte = 0x2E
        const val F5: Byte = 0x3E
        const val HOME: Byte = 0x4A
        const val PAGE_UP: Byte = 0x4B
        const val END: Byte = 0x4D
        const val PAGE_DOWN: Byte = 0x4E
        const val RIGHT_ARROW: Byte = 0x4F
        const val LEFT_ARROW: Byte = 0x50
        const val DOWN_ARROW: Byte = 0x51
        const val UP_ARROW: Byte = 0x52
    }

    // Consumer Control bitmasks (Report ID 3, 2-byte little-endian bitmask)
    object ConsumerBit {
        const val NONE: Int = 0x0000
        const val PLAY_PAUSE: Int = 0x0001
        const val NEXT_TRACK: Int = 0x0002
        const val PREV_TRACK: Int = 0x0004
        const val VOLUME_UP: Int = 0x0008
        const val VOLUME_DOWN: Int = 0x0010
        const val MUTE: Int = 0x0020
        const val STOP: Int = 0x0040
        const val MENU: Int = 0x0080
        const val BRIGHTNESS_UP: Int = 0x0100
        const val BRIGHTNESS_DOWN: Int = 0x0200
    }

    /**
     * Complete composite HID report descriptor registered via [android.bluetooth.BluetoothHidDeviceAppSdpSettings].
     */
    val COMPOSITE_DESCRIPTOR: ByteArray = byteArrayOf(
        // ============================================================
        // Collection 1: Mouse (Report ID = 1) -> 5 bytes payload
        // [buttons(1), dx(1), dy(1), wheel(1), acPan(1)]
        // ============================================================
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), REPORT_ID_MOUSE.toByte(), // Report ID (1)
        0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
        // 3 Buttons (Left, Right, Middle)
        0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1)
        0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3)
        0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(), //     Report Count (3)
        0x75.toByte(), 0x01.toByte(), //     Report Size (1)
        0x81.toByte(), 0x02.toByte(), //     Input (Data, Var, Abs)
        // 5 Padding bits
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x75.toByte(), 0x05.toByte(), //     Report Size (5)
        0x81.toByte(), 0x03.toByte(), //     Input (Const, Var, Abs)
        // X, Y, Vertical Wheel (signed 8-bit relative)
        0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     Usage (X)
        0x09.toByte(), 0x31.toByte(), //     Usage (Y)
        0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), //     Report Size (8)
        0x95.toByte(), 0x03.toByte(), //     Report Count (3)
        0x81.toByte(), 0x06.toByte(), //     Input (Data, Var, Rel)
        // Horizontal Scroll (Consumer AC Pan, signed 8-bit relative)
        0x05.toByte(), 0x0C.toByte(), //     Usage Page (Consumer)
        0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan = 0x0238)
        0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), //     Report Size (8)
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x81.toByte(), 0x06.toByte(), //     Input (Data, Var, Rel)
        0xC0.toByte(),                //   End Collection (Physical)
        0xC0.toByte(),                // End Collection (Application)

        // ============================================================
        // Collection 2: Keyboard (Report ID = 2) -> 8 bytes payload
        // [modifiers(1), reserved(1), key1..key6(6)]
        // ============================================================
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(), // Report ID (2)
        // Modifier keys (Left Ctrl .. Right GUI)
        0x05.toByte(), 0x07.toByte(), //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (224)
        0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (231)
        0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), //   Report Size (1)
        0x95.toByte(), 0x08.toByte(), //   Report Count (8)
        0x81.toByte(), 0x02.toByte(), //   Input (Data, Var, Abs)
        // Reserved byte
        0x95.toByte(), 0x01.toByte(), //   Report Count (1)
        0x75.toByte(), 0x08.toByte(), //   Report Size (8)
        0x81.toByte(), 0x01.toByte(), //   Input (Const)
        // 6 Simultaneous Keycodes
        0x95.toByte(), 0x06.toByte(), //   Report Count (6)
        0x75.toByte(), 0x08.toByte(), //   Report Size (8)
        0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(), //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(), //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(), //   Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(), //   Input (Data, Array)
        0xC0.toByte(),                // End Collection (Application)

        // ============================================================
        // Collection 3: Consumer Control (Report ID = 3) -> 2 bytes payload
        // Byte 0: [PlayPause, Next, Prev, VolUp, VolDown, Mute, Stop, Menu]
        // Byte 1: [BrightnessUp, BrightnessDown, 6-bit padding]
        // ============================================================
        0x05.toByte(), 0x0C.toByte(), // Usage Page (Consumer)
        0x09.toByte(), 0x01.toByte(), // Usage (Consumer Control)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), REPORT_ID_CONSUMER.toByte(), // Report ID (3)
        0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), //   Report Size (1)
        0x95.toByte(), 0x08.toByte(), //   Report Count (8)
        0x09.toByte(), 0xCD.toByte(), //   Usage (Play/Pause)
        0x09.toByte(), 0xB5.toByte(), //   Usage (Scan Next Track)
        0x09.toByte(), 0xB6.toByte(), //   Usage (Scan Previous Track)
        0x09.toByte(), 0xE9.toByte(), //   Usage (Volume Increment)
        0x09.toByte(), 0xEA.toByte(), //   Usage (Volume Decrement)
        0x09.toByte(), 0xE2.toByte(), //   Usage (Mute)
        0x09.toByte(), 0xB7.toByte(), //   Usage (Stop)
        0x09.toByte(), 0x40.toByte(), //   Usage (Menu)
        0x81.toByte(), 0x02.toByte(), //   Input (Data, Var, Abs)
        // Display Brightness Up/Down (2 bits + 6 padding bits)
        0x75.toByte(), 0x01.toByte(), //   Report Size (1)
        0x95.toByte(), 0x02.toByte(), //   Report Count (2)
        0x09.toByte(), 0x6F.toByte(), //   Usage (Display Brightness Increment)
        0x09.toByte(), 0x70.toByte(), //   Usage (Display Brightness Decrement)
        0x81.toByte(), 0x02.toByte(), //   Input (Data, Var, Abs)
        0x75.toByte(), 0x06.toByte(), //   Report Size (6)
        0x95.toByte(), 0x01.toByte(), //   Report Count (1)
        0x81.toByte(), 0x03.toByte(), //   Input (Const, Var, Abs)
        0xC0.toByte()                 // End Collection (Application)
    )

    /**
     * Builds a 5-byte HID Mouse report (Report ID 1).
     */
    fun buildMouseReport(
        buttons: Int = MOUSE_BUTTON_NONE,
        dx: Int = 0,
        dy: Int = 0,
        wheel: Int = 0,
        pan: Int = 0
    ): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte(),
        pan.coerceIn(-127, 127).toByte()
    )

    /**
     * Builds an 8-byte HID Keyboard report (Report ID 2).
     */
    fun buildKeyboardReport(
        keyCode: Byte = KeyCode.NONE,
        modifiers: Byte = 0x00
    ): ByteArray = byteArrayOf(
        modifiers,
        0x00,
        keyCode,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00
    )

    /**
     * Builds a 2-byte HID Consumer Control report (Report ID 3).
     */
    fun buildConsumerReport(bitmask: Int = ConsumerBit.NONE): ByteArray =
        byteArrayOf(
            (bitmask and 0xFF).toByte(),
            ((bitmask ushr 8) and 0x03).toByte()
        )
}
