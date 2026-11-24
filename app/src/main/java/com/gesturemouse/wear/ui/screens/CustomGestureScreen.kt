package com.gesturemouse.wear.ui.screens

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import com.gesturemouse.wear.gesture.CustomGestureDtwEngine
import com.gesturemouse.wear.gesture.CustomGestureTemplate
import com.gesturemouse.wear.model.CustomGestureAction
import com.gesturemouse.wear.ui.components.SettingsAccentActionRow
import com.gesturemouse.wear.ui.components.SettingsHeaderRow
import com.gesturemouse.wear.ui.components.SettingsTonalActionRow
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TARGET_SAMPLE_COUNT = 3

/**
 * Guided Custom 3D Air Gesture Recorder powered by on-device Dynamic Time Warping (DTW).
 *
 * Users select a target HID shortcut or host macro, record 3 repetitions of their own 3D wrist
 * gesture, and save it for live `< 0.5 ms` recognition on-device. Supports full crown wheel
 * scrolling via [RotaryScrollableDefaults.behavior].
 */
@Composable
fun CustomGestureScreen(
    templates: List<CustomGestureTemplate>,
    hapticsEnabled: Boolean,
    onSetSensorPreview: (Boolean) -> Unit,
    onStartSampleCapture: ((List<FloatArray>) -> Unit) -> Unit,
    onCancelSampleCapture: () -> Unit,
    onAddTemplate: (CustomGestureTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val accent = LocalGestureAccent.current
    val flingBehavior = ScrollableDefaults.flingBehavior()
    val rotaryBehavior = RotaryScrollableDefaults.behavior(
        scrollableState = listState,
        flingBehavior = flingBehavior,
        hapticFeedbackEnabled = hapticsEnabled
    )
    val scope = rememberCoroutineScope()

    var selectedAction by remember { mutableStateOf(CustomGestureAction.COPY) }
    var isCapturing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val recordedSamples = remember { mutableStateListOf<List<FloatArray>>() }

    DisposableEffect(Unit) {
        onSetSensorPreview(true)
        onDispose {
            onCancelSampleCapture()
            onSetSensorPreview(false)
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            flingBehavior = flingBehavior,
            rotaryScrollableBehavior = rotaryBehavior,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "hdr_recorder", contentType = "header") {
                SettingsHeaderRow(
                    title = "Custom 3D Gestures",
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_action_picker", contentType = "button") {
                SettingsTonalActionRow(
                    title = "Bound Action",
                    subtitle = "${selectedAction.displayName} (tap to cycle)",
                    icon = Icons.Rounded.Gesture,
                    onClick = {
                        if (!isCapturing) {
                            selectedAction = selectedAction.next()
                            statusMessage = null
                        }
                    },
                    transformationSpec = transformationSpec
                )
            }

            item(key = "btn_record_sample", contentType = "button") {
                val sampleNumber = (recordedSamples.size + 1).coerceAtMost(TARGET_SAMPLE_COUNT)
                val title = when {
                    isCapturing -> "Move Wrist Now..."
                    recordedSamples.size >= TARGET_SAMPLE_COUNT -> "Save Gesture"
                    else -> "Record Sample $sampleNumber/$TARGET_SAMPLE_COUNT"
                }
                val subtitle = when {
                    isCapturing -> "Perform gesture, then pause briefly"
                    recordedSamples.size >= TARGET_SAMPLE_COUNT ->
                        "3 samples ready for ${selectedAction.displayName}"
                    statusMessage != null -> statusMessage
                    else -> "Tap & repeat same 3D motion $TARGET_SAMPLE_COUNT times"
                }

                SettingsAccentActionRow(
                    title = title,
                    subtitle = subtitle,
                    icon = when {
                        isCapturing -> Icons.Rounded.Stop
                        recordedSamples.size >= TARGET_SAMPLE_COUNT -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.FiberManualRecord
                    },
                    containerColor = when {
                        isCapturing -> GestureColors.AmberWarning.copy(alpha = 0.25f)
                        recordedSamples.size >= TARGET_SAMPLE_COUNT -> accent.container
                        else -> GestureColors.CyanContainer
                    },
                    contentColor = when {
                        isCapturing -> GestureColors.AmberWarning
                        recordedSamples.size >= TARGET_SAMPLE_COUNT -> accent.glow
                        else -> GestureColors.CyanPairing
                    },
                    onClick = {
                        when {
                            isCapturing -> {
                                onCancelSampleCapture()
                                isCapturing = false
                                statusMessage = "Capture cancelled"
                            }

                            recordedSamples.size >= TARGET_SAMPLE_COUNT -> {
                                val id = "cg_${System.currentTimeMillis()}"
                                val built = CustomGestureDtwEngine.buildTemplate(
                                    id = id,
                                    name = selectedAction.displayName,
                                    action = selectedAction,
                                    samples = recordedSamples.toList()
                                )
                                if (built != null) {
                                    onAddTemplate(built)
                                    recordedSamples.clear()
                                    statusMessage = "Saved ${built.name}!"
                                } else {
                                    recordedSamples.clear()
                                    statusMessage = "Motion too small — try again"
                                }
                            }

                            else -> {
                                isCapturing = true
                                statusMessage = null
                                onStartSampleCapture { frames ->
                                    scope.launch(Dispatchers.Main) {
                                        isCapturing = false
                                        if (frames.size >= 8) {
                                            recordedSamples.add(frames)
                                            statusMessage = if (recordedSamples.size >= TARGET_SAMPLE_COUNT) {
                                                "All $TARGET_SAMPLE_COUNT captured! Tap Save"
                                            } else {
                                                "Sample ${recordedSamples.size}/$TARGET_SAMPLE_COUNT captured"
                                            }
                                        } else {
                                            statusMessage = "Too short — make a bigger motion"
                                        }
                                    }
                                }
                            }
                        }
                    },
                    transformationSpec = transformationSpec
                )
            }

            if (recordedSamples.isNotEmpty() && !isCapturing) {
                item(key = "btn_reset_samples", contentType = "button") {
                    SettingsTonalActionRow(
                        title = "Clear Draft (${recordedSamples.size}/$TARGET_SAMPLE_COUNT)",
                        subtitle = "Start recording over",
                        onClick = {
                            recordedSamples.clear()
                            statusMessage = "Draft cleared"
                        },
                        transformationSpec = transformationSpec
                    )
                }
            }

            item(key = "hdr_saved", contentType = "header") {
                SettingsHeaderRow(
                    title = "Saved (${templates.size}/6)",
                    transformationSpec = transformationSpec
                )
            }

            if (templates.isEmpty()) {
                item(key = "empty_saved", contentType = "button") {
                    SettingsTonalActionRow(
                        title = "No Custom Gestures",
                        subtitle = "Record 3 samples above to bind any air gesture",
                        onClick = {},
                        transformationSpec = transformationSpec
                    )
                }
            } else {
                items(
                    items = templates,
                    key = { it.id },
                    contentType = { "saved_template" }
                ) { template ->
                    SettingsAccentActionRow(
                        title = template.name,
                        subtitle = "${template.action.displayName} • Tap to delete",
                        icon = Icons.Rounded.Delete,
                        containerColor = GestureColors.SurfaceCard,
                        contentColor = GestureColors.CoralError,
                        onClick = { onDeleteTemplate(template.id) },
                        transformationSpec = transformationSpec
                    )
                }
            }
        }
    }
}
