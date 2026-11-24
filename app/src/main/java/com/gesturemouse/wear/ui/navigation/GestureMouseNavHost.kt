package com.gesturemouse.wear.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.gesturemouse.wear.ScreenBrightnessMode
import com.gesturemouse.wear.ui.GestureMouseViewModel
import com.gesturemouse.wear.ui.screens.AdvancedSettingsScreen
import com.gesturemouse.wear.ui.screens.CalibrationScreen
import com.gesturemouse.wear.ui.screens.CustomGestureScreen
import com.gesturemouse.wear.ui.screens.MainScreen
import com.gesturemouse.wear.ui.screens.OnboardingScreen
import com.gesturemouse.wear.ui.screens.QuickSettingsScreen
import com.gesturemouse.wear.ui.screens.TipsScreen
import com.gesturemouse.wear.ui.theme.GestureColors

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val QUICK_SETTINGS = "quick_settings"
    const val ADVANCED_SETTINGS = "advanced_settings"
    const val CALIBRATION = "calibration"
    const val CUSTOM_GESTURES = "custom_gestures"
    const val TIPS = "tips"
}

/**
 * Top-level Wear OS Compose Material 3 navigation graph wrapped in a single [AppScaffold].
 *
 * State collection is scoped strictly inside each destination composable so background
 * sensor, gesture, or connection ticks never recompose the navigation host or unrelated
 * settings screens while scrolling.
 */
