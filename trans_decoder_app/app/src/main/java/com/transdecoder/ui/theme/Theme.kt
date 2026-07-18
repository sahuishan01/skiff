package com.transdecoder.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Color Palette ───────────────────────────────────────────────────────────
// Warm teal "Signal" mood — connected, technical, confident.
// Surfaces are warm charcoal (not pure black) with subtle blue‑gray undertone.
object SkiffColors {
    val Signal = Color(0xFF40C4B8)
    val SignalDim = Color(0xFF2DA89E)
    val SignalContainer = Color(0xFF0D3D38)
    val OnSignalContainer = Color(0xFF6FEDE4)
    val Amber = Color(0xFFFFB74D)
    val Coral = Color(0xFFE57373)
    val Green = Color(0xFF81C784)

    val SurfaceBg = Color(0xFF121618)
    val SurfaceCard = Color(0xFF1A1D20)
    val SurfaceElevated = Color(0xFF23272A)
    val Border = Color(0xFF2E3236)
    val TextPrimary = Color(0xFFE8EAEB)
    val TextSecondary = Color(0xFF8F9498)
    val TextMuted = Color(0xFF5C6166)
}

private val SkiffColorScheme = darkColorScheme(
    primary = SkiffColors.Signal,
    onPrimary = Color(0xFF002B28),
    primaryContainer = SkiffColors.SignalContainer,
    onPrimaryContainer = SkiffColors.OnSignalContainer,
    secondary = SkiffColors.Amber,
    onSecondary = Color(0xFF3A2400),
    error = SkiffColors.Coral,
    onError = Color(0xFF440000),
    background = SkiffColors.SurfaceBg,
    onBackground = SkiffColors.TextPrimary,
    surface = SkiffColors.SurfaceCard,
    onSurface = SkiffColors.TextPrimary,
    surfaceVariant = SkiffColors.SurfaceElevated,
    onSurfaceVariant = SkiffColors.TextSecondary,
    outline = SkiffColors.Border,
)

val PairCodeFont: FontFamily get() = FontFamily.Monospace

private val SkiffTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = PairCodeFont,
        lineHeight = 44.sp,
        letterSpacing = 4.sp,
    ),
    displayMedium = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

private val SkiffShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
)

@Composable
fun SkiffTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SkiffColorScheme,
        typography = SkiffTypography,
        shapes = SkiffShapes,
        content = content,
    )
}
