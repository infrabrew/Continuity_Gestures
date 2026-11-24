package com.gesturemouse.wear.hid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.gesturemouse.wear.GestureMouseApp
import com.gesturemouse.wear.MainActivity
import com.gesturemouse.wear.R
import com.gesturemouse.wear.data.SettingsRepository
import com.gesturemouse.wear.feedback.HapticFeedbackManager
import com.gesturemouse.wear.gesture.CustomGestureDtwEngine
import com.gesturemouse.wear.gesture.CustomGestureRecognizer
import com.gesturemouse.wear.gesture.CustomGestureTemplate
import com.gesturemouse.wear.mode.HidAction
import com.gesturemouse.wear.mode.ModeManager
import com.gesturemouse.wear.mode.SpatialShortcutMapper
import com.gesturemouse.wear.model.AnalogRollAction
import com.gesturemouse.wear.model.ConnectionState
import com.gesturemouse.wear.model.CrownAction
import com.gesturemouse.wear.model.CustomGestureAction
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.model.ImuSample
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.PalmCoverAction
import com.gesturemouse.wear.model.SettingsState
import com.gesturemouse.wear.model.SideButtonAction
import com.gesturemouse.wear.model.TransportMode
import com.gesturemouse.wear.model.WatchHardwareProfile
import com.gesturemouse.wear.network.HostAppProfile
import com.gesturemouse.wear.network.HostHandoffMessage
import com.gesturemouse.wear.network.HostMacro
import com.gesturemouse.wear.network.WifiTransportClient
import com.gesturemouse.wear.sensor.CursorMapper
import com.gesturemouse.wear.sensor.GestureDetector
import com.gesturemouse.wear.sensor.PalmCoverDetector
import com.gesturemouse.wear.sensor.SensorFusionEngine
import com.gesturemouse.wear.spatial.ProximityDecision
import com.gesturemouse.wear.spatial.UwbProximityManager
import com.gesturemouse.wear.tile.SavedHostsTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Core Wear OS Foreground Service powering GestureMouse.
 *
 * Orchestrates:
 * - Standard Bluetooth HID composite Mouse/Keyboard/Consumer profile + optional 5 GHz Wi-Fi
 *   UDP/WebSocket dual transport with Active-App Auto-Profiles & Quick Host Handoff.
 * - 9-DoF Madgwick IMU sensor fusion, velocity-based acceleration curves, hybrid micro-trackpad,
 *   Multi-Monitor Teleport, Analog Forearm Roll Dial, Modifier + Flick Window Management, and
 *   on-device DTW Custom Gesture recognition.
 * - Tactile Crown scrolling (Smooth Scroll, Page Up/Down, Volume, Timeline Scrub) + Gen 3 haptics.
 * - Wear OS Ongoing Activity chip, Off-Body auto-pause, and Walk-Away auto-lock.
 */
