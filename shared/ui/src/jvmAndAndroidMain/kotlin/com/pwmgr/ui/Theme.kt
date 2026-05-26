package com.pwmgr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neon Space Red
private val NeonRed = Color(0xFFFF003C)
private val NeonRedDim = Color(0x33FF003C)
private val DarkNeonRed = Color(0xFFB3002A)
private val DeepSpaceBlack = Color(0xFF000000)
private val VoidGrey = Color(0xFF09090C)
private val ShipHullGrey = Color(0xFF121218)
private val StarlightWhite = Color(0xFFFFFFFF)
private val NebulaGrey = Color(0xFFA0A0A0)

private val LightColors = lightColorScheme(
    primary = NeonRed,
    onPrimary = Color.White,
    primaryContainer = NeonRedDim,
    onPrimaryContainer = NeonRed,
    secondary = DarkNeonRed,
    background = Color(0xFFF0F0F0),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5E5E5),
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF444444)
)

private val DarkColors = darkColorScheme(
    primary = NeonRed,
    onPrimary = DeepSpaceBlack,
    primaryContainer = NeonRedDim,
    onPrimaryContainer = NeonRed,
    secondary = DarkNeonRed,
    background = DeepSpaceBlack,
    surface = VoidGrey,
    surfaceVariant = ShipHullGrey,
    onBackground = StarlightWhite,
    onSurface = StarlightWhite,
    onSurfaceVariant = NebulaGrey
)

@Composable
fun PwMgrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
