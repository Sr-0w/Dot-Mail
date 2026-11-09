package app.dot.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors

/** [OLED] is Dark with a true-black background. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

private val LocalDotColors = staticCompositionLocalOf { DarkColors }
private val LocalDotTypography = staticCompositionLocalOf { DotTypography() }

/** Access tokens as `Dot.colors.textPrimary`, `Dot.type.body`. */
object Dot {
    val colors: DotColors
        @Composable @ReadOnlyComposable get() = LocalDotColors.current
    val type: DotTypography
        @Composable @ReadOnlyComposable get() = LocalDotTypography.current
}

@Composable
fun DotTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val colors = when (mode) {
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.OLED -> OledColors
    }
    val type = DotTypography()
    CompositionLocalProvider(
        LocalDotColors provides colors,
        LocalDotTypography provides type,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accentSoft),
    ) {
        // Material 3 is only a technical base: map its slots onto our tokens so any M3 widget
        // we use (menus, sheets, pull-to-refresh) inherits the quiet palette instead of Material You.
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = type.toMaterial(),
            shapes = Shapes(
                extraSmall = DotShapes.small,
                small = DotShapes.small,
                medium = DotShapes.medium,
                large = DotShapes.large,
                extraLarge = DotShapes.dialog,
            ),
            content = content,
        )
    }
}

private fun DotColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = inverse,
        onPrimary = onInverse,
        primaryContainer = surfaceRaised,
        onPrimaryContainer = textPrimary,
        secondary = textSecondary,
        onSecondary = background,
        secondaryContainer = surfaceRaised,
        onSecondaryContainer = textPrimary,
        tertiary = accent,
        onTertiary = onInverse,
        background = background,
        onBackground = textPrimary,
        surface = background,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceTint = background,
        surfaceBright = surfaceRaised,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceRaised,
        inverseSurface = inverse,
        inverseOnSurface = onInverse,
        inversePrimary = accent,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = onInverse,
        scrim = scrim,
    )
}

private fun DotTypography.toMaterial() = Typography(
    displayLarge = display, displayMedium = display, displaySmall = display,
    headlineLarge = display, headlineMedium = title, headlineSmall = title,
    titleLarge = title, titleMedium = headline, titleSmall = label,
    bodyLarge = body, bodyMedium = callout, bodySmall = caption,
    labelLarge = label, labelMedium = label, labelSmall = caption,
)