@Composable
fun GestureMouseNavHost(
    viewModel: GestureMouseViewModel,
    onMakeDiscoverable: () -> Unit,
    onRequestPermissions: () -> Unit,
    onBrightnessModeChanged: (ScreenBrightnessMode) -> Unit
) {
    val isSettingsLoaded by viewModel.isSettingsLoaded.collectAsStateWithLifecycle()

    if (!isSettingsLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GestureColors.BackgroundBlack)
        )
        return
    }

    val navController = rememberSwipeDismissableNavController()
    val startDestination = remember(viewModel) {
        if (viewModel.settingsState.value.onboardingCompleted) Routes.MAIN else Routes.ONBOARDING
    }

    AppScaffold {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            composable(Routes.ONBOARDING) {
                val lastGesture by viewModel.lastGesture.collectAsStateWithLifecycle()
                OnboardingScreen(
                    lastGesture = lastGesture,
                    onSetSensorPreview = viewModel::setSensorPreviewActive,
                    onFinish = {
                        val isReplay = viewModel.settingsState.value.onboardingCompleted
                        viewModel.setOnboardingCompleted(true)
                        if (isReplay) {
                            navController.popBackStack(Routes.MAIN, inclusive = false)
                        } else {
                            navController.navigate(Routes.MAIN) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(Routes.MAIN) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                MainScreen(
                    uiState = uiState,
                    onMakeDiscoverable = onMakeDiscoverable,
                    onReconnectLast = { viewModel.reconnectLastDevice() },
                    onDisconnect = viewModel::disconnectHost,
                    onTogglePause = viewModel::togglePause,
                    onToggleScrollClutch = viewModel::toggleScrollClutch,
                    onCycleMode = viewModel::cycleMode,
                    onManualClick = viewModel::triggerManualClick,
                    onLeftClick = viewModel::triggerLeftClick,
                    onRightClick = viewModel::triggerRightClick,
                    onMicroTrackpadDrag = viewModel::onMicroTrackpadDrag,
                    onTeleportToMonitor = viewModel::teleportToMonitor,
                    onCrownScroll = viewModel::onCrownScroll,
                    onTriggerHostMacro = viewModel::triggerHostMacro,
                    onOpenQuickSettings = { navController.navigate(Routes.QUICK_SETTINGS) },
                    onRequestPermissions = onRequestPermissions,
                    onBrightnessModeChanged = onBrightnessModeChanged
                )
            }

            composable(Routes.QUICK_SETTINGS) {
                val quickState by viewModel.quickSettingsState.collectAsStateWithLifecycle()
                QuickSettingsScreen(
                    uiState = quickState,
                    onSelectMode = viewModel::switchMode,
                    onSensitivityChange = viewModel::setCursorSensitivity,
                    onInvertYChange = viewModel::setInvertY,
                    onHandednessChange = viewModel::setHandedness,
                    onHybridMicroTrackpadChange = viewModel::setHybridMicroTrackpadEnabled,
                    onTeleportToMonitor = { monitor ->
                        viewModel.teleportToMonitor(monitor)
                        navController.popBackStack()
                    },
                    onPullHandoff = viewModel::pullHandoffFromHost,
                    onPushHandoff = viewModel::pushWatchClipboardToHost,
                    onCopyReceivedHandoff = { viewModel.copyReceivedHandoffToWatchClipboard() },
                    onLockHostScreen = {
                        viewModel.triggerHostLockScreen()
                        navController.popBackStack()
                    },
                    onCrownActionChange = viewModel::setCrownAction,
                    onSideButtonActionChange = viewModel::setSideButtonAction,
                    onPalmCoverActionChange = viewModel::setPalmCoverAction,
                    onTransportModeChange = viewModel::setTransportMode,
                    onTriggerHostMacro = viewModel::triggerHostMacro,
                    onWristTwistTargetChange = viewModel::setWristTwistMediaTarget,
                    onScrollClutchChange = viewModel::setScrollClutch,
                    onHapticsChange = viewModel::setHapticsEnabled,
                    onThemeColorChange = viewModel::setThemeColor,
                    onStealthTrackpadChange = viewModel::setStealthTrackpadEnabled,
                    onDimScreenChange = viewModel::setDimScreenWhileActive,
                    onTouchSdkChange = viewModel::setTouchSdkBleEnabled,
                    onResetToDefaults = viewModel::resetToDefaults,
                    onReconnectLast = {
                        viewModel.reconnectLastDevice()
                        navController.popBackStack()
                    },
                    onDisconnect = {
                        viewModel.disconnectHost()
                        navController.popBackStack()
                    },
                    onOpenCustomGestures = { navController.navigate(Routes.CUSTOM_GESTURES) },
                    onOpenAdvanced = { navController.navigate(Routes.ADVANCED_SETTINGS) },
                    onOpenTips = { navController.navigate(Routes.TIPS) }
                )
            }

            composable(Routes.ADVANCED_SETTINGS) {
                val settings by viewModel.settingsState.collectAsStateWithLifecycle()
                val detectedProfile by viewModel.detectedHardwareProfile.collectAsStateWithLifecycle()
                val uwbStatus by viewModel.uwbStatusText.collectAsStateWithLifecycle()
                AdvancedSettingsScreen(
                    settings = settings,
                    detectedHardwareProfile = detectedProfile,
                    uwbStatusText = uwbStatus,
                    onAccelerationCurveChange = viewModel::setAccelerationCurve,
                    onMonitorCountChange = viewModel::setMonitorCount,
                    onAnalogRollActionChange = viewModel::setAnalogRollAction,
                    onHostOsPresetChange = viewModel::setHostOsPreset,
                    onWindowManagementFlicksChange = viewModel::setWindowManagementFlicksEnabled,
                    onAutoAppProfilesChange = viewModel::setAutoAppProfilesEnabled,
                    onOffBodyAutoPauseChange = viewModel::setOffBodyAutoPauseEnabled,
                    onWalkAwayAutoLockChange = viewModel::setWalkAwayAutoLockEnabled,
                    onInvertYChange = viewModel::setInvertY,
                    onInvertXChange = viewModel::setInvertX,
                    onHorizontalSpeedChange = viewModel::setHorizontalSpeedScale,
                    onVerticalSpeedChange = viewModel::setVerticalSpeedScale,
                    onPointerAccelerationChange = viewModel::setPointerAcceleration,
                    onPinchThresholdChange = viewModel::setPinchThreshold,
                    onFlickThresholdChange = viewModel::setFlickThreshold,
                    onSmoothingChange = viewModel::setSmoothingFactor,
                    onDebounceChange = viewModel::setDebounceMs,
                    onHighFreqChange = viewModel::setHighFrequencySampling,
                    onUseNineDofChange = viewModel::setUseNineDofFusion,
                    onNativeGestureGuardChange = viewModel::setNativeGestureGuard,
                    onUwbAutoSwitchChange = viewModel::setUwbAutoSwitchEnabled,
                    onWifiHostIpChange = viewModel::setWifiHostIp,
                    onGestureModeSwitchChange = viewModel::setGestureModeSwitchEnabled,
                    onSelectProfile = viewModel::applyWatchProfile,
                    onThemeColorChange = viewModel::setThemeColor,
                    onResetToDefaults = viewModel::resetToDefaults,
                    onLaunchCalibration = { navController.navigate(Routes.CALIBRATION) },
                    onReplayOnboarding = { navController.navigate(Routes.ONBOARDING) }
                )
            }

            composable(Routes.CALIBRATION) {
                val impulseState = viewModel.liveImpulseScore.collectAsStateWithLifecycle()
                CalibrationScreen(
                    liveImpulseState = impulseState,
                    onSetSensorPreview = viewModel::setSensorPreviewActive,
                    onSaveCalibration = viewModel::saveCalibration,
                    onDone = { navController.popBackStack() }
                )
            }

            composable(Routes.CUSTOM_GESTURES) {
                val templates by viewModel.customTemplates.collectAsStateWithLifecycle()
                val settings by viewModel.settingsState.collectAsStateWithLifecycle()
                CustomGestureScreen(
                    templates = templates,
                    hapticsEnabled = settings.hapticsEnabled,
                    onSetSensorPreview = viewModel::setSensorPreviewActive,
                    onStartSampleCapture = viewModel::startCustomGestureSampleCapture,
                    onCancelSampleCapture = viewModel::cancelCustomGestureSampleCapture,
                    onAddTemplate = viewModel::addCustomGestureTemplate,
                    onDeleteTemplate = viewModel::deleteCustomGestureTemplate
                )
            }

            composable(Routes.TIPS) {
                val settings by viewModel.settingsState.collectAsStateWithLifecycle()
                TipsScreen(hapticsEnabled = settings.hapticsEnabled)
            }
        }
    }
}
