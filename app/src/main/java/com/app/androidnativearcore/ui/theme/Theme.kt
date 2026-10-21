package com.app.androidnativearcore.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = IosBlueDark,
    onPrimary = IosLabelDark,
    secondary = IosSecondaryLabelDark,
    error = IosRed,
    background = IosGroupedBgDark,
    onBackground = IosLabelDark,
    surface = IosSurfaceDark,
    onSurface = IosLabelDark,
    surfaceVariant = IosSurfaceDark,
    onSurfaceVariant = IosSecondaryLabelDark,
)

private val LightColorScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = IosSurfaceLight,
    secondary = IosSecondaryLabelLight,
    error = IosRed,
    background = IosGroupedBgLight,
    onBackground = IosLabelLight,
    surface = IosSurfaceLight,
    onSurface = IosLabelLight,
    surfaceVariant = IosSurfaceLight,
    onSurfaceVariant = IosSecondaryLabelLight,
)

// iOS-like rounded shapes.
private val IosShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AndroidNativeARCoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Fixed iOS-style palette (no Material You dynamic color) for a consistent look.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = IosShapes,
        content = content,
    )
}
