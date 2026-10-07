package com.openmausdroid.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF8FD694),
    onPrimary = Color(0xFF0B2C14),
    primaryContainer = Color(0xFF1E4A2A),
    onPrimaryContainer = Color(0xFFCDEFD2),
    secondary = Color(0xFF83C7E3),
    onSecondary = Color(0xFF06222E),
    secondaryContainer = Color(0xFF1B4256),
    onSecondaryContainer = Color(0xFFC6E8FA),
    tertiary = Color(0xFFE6C58A),
    background = Color(0xFF101315),
    onBackground = Color(0xFFE3E3E1),
    surface = Color(0xFF171A1C),
    onSurface = Color(0xFFE3E3E1),
    surfaceVariant = Color(0xFF22262A),
    onSurfaceVariant = Color(0xFFB9BEC4),
    outline = Color(0xFF6F767C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF650003),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography(),
        content = content,
    )
}
