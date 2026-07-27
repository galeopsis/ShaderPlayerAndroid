package com.goodwin.shaderplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ShaderPlayerColors = darkColorScheme(
    primary = Color(0xFF7CD7FF),
    secondary = Color(0xFFB5A7FF),
    background = Color(0xFF090B11),
    surface = Color(0xFF151927),
)

@Composable
fun ShaderPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShaderPlayerColors,
        content = content,
    )
}
