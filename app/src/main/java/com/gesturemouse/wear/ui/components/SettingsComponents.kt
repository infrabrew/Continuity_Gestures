package com.gesturemouse.wear.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.gesturemouse.wear.ui.theme.GestureColors
import com.gesturemouse.wear.ui.theme.LocalGestureAccent

/**
 * Skippable, primitive-parameter Wear OS Material 3 list rows for buttery smooth
 * 60–120 Hz rotary & touch scrolling across Settings screens.
 *
 * Isolating each row into its own composable with stable primitive parameters lets the
 * Compose compiler skip unchanged visible rows in O(1) when toggling switches or scrubbing
 * sliders.
 */
@Composable
fun TransformingLazyColumnItemScope.SettingsHeaderRow(
    title: String,
    transformationSpec: TransformationSpec
) {
    ListHeader(
        transformation = SurfaceTransformation(transformationSpec),
        modifier = Modifier
            .transformedHeight(this, transformationSpec)
            .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding)
    ) {
        Text(
            text = title,
            color = GestureColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TransformingLazyColumnItemScope.SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    transformationSpec: TransformationSpec
) {
    val accent = LocalGestureAccent.current
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(transformationSpec),
        colors = SwitchButtonDefaults.switchButtonColors(
            checkedContainerColor = accent.container,
            checkedContentColor = GestureColors.TextPrimary,
            checkedSecondaryContentColor = GestureColors.TextSecondary,
            checkedThumbColor = accent.onPrimary,
            checkedTrackColor = accent.active,
            uncheckedContainerColor = GestureColors.SurfaceCard,
            uncheckedContentColor = GestureColors.TextPrimary,
            uncheckedSecondaryContentColor = GestureColors.TextSecondary
        ),
        label = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            Text(
                text = subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
fun TransformingLazyColumnItemScope.SettingsTonalActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    transformationSpec: TransformationSpec
) {
    val accent = LocalGestureAccent.current
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(transformationSpec),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = GestureColors.SurfaceCard,
            contentColor = GestureColors.TextPrimary,
            secondaryContentColor = GestureColors.TextSecondary,
            iconColor = accent.glow
        ),
        icon = icon?.let { vector ->
            { Icon(imageVector = vector, contentDescription = null) }
        },
        label = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = subtitle?.let { sub ->
            {
                Text(
                    text = sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

@Composable
fun TransformingLazyColumnItemScope.SettingsSelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    selectedIcon: ImageVector,
    onClick: () -> Unit,
    transformationSpec: TransformationSpec
) {
    val accent = LocalGestureAccent.current
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(transformationSpec),
        colors = if (selected) {
            ButtonDefaults.buttonColors(
                containerColor = accent.container,
                contentColor = accent.glow,
                secondaryContentColor = GestureColors.TextPrimary.copy(alpha = 0.86f),
                iconColor = accent.glow
            )
        } else {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = GestureColors.SurfaceCard,
                contentColor = GestureColors.TextPrimary,
                secondaryContentColor = GestureColors.TextSecondary,
                iconColor = accent.glow
            )
        },
        icon = if (selected) {
            { Icon(imageVector = selectedIcon, contentDescription = "Selected") }
        } else {
            null
        },
        label = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            Text(
                text = subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
fun TransformingLazyColumnItemScope.SettingsAccentActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    containerColor: Color,
    contentColor: Color,
    secondaryContentColor: Color = GestureColors.TextSecondary,
    iconColor: Color = contentColor,
    onClick: () -> Unit,
    transformationSpec: TransformationSpec
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(transformationSpec),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
            iconColor = iconColor
        ),
        icon = icon?.let { vector ->
            { Icon(imageVector = vector, contentDescription = null) }
        },
        label = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = subtitle?.let { sub ->
            {
                Text(
                    text = sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

@Composable
fun SettingsFloatSliderRow(
    value: Float,
    minValue: Float,
    maxValue: Float,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = minValue..maxValue,
        steps = steps,
        segmented = false,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SettingsIntSliderRow(
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueProgression = (minValue..maxValue) step step,
        segmented = false,
        modifier = Modifier.fillMaxWidth()
    )
}
