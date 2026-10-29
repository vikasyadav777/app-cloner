package com.appcloner.dual.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F6E62)
private val TealDark = Color(0xFF8ED6CA)
private val Ink = Color(0xFF161D1B)
private val Mist = Color(0xFFF3F6F4)
private val Night = Color(0xFF101614)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EBE3),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF3E5C56),
    onSecondary = Color.White,
    background = Mist,
    onBackground = Ink,
    surface = Mist,
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF3F4946),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003731),
    primaryContainer = Teal,
    onPrimaryContainer = Color(0xFFB7EBE3),
    secondary = Color(0xFFB4CCC6),
    onSecondary = Color(0xFF203531),
    background = Night,
    onBackground = Color(0xFFDDE4E1),
    surface = Night,
    onSurface = Color(0xFFDDE4E1),
    onSurfaceVariant = Color(0xFFBFC9C5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun AppClonerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
