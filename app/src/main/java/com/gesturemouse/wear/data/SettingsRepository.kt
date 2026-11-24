package com.gesturemouse.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gesturemouse.wear.model.AccelerationCurve
import com.gesturemouse.wear.model.AnalogRollAction
import com.gesturemouse.wear.model.AppThemeColor
import com.gesturemouse.wear.model.CrownAction
import com.gesturemouse.wear.model.Handedness
import com.gesturemouse.wear.model.HostOsPreset
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.PalmCoverAction
import com.gesturemouse.wear.model.SavedHost
import com.gesturemouse.wear.model.SettingsState
import com.gesturemouse.wear.model.SideButtonAction
import com.gesturemouse.wear.model.TransportMode
import com.gesturemouse.wear.model.WatchHardwareProfile
import com.gesturemouse.wear.model.WristTwistMediaTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gesture_mouse_settings"
)

/**
 * Persistence layer backed by Jetpack DataStore Preferences for all GestureMouse settings,
 * calibration baselines, saved Bluetooth hosts, custom DTW gesture templates, hardware controls,
 * and transport preferences.
 *
 * Uses an optimistic in-memory cache with coalesced background I/O writes so continuous
 * Wear OS slider scrubbing updates the UI and DSP engines at 60+ fps with zero flash-storage
 * round-trip lag, while reliably persisting the latest snapshot to DataStore.
 */
class SettingsRepository(private val context: Context) {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val initialLoadComplete = CompletableDeferred<Unit>()
    private val _settingsState = MutableStateFlow<SettingsState?>(null)
    private var pendingPersistJob: Job? = null

    val settingsFlow: Flow<SettingsState> = _settingsState.filterNotNull()

    /** Direct suspend snapshot helper used by Wear OS Tile & Complication providers. */
    suspend fun getSnapshot(): SettingsState {
        initialLoadComplete.await()
        return synchronized(stateLock) { _settingsState.value ?: SettingsState() }
    }

    init {
        repoScope.launch {
            val prefs = context.settingsDataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .first()
            synchronized(stateLock) {
                if (_settingsState.value == null) {
                    _settingsState.value = prefs.toSettingsState()
                }
            }
            initialLoadComplete.complete(Unit)
        }
    }

    fun setInputMode(mode: InputMode) {
        updateSetting(debounceMs = 0L) { it.copy(inputMode = mode) }
    }

    fun setHandedness(handedness: Handedness) {
        updateSetting(debounceMs = 0L) { it.copy(handedness = handedness) }
    }

