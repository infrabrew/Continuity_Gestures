package com.gesturemouse.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.gesturemouse.wear.ui.components.SettingsHeaderRow
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent

private data class TipItem(
    val title: String,
    val detail: String
)

private val TIPS = listOf(
    TipItem(
        title = "Velocity Acceleration Curves",
        detail = "Pick Balanced S-Curve, Precision Plateau, or Dynamic Expo in Advanced settings for pixel-accurate micro-aim with effortless fast edge-to-edge flicks."
    ),
    TipItem(
        title = "Hybrid Micro-Trackpad",
        detail = "Coarse-aim with your wrist, then slide a finger directly on the watch glass to nudge the last 10 pixels with air-gyro tremor damping."
    ),
    TipItem(
        title = "Multi-Monitor Teleport",
        detail = "Set Monitors to 2 or 3 in Advanced settings, then tap D1 / D2 / D3 on the main screen to warp straight to the center of any display."
    ),
    TipItem(
        title = "Spatial Modifier: Roll & Flicks",
        detail = "Hold the watch side button to engage Spatial Modifier: twist your forearm like a doorknob to scrub/zoom/cycle tabs, or flick to snap windows & open Overview."
    ),
    TipItem(
        title = "Custom 3D Air Gestures",
        detail = "Open Custom 3D Gestures in Quick Settings to record 3 samples of any air motion with on-device DTW and bind it to Copy, Paste, Undo, Lock Screen, or macros."
    ),
    TipItem(
        title = "Tactile Crown Page Scrolling",
        detail = "Spin the watch crown to scroll app pages smoothly, or when connected, scroll host pages smoothly, jump Page Up/Down, adjust volume, or scrub timelines."
    ),
    TipItem(
        title = "Tile, Complication & Ongoing Chip",
        detail = "Add the Saved Hosts Tile or Watch-Face Complication for 1-tap connect. While active, tap the watch-face bottom ongoing chip to jump straight back."
    ),
    TipItem(
        title = "Off-Body & Walk-Away Auto-Lock",
        detail = "Taking off your watch auto-pauses tracking. Enable Walk-Away Auto-Lock to automatically lock your PC/Mac when you step out of UWB/BT range."
    ),
    TipItem(
        title = "Auto-Profiles & Host Handoff",
        detail = "Pair with the optional 5 GHz Wi-Fi companion script to auto-adapt crown/mode per active desktop app and pull/push clipboard URLs between watch and host."
    ),
    TipItem(
        title = "Palm-Cover & 1-Nit Stealth Pad",
        detail = "Cover the watch face to toggle Pause or Drag Lock eyes-free, or turn on 1-Nit Stealth Pad for a pure-black 1 Hz trackpad with invisible L/R click zones."
    )
)

/**
 * In-app ergonomics, hardware controls, spatial gesture, and connectivity tips screen with
 * smooth haptic crown scrolling.
 */
@Composable
fun TipsScreen(hapticsEnabled: Boolean = true) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val accent = LocalGestureAccent.current
    val flingBehavior = ScrollableDefaults.flingBehavior()
    val rotaryBehavior = RotaryScrollableDefaults.behavior(
        scrollableState = listState,
        flingBehavior = flingBehavior,
        hapticFeedbackEnabled = hapticsEnabled
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
            item(key = "hdr_tips", contentType = "header") {
                SettingsHeaderRow(title = "Tips & Controls", transformationSpec = transformationSpec)
            }

            itemsIndexed(
                items = TIPS,
                key = { index, _ -> "tip_$index" },
                contentType = { _, _ -> "tip_card" }
            ) { _, tip ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GestureColors.SurfaceCard)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = tip.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent.glow,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tip.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = GestureColors.TextSecondary
                    )
                }
            }
        }
    }
}
