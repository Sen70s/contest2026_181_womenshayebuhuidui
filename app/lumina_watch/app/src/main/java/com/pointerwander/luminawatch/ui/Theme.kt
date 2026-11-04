package com.pointerwander.luminawatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 语义化视觉 Token。页面不再直接使用旧 WatchTheme 里的 lavender/violet 这类与色相不符的名字。
 */
data class WatchColors(
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val groupedSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val actionBlack: Color,
    val onAction: Color,
    val heart: Color,
    val oxygen: Color,
    val warning: Color,
    val danger: Color,
    val outline: Color,
    val glass: Color,
)

private val LightWatchColors = WatchColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF2F2F7),
    groupedSurface = Color(0xFFF2F2F7),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF636366),
    accent = Color(0xFF005BB5),
    onAccent = Color.White,
    accentSoft = Color(0xFFE5F2FF),
    actionBlack = Color(0xFF171716),
    onAction = Color(0xFFFFFFFF),
    heart = Color(0xFFFF2D55),
    oxygen = Color(0xFF00A7D8),
    warning = Color(0xFFED8A00),
    danger = Color(0xFFB3261E),
    outline = Color(0xFFD1D1D6),
    glass = Color(0xDFFFFFFF),
)

private val DarkWatchColors = WatchColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceMuted = Color(0xFF2C2C2E),
    groupedSurface = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFAEAEB2),
    accent = Color(0xFFFFB14A),
    onAccent = Color.Black,
    accentSoft = Color(0xFF493017),
    actionBlack = Color(0xFF0E0C0B),
    onAction = Color(0xFFFFFFFF),
    heart = Color(0xFFFF5B7E),
    oxygen = Color(0xFF57D4F2),
    warning = Color(0xFFFFB340),
    danger = Color(0xFFF2998A),
    outline = Color(0xFF38383A),
    glass = Color(0xD923262B),
)

val LocalWatchColors = staticCompositionLocalOf { LightWatchColors }

@Composable
fun LuminaWatchTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkWatchColors else LightWatchColors
    CompositionLocalProvider(LocalWatchColors provides colors) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme(
                primary = colors.accent,
                background = colors.background,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                onBackground = colors.textPrimary,
                onPrimary = colors.onAccent,
                secondaryContainer = colors.accentSoft,
                onSecondaryContainer = colors.accent,
                surfaceVariant = colors.surfaceMuted,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.outline,
            ) else lightColorScheme(
                primary = colors.accent,
                background = colors.background,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                onBackground = colors.textPrimary,
                onPrimary = colors.onAccent,
                secondaryContainer = colors.accentSoft,
                onSecondaryContainer = colors.accent,
                surfaceVariant = colors.surfaceMuted,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.outline,
            ),
            content = content,
        )
    }
}
