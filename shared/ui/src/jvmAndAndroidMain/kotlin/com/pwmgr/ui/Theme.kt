package com.pwmgr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFFA5B4FC)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Color(0xFF0EA5E9),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF1E1B4B),
    secondary = Color(0xFF38BDF8),
)

@Composable
fun PwMgrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
