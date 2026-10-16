package com.moviebox.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFB020)
private val Background = Color(0xFF0E0F13)
private val Surface = Color(0xFF16171D)
private val SurfaceHigh = Color(0xFF22232B)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF8AB4F8),
    background = Background,
    onBackground = Color(0xFFECECF1),
    surface = Background,
    onSurface = Color(0xFFECECF1),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFA3A4AE),
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    secondaryContainer = SurfaceHigh,
    onSecondaryContainer = Color(0xFFECECF1),
)

@Composable
fun MovieBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
