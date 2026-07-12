package com.laylapro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LaylaPrimary = Color(0xFF4A4AAA)
private val LaylaSecondary = Color(0xFF8A8AFF)

private val DarkColors = darkColorScheme(
    primary = LaylaPrimary,
    secondary = LaylaSecondary,
)

private val LightColors = lightColorScheme(
    primary = LaylaPrimary,
    secondary = LaylaSecondary,
)

@Composable
fun LaylaProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
