package com.focuswall.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2F6F5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EFE9),
    onPrimaryContainer = Color(0xFF173B31),
    background = Color(0xFFF6F5F1),
    surface = Color(0xFFF6F5F1),
    surfaceContainer = Color.White,
    error = Color(0xFFB4443A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6CC0A5),
    onPrimary = Color(0xFF0E2A22),
    primaryContainer = Color(0xFF23352F),
    onPrimaryContainer = Color(0xFFCFE9DE),
    background = Color(0xFF151918),
    surface = Color(0xFF151918),
    surfaceContainer = Color(0xFF1E2422),
    error = Color(0xFFE0796F),
)

@Composable
fun FocusWallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