    fun setCursorSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.4f, 5.0f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(cursorSensitivity = clamped)
        }
    }

    fun setHorizontalSpeedScale(scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2.5f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(horizontalSpeedScale = clamped)
        }
    }

    fun setVerticalSpeedScale(scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2.5f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(verticalSpeedScale = clamped)
        }
    }

    fun setPointerAcceleration(acceleration: Float) {
        val clamped = acceleration.coerceIn(0.0f, 2.5f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(pointerAcceleration = clamped)
        }
    }

    fun setAccelerationCurve(curve: AccelerationCurve) {
        updateSetting(debounceMs = 0L) { it.copy(accelerationCurve = curve) }
    }

    fun setMonitorCount(count: Int) {
        val clamped = count.coerceIn(1, 3)
        updateSetting(debounceMs = 0L) { it.copy(monitorCount = clamped) }
    }

    fun setHybridMicroTrackpadEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(hybridMicroTrackpadEnabled = enabled) }
    }

    fun setInvertY(invert: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(invertY = invert) }
    }

    fun setInvertX(invert: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(invertX = invert) }
    }

    fun setSmoothingFactor(smoothing: Float) {
        val clamped = smoothing.coerceIn(0.0f, 0.85f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(smoothingFactor = clamped)
        }
    }

    fun setPinchThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(1.5f, 7.5f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(pinchThreshold = clamped)
        }
    }

    fun setFlickThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(2.2f, 8.0f)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(flickThreshold = clamped)
        }
    }

    fun setDebounceMs(debounceMs: Long) {
        val clamped = debounceMs.coerceIn(100L, 500L)
        updateSetting(debounceMs = SLIDER_PERSIST_DEBOUNCE_MS) {
            it.copy(debounceMs = clamped)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(hapticsEnabled = enabled) }
    }

    fun setDimScreenWhileActive(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(dimScreenWhileActive = enabled) }
    }

    fun setStealthTrackpadEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(stealthTrackpadEnabled = enabled) }
    }

    fun setTouchSdkBleEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(touchSdkBleEnabled = enabled) }
    }

    fun setHighFrequencySampling(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(highFrequencySampling = enabled) }
    }

    fun setUseNineDofFusion(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(useNineDofFusion = enabled) }
    }

    fun setNativeGestureGuard(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(nativeGestureGuard = enabled) }
    }

    fun setCrownAction(action: CrownAction) {
        updateSetting(debounceMs = 0L) { it.copy(crownAction = action) }
    }

    fun setSideButtonAction(action: SideButtonAction) {
        updateSetting(debounceMs = 0L) { it.copy(sideButtonAction = action) }
    }

    fun setAnalogRollAction(action: AnalogRollAction) {
        updateSetting(debounceMs = 0L) { it.copy(analogRollAction = action) }
    }

    fun setHostOsPreset(preset: HostOsPreset) {
        updateSetting(debounceMs = 0L) { it.copy(hostOsPreset = preset) }
    }

    fun setWindowManagementFlicksEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(windowManagementFlicksEnabled = enabled) }
    }

    fun setAutoAppProfilesEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(autoAppProfilesEnabled = enabled) }
    }

    fun setOffBodyAutoPauseEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(offBodyAutoPauseEnabled = enabled) }
    }

    fun setWalkAwayAutoLockEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(walkAwayAutoLockEnabled = enabled) }
    }

    fun setPalmCoverAction(action: PalmCoverAction) {
        updateSetting(debounceMs = 0L) { it.copy(palmCoverAction = action) }
    }

    fun setTransportMode(mode: TransportMode) {
        updateSetting(debounceMs = 0L) { it.copy(transportMode = mode) }
    }

    fun setWifiHostIp(ip: String) {
        val trimmed = ip.trim().ifBlank { "192.168.1.100" }
        updateSetting(debounceMs = 0L) { it.copy(wifiHostIp = trimmed) }
    }

    fun setUwbAutoSwitchEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(uwbAutoSwitchEnabled = enabled) }
    }

    fun setSerializedCustomGestures(serialized: String) {
        updateSetting(debounceMs = 0L) { it.copy(serializedCustomGestures = serialized) }
    }

    fun applyWatchProfile(profile: WatchHardwareProfile) {
        updateSetting(debounceMs = 0L) {
            it.copy(
                watchProfile = profile,
                pinchThreshold = profile.recommendedPinchThreshold,
                flickThreshold = profile.recommendedFlickThreshold,
                smoothingFactor = profile.recommendedSmoothing
            )
        }
    }

    fun saveCalibration(noiseFloor: Float, recommendedPinchThreshold: Float) {
        val clampedFloor = noiseFloor.coerceIn(0.15f, 2.5f)
        val clampedPinch = recommendedPinchThreshold.coerceIn(1.6f, 7.0f)
        updateSetting(debounceMs = 0L) {
            it.copy(
                calibratedNoiseFloor = clampedFloor,
                pinchThreshold = clampedPinch
            )
        }
    }

    fun setOnboardingCompleted(completed: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(onboardingCompleted = completed) }
    }

    fun saveLastConnectedDevice(address: String, name: String) {
        val safeAddr = address.trim()
        val safeName = name.trim().ifBlank { safeAddr }
        if (safeAddr.isEmpty()) return
        updateSetting(debounceMs = 0L) { current ->
            val updatedHosts = buildList {
                add(SavedHost(safeAddr, safeName))
                current.savedHosts
                    .filterNot { it.address.equals(safeAddr, ignoreCase = true) }
                    .take(MAX_SAVED_HOSTS - 1)
                    .forEach { add(it) }
            }
            current.copy(
                lastConnectedAddress = safeAddr,
                lastConnectedName = safeName,
                savedHosts = updatedHosts
            )
        }
    }

    fun setWristTwistMediaTarget(target: WristTwistMediaTarget) {
        updateSetting(debounceMs = 0L) { it.copy(wristTwistMediaTarget = target) }
    }

    fun setGestureModeSwitchEnabled(enabled: Boolean) {
        updateSetting(debounceMs = 0L) { it.copy(gestureModeSwitchEnabled = enabled) }
    }

    fun setThemeColor(color: AppThemeColor) {
        updateSetting(debounceMs = 0L) { it.copy(themeColor = color) }
    }

    /**
     * Restores all cursor, gesture, hardware, spatial, automation, and theme settings back to
     * factory [SettingsState] defaults while preserving onboarding completion and saved hosts.
     */
    fun resetToDefaults() {
        updateSetting(debounceMs = 0L) { current ->
            SettingsState(
                onboardingCompleted = current.onboardingCompleted,
                lastConnectedAddress = current.lastConnectedAddress,
                lastConnectedName = current.lastConnectedName,
                savedHosts = current.savedHosts
            )
        }
    }

    private fun updateSetting(
        debounceMs: Long,
        transform: (SettingsState) -> SettingsState
    ) {
        val updatedImmediately: SettingsState? = synchronized(stateLock) {
            val current = _settingsState.value ?: return@synchronized null
            val next = transform(current)
            if (next == current) return
            _settingsState.value = next
            next
        }

        if (updatedImmediately != null) {
            schedulePersist(updatedImmediately, debounceMs)
        } else {
            repoScope.launch {
                initialLoadComplete.await()
                val next = synchronized(stateLock) {
                    val base = _settingsState.value ?: SettingsState()
                    val computed = transform(base)
                    _settingsState.value = computed
                    computed
                }
                schedulePersist(next, debounceMs)
            }
        }
    }

    private fun schedulePersist(snapshot: SettingsState, debounceMs: Long) {
        synchronized(stateLock) {
            pendingPersistJob?.cancel()
            pendingPersistJob = repoScope.launch {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                val latest = synchronized(stateLock) { _settingsState.value ?: snapshot }
                persistSnapshot(latest)
            }
        }
    }

    private suspend fun persistSnapshot(state: SettingsState) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_INPUT_MODE] = state.inputMode.name
            prefs[KEY_HANDEDNESS] = state.handedness.name
            prefs[KEY_SENSITIVITY] = state.cursorSensitivity
            prefs[KEY_HORIZONTAL_SPEED] = state.horizontalSpeedScale
            prefs[KEY_VERTICAL_SPEED] = state.verticalSpeedScale
            prefs[KEY_POINTER_ACCEL] = state.pointerAcceleration
            prefs[KEY_ACCEL_CURVE] = state.accelerationCurve.name
            prefs[KEY_MONITOR_COUNT] = state.monitorCount
            prefs[KEY_HYBRID_TRACKPAD] = state.hybridMicroTrackpadEnabled
            prefs[KEY_INVERT_Y] = state.invertY
            prefs[KEY_INVERT_X] = state.invertX
            prefs[KEY_SMOOTHING] = state.smoothingFactor
            prefs[KEY_PINCH_THRESHOLD] = state.pinchThreshold
            prefs[KEY_FLICK_THRESHOLD] = state.flickThreshold
            prefs[KEY_DEBOUNCE_MS] = state.debounceMs
            prefs[KEY_HAPTICS] = state.hapticsEnabled
            prefs[KEY_DIM_SCREEN] = state.dimScreenWhileActive
            prefs[KEY_STEALTH_TRACKPAD] = state.stealthTrackpadEnabled
            prefs[KEY_TOUCH_SDK_BLE] = state.touchSdkBleEnabled
            prefs[KEY_HIGH_FREQ] = state.highFrequencySampling
            prefs[KEY_NINE_DOF] = state.useNineDofFusion
            prefs[KEY_NATIVE_GESTURE_GUARD] = state.nativeGestureGuard
            prefs[KEY_CROWN_ACTION] = state.crownAction.name
            prefs[KEY_SIDE_BUTTON_ACTION] = state.sideButtonAction.name
            prefs[KEY_ANALOG_ROLL_ACTION] = state.analogRollAction.name
            prefs[KEY_HOST_OS_PRESET] = state.hostOsPreset.name
            prefs[KEY_WINDOW_MGMT_FLICKS] = state.windowManagementFlicksEnabled
            prefs[KEY_AUTO_APP_PROFILES] = state.autoAppProfilesEnabled
            prefs[KEY_OFF_BODY_PAUSE] = state.offBodyAutoPauseEnabled
            prefs[KEY_WALK_AWAY_LOCK] = state.walkAwayAutoLockEnabled
            prefs[KEY_PALM_COVER_ACTION] = state.palmCoverAction.name
            prefs[KEY_TRANSPORT_MODE] = state.transportMode.name
            prefs[KEY_WIFI_HOST_IP] = state.wifiHostIp
            prefs[KEY_WIFI_UDP_PORT] = state.wifiUdpPort
            prefs[KEY_UWB_AUTO_SWITCH] = state.uwbAutoSwitchEnabled
            prefs[KEY_WATCH_PROFILE] = state.watchProfile.name
            prefs[KEY_NOISE_FLOOR] = state.calibratedNoiseFloor
            prefs[KEY_ONBOARDING_DONE] = state.onboardingCompleted
            prefs[KEY_LAST_DEVICE_ADDR] = state.lastConnectedAddress
            prefs[KEY_LAST_DEVICE_NAME] = state.lastConnectedName
            prefs[KEY_SAVED_HOSTS] = encodeSavedHosts(state.savedHosts)
            prefs[KEY_CUSTOM_GESTURES] = state.serializedCustomGestures
            prefs[KEY_WRIST_TWIST_TARGET] = state.wristTwistMediaTarget.name
            prefs[KEY_GESTURE_MODE_SWITCH] = state.gestureModeSwitchEnabled
            prefs[KEY_THEME_COLOR] = state.themeColor.name
        }
    }

    private fun Preferences.toSettingsState(): SettingsState {
        val lastAddr = this[KEY_LAST_DEVICE_ADDR] ?: ""
        val lastName = this[KEY_LAST_DEVICE_NAME] ?: ""
        val decodedHosts = decodeSavedHosts(this[KEY_SAVED_HOSTS].orEmpty()).ifEmpty {
            if (lastAddr.isNotBlank()) listOf(SavedHost(lastAddr, lastName.ifBlank { lastAddr }))
            else emptyList()
        }
        return SettingsState(
            inputMode = this[KEY_INPUT_MODE]
                ?.let { runCatching { InputMode.valueOf(it) }.getOrNull() }
                ?: InputMode.MOUSE,
            handedness = this[KEY_HANDEDNESS]
                ?.let { runCatching { Handedness.valueOf(it) }.getOrNull() }
                ?: Handedness.LEFT,
            cursorSensitivity = this[KEY_SENSITIVITY] ?: 1.6f,
            horizontalSpeedScale = this[KEY_HORIZONTAL_SPEED] ?: 1.0f,
            verticalSpeedScale = this[KEY_VERTICAL_SPEED] ?: 1.2f,
            pointerAcceleration = this[KEY_POINTER_ACCEL] ?: 1.2f,
            accelerationCurve = this[KEY_ACCEL_CURVE]
                ?.let { runCatching { AccelerationCurve.valueOf(it) }.getOrNull() }
                ?: AccelerationCurve.BALANCED,
            monitorCount = (this[KEY_MONITOR_COUNT] ?: 2).coerceIn(1, 3),
            hybridMicroTrackpadEnabled = this[KEY_HYBRID_TRACKPAD] ?: true,
            invertY = this[KEY_INVERT_Y] ?: false,
            invertX = this[KEY_INVERT_X] ?: false,
            smoothingFactor = this[KEY_SMOOTHING] ?: 0.35f,
            pinchThreshold = this[KEY_PINCH_THRESHOLD] ?: 3.2f,
            flickThreshold = this[KEY_FLICK_THRESHOLD] ?: 4.2f,
            debounceMs = this[KEY_DEBOUNCE_MS] ?: 220L,
            hapticsEnabled = this[KEY_HAPTICS] ?: true,
            dimScreenWhileActive = this[KEY_DIM_SCREEN] ?: false,
            stealthTrackpadEnabled = this[KEY_STEALTH_TRACKPAD] ?: false,
            touchSdkBleEnabled = this[KEY_TOUCH_SDK_BLE] ?: false,
            highFrequencySampling = this[KEY_HIGH_FREQ] ?: true,
            useNineDofFusion = this[KEY_NINE_DOF] ?: true,
            nativeGestureGuard = this[KEY_NATIVE_GESTURE_GUARD] ?: true,
            crownAction = this[KEY_CROWN_ACTION]
                ?.let { runCatching { CrownAction.valueOf(it) }.getOrNull() }
                ?: CrownAction.SMOOTH_SCROLL,
            sideButtonAction = this[KEY_SIDE_BUTTON_ACTION]
                ?.let { runCatching { SideButtonAction.valueOf(it) }.getOrNull() }
                ?: SideButtonAction.FREEZE_CURSOR,
            analogRollAction = this[KEY_ANALOG_ROLL_ACTION]
                ?.let { runCatching { AnalogRollAction.valueOf(it) }.getOrNull() }
                ?: AnalogRollAction.TIMELINE_SCRUB,
            hostOsPreset = this[KEY_HOST_OS_PRESET]
                ?.let { runCatching { HostOsPreset.valueOf(it) }.getOrNull() }
                ?: HostOsPreset.WINDOWS_LINUX,
            windowManagementFlicksEnabled = this[KEY_WINDOW_MGMT_FLICKS] ?: true,
            autoAppProfilesEnabled = this[KEY_AUTO_APP_PROFILES] ?: true,
            offBodyAutoPauseEnabled = this[KEY_OFF_BODY_PAUSE] ?: true,
            walkAwayAutoLockEnabled = this[KEY_WALK_AWAY_LOCK] ?: false,
            palmCoverAction = this[KEY_PALM_COVER_ACTION]
                ?.let { runCatching { PalmCoverAction.valueOf(it) }.getOrNull() }
                ?: PalmCoverAction.PAUSE_TRACKING,
            transportMode = this[KEY_TRANSPORT_MODE]
                ?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
                ?: TransportMode.BLUETOOTH_HID,
            wifiHostIp = this[KEY_WIFI_HOST_IP] ?: "192.168.1.100",
            wifiUdpPort = this[KEY_WIFI_UDP_PORT] ?: 5959,
            uwbAutoSwitchEnabled = this[KEY_UWB_AUTO_SWITCH] ?: false,
            watchProfile = this[KEY_WATCH_PROFILE]
                ?.let { runCatching { WatchHardwareProfile.valueOf(it) }.getOrNull() }
                ?: WatchHardwareProfile.AUTO,
            calibratedNoiseFloor = this[KEY_NOISE_FLOOR] ?: 0.45f,
            onboardingCompleted = this[KEY_ONBOARDING_DONE] ?: false,
            lastConnectedAddress = lastAddr,
            lastConnectedName = lastName,
            savedHosts = decodedHosts,
            serializedCustomGestures = this[KEY_CUSTOM_GESTURES] ?: "",
            wristTwistMediaTarget = this[KEY_WRIST_TWIST_TARGET]
                ?.let { runCatching { WristTwistMediaTarget.valueOf(it) }.getOrNull() }
                ?: WristTwistMediaTarget.VOLUME,
            gestureModeSwitchEnabled = this[KEY_GESTURE_MODE_SWITCH] ?: true,
            themeColor = this[KEY_THEME_COLOR]
                ?.let { runCatching { AppThemeColor.valueOf(it) }.getOrNull() }
                ?: AppThemeColor.NEON_GREEN
        )
    }

    private fun encodeSavedHosts(hosts: List<SavedHost>): String =
        hosts.take(MAX_SAVED_HOSTS).joinToString(separator = "\n") { host ->
            val safeName = host.name.replace("|", " ").replace("\n", " ")
            "${host.address}|$safeName"
        }

    private fun decodeSavedHosts(raw: String): List<SavedHost> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('|')
                if (idx <= 0) return@mapNotNull null
                val addr = line.substring(0, idx).trim()
                val name = line.substring(idx + 1).trim().ifBlank { addr }
                if (addr.isNotEmpty()) SavedHost(addr, name) else null
            }
            .take(MAX_SAVED_HOSTS)
            .toList()
    }

    private companion object {
        const val SLIDER_PERSIST_DEBOUNCE_MS = 150L
        const val MAX_SAVED_HOSTS = 3

        val KEY_INPUT_MODE = stringPreferencesKey("input_mode")
        val KEY_HANDEDNESS = stringPreferencesKey("handedness")
        val KEY_SENSITIVITY = floatPreferencesKey("cursor_sensitivity")
        val KEY_HORIZONTAL_SPEED = floatPreferencesKey("horizontal_speed_scale")
        val KEY_VERTICAL_SPEED = floatPreferencesKey("vertical_speed_scale")
        val KEY_POINTER_ACCEL = floatPreferencesKey("pointer_acceleration")
        val KEY_ACCEL_CURVE = stringPreferencesKey("acceleration_curve")
        val KEY_MONITOR_COUNT = intPreferencesKey("monitor_count")
        val KEY_HYBRID_TRACKPAD = booleanPreferencesKey("hybrid_micro_trackpad")
        val KEY_INVERT_Y = booleanPreferencesKey("invert_y")
        val KEY_INVERT_X = booleanPreferencesKey("invert_x")
        val KEY_SMOOTHING = floatPreferencesKey("smoothing_factor")
        val KEY_PINCH_THRESHOLD = floatPreferencesKey("pinch_threshold")
        val KEY_FLICK_THRESHOLD = floatPreferencesKey("flick_threshold")
        val KEY_DEBOUNCE_MS = longPreferencesKey("debounce_ms")
        val KEY_HAPTICS = booleanPreferencesKey("haptics_enabled")
        val KEY_DIM_SCREEN = booleanPreferencesKey("dim_screen_active")
        val KEY_STEALTH_TRACKPAD = booleanPreferencesKey("stealth_trackpad_enabled")
        val KEY_TOUCH_SDK_BLE = booleanPreferencesKey("touch_sdk_ble_enabled")
        val KEY_HIGH_FREQ = booleanPreferencesKey("high_freq_sampling")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        val KEY_NINE_DOF = booleanPreferencesKey("use_nine_dof_fusion")
        val KEY_NATIVE_GESTURE_GUARD = booleanPreferencesKey("native_gesture_guard")
        val KEY_CROWN_ACTION = stringPreferencesKey("crown_action")
        val KEY_SIDE_BUTTON_ACTION = stringPreferencesKey("side_button_action")
        val KEY_ANALOG_ROLL_ACTION = stringPreferencesKey("analog_roll_action")
        val KEY_HOST_OS_PRESET = stringPreferencesKey("host_os_preset")
        val KEY_WINDOW_MGMT_FLICKS = booleanPreferencesKey("window_mgmt_flicks")
        val KEY_AUTO_APP_PROFILES = booleanPreferencesKey("auto_app_profiles")
        val KEY_OFF_BODY_PAUSE = booleanPreferencesKey("off_body_auto_pause")
        val KEY_WALK_AWAY_LOCK = booleanPreferencesKey("walk_away_auto_lock")
        val KEY_PALM_COVER_ACTION = stringPreferencesKey("palm_cover_action")
        val KEY_TRANSPORT_MODE = stringPreferencesKey("transport_mode")
        val KEY_WIFI_HOST_IP = stringPreferencesKey("wifi_host_ip")
        val KEY_WIFI_UDP_PORT = intPreferencesKey("wifi_udp_port")
        val KEY_UWB_AUTO_SWITCH = booleanPreferencesKey("uwb_auto_switch")
        val KEY_WATCH_PROFILE = stringPreferencesKey("watch_profile")
        val KEY_NOISE_FLOOR = floatPreferencesKey("calibrated_noise_floor")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val KEY_LAST_DEVICE_ADDR = stringPreferencesKey("last_connected_address")
        val KEY_LAST_DEVICE_NAME = stringPreferencesKey("last_connected_name")
        val KEY_SAVED_HOSTS = stringPreferencesKey("saved_hosts")
        val KEY_CUSTOM_GESTURES = stringPreferencesKey("custom_gestures")
        val KEY_WRIST_TWIST_TARGET = stringPreferencesKey("wrist_twist_target")
        val KEY_GESTURE_MODE_SWITCH = booleanPreferencesKey("gesture_mode_switch")
    }
}
