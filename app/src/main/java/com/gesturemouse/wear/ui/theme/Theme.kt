package com.gesturemouse.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.gesturemouse.wear.model.AppThemeColor

/**
 * Dynamic neon accent palette supporting 5 OLED-tuned Wear OS Material 3 Expressive themes.
 */
@Immutable
data class GestureAccentPalette(
    val color: AppThemeColor,
    val active: Color,
    val glow: Color,
    val container: Color,
    val onPrimary: Color,
    val outline: Color
)

/**
 * High-contrast Wear OS Material 3 dark palette optimized for circular AMOLED displays.
 * True black background conserves watch battery while vibrant neon accents give instant
 * glanceable feedback for connection state, expressive controls, and gesture pulses.
 */
object GestureColors {
    val EmeraldActive = Color(0xFF00E676)
    val EmeraldGlow = Color(0xFF69F0AE)
    val EmeraldContainer = Color(0xFF0A2E1D)
    val CyanPairing = Color(0xFF00E5FF)
    val CyanContainer = Color(0xFF002B33)
    val AmberWarning = Color(0xFFFFB74D)
    val CoralError = Color(0xFFFF5252)
    val SlateIdle = Color(0xFF607D8B)
    val SurfaceCard = Color(0xFF161B1D)
    val SurfaceElevated = Color(0xFF1E2528)
    val BackgroundBlack = Color(0xFF000000)
    val TextPrimary = Color(0xFFF3F8F5)
    val TextSecondary = Color(0xFFBCCBC4)

    fun accentFor(themeColor: AppThemeColor): GestureAccentPalette = when (themeColor) {
        AppThemeColor.NEON_GREEN -> GestureAccentPalette(
            color = AppThemeColor.NEON_GREEN,
            active = EmeraldActive,
            glow = EmeraldGlow,
            container = EmeraldContainer,
            onPrimary = Color(0xFF002110),
            outline = Color(0xFF284539)
        )
        AppThemeColor.NEON_BLUE -> GestureAccentPalette(
            color = AppThemeColor.NEON_BLUE,
            active = Color(0xFF00D4FF),
            glow = Color(0xFF7CE8FF),
            container = Color(0xFF062836),
            onPrimary = Color(0xFF001F29),
            outline = Color(0xFF204352)
        )
        AppThemeColor.NEON_ORANGE -> GestureAccentPalette(
            color = AppThemeColor.NEON_ORANGE,
            active = Color(0xFFFF6D00),
            glow = Color(0xFFFFAB40),
            container = Color(0xFF331804),
            onPrimary = Color(0xFF291000),
            outline = Color(0xFF52321C)
        )
        AppThemeColor.NEON_PURPLE -> GestureAccentPalette(
            color = AppThemeColor.NEON_PURPLE,
            active = Color(0xFFB366FF),
            glow = Color(0xFFD9B3FF),
            container = Color(0xFF230F38),
            onPrimary = Color(0xFF1F003D),
            outline = Color(0xFF422C59)
        )
        AppThemeColor.NEON_PINK -> GestureAccentPalette(
            color = AppThemeColor.NEON_PINK,
            active = Color(0xFFFF2E93),
            glow = Color(0xFFFF80BF),
            container = Color(0xFF360B21),
            onPrimary = Color(0xFF2E0016),
            outline = Color(0xFF54253D)
        )
    }
}

val LocalGestureAccent = staticCompositionLocalOf {
    GestureColors.accentFor(AppThemeColor.NEON_GREEN)
}

private fun buildColorScheme(accent: GestureAccentPalette): ColorScheme = ColorScheme(
    primary = accent.active,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.container,
    onPrimaryContainer = accent.glow,
    secondary = GestureColors.CyanPairing,
    onSecondary = Color(0xFF002026),
    secondaryContainer = GestureColors.CyanContainer,
    onSecondaryContainer = Color(0xFFA6F3FF),
    tertiary = GestureColors.AmberWarning,
    onTertiary = Color(0xFF2B1700),
    error = GestureColors.CoralError,
    onError = Color(0xFF370005),
    background = GestureColors.BackgroundBlack,
    onBackground = GestureColors.TextPrimary,
    surfaceContainer = GestureColors.SurfaceCard,
    onSurface = GestureColors.TextPrimary,
    onSurfaceVariant = GestureColors.TextSecondary,
    outline = accent.outline
)

@Composable
fun GestureMouseTheme(
    themeColor: AppThemeColor = AppThemeColor.NEON_GREEN,
    content: @Composable () -> Unit
) {
    val accent = GestureColors.accentFor(themeColor)
    CompositionLocalProvider(LocalGestureAccent provides accent) {
        MaterialTheme(
            colorScheme = buildColorScheme(accent),
            content = content
        )
    }
}
