package com.moviebox.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Warna khas Lumi Box: gradasi biru muda. */
val Accent = Color(0xFF5AB8FF)
val AccentDeep = Color(0xFF1F5FD6)
val AccentLight = Color(0xFF7CCBFF)
val Background = Color(0xFF000000)
val Surface1 = Color(0xFF141414)
val Surface2 = Color(0xFF232323)
val TextDim = Color(0xFFB3B3B3)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00172E),
    secondary = Color.White,
    background = Background,
    onBackground = Color.White,
    surface = Background,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    secondaryContainer = Surface2,
    onSecondaryContainer = Color.White,
    outline = Color(0xFF5A5A5A),
)

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
