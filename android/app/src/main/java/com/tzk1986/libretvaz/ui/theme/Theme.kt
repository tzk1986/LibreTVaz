package com.tzk1986.libretvaz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LibreTVazDark = darkColorScheme(
    primary = Color(0xFF38BDF8),
    // 用户 2026-11-05：改纯黑看效果（原 0x0B101A/0x111827 深蓝灰）
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
)

@Composable
fun LibreTVazTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LibreTVazDark, content = content)
}
