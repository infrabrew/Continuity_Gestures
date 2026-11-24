package com.gesturemouse.wear.ui.screens

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import com.gesturemouse.wear.model.AppThemeColor
import com.gesturemouse.wear.model.CrownAction
import com.gesturemouse.wear.model.Handedness
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.PalmCoverAction
import com.gesturemouse.wear.model.SideButtonAction
import com.gesturemouse.wear.model.TransportMode
import com.gesturemouse.wear.model.WristTwistMediaTarget
import com.gesturemouse.wear.ui.QuickSettingsUiState
import com.gesturemouse.wear.ui.components.SettingsAccentActionRow
import com.gesturemouse.wear.ui.components.SettingsFloatSliderRow
import com.gesturemouse.wear.ui.components.SettingsHeaderRow
import com.gesturemouse.wear.ui.components.SettingsSelectableRow
import com.gesturemouse.wear.ui.components.SettingsSwitchRow
import com.gesturemouse.wear.ui.components.SettingsTonalActionRow
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val TYPE_HEADER = "header"
private const val TYPE_BUTTON = "button"
private const val TYPE_SWITCH = "switch"
private const val TYPE_SLIDER = "slider"
private const val TYPE_MACRO = "macro"

/**
 * Quick Settings screen with buttery smooth fling + haptic crown scrolling, Multi-Monitor
 * Teleport, Quick Host Handoff, Active-App Profile indicator, Custom 3D Gestures launcher,
 * Hybrid Micro-Trackpad, hardware control mappings, and dual BT/Wi-Fi transport selector.
 */
