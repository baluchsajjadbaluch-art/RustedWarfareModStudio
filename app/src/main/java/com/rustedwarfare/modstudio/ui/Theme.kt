package com.rustedwarfare.modstudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.rustedwarfare.modstudio.core.SettingsStore

// Rusty-military palette
private val Rust = Color(0xFFF2A33C)
private val RustDim = Color(0xFF8C5A1D)
private val Steel = Color(0xFF7FB4D8)
private val DarkBg = Color(0xFF14161A)
private val DarkSurface = Color(0xFF1C1F26)
private val DarkSurfaceVar = Color(0xFF262B33)
private val LightBg = Color(0xFFFAF7F2)
private val LightSurface = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFFB26A00)

private val DarkScheme = darkColorScheme(
    primary = Rust,
    onPrimary = Color(0xFF1E1503),
    secondary = Steel,
    onSecondary = Color(0xFF062033),
    tertiary = Color(0xFF9BD77F),
    background = DarkBg,
    onBackground = Color(0xFFE8EAED),
    surface = DarkSurface,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = DarkSurfaceVar,
    onSurfaceVariant = Color(0xFFB8BEC7),
    error = Color(0xFFE57373),
    outline = Color(0xFF4A5058)
)

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2A6A97),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4C7A2E),
    background = LightBg,
    onBackground = Color(0xFF1B1C1E),
    surface = LightSurface,
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFEDE5DA),
    onSurfaceVariant = Color(0xFF4C453C),
    error = Color(0xFFB3261E),
    outline = Color(0xFF857264)
)

@Composable
fun ModStudioTheme(
    settings: SettingsStore,
    content: @Composable () -> Unit
) {
    val dark = when (settings.themeMode) {
        SettingsStore.THEME_DARK -> true
        SettingsStore.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content
    )
}
