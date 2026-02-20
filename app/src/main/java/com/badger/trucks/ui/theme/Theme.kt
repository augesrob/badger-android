package com.badger.trucks.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Badger brand colors matching the web app
val Amber500 = Color(0xFFF59E0B)
val Amber400 = Color(0xFFFBBF24)
val DarkBg = Color(0xFF0F0F0F)
val DarkSurface = Color(0xFF1A1A1A)
val DarkCard = Color(0xFF222222)
val DarkBorder = Color(0xFF333333)
val MutedText = Color(0xFF9CA3AF)
val LightText = Color(0xFFF0F0F0)

// Status colors
val Blue500 = Color(0xFF3B82F6)
val Purple500 = Color(0xFF8B5CF6)
val Green500 = Color(0xFF22C55E)
val Green400 = Color(0xFF4ADE80)
val Red500 = Color(0xFFEF4444)
val Orange500 = Color(0xFFF97316)
val Pink400 = Color(0xFFF472B6)
val Blue400 = Color(0xFF60A5FA)
val Purple400 = Color(0xFFA78BFA)

private val DarkColorScheme = darkColorScheme(
    primary = Amber500,
    onPrimary = Color.Black,
    secondary = Amber400,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onBackground = LightText,
    onSurface = LightText,
    onSurfaceVariant = MutedText,
    outline = DarkBorder,
    error = Red500,
)

@Composable
fun BadgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
