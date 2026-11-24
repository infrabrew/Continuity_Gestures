package com.gesturemouse.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gesturemouse.wear.GestureMouseApp
import com.gesturemouse.wear.gesture.CustomGestureDtwEngine
import com.gesturemouse.wear.gesture.CustomGestureTemplate
import com.gesturemouse.wear.hid.BluetoothHidService
import com.gesturemouse.wear.model.AccelerationCurve
import com.gesturemouse.wear.model.AnalogRollAction
import com.gesturemouse.wear.model.AppThemeColor
import com.gesturemouse.wear.model.ConnectionState
import com.gesturemouse.wear.model.CrownAction
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.Handedness
import com.gesturemouse.wear.model.HostOsPreset
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.PalmCoverAction
import com.gesturemouse.wear.model.SettingsState
import com.gesturemouse.wear.model.SideButtonAction
import com.gesturemouse.wear.model.TransportMode
import com.gesturemouse.wear.model.WatchHardwareProfile
import com.gesturemouse.wear.model.WristTwistMediaTarget
import com.gesturemouse.wear.network.HostAppProfile
import com.gesturemouse.wear.network.HostHandoffMessage
import com.gesturemouse.wear.network.HostMacro
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for [com.gesturemouse.wear.ui.screens.MainScreen].
 * High-frequency calibration telemetry is intentionally excluded and exposed separately
 * via [GestureMouseViewModel.liveImpulseScore] so main & settings screens never recompose
 * on raw IMU frames.
 */
data class GestureMouseUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val settings: SettingsState = SettingsState(),
    val activeMode: InputMode = InputMode.MOUSE,
    val isPaused: Boolean = false,
    val isScrollClutchActive: Boolean = false,
    val isClutchFrozen: Boolean = false,
    val isPrecisionModeActive: Boolean = false,
    val isSpatialModifierActive: Boolean = false,
    val isOffBody: Boolean = false,
    val isWifiWsConnected: Boolean = false,
    val syncedMacros: List<HostMacro> = emptyList(),
    val activeAppProfile: HostAppProfile? = null,
    val receivedHandoff: HostHandoffMessage? = null,
    val statusBanner: String? = null,
    val customTemplates: List<CustomGestureTemplate> = emptyList(),
    val isUwbSupported: Boolean = false,
    val uwbStatusText: String = "Off",
    val batteryPercent: Int = 100,
    val lastGesture: GestureEvent? = null,
    val gesturePulseSeq: Int = 0,
    val touchSdkSubscriberCount: Int = 0,
    val detectedHardwareProfile: WatchHardwareProfile = WatchHardwareProfile.AUTO,
    val hasBluetoothPermissions: Boolean = false,
    val isServiceBound: Boolean = false,
    val isSettingsLoaded: Boolean = false
) {
    val isLowBattery: Boolean
        get() = batteryPercent <= 20

    val hasActiveTransport: Boolean
        get() = connectionState.isConnected || settings.transportMode != TransportMode.BLUETOOTH_HID
}

/**
 * Narrow UI state for [com.gesturemouse.wear.ui.screens.QuickSettingsScreen] isolating it
 * from gesture pulse animations, discoverable countdown ticks, and raw sensor telemetry.
 */
data class QuickSettingsUiState(
    val settings: SettingsState = SettingsState(),
    val activeMode: InputMode = InputMode.MOUSE,
    val isConnected: Boolean = false,
    val isScrollClutchActive: Boolean = false,
    val isWifiWsConnected: Boolean = false,
    val syncedMacros: List<HostMacro> = emptyList(),
    val activeAppProfile: HostAppProfile? = null,
    val receivedHandoff: HostHandoffMessage? = null,
    val customGestureCount: Int = 0,
    val batteryPercent: Int = 100,
    val touchSdkSubscriberCount: Int = 0
) {
    val isLowBattery: Boolean
        get() = batteryPercent <= 20

    val hasActiveTransport: Boolean
        get() = isConnected || settings.transportMode != TransportMode.BLUETOOTH_HID
}

/**
 * Shared Wear OS ViewModel bridging Compose screens with [BluetoothHidService] and
 * [com.gesturemouse.wear.data.SettingsRepository], exposing narrow per-screen [StateFlow]s
 * to avoid unnecessary recompositions while scrolling settings or moving the cursor.
 */
class GestureMouseViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = (application as GestureMouseApp).settingsRepository

    private val _uiState = MutableStateFlow(GestureMouseUiState())
    val uiState: StateFlow<GestureMouseUiState> = _uiState.asStateFlow()

    private val _quickSettingsState = MutableStateFlow(QuickSettingsUiState())
    val quickSettingsState: StateFlow<QuickSettingsUiState> = _quickSettingsState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _isSettingsLoaded = MutableStateFlow(false)
    val isSettingsLoaded: StateFlow<Boolean> = _isSettingsLoaded.asStateFlow()

    private val _detectedHardwareProfile = MutableStateFlow(WatchHardwareProfile.AUTO)
    val detectedHardwareProfile: StateFlow<WatchHardwareProfile> = _detectedHardwareProfile.asStateFlow()

    private val _uwbStatusText = MutableStateFlow("Checking UWB...")
    val uwbStatusText: StateFlow<String> = _uwbStatusText.asStateFlow()

    private val _lastGesture = MutableStateFlow<GestureEvent?>(null)
    val lastGesture: StateFlow<GestureEvent?> = _lastGesture.asStateFlow()

    private val _liveImpulseScore = MutableStateFlow(0f)
    val liveImpulseScore: StateFlow<Float> = _liveImpulseScore.asStateFlow()

    private val _customTemplates = MutableStateFlow<List<CustomGestureTemplate>>(emptyList())
    val customTemplates: StateFlow<List<CustomGestureTemplate>> = _customTemplates.asStateFlow()

    private var hidService: BluetoothHidService? = null
    private var serviceCollectorsJob: Job? = null
    private var gestureClearJob: Job? = null
    private var pendingConnectAddress: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _settingsState.value = settings
                _isSettingsLoaded.value = true
                val parsedTemplates = CustomGestureDtwEngine.deserialize(settings.serializedCustomGestures)
                if (hidService == null) {
                    _customTemplates.value = parsedTemplates
                }
                _quickSettingsState.update {
                    it.copy(
                        settings = settings,
                        activeMode = settings.inputMode,
                        customGestureCount = parsedTemplates.size
                    )
                }
                _uiState.update {
                    it.copy(
                        settings = settings,
                        activeMode = settings.inputMode,
                        customTemplates = if (hidService == null) parsedTemplates else it.customTemplates,
                        isSettingsLoaded = true
                    )
                }
            }
        }
    }

    fun attachService(service: BluetoothHidService) {
        if (hidService === service) return
        hidService = service
        _detectedHardwareProfile.value = service.detectedHardwareProfile
        _uiState.update {
            it.copy(
                isServiceBound = true,
                detectedHardwareProfile = service.detectedHardwareProfile,
                isUwbSupported = service.isUwbHardwareSupported
            )
        }

        // Flush any deep-linked Tile/Complication auto-connect request that arrived before bind
        pendingConnectAddress?.let { addr ->
            pendingConnectAddress = null
            service.connectToAddress(addr)
        }

        serviceCollectorsJob?.cancel()
        serviceCollectorsJob = viewModelScope.launch {
            launch {
                service.connectionState.collect { state ->
                    _uiState.update { it.copy(connectionState = state) }
                    val connected = state.isConnected
                    if (_quickSettingsState.value.isConnected != connected) {
                        _quickSettingsState.update { it.copy(isConnected = connected) }
                    }
                }
            }
            launch {
                service.activeMode.collect { mode ->
                    _uiState.update { it.copy(activeMode = mode) }
                    if (_quickSettingsState.value.activeMode != mode) {
                        _quickSettingsState.update { it.copy(activeMode = mode) }
                    }
                }
            }
            launch {
                service.isPaused.collect { paused ->
                    _uiState.update { it.copy(isPaused = paused) }
                }
            }
            launch {
                service.isScrollClutchActive.collect { active ->
                    _uiState.update { it.copy(isScrollClutchActive = active) }
                    if (_quickSettingsState.value.isScrollClutchActive != active) {
                        _quickSettingsState.update { it.copy(isScrollClutchActive = active) }
                    }
                }
            }
            launch {
                service.isClutchFrozen.collect { frozen ->
                    _uiState.update { it.copy(isClutchFrozen = frozen) }
                }
            }
            launch {
                service.isPrecisionModeActive.collect { precision ->
                    _uiState.update { it.copy(isPrecisionModeActive = precision) }
                }
            }
            launch {
                service.isSpatialModifierActive.collect { active ->
                    _uiState.update { it.copy(isSpatialModifierActive = active) }
                }
            }
            launch {
                service.isOffBody.collect { offBody ->
                    _uiState.update { it.copy(isOffBody = offBody) }
                }
            }
            launch {
                service.isWifiWsConnected.collect { connected ->
                    _uiState.update { it.copy(isWifiWsConnected = connected) }
                    if (_quickSettingsState.value.isWifiWsConnected != connected) {
                        _quickSettingsState.update { it.copy(isWifiWsConnected = connected) }
                    }
                }
            }
            launch {
                service.syncedMacros.collect { macros ->
                    _uiState.update { it.copy(syncedMacros = macros) }
                    _quickSettingsState.update { it.copy(syncedMacros = macros) }
                }
            }
            launch {
                service.activeAppProfile.collect { profile ->
                    _uiState.update { it.copy(activeAppProfile = profile) }
                    _quickSettingsState.update { it.copy(activeAppProfile = profile) }
                }
            }
            launch {
                service.receivedHandoff.collect { handoff ->
                    _uiState.update { it.copy(receivedHandoff = handoff) }
                    _quickSettingsState.update { it.copy(receivedHandoff = handoff) }
                }
            }
            launch {
                service.statusBanner.collect { banner ->
                    _uiState.update { it.copy(statusBanner = banner) }
                }
            }
            launch {
                service.customTemplates.collect { templates ->
                    _customTemplates.value = templates
                    _uiState.update { it.copy(customTemplates = templates) }
                    _quickSettingsState.update { it.copy(customGestureCount = templates.size) }
                }
            }
            launch {
                service.uwbStatusText.collect { status ->
                    _uwbStatusText.value = status
                    _uiState.update { it.copy(uwbStatusText = status) }
                }
            }
            launch {
                service.batteryPercent.collect { pct ->
                    _uiState.update { it.copy(batteryPercent = pct) }
                    if (_quickSettingsState.value.batteryPercent != pct) {
                        _quickSettingsState.update { it.copy(batteryPercent = pct) }
                    }
                }
            }
            launch {
                service.impulseTelemetry.collect { score ->
                    _liveImpulseScore.value = score
                }
            }
            launch {
                service.touchSdkSubscriberCount.collect { count ->
                    _uiState.update { it.copy(touchSdkSubscriberCount = count) }
                    if (_quickSettingsState.value.touchSdkSubscriberCount != count) {
                        _quickSettingsState.update { it.copy(touchSdkSubscriberCount = count) }
                    }
                }
            }
            launch {
                service.gestureEvents.collect { event ->
                    _lastGesture.value = event
                    _uiState.update {
                        it.copy(
                            lastGesture = event,
                            gesturePulseSeq = it.gesturePulseSeq + 1
                        )
                    }
                    gestureClearJob?.cancel()
                    gestureClearJob = launch {
                        delay(1_150L)
                        if (_lastGesture.value === event) {
                            _lastGesture.value = null
                        }
                        _uiState.update { state ->
                            if (state.lastGesture === event) state.copy(lastGesture = null) else state
                        }
                    }
                }
            }
        }
    }

    fun detachService() {
        serviceCollectorsJob?.cancel()
        serviceCollectorsJob = null
        hidService = null
        _uiState.update { it.copy(isServiceBound = false) }
    }

    fun updateBluetoothPermissionStatus(granted: Boolean) {
        _uiState.update { it.copy(hasBluetoothPermissions = granted) }
        if (granted) {
            hidService?.initializeHidProfile()
        }
    }

    fun notifyDiscoverableStarted(durationSeconds: Int = 180) {
        hidService?.notifyDiscoverableStarted(durationSeconds)
    }

    fun reconnectLastDevice(): Boolean = hidService?.reconnectLastDevice() ?: false

    fun connectToAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        val service = hidService
        if (service == null) {
            pendingConnectAddress = trimmed
            return true
        }
        return service.connectToAddress(trimmed)
    }

    fun disconnectHost() {
        hidService?.disconnectHost()
    }

    fun togglePause() {
        hidService?.setPaused(!_uiState.value.isPaused)
    }

    fun toggleScrollClutch() {
        hidService?.toggleScrollClutch()
    }

    fun setScrollClutch(active: Boolean) {
        hidService?.setScrollClutch(active)
    }

    fun isSideButtonClutchArmed(): Boolean {
        val state = _uiState.value
        return state.settings.sideButtonAction != SideButtonAction.OFF &&
            state.hasActiveTransport &&
            !state.isPaused
    }

    fun setSideButtonHeld(held: Boolean) {
        hidService?.setSideButtonHeld(held)
    }

    fun onMicroTrackpadDrag(dxPx: Float, dyPx: Float) {
        hidService?.onMicroTrackpadDrag(dxPx, dyPx)
    }

    fun teleportToMonitor(monitorIndex: Int) {
        hidService?.teleportToMonitor(monitorIndex)
    }

    fun onCrownScroll(deltaPixels: Float) {
        hidService?.onCrownScroll(deltaPixels)
    }

    fun triggerLeftClick() {
        hidService?.triggerLeftClick()
    }

    fun triggerRightClick() {
        hidService?.triggerRightClick()
    }

    fun triggerManualClick() {
        hidService?.triggerManualClick()
    }

    fun triggerHostMacro(macroId: String) {
        hidService?.triggerHostMacro(macroId)
    }

    fun triggerHostLockScreen() {
        hidService?.triggerHostLockScreen()
    }

    fun pushWatchClipboardToHost() {
        hidService?.pushWatchClipboardToHost()
    }

    fun pullHandoffFromHost() {
        hidService?.pullHandoffFromHost()
    }

    fun copyReceivedHandoffToWatchClipboard(): Boolean =
        hidService?.copyReceivedHandoffToWatchClipboard() ?: false

    fun startCustomGestureSampleCapture(onCaptured: (List<FloatArray>) -> Unit) {
        hidService?.startCustomGestureSampleCapture(onCaptured)
    }

    fun cancelCustomGestureSampleCapture() {
        hidService?.cancelCustomGestureSampleCapture()
    }

    fun addCustomGestureTemplate(template: CustomGestureTemplate) {
        val service = hidService
        if (service != null) {
            service.addCustomGestureTemplate(template)
        } else {
            val updated = (_customTemplates.value.filterNot { it.id == template.id } + template).take(6)
            _customTemplates.value = updated
            settingsRepository.setSerializedCustomGestures(CustomGestureDtwEngine.serialize(updated))
        }
    }

    fun deleteCustomGestureTemplate(id: String) {
        val service = hidService
        if (service != null) {
            service.deleteCustomGestureTemplate(id)
        } else {
            val updated = _customTemplates.value.filterNot { it.id == id }
            _customTemplates.value = updated
            settingsRepository.setSerializedCustomGestures(CustomGestureDtwEngine.serialize(updated))
        }
    }

    fun setSensorPreviewActive(active: Boolean) {
        hidService?.setSensorPreviewActive(active)
    }

    fun switchMode(mode: InputMode) {
        hidService?.switchMode(mode) ?: settingsRepository.setInputMode(mode)
    }

    fun cycleMode() {
        hidService?.cycleMode() ?: settingsRepository.setInputMode(_uiState.value.activeMode.next())
    }

    fun setHandedness(handedness: Handedness) {
        settingsRepository.setHandedness(handedness)
    }

    fun setCursorSensitivity(sensitivity: Float) {
        settingsRepository.setCursorSensitivity(sensitivity)
    }

    fun setHorizontalSpeedScale(scale: Float) {
        settingsRepository.setHorizontalSpeedScale(scale)
    }

    fun setVerticalSpeedScale(scale: Float) {
        settingsRepository.setVerticalSpeedScale(scale)
    }

    fun setPointerAcceleration(acceleration: Float) {
        settingsRepository.setPointerAcceleration(acceleration)
    }

    fun setAccelerationCurve(curve: AccelerationCurve) {
        settingsRepository.setAccelerationCurve(curve)
    }

    fun setMonitorCount(count: Int) {
        settingsRepository.setMonitorCount(count)
    }

    fun setHybridMicroTrackpadEnabled(enabled: Boolean) {
        settingsRepository.setHybridMicroTrackpadEnabled(enabled)
    }

    fun setInvertY(invert: Boolean) {
        settingsRepository.setInvertY(invert)
    }

    fun setInvertX(invert: Boolean) {
        settingsRepository.setInvertX(invert)
    }

    fun setSmoothingFactor(smoothing: Float) {
        settingsRepository.setSmoothingFactor(smoothing)
    }

    fun setPinchThreshold(threshold: Float) {
        settingsRepository.setPinchThreshold(threshold)
    }

    fun setFlickThreshold(threshold: Float) {
        settingsRepository.setFlickThreshold(threshold)
    }

    fun setDebounceMs(debounceMs: Long) {
        settingsRepository.setDebounceMs(debounceMs)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        settingsRepository.setHapticsEnabled(enabled)
    }

    fun setDimScreenWhileActive(enabled: Boolean) {
        settingsRepository.setDimScreenWhileActive(enabled)
    }

    fun setStealthTrackpadEnabled(enabled: Boolean) {
        settingsRepository.setStealthTrackpadEnabled(enabled)
    }

    fun setTouchSdkBleEnabled(enabled: Boolean) {
        settingsRepository.setTouchSdkBleEnabled(enabled)
    }

    fun setHighFrequencySampling(enabled: Boolean) {
        settingsRepository.setHighFrequencySampling(enabled)
    }

    fun setUseNineDofFusion(enabled: Boolean) {
        settingsRepository.setUseNineDofFusion(enabled)
    }

    fun setNativeGestureGuard(enabled: Boolean) {
        settingsRepository.setNativeGestureGuard(enabled)
    }

    fun setCrownAction(action: CrownAction) {
        settingsRepository.setCrownAction(action)
    }

    fun setSideButtonAction(action: SideButtonAction) {
        settingsRepository.setSideButtonAction(action)
    }

    fun setAnalogRollAction(action: AnalogRollAction) {
        settingsRepository.setAnalogRollAction(action)
    }

    fun setHostOsPreset(preset: HostOsPreset) {
        settingsRepository.setHostOsPreset(preset)
    }

    fun setWindowManagementFlicksEnabled(enabled: Boolean) {
        settingsRepository.setWindowManagementFlicksEnabled(enabled)
    }

    fun setAutoAppProfilesEnabled(enabled: Boolean) {
        settingsRepository.setAutoAppProfilesEnabled(enabled)
    }

    fun setOffBodyAutoPauseEnabled(enabled: Boolean) {
        settingsRepository.setOffBodyAutoPauseEnabled(enabled)
    }

    fun setWalkAwayAutoLockEnabled(enabled: Boolean) {
        settingsRepository.setWalkAwayAutoLockEnabled(enabled)
    }

    fun setPalmCoverAction(action: PalmCoverAction) {
        settingsRepository.setPalmCoverAction(action)
    }

    fun setTransportMode(mode: TransportMode) {
        settingsRepository.setTransportMode(mode)
    }

    fun setWifiHostIp(ip: String) {
        settingsRepository.setWifiHostIp(ip)
    }

    fun setUwbAutoSwitchEnabled(enabled: Boolean) {
        settingsRepository.setUwbAutoSwitchEnabled(enabled)
    }

    fun setWristTwistMediaTarget(target: WristTwistMediaTarget) {
        settingsRepository.setWristTwistMediaTarget(target)
    }

    fun setGestureModeSwitchEnabled(enabled: Boolean) {
        settingsRepository.setGestureModeSwitchEnabled(enabled)
    }

    fun setThemeColor(color: AppThemeColor) {
        settingsRepository.setThemeColor(color)
    }

    fun resetToDefaults() {
        settingsRepository.resetToDefaults()
    }

    fun applyWatchProfile(profile: WatchHardwareProfile) {
        settingsRepository.applyWatchProfile(profile)
    }

    fun saveCalibration(noiseFloor: Float, recommendedPinchThreshold: Float) {
        settingsRepository.saveCalibration(noiseFloor, recommendedPinchThreshold)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        settingsRepository.setOnboardingCompleted(completed)
    }

    override fun onCleared() {
        serviceCollectorsJob?.cancel()
        super.onCleared()
    }
}
