package com.sizwe.tvremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberately plain. The visual design lands later; everything in the UI reads its colours from
 * [MaterialTheme], so restyling means editing this file and nothing else.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1420),
    secondary = Color(0xFF9AA0A6),
    background = Color(0xFF101418),
    surface = Color(0xFF171C22),
    surfaceVariant = Color(0xFF232A32),
    error = Color(0xFFF28B82),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EAF0),
    error = Color(0xFFB3261E),
)

@Composable
fun TvRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
