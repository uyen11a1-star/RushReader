package com.rushreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RC {
    val Bg = Color(0xFF0B0B0C)
    val Surface = Color(0xFF131315)
    val Card = Color(0xFF1B1B1E)
    val Outline = Color(0xFF2C2C31)
    val Text = Color(0xFFEDEDED)
    val Sub = Color(0xFF9B9BA3)
    val Accent = Color(0xFFE8C547)
    val OnAccent = Color(0xFF15110A)
    val Danger = Color(0xFFE5645A)
}

@Composable
fun RushTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RC.Accent, onPrimary = RC.OnAccent, background = RC.Bg, onBackground = RC.Text,
            surface = RC.Surface, onSurface = RC.Text, surfaceVariant = RC.Card, onSurfaceVariant = RC.Sub,
            outline = RC.Outline, secondaryContainer = Color(0xFF2B2616), onSecondaryContainer = RC.Accent, error = RC.Danger
        ),
        content = content
    )
}
