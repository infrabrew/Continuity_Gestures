package com.gesturemouse.wear.ui.screens

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import com.gesturemouse.wear.model.AccelerationCurve
import com.gesturemouse.wear.model.AnalogRollAction
import com.gesturemouse.wear.model.AppThemeColor
import com.gesturemouse.wear.model.HostOsPreset
import com.gesturemouse.wear.model.SettingsState
import com.gesturemouse.wear.model.WatchHardwareProfile
import com.gesturemouse.wear.ui.components.SettingsAccentActionRow
import com.gesturemouse.wear.ui.components.SettingsFloatSliderRow
import com.gesturemouse.wear.ui.components.SettingsHeaderRow
import com.gesturemouse.wear.ui.components.SettingsIntSliderRow
import com.gesturemouse.wear.ui.components.SettingsSelectableRow
import com.gesturemouse.wear.ui.components.SettingsSwitchRow
import com.gesturemouse.wear.ui.components.SettingsTonalActionRow
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent
import kotlinx.coroutines.delay

private const val ADV_TYPE_HEADER = "header"
private const val ADV_TYPE_BUTTON = "button"
private const val ADV_TYPE_SWITCH = "switch"
private const val ADV_TYPE_SLIDER = "slider"

private val WIFI_HOST_PRESETS = listOf(
    "192.168.1.100",
    "192.168.0.100",
    "192.168.1.10",
    "10.0.0.100",
    "172.20.10.2"
)

/**
 * Advanced pointer curves, 3D spatial gestures, host automation, Wear OS security, sensor
 * fusion, and DSP threshold settings with buttery smooth rotary + touch scrolling.
 */
