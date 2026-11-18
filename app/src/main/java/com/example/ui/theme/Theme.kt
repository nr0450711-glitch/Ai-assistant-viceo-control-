package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF042029),
    primaryContainer = Color(0xFF083344),
    onPrimaryContainer = Color(0xFFB3F4FF),
    secondary = MintEmerald,
    onSecondary = Color(0xFF032117),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = AmberSignal,
    onTertiary = Color(0xFF2B1700),
    error = CoralAlert,
    onError = Color(0xFF3B0814),
    background = ObsidianBg,
    onBackground = InkLight,
    surface = SlateSurface,
    onSurface = InkLight,
    surfaceVariant = SlateCard,
    onSurfaceVariant = InkMuted,
    outline = SlateOutline
)

private val LightColorScheme = lightColorScheme(
    primary = DeepCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F6FF),
    onPrimaryContainer = Color(0xFF003640),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    error = Color(0xFFE11D48),
    onError = Color.White,
    background = LightBg,
    onBackground = InkDark,
    surface = LightSurface,
    onSurface = InkDark,
    surfaceVariant = LightCard,
    onSurfaceVariant = InkDarkMuted,
    outline = LightOutline
)

val ApkAiShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun ApkAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ApkAiShapes,
        content = content
    )
}
