package com.gesturemouse.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PanToolAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.gesturemouse.wear.model.GestureEvent
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent

/**
 * First-run 4-step interactive onboarding flow teaching strap placement, arm cursor aiming,
 * live thumb-index pinch confirmation, and Bluetooth HID pairing.
 * Supports rotating the watch crown wheel to scroll forward/backward through pages.
 */
@Composable
fun OnboardingScreen(
    lastGesture: GestureEvent?,
    onSetSensorPreview: (Boolean) -> Unit,
    onFinish: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var pinchConfirmed by remember { mutableStateOf(false) }
    var crownAccum by remember { mutableFloatStateOf(0f) }
    val accent = LocalGestureAccent.current

    // Enable live sensor preview on the interactive "Try a Pinch" step (page 2)
    DisposableEffect(page) {
        val preview = (page == 2)
        onSetSensorPreview(preview)
        onDispose {
            if (preview) onSetSensorPreview(false)
        }
    }

    LaunchedEffect(lastGesture, page) {
        if (page == 2 && (lastGesture is GestureEvent.SinglePinch ||
                lastGesture is GestureEvent.DoublePinch ||
                lastGesture is GestureEvent.PalmUpPinch)
        ) {
            pinchConfirmed = true
        }
    }

    val icon: ImageVector
    val title: String
    val body: String
    val ctaText: String

    when (page) {
        0 -> {
            icon = Icons.Rounded.Watch
            title = "Snug Watch Fit"
            body = "Wear your watch snugly 1–2 cm above the wrist bone so tendon snaps register cleanly."
            ctaText = "Next"
        }
        1 -> {
            icon = Icons.Rounded.PanToolAlt
            title = "Aim with Your Arm"
            body = "Move your forearm naturally in the air to glide the pointer. Slide on the glass for micro-aim."
            ctaText = "Next"
        }
        2 -> {
            icon = if (pinchConfirmed) Icons.Rounded.CheckCircle else Icons.Rounded.TouchApp
            title = if (pinchConfirmed) "Pinch Detected!" else "Try a Pinch"
            body = if (pinchConfirmed) {
                "Nice! Single pinch clicks, double pinch right-clicks, and hold drags."
            } else {
                "Tap your thumb and index finger together now to test click detection."
            }
            ctaText = if (pinchConfirmed) "Next" else "Skip"
        }
        else -> {
            icon = Icons.Rounded.Bluetooth
            title = "Pair Any Device"
            body = "Tap Pair on the main screen, then select Continuity Gestures in your PC, Mac, phone, or TV Bluetooth settings."
            ctaText = "Start"
        }
    }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GestureColors.BackgroundBlack)
                .requestFocusOnHierarchyActive()
                .onRotaryScrollEvent { event ->
                    crownAccum += event.verticalScrollPixels
                    if (crownAccum > 32f) {
                        crownAccum = 0f
                        if (page < 3) page++
                    } else if (crownAccum < -32f) {
                        crownAccum = 0f
                        if (page > 0) page--
                    }
                    true
                }
                .focusable()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Page progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    repeat(4) { idx ->
                        Box(
                            modifier = Modifier
                                .size(if (idx == page) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == page) accent.active
                                    else GestureColors.SlateIdle.copy(alpha = 0.45f)
                                )
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (page == 2 && pinchConfirmed) {
                            accent.active
                        } else {
                            accent.glow
                        },
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = GestureColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = GestureColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = {
                        if (page < 3) {
                            page++
                        } else {
                            onFinish()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(38.dp),
                    label = { Text(ctaText) }
                )
            }
        }
    }
}
