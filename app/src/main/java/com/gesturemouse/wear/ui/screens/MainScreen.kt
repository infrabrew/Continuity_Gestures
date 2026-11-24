package com.gesturemouse.wear.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.gesturemouse.wear.ScreenBrightnessMode
import com.gesturemouse.wear.model.ConnectionState
import com.gesturemouse.wear.model.CrownAction
import com.gesturemouse.wear.model.InputMode
import com.gesturemouse.wear.model.TransportMode
import com.gesturemouse.wear.ui.GestureMouseUiState
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent
import kotlinx.coroutines.delay

/**
 * Primary circular Wear OS controller screen for **Continuity Gestures**, crafted with a
 * clean **Material 3 Expressive** neural aesthetic:
 * - Dynamic 5-color neon theme ([LocalGestureAccent]) driving the circular bezel arc, soft
 *   radial aura, custom expressive mouse icon, and active action surfaces.
 * - **Circular-safe ergonomic arc layout**: keeps the Mouse hub centered and the middle
 *   Mode/Pair pill anchored at the bottom center, while shifting **Play/Pause** (left) and
 *   **Settings** (right) upward into the wide circular chord so buttons never clip off curved
 *   watch edges.
 * - **Clean tactile spring animations**: press-scale + subtle squircle corner morph and
 *   crossfade icon transitions without jarring sideways layout shifts.
 * - **Unified paused/active Mouse icon**: centered silhouette matching the color harmony and
 *   Material 3 Rounded styling of all other mode icons in every state.
 */