@Composable
fun QuickSettingsScreen(
    uiState: QuickSettingsUiState,
    onSelectMode: (InputMode) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onInvertYChange: (Boolean) -> Unit,
    onHandednessChange: (Handedness) -> Unit,
    onHybridMicroTrackpadChange: (Boolean) -> Unit,
    onTeleportToMonitor: (Int) -> Unit,
    onPullHandoff: () -> Unit,
    onPushHandoff: () -> Unit,
    onCopyReceivedHandoff: () -> Unit,
    onLockHostScreen: () -> Unit,
    onCrownActionChange: (CrownAction) -> Unit,
    onSideButtonActionChange: (SideButtonAction) -> Unit,
    onPalmCoverActionChange: (PalmCoverAction) -> Unit,
    onTransportModeChange: (TransportMode) -> Unit,
    onTriggerHostMacro: (String) -> Unit,
    onWristTwistTargetChange: (WristTwistMediaTarget) -> Unit,
    onScrollClutchChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onThemeColorChange: (AppThemeColor) -> Unit,
    onStealthTrackpadChange: (Boolean) -> Unit,
    onDimScreenChange: (Boolean) -> Unit,
    onTouchSdkChange: (Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onReconnectLast: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCustomGestures: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenTips: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val settings = uiState.settings
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
            if (uiState.isLowBattery) {
                item(key = "low_battery_notice", contentType = TYPE_BUTTON) {
                    SettingsAccentActionRow(
                        title = "Low Battery (${uiState.batteryPercent}%)",
                        subtitle = "Tap for 1-Nit Stealth Trackpad",
                        icon = Icons.Rounded.BatteryAlert,
                        containerColor = GestureColors.AmberWarning.copy(alpha = 0.22f),
                        contentColor = GestureColors.AmberWarning,
                        onClick = { onStealthTrackpadChange(true) },
                        transformationSpec = transformationSpec
                    )
                }
            }

            if (!uiState.isConnected && settings.lastConnectedAddress.isNotBlank()) {
                item(key = "reconnect_last", contentType = TYPE_BUTTON) {
                    SettingsAccentActionRow(
                        title = "Reconnect",
                        subtitle = settings.lastConnectedName.ifBlank { settings.lastConnectedAddress },
                        icon = Icons.Rounded.BluetoothConnected,
                        containerColor = accent.container,
                        contentColor = accent.glow,
                        onClick = onReconnectLast,
                        transformationSpec = transformationSpec
                    )
                }
            }

            // Active-App Auto-Profile banner pushed by companion daemon
            uiState.activeAppProfile?.let { profile ->
                item(key = "active_app_profile", contentType = TYPE_BUTTON) {
                    val crownLabel = profile.recommendedCrown?.displayName ?: settings.crownAction.displayName
                    SettingsAccentActionRow(
                        title = "Auto-Profile: ${profile.appName}",
                        subtitle = "${profile.category} • Crown: $crownLabel",
                        icon = Icons.Rounded.AutoAwesome,
                        containerColor = accent.container,
                        contentColor = accent.glow,
                        onClick = {},
                        transformationSpec = transformationSpec
                    )
                }
            }

            // Live app-specific macros pushed from 5 GHz Wi-Fi companion receiver
            if (uiState.syncedMacros.isNotEmpty()) {
                item(key = "hdr_macros", contentType = TYPE_HEADER) {
                    val appTitle = uiState.syncedMacros.first().appName
                    SettingsHeaderRow(
                        title = if (appTitle.isNotBlank()) "$appTitle Macros" else "Host Macros",
                        transformationSpec = transformationSpec
                    )
                }

                items(
                    items = uiState.syncedMacros,
                    key = { macro -> "macro_${macro.id}" },
                    contentType = { TYPE_MACRO }
                ) { macro ->
                    SettingsAccentActionRow(
                        title = macro.label,
                        subtitle = "Wi-Fi Macro",
                        icon = Icons.Rounded.Bolt,
                        containerColor = GestureColors.CyanContainer,
                        contentColor = GestureColors.CyanPairing,
                        onClick = { onTriggerHostMacro(macro.id) },
                        transformationSpec = transformationSpec
                    )
                }
            }

            // Multi-Monitor Teleport quick warp buttons
            if (uiState.hasActiveTransport && settings.monitorCount > 1) {
                item(key = "hdr_teleport", contentType = TYPE_HEADER) {
                    SettingsHeaderRow(
                        title = "Monitor Teleport",
                        transformationSpec = transformationSpec
                    )
                }

                for (monitor in 1..settings.monitorCount.coerceIn(1, 3)) {
                    item(key = "teleport_$monitor", contentType = TYPE_BUTTON) {
                        SettingsAccentActionRow(
                            title = "Warp to Display $monitor",
                            subtitle = "Center cursor on monitor $monitor",
                            icon = Icons.Rounded.DesktopWindows,
                            containerColor = GestureColors.CyanContainer,
                            contentColor = GestureColors.CyanPairing,
                            onClick = { onTeleportToMonitor(monitor) },
                            transformationSpec = transformationSpec
                        )
                    }
                }
            }

            // Quick Host Handoff & Host Lock Screen
            if (uiState.hasActiveTransport) {
                item(key = "hdr_handoff", contentType = TYPE_HEADER) {
                    SettingsHeaderRow(
                        title = "Host Handoff & Lock",
                        transformationSpec = transformationSpec
                    )
                }

                uiState.receivedHandoff?.let { handoff ->
                    item(key = "btn_copy_handoff", contentType = TYPE_BUTTON) {
                        SettingsAccentActionRow(
                            title = "Copy Received ${handoff.kind.uppercase()}",
                            subtitle = handoff.text.take(44),
                            icon = Icons.Rounded.ContentCopy,
                            containerColor = accent.container,
                            contentColor = accent.glow,
                            onClick = onCopyReceivedHandoff,
                            transformationSpec = transformationSpec
                        )
                    }
                }

                item(key = "btn_pull_handoff", contentType = TYPE_BUTTON) {
                    val isWifi = settings.transportMode != TransportMode.BLUETOOTH_HID
                    SettingsTonalActionRow(
                        title = if (isWifi) "Pull Host Clip / URL" else "Copy on Host",
                        subtitle = if (isWifi) "Fetch host clipboard to watch" else "Send Ctrl/Cmd+C",
                        icon = if (isWifi) Icons.Rounded.Download else Icons.Rounded.ContentCopy,
                        onClick = onPullHandoff,
                        transformationSpec = transformationSpec
                    )
                }

                item(key = "btn_push_handoff", contentType = TYPE_BUTTON) {
                    val isWifi = settings.transportMode != TransportMode.BLUETOOTH_HID
                    SettingsTonalActionRow(
                        title = if (isWifi) "Push Watch Clip" else "Paste on Host",
                        subtitle = if (isWifi) "Send watch clipboard to host" else "Send Ctrl/Cmd+V",
                        icon = if (isWifi) Icons.Rounded.Upload else Icons.Rounded.ContentPaste,
                        onClick = onPushHandoff,
                        transformationSpec = transformationSpec
                    )
                }

                item(key = "btn_lock_host", contentType = TYPE_BUTTON) {
                    SettingsAccentActionRow(
                        title = "Lock Host Screen",
                        subtitle = settings.hostOsPreset.displayName,
                        icon = Icons.Rounded.Lock,
                        containerColor = GestureColors.AmberWarning.copy(alpha = 0.22f),
                        contentColor = GestureColors.AmberWarning,
                        onClick = onLockHostScreen,
                        transformationSpec = transformationSpec
                    )
                }
            }

            item(key = "hdr_mode", contentType = TYPE_HEADER) {
                SettingsHeaderRow(title = "Input Mode", transformationSpec = transformationSpec)
            }

            InputMode.entries.forEach { mode ->
                val isSelected = uiState.activeMode == mode
                item(key = "mode_${mode.name}", contentType = TYPE_BUTTON) {
                    SettingsSelectableRow(
                        title = mode.displayName,
                        subtitle = mode.description,
                        selected = isSelected,
                        selectedIcon = Icons.Rounded.CheckCircle,
                        onClick = { onSelectMode(mode) },
                        transformationSpec = transformationSpec
                    )
                }
            }

            if (uiState.isConnected && uiState.activeMode == InputMode.MOUSE) {
                item(key = "scroll_clutch", contentType = TYPE_SWITCH) {
                    SettingsSwitchRow(
                        title = "Scroll Clutch",
                        subtitle = "Tilt wrist up/down to scroll",
                        checked = uiState.isScrollClutchActive,
                        onCheckedChange = onScrollClutchChange,
                        transformationSpec = transformationSpec
                    )
                }
            }

            item(key = "hdr_speed", contentType = TYPE_HEADER) {
                SettingsHeaderRow(
                    title = "Cursor Speed (${formatOneDecimal(settings.cursorSensitivity)}x)",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "slider_speed", contentType = TYPE_SLIDER) {
                SettingsFloatSliderRow(
                    value = settings.cursorSensitivity,
                    minValue = 0.4f,
                    maxValue = 5.0f,
                    steps = 22,
                    onValueChange = onSensitivityChange
                )
            }

            item(key = "sw_hybrid_trackpad", contentType = TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Micro-Trackpad",
                    subtitle = "Finger slide on glass nudges cursor",
                    checked = settings.hybridMicroTrackpadEnabled,
                    onCheckedChange = onHybridMicroTrackpadChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_handedness", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Wrist Handedness",
                    subtitle = settings.handedness.displayName,
                    onClick = {
                        val next = if (settings.handedness == Handedness.LEFT) {
                            Handedness.RIGHT
                        } else {
                            Handedness.LEFT
                        }
                        onHandednessChange(next)
                    },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_invert_y", contentType = TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Invert Up / Down",
                    subtitle = "Flip vertical cursor axis",
                    checked = settings.invertY,
                    onCheckedChange = onInvertYChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_hardware", contentType = TYPE_HEADER) {
                SettingsHeaderRow(title = "Hardware Controls", transformationSpec = transformationSpec)
            }

            item(key = "btn_crown_action", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Crown Wheel",
                    subtitle = "${settings.crownAction.displayName} • ${settings.crownAction.detail}",
                    onClick = { onCrownActionChange(settings.crownAction.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_side_clutch", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Side-Button Clutch",
                    subtitle = "${settings.sideButtonAction.displayName} • ${settings.sideButtonAction.detail}",
                    onClick = { onSideButtonActionChange(settings.sideButtonAction.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_palm_cover", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Palm-Cover Sensor",
                    subtitle = "${settings.palmCoverAction.displayName} • ${settings.palmCoverAction.detail}",
                    onClick = { onPalmCoverActionChange(settings.palmCoverAction.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_custom_gestures", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Custom 3D Gestures",
                    subtitle = "${uiState.customGestureCount} saved • Record DTW macros",
                    icon = Icons.Rounded.Gesture,
                    onClick = onOpenCustomGestures,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_display_conn", contentType = TYPE_HEADER) {
                SettingsHeaderRow(title = "Theme, Display & Transport", transformationSpec = transformationSpec)
            }

            item(key = "btn_theme_color", contentType = TYPE_BUTTON) {
                SettingsAccentActionRow(
                    title = "Theme Color",
                    subtitle = "${settings.themeColor.displayName} • Tap to cycle (${AppThemeColor.entries.size})",
                    icon = Icons.Rounded.Palette,
                    containerColor = accent.container,
                    contentColor = accent.glow,
                    onClick = { onThemeColorChange(settings.themeColor.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_stealth_1nit", contentType = TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "1-Nit Stealth Pad",
                    subtitle = "Pure black 1 Hz L/R tap + slide",
                    checked = settings.stealthTrackpadEnabled,
                    onCheckedChange = onStealthTrackpadChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_dim", contentType = TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Dim While Active",
                    subtitle = "Soft OLED dim veil",
                    checked = settings.dimScreenWhileActive,
                    onCheckedChange = onDimScreenChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_transport", contentType = TYPE_BUTTON) {
                val transportSub = when (settings.transportMode) {
                    TransportMode.BLUETOOTH_HID -> settings.transportMode.detail
                    TransportMode.WIFI_UDP_WS,
                    TransportMode.DUAL_BT_AND_WIFI -> {
                        val wsTag = if (uiState.isWifiWsConnected) "WS Live" else settings.wifiHostIp
                        "${settings.transportMode.displayName} • $wsTag"
                    }
                }
                SettingsTonalActionRow(
                    title = "Transport Mode",
                    subtitle = transportSub,
                    onClick = { onTransportModeChange(settings.transportMode.next()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "hdr_preferences", contentType = TYPE_HEADER) {
                SettingsHeaderRow(title = "Preferences", transformationSpec = transformationSpec)
            }

            item(key = "btn_wrist_twist", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Wrist Twist (Media)",
                    subtitle = settings.wristTwistMediaTarget.displayName,
                    onClick = { onWristTwistTargetChange(settings.wristTwistMediaTarget.toggle()) },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_haptics", contentType = TYPE_SWITCH) {
                SettingsSwitchRow(
                    title = "Gen 3 Haptics",
                    subtitle = "Distinct L/R click & crown ticks",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = onHapticsChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "sw_touch_sdk", contentType = TYPE_SWITCH) {
                val detail = when {
                    !settings.touchSdkBleEnabled -> "Stream raw gestures (GATT)"
                    uiState.touchSdkSubscriberCount > 0 ->
                        "Streaming (${uiState.touchSdkSubscriberCount} client)"
                    else -> "Advertising GATT service"
                }
                SettingsSwitchRow(
                    title = "TouchSDK BLE",
                    subtitle = detail,
                    checked = settings.touchSdkBleEnabled,
                    onCheckedChange = onTouchSdkChange,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_advanced", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Curves, Spatial & Security",
                    subtitle = "Accel curve, roll dial, OS, auto-lock",
                    icon = Icons.Rounded.Tune,
                    onClick = onOpenAdvanced,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_tips", contentType = TYPE_BUTTON) {
                SettingsTonalActionRow(
                    title = "Placement & Tips",
                    icon = Icons.Rounded.Info,
                    onClick = onOpenTips,
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_reset_defaults", contentType = TYPE_BUTTON) {
                SettingsAccentActionRow(
                    title = when {
                        resetDoneNotice -> "Defaults Restored"
                        confirmResetArmed -> "Tap Again to Reset"
                        else -> "Reset to Defaults"
                    },
                    subtitle = when {
                        resetDoneNotice -> "All settings & theme reset"
                        confirmResetArmed -> "Restores tuning, gestures & green theme"
                        else -> "Restore default tuning & theme"
                    },
                    icon = Icons.Rounded.RestartAlt,
                    containerColor = if (confirmResetArmed) {
                        GestureColors.AmberWarning.copy(alpha = 0.28f)
                    } else {
                        GestureColors.SurfaceCard
                    },
                    contentColor = if (resetDoneNotice) {
                        accent.glow
                    } else {
                        GestureColors.AmberWarning
                    },
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

            if (uiState.isConnected) {
                item(key = "btn_disconnect", contentType = TYPE_BUTTON) {
                    SettingsAccentActionRow(
                        title = "Disconnect",
                        icon = Icons.Rounded.BluetoothDisabled,
                        containerColor = GestureColors.CoralError.copy(alpha = 0.22f),
                        contentColor = GestureColors.CoralError,
                        onClick = onDisconnect,
                        transformationSpec = transformationSpec
                    )
                }
            }
        }
    }
}

internal fun formatOneDecimal(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    val whole = tenths / 10
    val frac = kotlin.math.abs(tenths % 10)
    return "$whole.$frac"
}

internal fun formatTwoDecimals(value: Float): String {
    val hundredths = (value * 100f).roundToInt()
    val whole = hundredths / 100
    val frac = kotlin.math.abs(hundredths % 100)
    return if (frac < 10) "$whole.0$frac" else "$whole.$frac"
}
