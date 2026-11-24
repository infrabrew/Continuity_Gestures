package com.gesturemouse.wear

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import com.gesturemouse.wear.hid.BluetoothHidService
import com.gesturemouse.wear.model.AppThemeColor
import com.gesturemouse.wear.model.TransportMode
import com.gesturemouse.wear.ui.GestureMouseViewModel
import com.gesturemouse.wear.ui.navigation.GestureMouseNavHost
import com.gesturemouse.wear.ui.theme.GestureMouseTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Window brightness presets for active tracking, OLED dim veil, and 1-Nit Stealth Trackpad mode.
 */
enum class ScreenBrightnessMode {
    NORMAL,
    DIM_ACTIVE,
    STEALTH_1NIT
}

/**
 * Main Wear OS Activity hosting the Jetpack Compose Material 3 UI, managing runtime
 * Bluetooth/Notification permissions, providing [LocalAmbientModeManager] for burn-in-safe
 * ambient mode, mapping physical Wear OS side stem buttons to the hold-to-freeze/precision/
 * spatial-modifier clutch, handling Tile/Complication one-tap connect intents, starting/binding
 * [BluetoothHidService], and launching Bluetooth Classic discoverability.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: GestureMouseViewModel by viewModels()
    private var isBound = false
    private var pendingTileConnectAddress: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = (service as? BluetoothHidService.LocalBinder) ?: return
            viewModel.attachService(binder.getService())
            isBound = true
            pendingTileConnectAddress?.let { addr ->
                pendingTileConnectAddress = null
                viewModel.connectToAddress(addr)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            viewModel.detachService()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = hasRequiredBluetoothPermissions()
        viewModel.updateBluetoothPermissionStatus(granted)
        if (granted) {
            ensureServiceStartedAndBound()
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_CANCELED) {
            val durationSec = if (result.resultCode > 0) result.resultCode else DISCOVERABLE_DURATION_SEC
            viewModel.notifyDiscoverableStarted(durationSec)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleLaunchIntent(intent)

        val permissionsGranted = hasRequiredBluetoothPermissions()
        viewModel.updateBluetoothPermissionStatus(permissionsGranted)
        if (!permissionsGranted) {
            requestRuntimePermissions()
        } else {
            ensureServiceStartedAndBound()
        }

        // Keep screen gently awake while actively streaming (Bluetooth HID or 5 GHz Wi-Fi)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .map { state ->
                        (state.connectionState.isConnected ||
                            state.settings.transportMode != TransportMode.BLUETOOTH_HID) && !state.isPaused
                    }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            setWindowBrightnessMode(ScreenBrightnessMode.NORMAL)
                        }
                    }
            }
        }

        setContent {
            val ambientModeManager = rememberAmbientModeManager()
            val themeColorFlow = remember(viewModel) {
                viewModel.settingsState
                    .map { it.themeColor }
                    .distinctUntilChanged()
            }
            val themeColor by themeColorFlow.collectAsStateWithLifecycle(
                initialValue = AppThemeColor.NEON_GREEN
            )
            CompositionLocalProvider(LocalAmbientModeManager provides ambientModeManager) {
                GestureMouseTheme(themeColor = themeColor) {
                    GestureMouseNavHost(
                        viewModel = viewModel,
                        onMakeDiscoverable = ::launchMakeDiscoverable,
                        onRequestPermissions = ::requestRuntimePermissions,
                        onBrightnessModeChanged = ::setWindowBrightnessMode
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredBluetoothPermissions()) {
            ensureServiceStartedAndBound()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.setSideButtonHeld(held = false)
        setWindowBrightnessMode(ScreenBrightnessMode.NORMAL)
        // Unbind UI observer while keeping the foreground BluetoothHidService alive in background
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            viewModel.detachService()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isSideStemKey(keyCode) && viewModel.isSideButtonClutchArmed()) {
            if (event.repeatCount == 0) {
                viewModel.setSideButtonHeld(held = true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isSideStemKey(keyCode) && viewModel.isSideButtonClutchArmed()) {
            viewModel.setSideButtonHeld(held = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val targetAddress = intent?.getStringExtra(EXTRA_CONNECT_ADDRESS)?.trim().orEmpty()
        if (targetAddress.isNotEmpty()) {
            if (isBound) {
                viewModel.connectToAddress(targetAddress)
            } else {
                pendingTileConnectAddress = targetAddress
            }
        }
    }

    private fun isSideStemKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_STEM_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_STEM_1 ||
            keyCode == KeyEvent.KEYCODE_STEM_2 ||
            keyCode == KeyEvent.KEYCODE_STEM_3

    private fun setWindowBrightnessMode(mode: ScreenBrightnessMode) {
        val target = when (mode) {
            ScreenBrightnessMode.NORMAL -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            ScreenBrightnessMode.DIM_ACTIVE -> DIM_SCREEN_BRIGHTNESS
            ScreenBrightnessMode.STEALTH_1NIT -> STEALTH_1NIT_BRIGHTNESS
        }
        if (window.attributes.screenBrightness != target) {
            val params = window.attributes
            params.screenBrightness = target
            window.attributes = params
        }
    }

    private fun ensureServiceStartedAndBound() {
        val serviceIntent = Intent(this, BluetoothHidService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        if (!isBound) {
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    private fun launchMakeDiscoverable() {
        if (!hasRequiredBluetoothPermissions()) {
            requestRuntimePermissions()
            return
        }
        ensureServiceStartedAndBound()
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SEC)
        }
        discoverableLauncher.launch(discoverableIntent)
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val connect = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val advertise = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
        return connect && advertise
    }

    companion object {
        const val EXTRA_CONNECT_ADDRESS = "com.gesturemouse.wear.EXTRA_CONNECT_ADDRESS"
        private const val DISCOVERABLE_DURATION_SEC = 180
        private const val DIM_SCREEN_BRIGHTNESS = 0.08f
        private const val STEALTH_1NIT_BRIGHTNESS = 0.01f
    }
}
