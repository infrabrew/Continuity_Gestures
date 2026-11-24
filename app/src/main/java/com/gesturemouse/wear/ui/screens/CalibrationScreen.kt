package com.gesturemouse.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.gesturemouse.wear.ui.theme.GestureColors
import kotlinx.coroutines.delay

private enum class CalibrationStep {
    HOLD_STILL,
    SAMPLE_PINCHES,
    COMPLETE
}

/**
 * Interactive 3-step live IMU calibration wizard.
 *
 * 1. Measures resting wrist accelerometer noise floor for 2.5 seconds.
 * 2. Captures 3 natural index+thumb pinches while displaying a draw-phase live impulse bar.
 * 3. Computes and persists an optimal pinch threshold tailored to the user's strap fit and watch IMU.
 */
@Composable
fun CalibrationScreen(
    liveImpulseState: State<Float>,
    onSetSensorPreview: (Boolean) -> Unit,
    onSaveCalibration: (noiseFloor: Float, recommendedPinchThreshold: Float) -> Unit,
    onDone: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    var step by remember { mutableStateOf(CalibrationStep.HOLD_STILL) }
    var measuredNoiseFloor by remember { mutableFloatStateOf(0.45f) }
    var restCountdown by remember { mutableIntStateOf(3) }
    val pinchPeaks = remember { mutableStateListOf<Float>() }
    var lastPeakMs by remember { mutableLongStateOf(0L) }
    var recommendedThreshold by remember { mutableFloatStateOf(3.2f) }

    DisposableEffect(Unit) {
        onSetSensorPreview(true)
        onDispose {
            onSetSensorPreview(false)
        }
    }

    // Step 1 & Step 2 run in a single step-scoped LaunchedEffect so high-frequency impulse
    // updates never recompose the entire CalibrationScreen body.
    LaunchedEffect(step) {
        when (step) {
            CalibrationStep.HOLD_STILL -> {
                var sum = 0f
                var count = 0
                for (tick in 25 downTo 1) {
                    restCountdown = ((tick + 9) / 10).coerceAtLeast(1)
                    sum += liveImpulseState.value
                    count++
                    delay(100L)
                }
                measuredNoiseFloor = (if (count > 0) sum / count else 0.45f).coerceIn(0.20f, 1.5f)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                step = CalibrationStep.SAMPLE_PINCHES
            }

            CalibrationStep.SAMPLE_PINCHES -> {
                val captureFloor = maxOf(measuredNoiseFloor * 2.5f, 1.8f)
                snapshotFlow { liveImpulseState.value }.collect { score ->
                    if (pinchPeaks.size < 3) {
                        val now = System.currentTimeMillis()
                        if (score >= captureFloor && (now - lastPeakMs) > 450L) {
                            lastPeakMs = now
                            pinchPeaks.add(score)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (pinchPeaks.size >= 3) {
                                val avgPeak = pinchPeaks.average().toFloat()
                                recommendedThreshold = maxOf(measuredNoiseFloor * 2.8f, avgPeak * 0.62f)
                                    .coerceIn(1.8f, 6.5f)
                                onSaveCalibration(measuredNoiseFloor, recommendedThreshold)
                                step = CalibrationStep.COMPLETE
                            }
                        }
                    }
                }
            }

            CalibrationStep.COMPLETE -> Unit
        }
    }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GestureColors.BackgroundBlack)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            when (step) {
                CalibrationStep.HOLD_STILL -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Hold Wrist Still",
                            style = MaterialTheme.typography.titleSmall,
                            color = GestureColors.CyanPairing,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Measuring resting sensor noise… ($restCountdown)",
                            style = MaterialTheme.typography.bodySmall,
                            color = GestureColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ImpulseBar(
                            valueProvider = { liveImpulseState.value },
                            maxVal = 6f
                        )
                    }
                }

                CalibrationStep.SAMPLE_PINCHES -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Pinch ${pinchPeaks.size} / 3",
                            style = MaterialTheme.typography.titleMedium,
                            color = GestureColors.EmeraldActive,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap thumb & index finger together firmly 3 times",
                            style = MaterialTheme.typography.bodySmall,
                            color = GestureColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        ImpulseBar(
                            valueProvider = { liveImpulseState.value },
                            maxVal = 7f
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LiveSignalLabel(liveImpulseState = liveImpulseState)
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = onDone,
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(34.dp),
                            label = { Text("Cancel") }
                        )
                    }
                }

                CalibrationStep.COMPLETE -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Calibrated",
                            tint = GestureColors.EmeraldActive,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Calibrated!",
                            style = MaterialTheme.typography.titleSmall,
                            color = GestureColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Threshold: ${formatOneDecimal(recommendedThreshold)} • Floor: ${formatTwoDecimals(measuredNoiseFloor)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GestureColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth(0.75f),
                            label = { Text("Done") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSignalLabel(liveImpulseState: State<Float>) {
    Text(
        text = "Signal: ${formatOneDecimal(liveImpulseState.value)} m/s²",
        style = MaterialTheme.typography.labelSmall,
        color = GestureColors.TextSecondary
    )
}

@Composable
private fun ImpulseBar(
    valueProvider: () -> Float,
    maxVal: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .height(10.dp)
            .drawBehind {
                val fraction = (valueProvider() / maxVal).coerceIn(0f, 1f)
                val radius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                drawRoundRect(
                    color = GestureColors.SurfaceCard,
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = if (fraction > 0.45f) GestureColors.EmeraldActive else GestureColors.CyanPairing,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius
                )
            }
    )
}