@Composable
fun MainScreen(
    uiState: GestureMouseUiState,
    onMakeDiscoverable: () -> Unit,
    onReconnectLast: () -> Unit,
    onDisconnect: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleScrollClutch: () -> Unit,
    onCycleMode: () -> Unit,
    onManualClick: () -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onMicroTrackpadDrag: (Float, Float) -> Unit,
    onTeleportToMonitor: (Int) -> Unit,
    onCrownScroll: (Float) -> Unit,
    onTriggerHostMacro: (String) -> Unit,
    onOpenQuickSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onBrightnessModeChanged: (ScreenBrightnessMode) -> Unit
) {
    val ambientModeManager = LocalAmbientModeManager.current
    val ambientMode = ambientModeManager?.currentAmbientMode

    // Render burn-in-safe OLED ambient UI when the watch enters ambient/always-on mode
    if (ambientMode is AmbientMode.Ambient) {
        SideEffect {
            onBrightnessModeChanged(ScreenBrightnessMode.NORMAL)
        }
        AmbientMainScreen(uiState = uiState)
        return
    }

    val accent = LocalGestureAccent.current
    val pulseProgress = remember { Animatable(0f) }
    var rotaryAccum by remember { mutableFloatStateOf(0f) }
    var isDimVeilEngaged by remember { mutableStateOf(false) }
    var isStealthEngaged by remember { mutableStateOf(false) }
    var backGuardWarningVisible by remember { mutableStateOf(false) }

    val isTransportActive = uiState.hasActiveTransport && !uiState.isPaused

    // Guard against Wear OS native Wrist Turn (system Back/Dismiss) accidentally exiting while pointing
    BackHandler(
        enabled = uiState.settings.nativeGestureGuard &&
            isTransportActive &&
            !backGuardWarningVisible
    ) {
        backGuardWarningVisible = true
    }

    LaunchedEffect(backGuardWarningVisible) {
        if (backGuardWarningVisible) {
            delay(2_200L)
            backGuardWarningVisible = false
        }
    }

    // Trigger ring pulse animation on every detected gesture (skipped in 1-Nit Stealth to stay at 1 Hz)
    LaunchedEffect(uiState.gesturePulseSeq, isStealthEngaged) {
        if (uiState.gesturePulseSeq > 0 && !isStealthEngaged) {
            pulseProgress.snapTo(0f)
            pulseProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
            )
        }
    }

    // Auto-engage 1-Nit Stealth Trackpad (3.5 s) or standard OLED Dim Veil (5 s) while actively pointing
    LaunchedEffect(
        uiState.settings.stealthTrackpadEnabled,
        uiState.settings.dimScreenWhileActive,
        isTransportActive,
        isStealthEngaged,
        isDimVeilEngaged
    ) {
        if (!isTransportActive) {
            isStealthEngaged = false
            isDimVeilEngaged = false
            return@LaunchedEffect
        }

        if (uiState.settings.stealthTrackpadEnabled) {
            isDimVeilEngaged = false
            if (!isStealthEngaged) {
                delay(3_500L)
                isStealthEngaged = true
            }
        } else if (uiState.settings.dimScreenWhileActive) {
            isStealthEngaged = false
            if (!isDimVeilEngaged) {
                delay(5_000L)
                isDimVeilEngaged = true
            }
        } else {
            isStealthEngaged = false
            isDimVeilEngaged = false
        }
    }

    val targetBrightnessMode = when {
        isStealthEngaged -> ScreenBrightnessMode.STEALTH_1NIT
        isDimVeilEngaged -> ScreenBrightnessMode.DIM_ACTIVE
        else -> ScreenBrightnessMode.NORMAL
    }

    SideEffect {
        onBrightnessModeChanged(targetBrightnessMode)
    }

    DisposableEffect(Unit) {
        onDispose { onBrightnessModeChanged(ScreenBrightnessMode.NORMAL) }
    }

    val ringColorState = animateColorAsState(
        targetValue = when {
            !uiState.hasBluetoothPermissions && uiState.settings.transportMode != TransportMode.WIFI_UDP_WS ->
                GestureColors.AmberWarning
            uiState.isOffBody -> GestureColors.AmberWarning
            uiState.isSpatialModifierActive -> GestureColors.CyanPairing
            uiState.isClutchFrozen -> GestureColors.AmberWarning
            uiState.isPrecisionModeActive -> GestureColors.CyanPairing
            uiState.hasActiveTransport && uiState.isPaused -> GestureColors.AmberWarning
            uiState.hasActiveTransport && uiState.isScrollClutchActive -> GestureColors.CyanPairing
            uiState.hasActiveTransport -> accent.active
            uiState.connectionState is ConnectionState.Discoverable ||
                uiState.connectionState is ConnectionState.Connecting -> GestureColors.CyanPairing
            uiState.connectionState is ConnectionState.Error -> GestureColors.CoralError
            else -> accent.active.copy(alpha = 0.58f)
        },
        animationSpec = tween(280),
        label = "ringColor"
    )
    val ringColor = ringColorState.value

    val hubInteraction = remember { MutableInteractionSource() }
    val hubPressed by hubInteraction.collectIsPressedAsState()
    val hubScale by animateFloatAsState(
        targetValue = if (hubPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "hubScale"
    )
    val hubCornerRadius by animateDpAsState(
        targetValue = when {
            hubPressed -> 18.dp
            isTransportActive -> 23.dp
            else -> 30.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "hubCornerRadius"
    )

    val isPairing = !isStealthEngaged && (
        uiState.connectionState is ConnectionState.Discoverable ||
            uiState.connectionState is ConnectionState.Connecting
        )
    val isBreathingActive = !isStealthEngaged && isTransportActive

    val sweepAngleState = rememberPairingSweepState(enabled = isPairing)
    val breathAlphaState = rememberBreathAlphaState(enabled = isBreathingActive)

    val microTrackpadModifier = if (isTransportActive && uiState.settings.hybridMicroTrackpadEnabled) {
        Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onMicroTrackpadDrag(dragAmount.x, dragAmount.y)
            }
        }
    } else {
        Modifier
    }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GestureColors.BackgroundBlack)
                .requestFocusOnHierarchyActive()
                .onRotaryScrollEvent { event ->
                    if (isTransportActive && uiState.settings.crownAction != CrownAction.OPEN_SETTINGS) {
                        onCrownScroll(event.verticalScrollPixels)
                    } else {
                        rotaryAccum += event.verticalScrollPixels
                        if (kotlin.math.abs(rotaryAccum) > 28f) {
                            rotaryAccum = 0f
                            onOpenQuickSettings()
                        }
                    }
                    true
                }
                .focusable()
                .then(microTrackpadModifier),
            contentAlignment = Alignment.Center
        ) {
            if (isStealthEngaged) {
                StealthTrackpadOverlay(
                    microTrackpadEnabled = uiState.settings.hybridMicroTrackpadEnabled,
                    onLeftClick = onLeftClick,
                    onRightClick = onRightClick,
                    onMicroTrackpadDrag = onMicroTrackpadDrag,
                    onWake = { isStealthEngaged = false },
                    onOpenQuickSettings = {
                        isStealthEngaged = false
                        onOpenQuickSettings()
                    }
                )
            } else {
                // Neural Expressive outer bezel ring + soft ambient radial aura
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val strokePx = 4.dp.toPx()
                            val inset = strokePx / 2f + 2.dp.toPx()
                            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                            val topLeft = Offset(inset, inset)
                            val baseStroke = Stroke(width = strokePx)
                            val roundStroke = Stroke(width = strokePx, cap = StrokeCap.Round)
                            val maxHaloStrokePx = 3.5.dp.toPx()
                            val minDimPx = size.minDimension
                            val auraCenter = Offset(size.width / 2f, size.height * 0.46f)

                            onDrawBehind {
                                val activeRingColor = ringColorState.value
                                val activeAlpha = breathAlphaState?.value ?: 0.72f

                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            activeRingColor.copy(alpha = if (isTransportActive) 0.16f * activeAlpha else 0.08f),
                                            Color.Transparent
                                        ),
                                        center = auraCenter,
                                        radius = minDimPx * 0.44f
                                    ),
                                    radius = minDimPx * 0.44f,
                                    center = auraCenter
                                )

                                drawArc(
                                    color = activeRingColor.copy(alpha = 0.16f),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = baseStroke
                                )

                                if (isPairing) {
                                    drawArc(
                                        color = activeRingColor,
                                        startAngle = sweepAngleState?.value ?: 0f,
                                        sweepAngle = 115f,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = roundStroke
                                    )
                                } else {
                                    drawArc(
                                        color = activeRingColor.copy(alpha = activeAlpha),
                                        startAngle = -90f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = baseStroke
                                    )
                                }

                                val p = pulseProgress.value
                                if (p > 0f && p < 1f) {
                                    val radius = (minDimPx * 0.21f) + (minDimPx * 0.25f * p)
                                    drawCircle(
                                        color = activeRingColor.copy(alpha = (1f - p) * 0.65f),
                                        radius = radius,
                                        center = auraCenter,
                                        style = Stroke(width = (maxHaloStrokePx * (1f - p)).coerceAtLeast(1f))
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val minDim = minOf(maxWidth, maxHeight)
                    val showMonitorPills = isTransportActive && uiState.settings.monitorCount > 1
                    val hubSize = (minDim * 0.31f).coerceIn(60.dp, 72.dp)
                    val sideButtonSize = (minDim * 0.21f).coerceIn(42.dp, 48.dp)
                    val sideOffsetX = minDim * 0.305f
                    val sideOffsetY = minDim * 0.095f

                    // 1. Top clean neural status pill + concise subtitle
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = minDim * 0.082f,
                                start = 28.dp,
                                end = 28.dp
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(GestureColors.SurfaceCard.copy(alpha = 0.88f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ringColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = when {
                                    !uiState.hasBluetoothPermissions &&
                                        uiState.settings.transportMode != TransportMode.WIFI_UDP_WS ->
                                        "Permissions"
                                    uiState.isOffBody && uiState.isPaused -> "Off-Body"
                                    uiState.isSpatialModifierActive -> "Spatial Dial"
                                    uiState.isClutchFrozen -> "Frozen"
                                    uiState.isPrecisionModeActive -> "Precision"
                                    uiState.hasActiveTransport && uiState.isPaused -> "Paused"
                                    uiState.settings.transportMode == TransportMode.WIFI_UDP_WS ->
                                        if (uiState.isWifiWsConnected) "5 GHz Wi-Fi" else "Wi-Fi UDP"
                                    else -> uiState.connectionState.statusText
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GestureColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uiState.isLowBattery) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.BatteryAlert,
                                    contentDescription = "Low battery ${uiState.batteryPercent}%",
                                    tint = GestureColors.AmberWarning,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "${uiState.batteryPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GestureColors.AmberWarning,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        val subtitleText = when {
                            backGuardWarningVisible -> "Back again to exit"
                            uiState.statusBanner != null -> uiState.statusBanner
                            uiState.isSpatialModifierActive -> "Roll dial or flick"
                            uiState.isClutchFrozen -> "Release button to aim"
                            uiState.isPrecisionModeActive -> "Micro-aim 0.3x"
                            uiState.hasActiveTransport && uiState.isScrollClutchActive ->
                                "Scroll Clutch • 2x off"
                            uiState.activeAppProfile != null && uiState.settings.autoAppProfilesEnabled ->
                                "${uiState.activeAppProfile.appName} • ${uiState.activeMode.shortName}"
                            uiState.settings.transportMode == TransportMode.DUAL_BT_AND_WIFI ->
                                "${uiState.activeMode.shortName} • BT+Wi-Fi"
                            uiState.settings.touchSdkBleEnabled && uiState.touchSdkSubscriberCount > 0 ->
                                "${uiState.activeMode.shortName} • BLE (${uiState.touchSdkSubscriberCount})"
                            !uiState.connectionState.isConnected &&
                                uiState.settings.transportMode == TransportMode.BLUETOOTH_HID &&
                                uiState.settings.lastConnectedName.isNotBlank() ->
                                uiState.settings.lastConnectedName
                            else -> uiState.activeMode.displayName
                        }

                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                backGuardWarningVisible || uiState.isClutchFrozen || uiState.isOffBody ->
                                    GestureColors.AmberWarning
                                uiState.statusBanner != null -> accent.glow
                                uiState.isSpatialModifierActive ||
                                    uiState.isScrollClutchActive ||
                                    uiState.isPrecisionModeActive -> GestureColors.CyanPairing
                                else -> GestureColors.TextSecondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 2. Center Material 3 Expressive Neural Mouse Hub (stays cleanly centered)
                    val hubBg by animateColorAsState(
                        targetValue = when {
                            uiState.isSpatialModifierActive ||
                                (uiState.hasActiveTransport &&
                                    (uiState.isScrollClutchActive || uiState.isPrecisionModeActive)) ->
                                GestureColors.CyanContainer
                            uiState.isClutchFrozen || uiState.isOffBody ||
                                (uiState.hasActiveTransport && uiState.isPaused) ->
                                GestureColors.AmberWarning.copy(alpha = 0.16f)
                            isTransportActive -> accent.container
                            else -> GestureColors.SurfaceCard
                        },
                        animationSpec = tween(260),
                        label = "hubBg"
                    )
                    val hubShape = RoundedCornerShape(hubCornerRadius)

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = -minDim * 0.04f)
                            .size(hubSize)
                            .graphicsLayer {
                                scaleX = hubScale
                                scaleY = hubScale
                            }
                            .clip(hubShape)
                            .background(hubBg)
                            .border(
                                width = 1.5.dp,
                                color = ringColor.copy(alpha = if (isTransportActive) 0.72f else 0.42f),
                                shape = hubShape
                            )
                            .combinedClickable(
                                interactionSource = hubInteraction,
                                indication = null,
                                onClick = {
                                    when {
                                        !uiState.hasBluetoothPermissions &&
                                            uiState.settings.transportMode != TransportMode.WIFI_UDP_WS ->
                                            onRequestPermissions()
                                        uiState.hasActiveTransport && uiState.isPaused -> onTogglePause()
                                        uiState.hasActiveTransport -> onManualClick()
                                        else -> onMakeDiscoverable()
                                    }
                                },
                                onDoubleClick = {
                                    if (isTransportActive && uiState.activeMode == InputMode.MOUSE) {
                                        onToggleScrollClutch()
                                    }
                                },
                                onLongClick = onOpenQuickSettings
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.activeMode == InputMode.MOUSE && !uiState.isScrollClutchActive) {
                            NeuralExpressiveMouseIcon(
                                tint = ringColor,
                                isActive = isTransportActive,
                                isPaused = uiState.hasActiveTransport && uiState.isPaused,
                                pulseAmount = pulseProgress.value,
                                modifier = Modifier.size(hubSize * 0.56f)
                            )
                        } else {
                            Icon(
                                imageVector = if (uiState.isScrollClutchActive) {
                                    Icons.Rounded.SwapVert
                                } else {
                                    iconForMode(uiState.activeMode)
                                },
                                contentDescription = if (uiState.isScrollClutchActive) {
                                    "Scroll Clutch active"
                                } else {
                                    uiState.activeMode.displayName
                                },
                                tint = ringColor,
                                modifier = Modifier.size(hubSize * 0.52f)
                            )
                        }
                    }

                    // 3. Optional Multi-Monitor Teleport micro-pills neatly between hub & middle pill
                    if (showMonitorPills) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = minDim * 0.17f)
                        ) {
                            for (monitor in 1..uiState.settings.monitorCount.coerceIn(1, 3)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(GestureColors.SurfaceElevated)
                                        .border(
                                            width = 1.dp,
                                            color = accent.active.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(50)
                                        )
                                        .clickable { onTeleportToMonitor(monitor) }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "D$monitor",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent.glow,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // 4. Shifted-UP Left Button: Play/Pause (when connected) or Reconnect (when disconnected)
                    val permissionsReady = uiState.hasBluetoothPermissions ||
                        uiState.settings.transportMode == TransportMode.WIFI_UDP_WS

                    if (permissionsReady) {
                        if (uiState.hasActiveTransport) {
                            val playPauseContainer by animateColorAsState(
                                targetValue = if (uiState.isPaused) accent.active else GestureColors.SurfaceElevated,
                                animationSpec = tween(220),
                                label = "playPauseBg"
                            )
                            val playPauseIconColor by animateColorAsState(
                                targetValue = if (uiState.isPaused) accent.onPrimary else accent.glow,
                                animationSpec = tween(220),
                                label = "playPauseFg"
                            )
                            val playPauseBorder by animateColorAsState(
                                targetValue = if (uiState.isPaused) {
                                    accent.glow.copy(alpha = 0.85f)
                                } else {
                                    accent.active.copy(alpha = 0.35f)
                                },
                                animationSpec = tween(220),
                                label = "playPauseBorder"
                            )

                            ExpressiveCircleActionButton(
                                size = sideButtonSize,
                                containerColor = playPauseContainer,
                                borderColor = playPauseBorder,
                                contentDescription = if (uiState.isPaused) "Resume" else "Pause",
                                onClick = onTogglePause,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = -sideOffsetX, y = sideOffsetY)
                            ) {
                                AnimatedContent(
                                    targetState = uiState.isPaused,
                                    transitionSpec = {
                                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.78f, animationSpec = tween(180)))
                                            .togetherWith(
                                                fadeOut(tween(130)) + scaleOut(targetScale = 0.78f, animationSpec = tween(130))
                                            )
                                    },
                                    label = "playPauseIcon"
                                ) { paused ->
                                    Icon(
                                        imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        contentDescription = null,
                                        tint = playPauseIconColor,
                                        modifier = Modifier.size(if (paused) 25.dp else 23.dp)
                                    )
                                }
                            }
                        } else if (uiState.settings.lastConnectedAddress.isNotBlank()) {
                            ExpressiveCircleActionButton(
                                size = sideButtonSize,
                                containerColor = accent.container,
                                borderColor = accent.active.copy(alpha = 0.45f),
                                contentDescription = "Reconnect last device",
                                onClick = onReconnectLast,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = -sideOffsetX, y = sideOffsetY)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = accent.glow,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // 5. Shifted-UP Right Button: Settings (long-press disconnects when connected)
                        val settingsInteraction = remember { MutableInteractionSource() }
                        val settingsPressed by settingsInteraction.collectIsPressedAsState()
                        val gearRotation by animateFloatAsState(
                            targetValue = if (settingsPressed) 28f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "gearRotation"
                        )

                        ExpressiveCircleActionButton(
                            size = sideButtonSize,
                            containerColor = GestureColors.SurfaceElevated,
                            borderColor = GestureColors.TextSecondary.copy(alpha = 0.28f),
                            contentDescription = "Quick Settings",
                            interactionSource = settingsInteraction,
                            onClick = onOpenQuickSettings,
                            onLongClick = if (uiState.hasActiveTransport) onDisconnect else null,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = sideOffsetX, y = sideOffsetY)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = GestureColors.TextPrimary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { rotationZ = gearRotation }
                            )
                        }
                    }

                    // 6. Centered Bottom Middle Button (Mode/Macro/Pair/Grant BT) OR Live Gesture Pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = minDim * 0.082f)
                            .height(38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedVisibility(
                            visible = uiState.lastGesture != null,
                            enter = fadeIn(tween(130)) + scaleIn(initialScale = 0.90f, animationSpec = tween(150)),
                            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180))
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(accent.container)
                                    .border(1.dp, accent.active.copy(alpha = 0.65f), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = uiState.lastGesture?.displayLabel().orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accent.glow,
                                    maxLines = 1
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = uiState.lastGesture == null,
                            enter = fadeIn(tween(160)),
                            exit = fadeOut(tween(100))
                        ) {
                            when {
                                !permissionsReady -> {
                                    ExpressiveMiddlePillButton(
                                        label = "Grant BT",
                                        containerColor = GestureColors.AmberWarning,
                                        contentColor = Color.Black,
                                        borderColor = GestureColors.AmberWarning,
                                        onClick = onRequestPermissions
                                    )
                                }

                                uiState.hasActiveTransport -> {
                                    val primaryMacro = uiState.syncedMacros.firstOrNull()
                                    ExpressiveMiddlePillButton(
                                        label = primaryMacro?.label ?: uiState.activeMode.shortName,
                                        containerColor = if (primaryMacro != null) {
                                            GestureColors.CyanContainer
                                        } else {
                                            accent.container
                                        },
                                        contentColor = if (primaryMacro != null) {
                                            GestureColors.CyanPairing
                                        } else {
                                            accent.glow
                                        },
                                        borderColor = if (primaryMacro != null) {
                                            GestureColors.CyanPairing.copy(alpha = 0.45f)
                                        } else {
                                            accent.active.copy(alpha = 0.45f)
                                        },
                                        onClick = {
                                            if (primaryMacro != null) {
                                                onTriggerHostMacro(primaryMacro.id)
                                            } else {
                                                onCycleMode()
                                            }
                                        }
                                    )
                                }

                                else -> {
                                    val isDiscoverable = uiState.connectionState is ConnectionState.Discoverable
                                    ExpressiveMiddlePillButton(
                                        label = if (isDiscoverable) "Pairing" else "Pair",
                                        leadingIcon = if (isDiscoverable) {
                                            Icons.AutoMirrored.Rounded.BluetoothSearching
                                        } else {
                                            Icons.Rounded.Bluetooth
                                        },
                                        containerColor = if (isDiscoverable) {
                                            GestureColors.CyanPairing
                                        } else {
                                            accent.active
                                        },
                                        contentColor = if (isDiscoverable) Color.Black else accent.onPrimary,
                                        borderColor = Color.Transparent,
                                        onClick = onMakeDiscoverable
                                    )
                                }
                            }
                        }
                    }

                    // Standard OLED-saving dim overlay (when Dim While Active is enabled without Stealth Trackpad)
                    if (isDimVeilEngaged) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.85f))
                                .combinedClickable(
                                    onClick = { isDimVeilEngaged = false },
                                    onLongClick = {
                                        isDimVeilEngaged = false
                                        onOpenQuickSettings()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${uiState.activeMode.shortName} Active\nTap to wake",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent.active.copy(alpha = 0.60f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean Material 3 Expressive circular action button with tactile spring scale & subtle
 * squircle corner-morph animation on press — zero horizontal jitter or layout shifting.
 */
@Composable
private fun ExpressiveCircleActionButton(
    size: Dp,
    containerColor: Color,
    borderColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.89f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "circleBtnScale"
    )
    val cornerPercent by animateIntAsState(
        targetValue = if (pressed) 32 else 50,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cornerMorph"
    )
    val shape = RoundedCornerShape(percent = cornerPercent)

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Clean centered bottom Material 3 Expressive pill button with smooth spring press animation
 * and crossfading label text.
 */
@Composable
private fun ExpressiveMiddlePillButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pillScale"
    )
    val animatedBg by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(220),
        label = "pillBg"
    )
    val animatedFg by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(220),
        label = "pillFg"
    )
    val shape = RoundedCornerShape(50)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 78.dp, max = 108.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(animatedBg)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = animatedFg,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(150)).togetherWith(fadeOut(tween(110))) },
            label = "pillLabel"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = animatedFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Unified Material 3 Expressive Continuity Mouse icon drawn via Compose [Canvas]:
 * - Keeps the ergonomic mouse silhouette cleanly centered in ALL states (active, paused, idle,
 *   and Always-On Display ambient mode) with unified [tint] color harmony.
 * - Shows smooth continuity air-wave arcs when [isActive] is true.
 * - Shows crisp Material rounded Pause bars inside the lower mouse palm when [isPaused] is true.
 * - Supports burn-in-safe hollow strokes when [ambientHollow] is enabled for Always-On Display.
 */
