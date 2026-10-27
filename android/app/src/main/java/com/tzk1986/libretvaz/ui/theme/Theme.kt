package com.tzk1986.libretvaz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LibreTVazDark = darkColorScheme(
    primary = Color(0xFF38BDF8),
    background = Color(0xFF0B101A),
    surface = Color(0xFF111827),
)

@Composable
fun LibreTVazTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LibreTVazDark, content = content)
}