class BluetoothHidService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothHidService = this@BluetoothHidService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hidExecutor = Executors.newSingleThreadExecutor()

    /** Serializes access to mutable DSP/mode/button state across sensor, settings, and HID threads. */
    private val stateLock = Any()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var hapticManager: HapticFeedbackManager
    private lateinit var sensorEngine: SensorFusionEngine
    private lateinit var touchSdkServer: TouchSdkGattServer
    private lateinit var uwbProximityManager: UwbProximityManager

    private val gestureDetector = GestureDetector()
    private val cursorMapper = CursorMapper()
    private val modeManager = ModeManager()
    private val palmCoverDetector = PalmCoverDetector()
    private val wifiTransport = WifiTransportClient()
    private val customGestureRecognizer = CustomGestureRecognizer()

    private var bluetoothAdapter: BluetoothAdapter? = null

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var connectedHost: BluetoothDevice? = null

    @Volatile
    private var isAppRegistered = false

    @Volatile
    private var currentSettings = SettingsState()

    @Volatile
    private var pendingReconnectAddress: String? = null

    private var transientClickButtons: Int = HidDescriptors.MOUSE_BUTTON_NONE
    private var isPalmDragLocked: Boolean = false
    private var crownScrollAccum: Float = 0f
    private var analogRollAccumDeg: Float = 0f
    private var lastRollTimestampMs: Long = 0L
    private var isSpatialModifierHeld: Boolean = false
    private var sensorRunningLightOnly: Boolean = false
    private var hasAppliedInitialAutoProfile = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var discoverableTimerJob: Job? = null

    /** Hardware profile detected from Build.MANUFACTURER / Build.MODEL on startup. */
    var detectedHardwareProfile: WatchHardwareProfile = WatchHardwareProfile.AUTO
        private set

    @Volatile
    private var sensorPreviewActive = false

    private var lastTelemetryEmitMs = 0L
    private var telemetryPeakScore = 0f

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeMode = MutableStateFlow(InputMode.MOUSE)
    val activeMode: StateFlow<InputMode> = _activeMode.asStateFlow()

    private val _gestureEvents = MutableSharedFlow<GestureEvent>(extraBufferCapacity = 32)
    val gestureEvents: SharedFlow<GestureEvent> = _gestureEvents.asSharedFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _impulseTelemetry = MutableStateFlow(0f)
    val impulseTelemetry: StateFlow<Float> = _impulseTelemetry.asStateFlow()

    private val _batteryPercent = MutableStateFlow(100)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isScrollClutchActive = MutableStateFlow(false)
    val isScrollClutchActive: StateFlow<Boolean> = _isScrollClutchActive.asStateFlow()

    private val _isClutchFrozen = MutableStateFlow(false)
    val isClutchFrozen: StateFlow<Boolean> = _isClutchFrozen.asStateFlow()

    private val _isPrecisionModeActive = MutableStateFlow(false)
    val isPrecisionModeActive: StateFlow<Boolean> = _isPrecisionModeActive.asStateFlow()

    private val _isSpatialModifierActive = MutableStateFlow(false)
    val isSpatialModifierActive: StateFlow<Boolean> = _isSpatialModifierActive.asStateFlow()

    private val _isOffBody = MutableStateFlow(false)
    val isOffBody: StateFlow<Boolean> = _isOffBody.asStateFlow()

    private val _customTemplates = MutableStateFlow<List<CustomGestureTemplate>>(emptyList())
    val customTemplates: StateFlow<List<CustomGestureTemplate>> = _customTemplates.asStateFlow()

    val touchSdkSubscriberCount: StateFlow<Int>
        get() = touchSdkServer.subscriberCount

    val isWifiWsConnected: StateFlow<Boolean>
        get() = wifiTransport.isWsConnected

    val syncedMacros: StateFlow<List<HostMacro>>
        get() = wifiTransport.syncedMacros

    val activeAppProfile: StateFlow<HostAppProfile?>
        get() = wifiTransport.activeAppProfile

    val receivedHandoff: StateFlow<HostHandoffMessage?>
        get() = wifiTransport.receivedHandoff

    val isUwbHardwareSupported: Boolean
        get() = ::uwbProximityManager.isInitialized && uwbProximityManager.isUwbHardwareSupported

    val uwbStatusText: StateFlow<String>
        get() = uwbProximityManager.statusText

    private var bannerClearJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level >= 0) {
                _batteryPercent.value = (level * 100) / scale
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    discoverableTimerJob?.cancel()
                    connectedHost = null
                    isAppRegistered = false
                    clearTransientControlStates()
                    releaseWakeLock()
                    touchSdkServer.stop()
                    _connectionState.value = ConnectionState.Error("Enable Bluetooth")
                    updateSensorLifecycle()
                    updateOngoingNotification()
                }

                BluetoothAdapter.STATE_ON -> {
                    _connectionState.value = ConnectionState.Disconnected
                    initializeHidProfile()
                    if (currentSettings.touchSdkBleEnabled) {
                        touchSdkServer.start()
                    }
                    updateOngoingNotification()
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidApplication()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
                isAppRegistered = false
                clearTransientControlStates()
                _connectionState.value = ConnectionState.Disconnected
                updateSensorLifecycle()
                updateOngoingNotification()
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app registration changed: registered=$registered")
            isAppRegistered = registered
            if (registered) {
                val pendingAddr = pendingReconnectAddress
                if (pendingAddr != null) {
                    pendingReconnectAddress = null
                    val adapter = bluetoothAdapter
                    if (adapter != null && adapter.isEnabled && hasBluetoothConnectPermission()) {
                        runCatching { adapter.getRemoteDevice(pendingAddr) }
                            .getOrNull()
                            ?.let { connectToDevice(it) }
                    }
                } else if (pluggedDevice != null) {
                    connectToDevice(pluggedDevice)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val name = safeDeviceName(device)
            Log.i(TAG, "HID connection state changed: host=$name state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    discoverableTimerJob?.cancel()
                    pendingReconnectAddress = null
                    connectedHost = device
                    _connectionState.value = ConnectionState.Connected(name, device.address)
                    hapticManager.playConnected(currentSettings.hapticsEnabled)
                    acquireWakeLock()
                    serviceScope.launch {
                        settingsRepository.saveLastConnectedDevice(device.address, name)
                        SavedHostsTileService.requestUpdate(this@BluetoothHidService)
                    }
                    updateSensorLifecycle()
                    updateOngoingNotification()
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.Connecting(name, device.address)
                    updateOngoingNotification()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device.address) {
                        connectedHost = null
                    }
                    clearTransientControlStates()
                    releaseWakeLock()
                    if (_connectionState.value !is ConnectionState.Discoverable) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    updateSensorLifecycle()
                    updateOngoingNotification()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (!hasBluetoothConnectPermission()) return
            val report = when (id.toInt()) {
                HidDescriptors.REPORT_ID_MOUSE -> HidDescriptors.buildMouseReport()
                HidDescriptors.REPORT_ID_KEYBOARD -> HidDescriptors.buildKeyboardReport()
                HidDescriptors.REPORT_ID_CONSUMER -> HidDescriptors.buildConsumerReport()
                else -> null
            }
            try {
                if (report != null) {
                    hidDevice?.replyReport(device, type, id, report)
                } else {
                    hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "Missing permission replying to HID GET_REPORT", se)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as GestureMouseApp
        settingsRepository = app.settingsRepository
        hapticManager = HapticFeedbackManager(this)
        touchSdkServer = TouchSdkGattServer(this)
        uwbProximityManager = UwbProximityManager(this, ::handleUwbDecision)
        sensorEngine = SensorFusionEngine(
            context = this,
            onSample = ::onImuSample,
            onLightSample = ::onAmbientLightSample,
            onOffBodyChanged = ::onOffBodySensorChanged
        )
        detectedHardwareProfile = sensorEngine.detectHardwareProfile()

        gestureDetector.onImpulseTelemetry = { score, _ ->
            if (sensorPreviewActive) {
                telemetryPeakScore = maxOf(telemetryPeakScore, score)
                val now = SystemClock.elapsedRealtime()
                if (now - lastTelemetryEmitMs >= TELEMETRY_EMIT_INTERVAL_MS) {
                    lastTelemetryEmitMs = now
                    _impulseTelemetry.value = telemetryPeakScore
                    telemetryPeakScore = 0f
                }
            }
        }

        val btManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter

        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Observe Active-App Auto-Profiles pushed from 5 GHz Wi-Fi host companion daemon
        serviceScope.launch {
            wifiTransport.activeAppProfile.collect { profile ->
                if (profile == null || !currentSettings.autoAppProfilesEnabled) return@collect
                profile.recommendedCrown?.let { crown ->
                    if (currentSettings.crownAction != crown) {
                        settingsRepository.setCrownAction(crown)
                    }
                }
                profile.recommendedMode?.let { mode ->
                    if (_activeMode.value != mode) {
                        switchMode(mode)
                    }
                }
                showTransientBanner("Profile: ${profile.appName}")
            }
        }

        // Observe persisted settings and propagate live to DSP, DTW, Wi-Fi, UWB, and BLE engines
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                if (!hasAppliedInitialAutoProfile &&
                    !settings.onboardingCompleted &&
                    settings.watchProfile == WatchHardwareProfile.AUTO &&
                    detectedHardwareProfile != WatchHardwareProfile.AUTO
                ) {
                    hasAppliedInitialAutoProfile = true
                    settingsRepository.applyWatchProfile(detectedHardwareProfile)
                    return@collect
                }
                hasAppliedInitialAutoProfile = true

                val sensorConfigChanged =
                    currentSettings.highFrequencySampling != settings.highFrequencySampling ||
                        currentSettings.useNineDofFusion != settings.useNineDofFusion ||
                        (currentSettings.palmCoverAction != PalmCoverAction.OFF) !=
                        (settings.palmCoverAction != PalmCoverAction.OFF) ||
                        currentSettings.transportMode != settings.transportMode
                val touchSdkChanged = currentSettings.touchSdkBleEnabled != settings.touchSdkBleEnabled
                val customGesturesChanged =
                    currentSettings.serializedCustomGestures != settings.serializedCustomGestures
                currentSettings = settings

                val cleanupActions: List<HidAction>
                synchronized(stateLock) {
                    gestureDetector.updateSettings(settings)
                    cursorMapper.updateSettings(settings)
                    modeManager.setWristTwistMediaTarget(settings.wristTwistMediaTarget)

                    if (customGesturesChanged || _customTemplates.value.isEmpty()) {
                        val decoded = CustomGestureDtwEngine.deserialize(settings.serializedCustomGestures)
                        customGestureRecognizer.setTemplates(decoded)
                        _customTemplates.value = decoded
                    }

                    cleanupActions = if (_activeMode.value != settings.inputMode) {
                        val cleanup = modeManager.setMode(settings.inputMode)
                        isPalmDragLocked = false
                        if (settings.inputMode != InputMode.MOUSE) {
                            cursorMapper.setScrollClutch(false)
                            _isScrollClutchActive.value = false
                        }
                        _activeMode.value = settings.inputMode
                        cleanup
                    } else {
                        emptyList()
                    }
                }
                dispatchActions(cleanupActions)

                wifiTransport.configure(
                    enabled = settings.transportMode != TransportMode.BLUETOOTH_HID,
                    hostIp = settings.wifiHostIp,
                    port = settings.wifiUdpPort
                )

                uwbProximityManager.configure(
                    uwbAutoSwitch = settings.uwbAutoSwitchEnabled,
                    walkAwayLock = settings.walkAwayAutoLockEnabled
                )

                if (touchSdkChanged || settings.touchSdkBleEnabled) {
                    if (settings.touchSdkBleEnabled) {
                        touchSdkServer.start()
                    } else {
                        touchSdkServer.stop()
                    }
                    updateSensorLifecycle()
                }

                if (sensorConfigChanged) {
                    restartSensorsIfRunning()
                    updateOngoingNotification()
                }
            }
        }

        if (!startForegroundCompat(buildNotification())) {
            Log.w(TAG, "Cannot enter connectedDevice foreground state without Bluetooth permission")
            stopSelf()
            return
        }
        initializeHidProfile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasBluetoothConnectPermission()) {
            _connectionState.value = ConnectionState.Error("Grant BT permission")
            stopSelf()
            return START_NOT_STICKY
        }
        initializeHidProfile()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        discoverableTimerJob?.cancel()
        bannerClearJob?.cancel()
        sensorEngine.stop()
        touchSdkServer.stop()
        wifiTransport.stop()
        if (::uwbProximityManager.isInitialized) {
            uwbProximityManager.stop()
        }
        releaseWakeLock()
        unregisterHidApplication()
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        hidExecutor.shutdownNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    fun initializeHidProfile() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = ConnectionState.Error("No Bluetooth hardware")
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Enable Bluetooth")
            return
        }
        if (!hasBluetoothConnectPermission()) {
            _connectionState.value = ConnectionState.Error("Grant BT permission")
            return
        }
        if (hidDevice == null) {
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        } else if (!isAppRegistered) {
            registerHidApplication()
        }
    }

    fun notifyDiscoverableStarted(durationSeconds: Int = 180) {
        initializeHidProfile()
        discoverableTimerJob?.cancel()
        discoverableTimerJob = serviceScope.launch {
            var remaining = durationSeconds
            _connectionState.value = ConnectionState.Discoverable(remaining)
            updateOngoingNotification()
            while (remaining > 0 && connectedHost == null) {
                _connectionState.value = ConnectionState.Discoverable(remaining)
                delay(1_000L)
                remaining--
            }
            if (connectedHost == null && _connectionState.value is ConnectionState.Discoverable) {
                _connectionState.value = ConnectionState.Disconnected
                updateOngoingNotification()
            }
        }
    }

    fun reconnectLastDevice(): Boolean = connectToAddress(currentSettings.lastConnectedAddress)

    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled || !hasBluetoothConnectPermission()) return false

        val device = try {
            adapter.getRemoteDevice(trimmed)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid MAC address: $trimmed", e)
            return false
        }

        if (hidDevice == null || !isAppRegistered) {
            pendingReconnectAddress = trimmed
            val cachedName = currentSettings.savedHosts
                .firstOrNull { it.address.equals(trimmed, ignoreCase = true) }
                ?.name
                ?: safeDeviceName(device)
            _connectionState.value = ConnectionState.Connecting(cachedName, trimmed)
            initializeHidProfile()
            return true
        }
        return connectToDevice(device)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice): Boolean {
        val hid = hidDevice ?: return false
        if (!hasBluetoothConnectPermission()) return false
        return try {
            _connectionState.value = ConnectionState.Connecting(safeDeviceName(device), device.address)
            hid.connect(device)
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing permission connecting to HID host", se)
            _connectionState.value = ConnectionState.Error("Grant BT permission")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectHost() {
        val host = connectedHost ?: return
        if (!hasBluetoothConnectPermission()) return
        try {
            val cleanup = clearTransientControlStates()
            dispatchActions(cleanup)
            hidDevice?.disconnect(host)
        } catch (se: SecurityException) {
            Log.w(TAG, "Missing permission disconnecting HID host", se)
        }
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
        if (paused) {
            val cleanup = clearTransientControlStates()
            dispatchActions(cleanup)
        }
        updateSensorLifecycle()
        updateOngoingNotification()
    }

    fun setSensorPreviewActive(active: Boolean) {
        sensorPreviewActive = active
        if (!active) {
            telemetryPeakScore = 0f
            lastTelemetryEmitMs = 0L
        }
        updateSensorLifecycle()
    }

    fun setScrollClutch(active: Boolean) {
        val allowed = active && _activeMode.value == InputMode.MOUSE
        synchronized(stateLock) {
            cursorMapper.setScrollClutch(allowed)
        }
        _isScrollClutchActive.value = allowed
    }

    fun toggleScrollClutch() {
        setScrollClutch(!_isScrollClutchActive.value)
    }

    /**
     * Physical Wear OS side-button clutch & spatial modifier handler.
     */
    fun setSideButtonHeld(held: Boolean) {
        val action = currentSettings.sideButtonAction
        if (action == SideButtonAction.OFF) return

        var changed = false
        synchronized(stateLock) {
            when (action) {
                SideButtonAction.FREEZE_CURSOR -> {
                    if (cursorMapper.isClutchFrozen != held || isSpatialModifierHeld != held) {
                        cursorMapper.setClutchFrozen(held)
                        cursorMapper.setPrecisionMode(false)
                        isSpatialModifierHeld = held
                        analogRollAccumDeg = 0f
                        changed = true
                    }
                }
                SideButtonAction.PRECISION_MODE -> {
                    if (cursorMapper.isPrecisionModeActive != held) {
                        cursorMapper.setPrecisionMode(held)
                        cursorMapper.setClutchFrozen(false)
                        isSpatialModifierHeld = false
                        changed = true
                    }
                }
                SideButtonAction.SPATIAL_MODIFIER -> {
                    if (isSpatialModifierHeld != held) {
                        isSpatialModifierHeld = held
                        cursorMapper.setClutchFrozen(held)
                        cursorMapper.setPrecisionMode(false)
                        analogRollAccumDeg = 0f
                        changed = true
                    }
                }
                else -> Unit
            }
        }
        if (changed) {
            _isClutchFrozen.value = (action == SideButtonAction.FREEZE_CURSOR ||
                action == SideButtonAction.SPATIAL_MODIFIER) && held
            _isPrecisionModeActive.value = action == SideButtonAction.PRECISION_MODE && held
            _isSpatialModifierActive.value = (action == SideButtonAction.FREEZE_CURSOR ||
                action == SideButtonAction.SPATIAL_MODIFIER) && held
            hapticManager.playClutchState(engaged = held, enabled = currentSettings.hapticsEnabled)
        }
    }

    /**
     * Hybrid Air-Mouse + Micro-Trackpad touch drag handler: streams pixel-precise relative
     * cursor deltas while simultaneously damping air-gyro tremor during finger slide.
     */
    fun onMicroTrackpadDrag(dxPx: Float, dyPx: Float) {
        if (!currentSettings.hybridMicroTrackpadEnabled || !hasActiveTransport() || _isPaused.value) return
        val action = synchronized(stateLock) {
            val delta = cursorMapper.mapMicroTrackpadDrag(dxPx, dyPx, SystemClock.elapsedRealtime())
            modeManager.mapCursorDelta(delta)
        }
        action?.let { dispatchAction(it) }
    }

    /**
     * Multi-Monitor "Teleport": warps the cursor straight to the center of Display 1, 2, or 3.
     * Uses instant UDP teleport if Wi-Fi transport is active, and performs a reliable relative
     * left-edge anchor sweep over Bluetooth HID so it works on any OS with zero drivers.
     */
    fun teleportToMonitor(monitorIndex: Int) {
        val count = currentSettings.monitorCount.coerceIn(1, 3)
        val clampedTarget = monitorIndex.coerceIn(1, count)
        if (!hasActiveTransport() || _isPaused.value) return

        hapticManager.playModeSwitch(currentSettings.hapticsEnabled)
        showTransientBanner("Display $clampedTarget")

        if (currentSettings.transportMode != TransportMode.BLUETOOTH_HID) {
            wifiTransport.sendTeleport(clampedTarget, count)
        }

        if (currentSettings.transportMode != TransportMode.WIFI_UDP_WS && connectedHost != null) {
            serviceScope.launch {
                val buttons = synchronized(stateLock) { modeManager.heldMouseButtons }
                // 1. Anchor to far-left top corner across up to 3 displays
                repeat(count * 16) {
                    dispatchAction(HidAction.MouseState(buttons = buttons, dx = -120, dy = -60))
                    delay(4L)
                }
                // 2. Step cleanly right to the horizontal center of target monitor and center Y
                val targetStepsX = ((clampedTarget - 1) * 14) + 7
                repeat(targetStepsX) {
                    dispatchAction(HidAction.MouseState(buttons = buttons, dx = 110, dy = 0))
                    delay(4L)
                }
                repeat(5) {
                    dispatchAction(HidAction.MouseState(buttons = buttons, dx = 0, dy = 95))
                    delay(4L)
                }
            }
        }
    }

    /**
     * Tactile Scroll Wheel handler mapping physical crown rotation (while connected) to
     * smooth page scrolling, Page Up/Down jumps, host volume, or timeline scrubbing.
     */
    fun onCrownScroll(deltaPixels: Float) {
        if (!hasActiveTransport() || _isPaused.value) return
        val crownAction = currentSettings.crownAction
        if (crownAction == CrownAction.OPEN_SETTINGS) return

        crownScrollAccum += deltaPixels
        val steps = (crownScrollAccum / CROWN_STEP_PX).toInt()
        if (steps == 0) return
        crownScrollAccum -= steps * CROWN_STEP_PX

        val clampedSteps = steps.coerceIn(-4, 4)
        val direction = if (clampedSteps > 0) 1 else -1
        val count = abs(clampedSteps)

        when (crownAction) {
            CrownAction.SMOOTH_SCROLL -> {
                val invertSign = if (currentSettings.invertY) -1 else 1
                val wheelDelta = (-clampedSteps * 2 * invertSign).coerceIn(-12, 12)
                val buttons = synchronized(stateLock) {
                    modeManager.heldMouseButtons or transientClickButtons
                }
                dispatchAction(HidAction.MouseState(buttons = buttons, wheel = wheelDelta))
            }

            CrownAction.PAGE_SCROLL -> {
                val invertSign = if (currentSettings.invertY) -1 else 1
                val key = if (direction * invertSign > 0) {
                    HidDescriptors.KeyCode.PAGE_DOWN
                } else {
                    HidDescriptors.KeyCode.PAGE_UP
                }
                dispatchAction(HidAction.KeyTap(key))
            }

            CrownAction.VOLUME -> {
                val bit = if (direction > 0) {
                    HidDescriptors.ConsumerBit.VOLUME_UP
                } else {
                    HidDescriptors.ConsumerBit.VOLUME_DOWN
                }
                repeat(count) { dispatchAction(HidAction.ConsumerTap(bit)) }
            }

            CrownAction.TIMELINE_SCRUB -> {
                val key = if (direction > 0) {
                    HidDescriptors.KeyCode.RIGHT_ARROW
                } else {
                    HidDescriptors.KeyCode.LEFT_ARROW
                }
                repeat(count) { dispatchAction(HidAction.KeyTap(key)) }
            }

            CrownAction.OPEN_SETTINGS -> return
        }

        hapticManager.playCrownTick(
            intensity = (0.45f + 0.12f * count).coerceAtMost(0.95f),
            enabled = currentSettings.hapticsEnabled
        )
    }

    fun switchMode(mode: InputMode) {
        val cleanup = synchronized(stateLock) {
            transientClickButtons = HidDescriptors.MOUSE_BUTTON_NONE
            isPalmDragLocked = false
            if (mode != InputMode.MOUSE) {
                cursorMapper.setScrollClutch(false)
            }
            modeManager.setMode(mode)
        }
        if (mode != InputMode.MOUSE) {
            _isScrollClutchActive.value = false
        }
        dispatchActions(cleanup)
        _activeMode.value = mode
        hapticManager.playModeSwitch(currentSettings.hapticsEnabled)
        updateOngoingNotification()
        serviceScope.launch {
            settingsRepository.setInputMode(mode)
        }
    }

    fun cycleMode() {
        switchMode(_activeMode.value.next())
    }

    fun previousMode() {
        switchMode(_activeMode.value.previous())
    }

    fun triggerLeftClick() {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            cursorMapper.triggerClickStabilization(now)
        }
        hapticManager.playLeftClick(currentSettings.hapticsEnabled)
        _gestureEvents.tryEmit(GestureEvent.SinglePinch(now))
        if (hasActiveTransport() && !_isPaused.value) {
            dispatchAction(HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_LEFT))
        }
    }

    fun triggerRightClick() {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            cursorMapper.triggerClickStabilization(now)
        }
        hapticManager.playRightClick(currentSettings.hapticsEnabled)
        _gestureEvents.tryEmit(GestureEvent.DoublePinch(now))
        if (hasActiveTransport() && !_isPaused.value) {
            dispatchAction(HidAction.MouseClick(HidDescriptors.MOUSE_BUTTON_RIGHT))
        }
    }

    fun triggerManualClick() {
        val event = GestureEvent.SinglePinch(System.currentTimeMillis())
        handleGestureEvent(event)
    }

    fun triggerHostMacro(macroId: String) {
        wifiTransport.triggerMacro(macroId)
        hapticManager.playLeftClick(currentSettings.hapticsEnabled)
    }

    /**
     * Fires the OS Lock Screen shortcut (`Win + L` or `Ctrl + Cmd + Q`) on the connected host.
     */
    fun triggerHostLockScreen() {
        if (!hasActiveTransport()) return
        dispatchAction(SpatialShortcutMapper.buildLockScreenAction(currentSettings.hostOsPreset))
        hapticManager.playPalmCoverToggle(currentSettings.hapticsEnabled)
        showTransientBanner("Host Locked")
    }

    // -------------------------------------------------------------------------
    // Quick Host Handoff (Clipboard / URL bidirectional sync)
    // -------------------------------------------------------------------------

    fun pushWatchClipboardToHost() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && currentSettings.transportMode != TransportMode.BLUETOOTH_HID) {
            wifiTransport.pushHandoffToHost(
                text = text,
                kind = if (text.startsWith("http://") || text.startsWith("https://")) "url" else "clipboard"
            )
            hapticManager.playLeftClick(currentSettings.hapticsEnabled)
            showTransientBanner("Sent to Host")
        } else if (hasActiveTransport()) {
            // Fallback over pure Bluetooth HID: trigger Paste on host
            SpatialShortcutMapper.mapCustomAction(CustomGestureAction.PASTE, currentSettings.hostOsPreset)
                ?.let { dispatchAction(it) }
            hapticManager.playLeftClick(currentSettings.hapticsEnabled)
            showTransientBanner("Host Paste Sent")
        }
    }

    fun pullHandoffFromHost() {
        if (currentSettings.transportMode != TransportMode.BLUETOOTH_HID) {
            wifiTransport.requestHandoffFromHost()
            hapticManager.playCrownTick(0.7f, currentSettings.hapticsEnabled)
            showTransientBanner("Requested Clipboard")
        } else if (hasActiveTransport()) {
            // Over pure BT HID, copy selected URL/text on host
            SpatialShortcutMapper.mapCustomAction(CustomGestureAction.COPY, currentSettings.hostOsPreset)
                ?.let { dispatchAction(it) }
            hapticManager.playLeftClick(currentSettings.hapticsEnabled)
            showTransientBanner("Copied on Host")
        }
    }

    fun copyReceivedHandoffToWatchClipboard(): Boolean {
        val msg = wifiTransport.receivedHandoff.value ?: return false
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Continuity Gestures Handoff", msg.text))
        hapticManager.playLeftClick(currentSettings.hapticsEnabled)
        showTransientBanner("Copied to Watch")
        return true
    }

    // -------------------------------------------------------------------------
    // Custom 3D Gesture Recorder (On-Device DTW)
    // -------------------------------------------------------------------------

    fun startCustomGestureSampleCapture(onCaptured: (List<FloatArray>) -> Unit) {
        synchronized(stateLock) {
            customGestureRecognizer.startSingleSampleCapture(onCaptured)
        }
    }

    fun cancelCustomGestureSampleCapture() {
        synchronized(stateLock) {
            customGestureRecognizer.cancelSampleCapture()
        }
    }

    fun addCustomGestureTemplate(template: CustomGestureTemplate) {
        val updated = (_customTemplates.value.filterNot { it.id == template.id } + template).take(6)
        persistCustomTemplates(updated)
    }

    fun deleteCustomGestureTemplate(id: String) {
        val updated = _customTemplates.value.filterNot { it.id == id }
        persistCustomTemplates(updated)
    }

    private fun persistCustomTemplates(updated: List<CustomGestureTemplate>) {
        synchronized(stateLock) {
            customGestureRecognizer.setTemplates(updated)
        }
        _customTemplates.value = updated
        settingsRepository.setSerializedCustomGestures(CustomGestureDtwEngine.serialize(updated))
    }

    // -------------------------------------------------------------------------
    // Core real-time pipeline: IMU -> DTW / Analog Roll / GestureDetector / CursorMapper -> HID/Wi-Fi
    // -------------------------------------------------------------------------

    private fun onOffBodySensorChanged(isOnBody: Boolean) {
        _isOffBody.value = !isOnBody
        if (!isOnBody && currentSettings.offBodyAutoPauseEnabled && hasActiveTransport() && !_isPaused.value) {
            setPaused(true)
            showTransientBanner("Off-Body Paused")
        }
    }

    private fun onAmbientLightSample(lux: Float, timestampMs: Long) {
        val action = currentSettings.palmCoverAction
        if (action == PalmCoverAction.OFF || !hasActiveTransport()) return

        val triggered = synchronized(stateLock) {
            palmCoverDetector.onLuxSample(lux, timestampMs)
        }
        if (!triggered) return

        when (action) {
            PalmCoverAction.PAUSE_TRACKING -> {
                setPaused(!_isPaused.value)
                hapticManager.playPalmCoverToggle(currentSettings.hapticsEnabled)
            }

            PalmCoverAction.TOGGLE_DRAG_LOCK -> {
                if (_isPaused.value) return
                val actions = synchronized(stateLock) {
                    isPalmDragLocked = !isPalmDragLocked
                    val gesture = if (isPalmDragLocked) {
                        GestureEvent.PinchHoldStart(timestampMs)
                    } else {
                        GestureEvent.PinchHoldEnd(timestampMs)
                    }
                    _gestureEvents.tryEmit(gesture)
                    modeManager.mapGesture(gesture)
                }
                dispatchActions(actions)
                hapticManager.playPalmCoverToggle(currentSettings.hapticsEnabled)
            }

            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleUwbDecision(decision: ProximityDecision) {
        when (decision) {
            is ProximityDecision.AutoWake -> {
                if (_isPaused.value && !_isOffBody.value) {
                    setPaused(false)
                    hapticManager.playConnected(currentSettings.hapticsEnabled)
                    showTransientBanner("UWB Auto-Wake")
                }
            }
            is ProximityDecision.SwitchHost -> {
                val adapter = bluetoothAdapter ?: return
                if (!adapter.isEnabled || !hasBluetoothConnectPermission()) return
                if (connectedHost?.address == decision.targetHost.address) return
                runCatching { adapter.getRemoteDevice(decision.targetHost.address) }
                    .getOrNull()
                    ?.let { target ->
                        disconnectHost()
                        connectToDevice(target)
                    }
            }
            is ProximityDecision.WalkAwayLock -> {
                if (currentSettings.walkAwayAutoLockEnabled && hasActiveTransport()) {
                    triggerHostLockScreen()
                    setPaused(true)
                }
            }
        }
    }

    private fun onImuSample(sample: ImuSample) {
        if (_isPaused.value && !sensorPreviewActive) return

        // 0. Custom DTW gesture recording or live matching
        val matchedCustom = synchronized(stateLock) {
            customGestureRecognizer.process(sample)
        }
        if (matchedCustom != null && hasActiveTransport() && !_isPaused.value && !sensorPreviewActive) {
            handleCustomGestureMatch(matchedCustom)
        }

        // 1. Analog Forearm Roll Dial while holding Spatial Modifier / Freeze Clutch
        val rollAction = synchronized(stateLock) {
            processAnalogRollDial(sample)
        }
        if (rollAction != null && hasActiveTransport() && !_isPaused.value) {
            dispatchAction(rollAction)
            hapticManager.playCrownTick(intensity = 0.50f, enabled = currentSettings.hapticsEnabled)
        }

        // 2. Run core gesture recognition on every IMU frame
        val gestures = synchronized(stateLock) {
            gestureDetector.process(sample)
        }
        for (gesture in gestures) {
            handleGestureEvent(gesture)
        }

        // 3. Map continuous wrist motion to cursor deltas when an output transport is active
        if (hasActiveTransport() && !_isPaused.value) {
            val action = synchronized(stateLock) {
                val delta = cursorMapper.mapSample(sample)
                modeManager.mapCursorDelta(delta)
            }
            action?.let { dispatchAction(it) }
        }

        // 4. Optional developer telemetry stream over BLE GATT
        if (currentSettings.touchSdkBleEnabled) {
            touchSdkServer.publishOrientation(sample)
        }
    }

    private fun processAnalogRollDial(sample: ImuSample): HidAction? {
        if (!isSpatialModifierHeld || currentSettings.analogRollAction == AnalogRollAction.OFF) {
            lastRollTimestampMs = sample.timestampMs
            analogRollAccumDeg = 0f
            return null
        }

        val dtSec = if (lastRollTimestampMs > 0L) {
            ((sample.timestampMs - lastRollTimestampMs).coerceIn(1L, 80L)) / 1000f
        } else {
            0.01f
        }
        lastRollTimestampMs = sample.timestampMs

        val rollRate = sample.gyroX * currentSettings.handedness.axisSign
        if (abs(rollRate) < ANALOG_ROLL_DEADZONE_RAD_S) {
            analogRollAccumDeg *= 0.88f
            return null
        }

        analogRollAccumDeg += (rollRate * RAD_TO_DEG) * dtSec
        val direction = when {
            analogRollAccumDeg >= ANALOG_ROLL_STEP_DEG -> 1
            analogRollAccumDeg <= -ANALOG_ROLL_STEP_DEG -> -1
            else -> 0
        }
        if (direction == 0) return null
        analogRollAccumDeg -= direction * ANALOG_ROLL_STEP_DEG
        return SpatialShortcutMapper.mapAnalogRollStep(
            direction = direction,
            action = currentSettings.analogRollAction,
            os = currentSettings.hostOsPreset,
            heldMouseButtons = modeManager.heldMouseButtons
        )
    }

    private fun handleCustomGestureMatch(template: CustomGestureTemplate) {
        hapticManager.playModeSwitch(currentSettings.hapticsEnabled)
        showTransientBanner("Custom: ${template.name}")
        if (template.action == CustomGestureAction.MACRO_1) {
            syncedMacros.value.firstOrNull()?.let { triggerHostMacro(it.id) }
            return
        }
        SpatialShortcutMapper.mapCustomAction(template.action, currentSettings.hostOsPreset)
            ?.let { dispatchAction(it) }
    }

    private fun handleGestureEvent(gesture: GestureEvent) {
        if (gesture is GestureEvent.SinglePinch ||
            gesture is GestureEvent.DoublePinch ||
            gesture is GestureEvent.PalmUpPinch
        ) {
            synchronized(stateLock) {
                cursorMapper.triggerClickStabilization(gesture.timestampMs)
            }
        }

        // Intercept Modifier + Directional Flick for OS-level Window Management shortcuts
        if (isSpatialModifierHeld &&
            currentSettings.windowManagementFlicksEnabled &&
            hasActiveTransport() &&
            !_isPaused.value
        ) {
            val windowAction = SpatialShortcutMapper.mapModifierFlick(gesture, currentSettings.hostOsPreset)
            if (windowAction != null) {
                hapticManager.playModeSwitch(currentSettings.hapticsEnabled)
                _gestureEvents.tryEmit(gesture)
                dispatchAction(windowAction)
                showTransientBanner("Window: ${gesture.displayLabel()}")
                return
            }
        }

        hapticManager.playForGesture(gesture, currentSettings.hapticsEnabled)
        _gestureEvents.tryEmit(gesture)

        if (currentSettings.touchSdkBleEnabled) {
            touchSdkServer.publishGesture(gesture)
        }

        if (gesture is GestureEvent.ModeSwitch) {
            if (gesture.forward) cycleMode() else previousMode()
            return
        }

        if (hasActiveTransport() && !_isPaused.value) {
            val actions = synchronized(stateLock) {
                modeManager.mapGesture(gesture)
            }
            dispatchActions(actions)
        }
    }

    private fun showTransientBanner(message: String) {
        _statusBanner.value = message
        bannerClearJob?.cancel()
        bannerClearJob = serviceScope.launch {
            delay(1_800L)
            if (_statusBanner.value == message) {
                _statusBanner.value = null
            }
        }
    }

    private fun clearTransientControlStates(): List<HidAction> {
        val cleanup = synchronized(stateLock) {
            transientClickButtons = HidDescriptors.MOUSE_BUTTON_NONE
            isPalmDragLocked = false
            isSpatialModifierHeld = false
            analogRollAccumDeg = 0f
            cursorMapper.reset()
            modeManager.releaseHeldButtons()
        }
        _isScrollClutchActive.value = false
        _isClutchFrozen.value = false
        _isPrecisionModeActive.value = false
        _isSpatialModifierActive.value = false
        return cleanup
    }

    private fun hasActiveTransport(): Boolean =
        connectedHost != null || currentSettings.transportMode != TransportMode.BLUETOOTH_HID

    private fun dispatchActions(actions: List<HidAction>) {
        for (action in actions) {
            dispatchAction(action)
        }
    }

    @SuppressLint("MissingPermission")
    private fun dispatchAction(action: HidAction) {
        val mode = currentSettings.transportMode
        val sendOverWifi = mode != TransportMode.BLUETOOTH_HID
        val sendOverBt = mode != TransportMode.WIFI_UDP_WS

        if (sendOverWifi) {
            val wifiAction = if (action is HidAction.MouseState) {
                val effectiveButtons = synchronized(stateLock) {
                    action.buttons or transientClickButtons
                }
                action.copy(buttons = effectiveButtons)
            } else {
                action
            }
            wifiTransport.sendAction(wifiAction)
        }

        if (!sendOverBt) return
        val host = connectedHost ?: return
        val hid = hidDevice ?: return
        if (!hasBluetoothConnectPermission()) return

        when (action) {
            is HidAction.MouseState -> {
                val effectiveButtons = synchronized(stateLock) {
                    action.buttons or transientClickButtons
                }
                val report = HidDescriptors.buildMouseReport(
                    buttons = effectiveButtons,
                    dx = action.dx,
                    dy = action.dy,
                    wheel = action.wheel,
                    pan = action.pan
                )
                sendReportSafe(hid, host, HidDescriptors.REPORT_ID_MOUSE, report)
            }

            is HidAction.MouseClick -> {
                val pressButtons = synchronized(stateLock) {
                    transientClickButtons = transientClickButtons or action.buttonMask
                    modeManager.heldMouseButtons or transientClickButtons
                }
                val pressReport = HidDescriptors.buildMouseReport(buttons = pressButtons)
                sendReportSafe(hid, host, HidDescriptors.REPORT_ID_MOUSE, pressReport)
                serviceScope.launch {
                    delay(MOMENTARY_PRESS_MS)
                    val releaseButtons = synchronized(stateLock) {
                        transientClickButtons = transientClickButtons and action.buttonMask.inv()
                        modeManager.heldMouseButtons or transientClickButtons
                    }
                    val currentHost = connectedHost ?: return@launch
                    val currentHid = hidDevice ?: return@launch
                    val releaseReport = HidDescriptors.buildMouseReport(buttons = releaseButtons)
                    sendReportSafe(currentHid, currentHost, HidDescriptors.REPORT_ID_MOUSE, releaseReport)
                }
            }

            is HidAction.KeyTap -> {
                val pressReport = HidDescriptors.buildKeyboardReport(action.keyCode, action.modifiers)
                val releaseReport = HidDescriptors.buildKeyboardReport()
                sendReportSafe(hid, host, HidDescriptors.REPORT_ID_KEYBOARD, pressReport)
                serviceScope.launch {
                    delay(MOMENTARY_PRESS_MS)
                    val currentHost = connectedHost ?: return@launch
                    val currentHid = hidDevice ?: return@launch
                    sendReportSafe(currentHid, currentHost, HidDescriptors.REPORT_ID_KEYBOARD, releaseReport)
                }
            }

            is HidAction.ConsumerTap -> {
                val pressReport = HidDescriptors.buildConsumerReport(action.bitmask)
                val releaseReport = HidDescriptors.buildConsumerReport(HidDescriptors.ConsumerBit.NONE)
                sendReportSafe(hid, host, HidDescriptors.REPORT_ID_CONSUMER, pressReport)
                serviceScope.launch {
                    delay(MOMENTARY_PRESS_MS)
                    val currentHost = connectedHost ?: return@launch
                    val currentHid = hidDevice ?: return@launch
                    sendReportSafe(currentHid, currentHost, HidDescriptors.REPORT_ID_CONSUMER, releaseReport)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendReportSafe(
        hid: BluetoothHidDevice,
        host: BluetoothDevice,
        reportId: Int,
        payload: ByteArray
    ) {
        try {
            hid.sendReport(host, reportId, payload)
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException sending HID report $reportId", se)
        } catch (e: Exception) {
            Log.w(TAG, "Error sending HID report $reportId", e)
        }
    }

    // -------------------------------------------------------------------------
    // HID Profile Registration, Sensor Lifecycle, & Wear OS Ongoing Activity
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun registerHidApplication() {
        val hid = hidDevice ?: return
        if (isAppRegistered || !hasBluetoothConnectPermission()) return

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            getString(R.string.hid_sdp_name),
            getString(R.string.hid_sdp_description),
            getString(R.string.hid_sdp_provider),
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptors.COMPOSITE_DESCRIPTOR
        )

        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11_250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        try {
            hid.registerApp(sdpSettings, null, outQos, hidExecutor, hidCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission registering HID app", se)
            _connectionState.value = ConnectionState.Error("Grant BT permission")
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterHidApplication() {
        try {
            if (isAppRegistered && hasBluetoothConnectPermission()) {
                hidDevice?.unregisterApp()
            }
            hidDevice?.let {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException cleaning up HID app", se)
        } finally {
            isAppRegistered = false
            hidDevice = null
        }
    }

    private fun updateSensorLifecycle() {
        val activeTransport = hasActiveTransport()
        val enableLight = currentSettings.palmCoverAction != PalmCoverAction.OFF && activeTransport
        val needFullImu = (activeTransport && !_isPaused.value) ||
            sensorPreviewActive ||
            currentSettings.touchSdkBleEnabled
        val needLightOnly = !needFullImu &&
            _isPaused.value &&
            currentSettings.palmCoverAction == PalmCoverAction.PAUSE_TRACKING &&
            activeTransport

        if (needFullImu) {
            if (sensorRunningLightOnly) {
                sensorEngine.stop()
                sensorRunningLightOnly = false
            }
            val highFreq = currentSettings.highFrequencySampling && activeTransport
            sensorEngine.start(
                highFrequency = highFreq,
                useNineDof = currentSettings.useNineDofFusion,
                enableLightSensor = enableLight,
                lightOnly = false
            )
        } else if (needLightOnly) {
            if (!sensorRunningLightOnly) {
                sensorEngine.stop()
                sensorRunningLightOnly = true
            }
            sensorEngine.start(
                highFrequency = false,
                useNineDof = false,
                enableLightSensor = true,
                lightOnly = true
            )
        } else {
            sensorRunningLightOnly = false
            sensorEngine.stop()
            synchronized(stateLock) {
                gestureDetector.reset()
                cursorMapper.reset()
                palmCoverDetector.reset()
                customGestureRecognizer.reset()
            }
        }
    }

    private fun restartSensorsIfRunning() {
        sensorRunningLightOnly = false
        sensorEngine.stop()
        updateSensorLifecycle()
    }

    private fun startForegroundCompat(notification: Notification): Boolean {
        if (!hasBluetoothConnectPermission()) return false
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            true
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting connectedDevice foreground service", se)
            false
        }
    }

    private fun updateOngoingNotification() {
        val notification = buildNotification()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeShort = _activeMode.value.shortName
        val statusString = when (val state = _connectionState.value) {
            is ConnectionState.Connected -> if (_isPaused.value) {
                "Paused • ${state.deviceName}"
            } else {
                "$modeShort • ${state.deviceName}"
            }
            is ConnectionState.Discoverable -> getString(R.string.ongoing_discoverable_status)
            else -> if (currentSettings.transportMode != TransportMode.BLUETOOTH_HID) {
                if (_isPaused.value) "Wi-Fi Paused" else "Wi-Fi • $modeShort"
            } else {
                getString(R.string.ongoing_disconnected_status)
            }
        }

        val builder = NotificationCompat.Builder(this, GestureMouseApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusString)
            .setSmallIcon(R.drawable.ic_ongoing_mouse)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        // Attach Wear OS Ongoing Activity chip on the bottom watch face for instant one-tap return
        val ongoingStatus = Status.Builder()
            .addTemplate(statusString)
            .build()
        val ongoingActivity = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_ongoing_mouse)
            .setTouchIntent(pendingIntent)
            .setStatus(ongoingStatus)
            .build()
        ongoingActivity.apply(applicationContext)

        return builder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GestureMouse::HidActiveLock").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        if (!hasBluetoothConnectPermission()) return device.address
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "BluetoothHidService"
        private const val NOTIFICATION_ID = 4201
        private const val MOMENTARY_PRESS_MS = 28L
        private const val TELEMETRY_EMIT_INTERVAL_MS = 33L
        private const val CROWN_STEP_PX = 24f
        private const val ANALOG_ROLL_DEADZONE_RAD_S = 0.85f
        private const val ANALOG_ROLL_STEP_DEG = 16f
        private const val RAD_TO_DEG = 57.29578f
    }
}