@Composable
private fun NeuralExpressiveMouseIcon(
    tint: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    pulseAmount: Float = 0f,
    ambientHollow: Boolean = false
) {
    val waveAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(if (ambientHollow) 0 else 260),
        label = "mouseWaveAlpha"
    )
    val pauseAlpha by animateFloatAsState(
        targetValue = if (isPaused) 1f else 0f,
        animationSpec = tween(if (ambientHollow) 0 else 240),
        label = "mousePauseAlpha"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = if (ambientHollow) {
            1.75.dp.toPx()
        } else {
            (w * 0.072f).coerceAtLeast(2.dp.toPx())
        }

        // Smoothly fading continuity signal arcs above the mouse nose (zero layout jump on pause)
        if (waveAlpha > 0.01f) {
            val pulseBoost = if (ambientHollow) {
                0.85f
            } else {
                (0.78f + 0.22f * (1f - pulseAmount)).coerceIn(0f, 1f)
            }
            val innerArcSize = Size(w * 0.38f, h * 0.18f)
            drawArc(
                color = tint.copy(alpha = 0.88f * waveAlpha * pulseBoost),
                startAngle = -145f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset((w - innerArcSize.width) / 2f, h * 0.08f),
                size = innerArcSize,
                style = Stroke(width = strokePx * 0.78f, cap = StrokeCap.Round)
            )
            val outerArcSize = Size(w * 0.58f, h * 0.22f)
            drawArc(
                color = tint.copy(alpha = 0.48f * waveAlpha * pulseBoost),
                startAngle = -140f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset((w - outerArcSize.width) / 2f, h * 0.02f),
                size = outerArcSize,
                style = Stroke(width = strokePx * 0.66f, cap = StrokeCap.Round)
            )
        }

        // Fixed centered Material 3 ergonomic mouse silhouette (consistent across active, paused & ambient)
        val bodyWidth = w * 0.54f
        val bodyHeight = h * 0.68f
        val bodyLeft = (w - bodyWidth) / 2f
        val bodyTop = h * 0.24f
        val cornerR = bodyWidth * 0.48f

        // Subtle tonal fill in interactive mode (skipped in ambientHollow mode for OLED burn-in safety)
        if (!ambientHollow) {
            drawRoundRect(
                color = tint.copy(alpha = 0.18f),
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(cornerR, cornerR)
            )
        }

        // Bold crisp rounded silhouette stroke matching Material 3 Rounded icon weight
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(cornerR, cornerR),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Subtle button divider seam
        val seamY = bodyTop + bodyHeight * 0.41f
        drawLine(
            color = tint.copy(alpha = if (ambientHollow) 0.42f else 0.48f),
            start = Offset(bodyLeft + strokePx * 0.7f, seamY),
            end = Offset(bodyLeft + bodyWidth - strokePx * 0.7f, seamY),
            strokeWidth = strokePx * 0.56f,
            cap = StrokeCap.Round
        )

        // Centered tactile scroll wheel capsule (hollow pill in ambient mode to stay burn-in-safe)
        val wheelW = bodyWidth * 0.21f
        val wheelH = bodyHeight * 0.23f
        val wheelLeft = (w - wheelW) / 2f
        val wheelTop = bodyTop + bodyHeight * 0.13f
        if (ambientHollow) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(wheelLeft, wheelTop),
                size = Size(wheelW, wheelH),
                cornerRadius = CornerRadius(wheelW / 2f, wheelW / 2f),
                style = Stroke(width = strokePx * 0.78f, cap = StrokeCap.Round)
            )
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(wheelLeft, wheelTop),
                size = Size(wheelW, wheelH),
                cornerRadius = CornerRadius(wheelW / 2f, wheelW / 2f)
            )
        }

        // Crisp Material rounded twin Pause bars in the lower mouse palm when paused
        if (pauseAlpha > 0.01f) {
            val barTop = bodyTop + bodyHeight * 0.57f
            val barBottom = bodyTop + bodyHeight * 0.81f
            val barOffset = bodyWidth * 0.135f
            val barStroke = if (ambientHollow) strokePx * 0.95f else strokePx * 0.92f
            val barColor = tint.copy(alpha = pauseAlpha)
            drawLine(
                color = barColor,
                start = Offset(w / 2f - barOffset, barTop),
                end = Offset(w / 2f - barOffset, barBottom),
                strokeWidth = barStroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = barColor,
                start = Offset(w / 2f + barOffset, barTop),
                end = Offset(w / 2f + barOffset, barBottom),
                strokeWidth = barStroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 1-Nit Stealth Trackpad Mode:
 * Pure `#000000` static canvas (allowing LTPO displays to drop to 1 Hz + ~1 nit brightness)
 * split into large invisible Left-Click and Right-Click tap zones, with simultaneous
 * hybrid micro-trackpad finger-slide cursor nudging.
 */
@Composable
private fun StealthTrackpadOverlay(
    microTrackpadEnabled: Boolean,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onMicroTrackpadDrag: (Float, Float) -> Unit,
    onWake: () -> Unit,
    onOpenQuickSettings: () -> Unit
) {
    val dragMod = if (microTrackpadEnabled) {
        Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onMicroTrackpadDrag(dragAmount.x, dragAmount.y)
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(dragMod)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        onClick = onLeftClick,
                        onDoubleClick = onWake,
                        onLongClick = onOpenQuickSettings
                    )
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 44.dp)
                    .background(Color.White.copy(alpha = 0.10f))
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        onClick = onRightClick,
                        onDoubleClick = onWake,
                        onLongClick = onOpenQuickSettings
                    )
            )
        }

        Text(
            text = if (microTrackpadEnabled) "L | R • slide • 2x wake" else "L | R • 2x wake",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.16f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        )
    }
}

