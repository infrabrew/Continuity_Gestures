package com.gesturemouse.wear.model

/**
 * Wrist on which the watch is worn. Used to mirror vertical pitch axes,
 * directional flick signs, and wrist-twist direction so gestures feel natural
 * on either wrist.
 */
enum class Handedness(val displayName: String) {
    LEFT("Left Wrist"),
    RIGHT("Right Wrist");

    /** Axis sign multiplier: +1 for left wrist (standard), -1 for right wrist. */
    val axisSign: Float
        get() = if (this == LEFT) 1f else -1f
}

/**
 * Target host control for continuous wrist rotation (pronation/supination) while in
 * [InputMode.MEDIA_CONTROLS] mode.
 */
enum class WristTwistMediaTarget(val displayName: String) {
    VOLUME("Volume"),
    BRIGHTNESS("Brightness");

    fun toggle(): WristTwistMediaTarget =
        if (this == VOLUME) BRIGHTNESS else VOLUME
}

/**
 * Five high-contrast OLED neon accent themes (keeping the classic Neon Green default
 * plus Neon Blue, Neon Orange, Neon Purple, and Neon Pink).
 */
enum class AppThemeColor(val displayName: String) {
    NEON_GREEN("Neon Green"),
    NEON_BLUE("Neon Blue"),
    NEON_ORANGE("Neon Orange"),
    NEON_PURPLE("Neon Purple"),
    NEON_PINK("Neon Pink");

    fun next(): AppThemeColor = entries[(ordinal + 1) % entries.size]
}

/**
 * Velocity-based pointer acceleration curve governing micro-aim precision vs. fast-flick travel.
 */
enum class AccelerationCurve(val displayName: String, val detail: String) {
    LINEAR("Linear 1:1", "Constant speed, no curve"),
    PRECISION("Precision Plateau", "Ultra-slow micro-aim, soft boost"),
    BALANCED("Balanced S-Curve", "Linear micro-aim, exponential flick"),
    DYNAMIC_EXPONENTIAL("Dynamic Expo", "Fast edge-to-edge ultrawide boost");

    fun next(): AccelerationCurve = entries[(ordinal + 1) % entries.size]
}

/**
 * Action mapped to the physical rotating crown / bezel while actively connected on the main screen.
 */
enum class CrownAction(val displayName: String, val detail: String) {
    SMOOTH_SCROLL("Smooth Scroll", "Scroll pages up / down smoothly"),
    PAGE_SCROLL("Page Up / Down", "Jump web & document pages"),
    VOLUME("Volume Control", "Host volume up / down"),
    TIMELINE_SCRUB("Timeline Scrub", "Frame step / horizontal scrub"),
    OPEN_SETTINGS("Quick Settings", "Turn crown to open menu");

    fun next(): CrownAction = entries[(ordinal + 1) % entries.size]
}

/**
 * Action mapped to holding the physical Wear OS side stem button while connected.
 */
enum class SideButtonAction(val displayName: String, val detail: String) {
    FREEZE_CURSOR("Freeze + Spatial", "Freeze cursor; roll/flick shortcuts"),
    PRECISION_MODE("Precision 0.3x", "Hold for fine micro-aim"),
    SPATIAL_MODIFIER("Spatial Modifier", "Hold for roll dial & window flicks"),
    OFF("Off", "System default button");

    fun next(): SideButtonAction = entries[(ordinal + 1) % entries.size]
}

/**
 * Continuous analog dial action mapped to forearm roll (doorknob twist) while holding the modifier.
 */
enum class AnalogRollAction(val displayName: String, val detail: String) {
    TIMELINE_SCRUB("Timeline Scrub", "Horizontal scrub / pan"),
    ZOOM("Zoom In / Out", "Ctrl/Cmd + / - zoom"),
    CYCLE_TABS("Cycle Tabs", "Browser tabs / workspaces"),
    OFF("Off", "Disable analog roll dial");

    fun next(): AnalogRollAction = entries[(ordinal + 1) % entries.size]
}

/**
 * Target desktop OS shortcut layout for window management flicks, zoom, and lock screen.
 */
enum class HostOsPreset(val displayName: String) {
    WINDOWS_LINUX("Windows / Linux"),
    MACOS("macOS");

    fun toggle(): HostOsPreset = if (this == WINDOWS_LINUX) MACOS else WINDOWS_LINUX
}

/**
 * Action triggered when covering the watch face with your other hand (Ambient Light Sensor drop).
 */
enum class PalmCoverAction(val displayName: String, val detail: String) {
    PAUSE_TRACKING("Pause / Resume", "Cover face to pause cursor"),
    TOGGLE_DRAG_LOCK("Drag Lock", "Cover face to hold/release click"),
    OFF("Off", "Ignore ambient light drop");

