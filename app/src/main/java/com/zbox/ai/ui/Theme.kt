package com.zbox.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 尼康标志色 */
object ZColors {
    /** Updated from the persisted theme setting; Compose tracks reads and redraws all palette users. */
    var isLightTheme by mutableStateOf(false)
    val NikonYellow = Color(0xFFFFE600)
    val YellowDim get() = if (isLightTheme) Color(0xFF796600) else Color(0xFFB8A600)
    val Bg get() = if (isLightTheme) Color(0xFFF0EEE7) else Color(0xFF0A0B0C)
    val Surface get() = if (isLightTheme) Color(0xFFE8E6DE) else Color(0xFF141517)
    val SurfaceHigh get() = if (isLightTheme) Color(0xFFDFDDD4) else Color(0xFF1C1E20)
    val SurfaceHigher get() = if (isLightTheme) Color(0xFFD4D2C8) else Color(0xFF26282B)
    val Outline get() = if (isLightTheme) Color(0xFFC2C0B6) else Color(0xFF303236)
    val TextPrimary get() = if (isLightTheme) Color(0xFF282925) else Color(0xFFF1F2F3)
    val TextSecondary get() = if (isLightTheme) Color(0xFF55564F) else Color(0xFFA4A7AC)
    val TextTertiary get() = if (isLightTheme) Color(0xFF74756D) else Color(0xFF767A80)
    val Danger get() = if (isLightTheme) Color(0xFFB3261E) else Color(0xFFFF5252)
    val Success get() = if (isLightTheme) Color(0xFF167451) else Color(0xFF35D39A)
    val Info get() = if (isLightTheme) Color(0xFF2864AA) else Color(0xFF60A8FF)
    val Mono get() = if (isLightTheme) Color(0xFF315D68) else Color(0xFF9CDCFE)
}

private fun zcColorScheme(light: Boolean) = if (light) {
    lightColorScheme(
        primary = ZColors.NikonYellow,
        onPrimary = Color(0xFF171717),
        primaryContainer = Color(0xFFFFF1A8),
        onPrimaryContainer = Color(0xFF342B00),
        secondary = ZColors.YellowDim,
        onSecondary = Color(0xFF282925),
        background = ZColors.Bg,
        onBackground = ZColors.TextPrimary,
        surface = ZColors.Surface,
        onSurface = ZColors.TextPrimary,
        surfaceVariant = ZColors.SurfaceHigh,
        onSurfaceVariant = ZColors.TextSecondary,
        outline = ZColors.Outline,
        outlineVariant = ZColors.Outline,
        error = ZColors.Danger,
        onError = Color.White,
    )
} else {
    darkColorScheme(
        primary = ZColors.NikonYellow,
        onPrimary = Color(0xFF0A0B0C),
        primaryContainer = Color(0xFF3A3300),
        onPrimaryContainer = ZColors.NikonYellow,
        secondary = ZColors.NikonYellow,
        onSecondary = Color(0xFF0A0B0C),
        background = ZColors.Bg,
        onBackground = ZColors.TextPrimary,
        surface = ZColors.Surface,
        onSurface = ZColors.TextPrimary,
        surfaceVariant = ZColors.SurfaceHigh,
        onSurfaceVariant = ZColors.TextSecondary,
        outline = ZColors.Outline,
        outlineVariant = Color(0xFF2A2A2A),
        error = ZColors.Danger,
        onError = Color(0xFF0A0B0C),
    )
}

private val ZcTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        labelMedium = labelMedium.copy(letterSpacing = 0.4.sp),
    )
}

/** 等宽字体样式，给 Hex 日志和数值用。 */
val MonoStyle: TextStyle get() = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    color = ZColors.Mono,
)

val NumStyle: TextStyle get() = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    color = ZColors.TextPrimary,
)

@Composable
fun ZboxTheme(content: @Composable () -> Unit) {
    val light = ZColors.isLightTheme
    MaterialTheme(
        colorScheme = zcColorScheme(light),
        typography = ZcTypography,
        content = content,
    )
}