@Composable
fun AdvancedSettingsScreen(
    settings: SettingsState,
    detectedHardwareProfile: WatchHardwareProfile,
    uwbStatusText: String,
    onAccelerationCurveChange: (AccelerationCurve) -> Unit,
    onMonitorCountChange: (Int) -> Unit,
    onAnalogRollActionChange: (AnalogRollAction) -> Unit,
    onHostOsPresetChange: (HostOsPreset) -> Unit,
    onWindowManagementFlicksChange: (Boolean) -> Unit,
    onAutoAppProfilesChange: (Boolean) -> Unit,
    onOffBodyAutoPauseChange: (Boolean) -> Unit,
    onWalkAwayAutoLockChange: (Boolean) -> Unit,
    onInvertYChange: (Boolean) -> Unit,
    onInvertXChange: (Boolean) -> Unit,
    onHorizontalSpeedChange: (Float) -> Unit,
    onVerticalSpeedChange: (Float) -> Unit,
    onPointerAccelerationChange: (Float) -> Unit,
    onPinchThresholdChange: (Float) -> Unit,
    onFlickThresholdChange: (Float) -> Unit,
    onSmoothingChange: (Float) -> Unit,
    onDebounceChange: (Long) -> Unit,
    onHighFreqChange: (Boolean) -> Unit,
    onUseNineDofChange: (Boolean) -> Unit,
    onNativeGestureGuardChange: (Boolean) -> Unit,
    onUwbAutoSwitchChange: (Boolean) -> Unit,
    onWifiHostIpChange: (String) -> Unit,
    onGestureModeSwitchChange: (Boolean) -> Unit,
    onSelectProfile: (WatchHardwareProfile) -> Unit,
    onThemeColorChange: (AppThemeColor) -> Unit,
    onResetToDefaults: () -> Unit,
    onLaunchCalibration: () -> Unit,
    onReplayOnboarding: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val accent = LocalGestureAccent.current
    var confirmResetArmed by remember { mutableStateOf(false) }
    var resetDoneNotice by remember { mutableStateOf(false) }

    LaunchedEffect(confirmResetArmed) {
        if (confirmResetArmed) {
            delay(3_500L)
            confirmResetArmed = false
        }
    }

    LaunchedEffect(resetDoneNotice) {
        if (resetDoneNotice) {
            delay(2_200L)
            resetDoneNotice = false
        }
    }

    val flingBehavior = ScrollableDefaults.flingBehavior()
    val rotaryBehavior = RotaryScrollableDefaults.behavior(
        scrollableState = listState,
        flingBehavior = flingBehavior,
        hapticFeedbackEnabled = settings.hapticsEnabled
    )

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            flingBehavior = flingBehavior,
            rotaryScrollableBehavior = rotaryBehavior,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "hdr_calibration", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "Calibration", transformationSpec = transformationSpec)
            }

            item(key = "btn_calibrate", contentType = ADV_TYPE_BUTTON) {
                SettingsAccentActionRow(
                    title = "Calibrate Pinch",
                    subtitle = "Noise floor: ${formatTwoDecimals(settings.calibratedNoiseFloor)} m/s²",
                    icon = Icons.Rounded.AutoFixHigh,
                    containerColor = accent.container,
                    contentColor = accent.glow,
                    onClick = onLaunchCalibration,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_pointer_mechanics", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "Pointer & Monitors", transformationSpec = transformationSpec)
            }

            item(key = "btn_accel_curve", contentType = ADV_TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Accel Curve",
                    subtitle = "${settings.accelerationCurve.displayName} • ${settings.accelerationCurve.detail}",
                    icon = Icons.Rounded.Speed,
                    onClick = { onAccelerationCurveChange(settings.accelerationCurve.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_monitor_count", contentType = ADV_TYPE_BUTTON) {
                val count = settings.monitorCount.coerceIn(1, 3)
                SettingsTonalActionRow(
                    title = "Monitors (Teleport)",
                    subtitle = if (count == 1) "1 Display (Teleport hidden)" else "$count Displays (D1..D$count pills)",
                    icon = Icons.Rounded.DesktopWindows,
                    onClick = {
                        val nextCount = if (count >= 3) 1 else count + 1
                        onMonitorCountChange(nextCount)
                    },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_spatial_3d", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "3D Spatial Gestures", transformationSpec = transformationSpec)
            }

            item(key = "btn_analog_roll", contentType = ADV_TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Analog Roll Dial",
                    subtitle = "${settings.analogRollAction.displayName} • ${settings.analogRollAction.detail}",
                    icon = Icons.AutoMirrored.Rounded.RotateRight,
                    onClick = { onAnalogRollActionChange(settings.analogRollAction.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_host_os", contentType = ADV_TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Host OS Shortcuts",
                    subtitle = settings.hostOsPreset.displayName,
                    icon = Icons.Rounded.Computer,
                    onClick = { onHostOsPresetChange(settings.hostOsPreset.toggle()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_window_flicks", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Window Mgmt Flicks",
                    subtitle = "Hold side button + flick to snap/overview",
                    checked = settings.windowManagementFlicksEnabled,
                    onCheckedChange = onWindowManagementFlicksChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_automation_security", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "Automation & Security", transformationSpec = transformationSpec)
            }

            item(key = "sw_auto_profiles", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Active-App Profiles",
                    subtitle = "Auto-adapt crown & mode from host",
                    checked = settings.autoAppProfilesEnabled,
                    onCheckedChange = onAutoAppProfilesChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_off_body", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Off-Body Auto-Pause",
                    subtitle = "Pause cursor when watch is removed",
                    checked = settings.offBodyAutoPauseEnabled,
                    onCheckedChange = onOffBodyAutoPauseChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_walk_away_lock", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Walk-Away Auto-Lock",
                    subtitle = "Lock host PC/Mac via UWB/BT range",
                    checked = settings.walkAwayAutoLockEnabled,
                    onCheckedChange = onWalkAwayAutoLockChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_fusion_spatial", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "Sensor Fusion & Connectivity", transformationSpec = transformationSpec)
            }

            item(key = "sw_nine_dof", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "9-DoF Drift-Free IMU",
                    subtitle = if (settings.useNineDofFusion) {
                        "Madgwick accel+gyro+mag"
                    } else {
                        "6-DoF hardware rotation"
                    },
                    checked = settings.useNineDofFusion,
                    onCheckedChange = onUseNineDofChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_native_guard", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "System Gesture Guard",
                    subtitle = "Prevent Wrist-Turn exits & misfires",
                    checked = settings.nativeGestureGuard,
                    onCheckedChange = onNativeGestureGuardChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_uwb_switch", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "UWB Proximity Switch",
                    subtitle = uwbStatusText,
                    checked = settings.uwbAutoSwitchEnabled,
                    onCheckedChange = onUwbAutoSwitchChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_wifi_ip", contentType = ADV_TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Wi-Fi Receiver IP",
                    subtitle = "${settings.wifiHostIp}:${settings.wifiUdpPort} (tap to cycle)",
                    icon = Icons.Rounded.Router,
                    onClick = {
                        val currentIndex = WIFI_HOST_PRESETS.indexOf(settings.wifiHostIp)
                        val nextIp = WIFI_HOST_PRESETS[(currentIndex + 1) % WIFI_HOST_PRESETS.size]
                        onWifiHostIpChange(nextIp)
                    },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_cursor_axis", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "Cursor Axis & Speed", transformationSpec = transformationSpec)
            }

            item(key = "sw_invert_y", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Invert Up / Down (Y)",
                    subtitle = "Flip vertical cursor & scroll",
                    checked = settings.invertY,
                    onCheckedChange = onInvertYChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_invert_x", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Invert Left / Right (X)",
                    subtitle = "Flip horizontal cursor axis",
                    checked = settings.invertX,
                    onCheckedChange = onInvertXChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_horiz_speed", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Horizontal Speed (${formatOneDecimal(settings.horizontalSpeedScale)}x)",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_horiz_speed", contentType = ADV_TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.horizontalSpeedScale,
                    minValue = 0.5f,
                    maxValue = 2.5f,
                    steps = 19,
                    onValueChange = onHorizontalSpeedChange
                )
            }

            item(key = "hdr_vert_speed", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Vertical Speed (${formatOneDecimal(settings.verticalSpeedScale)}x)",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_vert_speed", contentType = ADV_TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.verticalSpeedScale,
                    minValue = 0.5f,
                    maxValue = 2.5f,
                    steps = 19,
                    onValueChange = onVerticalSpeedChange
                )
            }

            item(key = "hdr_accel", contentType = ADV_TYPE_HEADER) {
                val accelLabel = if (settings.pointerAcceleration < 0.05f) {
                    "Accel Boost (Off)"
                } else {
                    "Accel Boost (${formatOneDecimal(settings.pointerAcceleration)}x)"
                }
                SettingsHeaderRow(title = accelLabel, transformationSpec = transformationSpec)
            }

            item(key = "slider_accel", contentType = ADV_TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.pointerAcceleration,
                    minValue = 0.0f,
                    maxValue = 2.5f,
                    steps = 24,
                    onValueChange = onPointerAccelerationChange
                )
            }

            item(key = "hdr_smoothing", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Cursor Smoothing (${(settings.smoothingFactor * 100).toInt()}%)",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_smoothing", contentType = ADV_TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.smoothingFactor,
                    minValue = 0.0f,
                    maxValue = 0.85f,
                    steps = 16,
                    onValueChange = onSmoothingChange
                )
            }

            item(key = "hdr_pinch_thresh", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Pinch Threshold (${formatOneDecimal(settings.pinchThreshold)})",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_pinch_thresh", contentType = ADV_TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.pinchThreshold,
                    minValue = 1.5f,
                    maxValue = 7.5f,
                    steps = 23,
                    onValueChange = onPinchThresholdChange
                )
            }

            item(key = "hdr_flick_thresh", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Flick Threshold (${formatOneDecimal(settings.flickThreshold)})",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_flick_thresh", contentType = ADV_TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.flickThreshold,
                    minValue = 2.2f,
                    maxValue = 8.0f,
                    steps = 28,
                    onValueChange = onFlickThresholdChange
                )
            }

            item(key = "hdr_debounce", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Debounce (${settings.debounceMs} ms)",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_debounce", contentType = ADV_TYPE_SLIDER) {
                SettingsIntSliderRow(
                    value = settings.debounceMs.toInt(),
                    minValue = 100,
                    maxValue = 500,
                    step = 25,
                    onValueChange = { onDebounceChange(it.toLong()) }
                )
            }

            item(key = "sw_high_freq", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "100 Hz Tracking",
                    subtitle = if (settings.highFrequencySampling) "Ultra smooth" else "50 Hz battery saver",
                    checked = settings.highFrequencySampling,
                    onCheckedChange = onHighFreqChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_gesture_mode", contentType = ADV_TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Gesture Mode Switch",
                    subtitle = "Palm-up flick cycles modes",
                    checked = settings.gestureModeSwitchEnabled,
                    onCheckedChange = onGestureModeSwitchChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_profile", contentType = ADV_TYPE_HEADER) {
                SettingsHeaderRow(title = "Watch Sensor Profile", transformationSpec = transformationSpec)
            }

            WatchHardwareProfile.entries.forEach { profile ->
                val selected = settings.watchProfile == profile
                item(key = "profile_${profile.name}", contentType = ADV_TYPE_BUTTON) {
                    val labelText = if (profile == WatchHardwareProfile.AUTO &&
                        detectedHardwareProfile != WatchHardwareProfile.AUTO
                    ) {
                        "Auto (${detectedHardwareProfile.displayName})"
                    } else {
                        profile.displayName
                    }
                    SettingsSelectableRow(
                        title = labelText,
                        subtitle = profile.notes,
                        selected = selected,
                        selectedIcon = Icons.Rounded.CheckCircle,
                        onClick = { onSelectProfile(profile) },
                        transformationSpec = transformationSpec
                    )
                }
            }

            item(key = "btn_theme_color_adv", contentType = ADV_TYPE_BUTTON) {
                SettingsAccentActionRow(
                    title = "Theme Color",
                    subtitle = "${settings.themeColor.displayName} (tap to cycle)",
                    icon = Icons.Rounded.Palette,
                    containerColor = accent.container,
                    contentColor = accent.glow,
                    onClick = { onThemeColorChange(settings.themeColor.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_replay_onboarding", contentType = ADV_TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Replay Tutorial",
                    icon = Icons.Rounded.School,
                    onClick = onReplayOnboarding,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_reset_defaults_adv", contentType = ADV_TYPE_BUTTON) {
                SettingsAccentActionRow(
                    title = when {
                        resetDoneNotice -> "Defaults Restored"
                        confirmResetArmed -> "Tap Again to Reset"
                        else -> "Reset All Settings"
                    },
                    subtitle = when {
                        resetDoneNotice -> "Tuning, gestures & theme restored"
                        confirmResetArmed -> "Confirm reset to factory defaults"
                        else -> "Restore default tuning & theme"
                    },
                    icon = Icons.Rounded.RestartAlt,
                    containerColor = if (confirmResetArmed) {
                        GestureColors.AmberWarning.copy(alpha = 0.28f)
                    } else {
                        GestureColors.SurfaceCard
                    },
                    contentColor = if (resetDoneNotice) accent.glow else GestureColors.AmberWarning,
                    onClick = {
                        if (confirmResetArmed) {
                            confirmResetArmed = false
                            resetDoneNotice = true
                            onResetToDefaults()
                        } else {
                            resetDoneNotice = false
                            confirmResetArmed = true
                        }
                    },
                    transformationSpec = transformationSpec
                )
            }
        }
    }
}