    fun next(): PalmCoverAction = entries[(ordinal + 1) % entries.size]
}

/**
 * Active transport channel for sending cursor & gesture reports to paired host devices.
 */
enum class TransportMode(val displayName: String, val detail: String) {
    BLUETOOTH_HID("Bluetooth HID", "Zero-setup on any device"),
    WIFI_UDP_WS("5 GHz Wi-Fi", "High-rate UDP + macro sync"),
    DUAL_BT_AND_WIFI("Dual BT + Wi-Fi", "HID + live Wi-Fi macros");

    fun next(): TransportMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Bindable HID shortcut or macro triggered when a user-recorded custom 3D gesture matches.
 */
enum class CustomGestureAction(val displayName: String) {
    COPY("Copy"),
    PASTE("Paste"),
    UNDO("Undo"),
    PLAY_PAUSE("Play / Pause"),
    NEXT_TAB("Next Tab"),
    PREV_TAB("Prev Tab"),
    MISSION_CONTROL("Mission Control"),
    LOCK_SCREEN("Lock Screen"),
    MACRO_1("Host Macro #1");

    fun next(): CustomGestureAction = entries[(ordinal + 1) % entries.size]
}

/**
 * Saved Bluetooth HID host entry displayed in the Wear OS Tile and Quick Reconnect list.
 */
data class SavedHost(
    val address: String,
    val name: String
)

/**
 * Device-specific IMU tuning profile accounting for accelerometer/gyroscope
 * noise floors and mounting characteristics across popular Wear OS watches.
 */
enum class WatchHardwareProfile(
    val displayName: String,
    val recommendedPinchThreshold: Float,
    val recommendedFlickThreshold: Float,
    val recommendedSmoothing: Float,
    val notes: String
) {
    AUTO(
        displayName = "Auto-Detect",
        recommendedPinchThreshold = 3.2f,
        recommendedFlickThreshold = 4.2f,
        recommendedSmoothing = 0.35f,
        notes = "Automatically selects Galaxy Watch or Pixel Watch tuning"
    ),
    GALAXY_WATCH(
        displayName = "Galaxy Watch 4–Ultra",
        recommendedPinchThreshold = 3.0f,
        recommendedFlickThreshold = 4.0f,
        recommendedSmoothing = 0.32f,
        notes = "Tuned for Samsung Bosch/STMicro 200 Hz IMU with sharp tendon impulses"
    ),
    PIXEL_WATCH(
        displayName = "Pixel Watch 2–5",
        recommendedPinchThreshold = 3.4f,
        recommendedFlickThreshold = 4.4f,
        recommendedSmoothing = 0.36f,
        notes = "Tuned for Pixel Watch IMU, Gen 3 haptics, and tactile crown"
    )
}

/**
 * Persistent user preferences and calibration parameters stored in Jetpack DataStore.
 */
data class SettingsState(
    val inputMode: InputMode = InputMode.MOUSE,
    val handedness: Handedness = Handedness.LEFT,
    /** Overall cursor speed multiplier from 0.4 (slow/precise) to 5.0 (fast/ultrawide). */
    val cursorSensitivity: Float = 1.6f,
    /** Independent horizontal (left/right) cursor speed multiplier from 0.5 to 2.5. */
    val horizontalSpeedScale: Float = 1.0f,
    /** Independent vertical (up/down) cursor speed multiplier from 0.5 to 2.5. */
    val verticalSpeedScale: Float = 1.2f,
    /** Non-linear pointer acceleration boost from 0.0 (linear) to 2.5 (fast full-screen flicks). */
    val pointerAcceleration: Float = 1.2f,
    /** Velocity-based pointer acceleration curve profile. */
    val accelerationCurve: AccelerationCurve = AccelerationCurve.BALANCED,
    /** Number of connected monitors (1–3) for Multi-Monitor Teleport targeting. */
    val monitorCount: Int = 2,
    /** Keep watch glass active as a relative micro-trackpad simultaneously with air-pointing. */
    val hybridMicroTrackpadEnabled: Boolean = true,
    /** Flip vertical (up/down) pointer and scroll-clutch direction. */
    val invertY: Boolean = false,
    /** Flip horizontal (left/right) pointer direction. */
    val invertX: Boolean = false,
    /** One-Euro / EMA smoothing strength from 0.0 (raw/snappy) to 0.85 (ultra-smooth). */
    val smoothingFactor: Float = 0.35f,
    /** Linear acceleration impulse threshold in m/s² required to recognize a finger pinch. */
    val pinchThreshold: Float = 3.2f,
    /** Angular velocity threshold in rad/s required to recognize a wrist flick. */
    val flickThreshold: Float = 4.2f,
    /** Minimum cooldown in milliseconds between consecutive pinch detections. */
    val debounceMs: Long = 220L,
    /** Enable Gen 3 tactile haptic confirmation on gestures, clicks, and crown detents. */
    val hapticsEnabled: Boolean = true,
    /** Overlay an OLED-saving dim veil while mouse mode is active to extend battery. */
    val dimScreenWhileActive: Boolean = false,
    /** 1-Nit Stealth Trackpad mode: pure black 1 Hz LTPO screen with invisible left/right tap zones. */
    val stealthTrackpadEnabled: Boolean = false,
    /** Enable optional TouchSDK-style BLE GATT server for external Unity/Python/Web apps. */
    val touchSdkBleEnabled: Boolean = false,
    /** Use 100 Hz (true) vs battery-saver 50 Hz (false) IMU sampling while active. */
    val highFrequencySampling: Boolean = true,
    /** Use 9-DoF Madgwick sensor fusion (accel + gyro + magnetometer) to cut yaw drift. */
    val useNineDofFusion: Boolean = true,
    /** Guard against Wear OS native Wrist Turn / Double Pinch system gesture collisions. */
    val nativeGestureGuard: Boolean = true,
    /** Action mapped to rotating the haptic crown while connected. */
    val crownAction: CrownAction = CrownAction.SMOOTH_SCROLL,
    /** Action mapped to holding the physical side stem button while connected. */
    val sideButtonAction: SideButtonAction = SideButtonAction.FREEZE_CURSOR,
    /** Action mapped to forearm roll while holding the spatial modifier. */
    val analogRollAction: AnalogRollAction = AnalogRollAction.TIMELINE_SCRUB,
    /** OS shortcut layout for window management flicks, zoom, and lock screen. */
    val hostOsPreset: HostOsPreset = HostOsPreset.WINDOWS_LINUX,
    /** Enable modifier + flick OS window management shortcuts. */
    val windowManagementFlicksEnabled: Boolean = true,
    /** Automatically adapt crown/mode/macros when a companion daemon pushes focused app profiles. */
    val autoAppProfilesEnabled: Boolean = true,
    /** Pause HID tracking automatically when watch is removed from wrist (off-body sensor). */
    val offBodyAutoPauseEnabled: Boolean = true,
    /** Fire Lock Screen shortcut on host automatically when walking out of UWB/BT range. */
    val walkAwayAutoLockEnabled: Boolean = false,
    /** Action triggered when covering the watch face (ambient light sensor drop). */
    val palmCoverAction: PalmCoverAction = PalmCoverAction.PAUSE_TRACKING,
    /** Active transport mode: standard Bluetooth HID, 5 GHz Wi-Fi UDP/WS, or Dual. */
    val transportMode: TransportMode = TransportMode.BLUETOOTH_HID,
    /** Target IP address for the optional 5 GHz Wi-Fi companion receiver. */
    val wifiHostIp: String = "192.168.1.100",
    /** Target UDP port for the optional 5 GHz Wi-Fi companion receiver (WS uses port + 1). */
    val wifiUdpPort: Int = 5959,
    /** Enable Ultra-Wideband (UWB) proximity auto-wake and host auto-switch on supported watches. */
    val uwbAutoSwitchEnabled: Boolean = false,
    /** Hardware sensor profile preset. */
    val watchProfile: WatchHardwareProfile = WatchHardwareProfile.AUTO,
    /** Resting noise baseline measured during calibration (m/s²). */
    val calibratedNoiseFloor: Float = 0.45f,
    /** Whether the first-run onboarding tutorial has been completed. */
    val onboardingCompleted: Boolean = false,
    /** MAC address of the last connected Bluetooth HID host for one-tap reconnect. */
    val lastConnectedAddress: String = "",
    /** Friendly name of the last connected Bluetooth HID host. */
    val lastConnectedName: String = "",
    /** Up to 3 recently connected Bluetooth HID hosts for Tile one-tap connect. */
    val savedHosts: List<SavedHost> = emptyList(),
    /** Compact serialized custom DTW gesture templates recorded by the user. */
    val serializedCustomGestures: String = "",
    /** Controls whether wrist rotation adjusts Volume or Display Brightness in Media mode. */
    val wristTwistMediaTarget: WristTwistMediaTarget = WristTwistMediaTarget.VOLUME,
    /** Allow switching Input Modes hands-free via a palm-up horizontal wrist flick. */
    val gestureModeSwitchEnabled: Boolean = true,
    /** Selected neon accent theme color across the main screen and UI chrome. */
    val themeColor: AppThemeColor = AppThemeColor.NEON_GREEN
)