/**
 * Burn-in-safe Wear OS Ambient Mode presentation: pure black background, thin hollow ring,
 * monochrome status label, zero continuous animations, and subtle pixel shift on ambient ticks.
 */
@Composable
private fun AmbientMainScreen(uiState: GestureMouseUiState) {
    val ambientModeManager = LocalAmbientModeManager.current
    var shiftStep by remember { mutableIntStateOf(0) }

    ambientModeManager?.AmbientTickEffect {
        shiftStep = (shiftStep + 1) % 4
    }

    val burnInOffset = when (shiftStep) {
        0 -> IntOffset(0, 0)
        1 -> IntOffset(2, 0)
        2 -> IntOffset(2, 2)
        else -> IntOffset(0, 2)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawWithCache {
                val stroke = Stroke(width = 1.5.dp.toPx())
                val inset = 4.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)
                onDrawBehind {
                    drawArc(
                        color = Color(0xFF7A8580),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .offset { burnInOffset }
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isAmbientPaused = uiState.hasActiveTransport && uiState.isPaused
            val isAmbientActive = uiState.hasActiveTransport && !uiState.isPaused
            val ambientIconTint = if (isAmbientPaused) Color(0xFFD0C2A8) else Color(0xFFB8C2BE)

            if (uiState.activeMode == InputMode.MOUSE) {
                NeuralExpressiveMouseIcon(
                    tint = ambientIconTint,
                    isActive = isAmbientActive,
                    isPaused = isAmbientPaused,
                    ambientHollow = true,
                    modifier = Modifier.size(44.dp)
                )
            } else {
                Icon(
                    imageVector = iconForMode(uiState.activeMode),
                    contentDescription = uiState.activeMode.displayName,
                    tint = ambientIconTint,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isAmbientPaused -> "Paused"
                    uiState.hasActiveTransport -> uiState.activeMode.displayName
                    else -> "Disconnected"
                },
                style = MaterialTheme.typography.labelMedium,
                color = ambientIconTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (uiState.connectionState is ConnectionState.Connected) {
                Text(
                    text = uiState.connectionState.deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A8580),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun rememberPairingSweepState(enabled: Boolean): State<Float>? {
    if (!enabled) return null
    val transition = rememberInfiniteTransition(label = "pairingSweepTransition")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pairingSweep"
    )
}

@Composable
private fun rememberBreathAlphaState(enabled: Boolean): State<Float>? {
    if (!enabled) return null
    val transition = rememberInfiniteTransition(label = "breathAlphaTransition")
    return transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )
}

private fun iconForMode(mode: InputMode): ImageVector = when (mode) {
    InputMode.MOUSE -> Icons.Rounded.Mouse
    InputMode.ARROW_KEYS -> Icons.Rounded.OpenWith
    InputMode.MEDIA_CONTROLS -> Icons.Rounded.MusicNote
    InputMode.PRESENTER -> Icons.Rounded.Slideshow
}
