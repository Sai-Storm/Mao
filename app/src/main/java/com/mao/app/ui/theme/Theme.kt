package com.mao.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MaoColors {
    val Charcoal950 = Color(0xFF0B0B0E)
    val Charcoal900 = Color(0xFF111114)
    val Charcoal800 = Color(0xFF18181D)
    val Charcoal700 = Color(0xFF222229)
    val Gold = Color(0xFFF5C542)
    val GoldDeep = Color(0xFFD69E12)
    val GoldSoft = Color(0xFFFFE39A)
    val OnGold = Color(0xFF1A1405)
    val TextPrimary = Color(0xFFF5F1E6)
    val TextSecondary = Color(0xFFA9A497)
    val TextMuted = Color(0xFF6F6B62)
    val Danger = Color(0xFFFF6464)
    val Success = Color(0xFF4FD68E)
    val ReadBlue = Color(0xFF6CC4FF)
    val Glass = Color.White.copy(alpha = 0.06f)
    val GlassStrong = Color.White.copy(alpha = 0.10f)
    val GlassBorder = Gold.copy(alpha = 0.22f)
    val MineBubble = Color(0xFF3A2F12)
    val TheirBubble = Color.White.copy(alpha = 0.07f)
}

private val scheme = darkColorScheme(
    primary = MaoColors.Gold,
    onPrimary = MaoColors.OnGold,
    primaryContainer = MaoColors.MineBubble,
    onPrimaryContainer = MaoColors.GoldSoft,
    secondary = MaoColors.GoldSoft,
    onSecondary = MaoColors.OnGold,
    background = MaoColors.Charcoal900,
    onBackground = MaoColors.TextPrimary,
    surface = MaoColors.Charcoal800,
    onSurface = MaoColors.TextPrimary,
    surfaceVariant = MaoColors.Charcoal700,
    onSurfaceVariant = MaoColors.TextSecondary,
    surfaceContainer = MaoColors.Charcoal800,
    surfaceContainerHigh = MaoColors.Charcoal700,
    surfaceContainerLow = MaoColors.Charcoal800,
    outline = MaoColors.GlassBorder,
    outlineVariant = Color.White.copy(alpha = 0.08f),
    error = MaoColors.Danger,
    onError = Color.Black,
)

private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MaoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
