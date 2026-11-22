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

/** 主题语义色板：浅色沿用 Material 3 Mono（中对比度），深色还原最初尼康暗黑炭灰 + 尼康黄配色 */
object ZColors {
    /** Updated from the persisted theme setting; Compose tracks reads and redraws all palette users. */
    var isLightTheme by mutableStateOf(false)
    val NikonYellow get() = if (isLightTheme) Color(0xFF4A4459) else Color(0xFFFFE600)
    val YellowDim get() = if (isLightTheme) Color(0xFF48464D) else Color(0xFFB8A600)
    val OnAccent get() = if (isLightTheme) Color(0xFFFFFFFF) else Color(0xFF0A0B0C)
    val OnAccentMuted get() = if (isLightTheme) Color(0xFFE7DFF8) else Color(0xFF282400)
    val ButtonBg get() = if (isLightTheme) Color(0xFF4A4459) else Color(0xFFFFE600)
    val ButtonFg get() = if (isLightTheme) Color(0xFFFFFFFF) else Color(0xFF0A0B0C)
    val ButtonFgMuted get() = if (isLightTheme) Color(0xFFE7DFF8) else Color(0xFF282400)
    val ButtonBorder get() = if (isLightTheme) Color(0xFF4A4459) else Color(0xFFFFE600)
    val AccentContainer get() = if (isLightTheme) Color(0xFFE4E1EA) else Color(0xFF3A3300)
    val NavIndicator get() = if (isLightTheme) Color(0xFFE4E1EA) else Color(0xFF3A3300)
    val NavSelectedIcon get() = if (isLightTheme) Color(0xFF312F36) else Color(0xFFFFE600)
    val Bg get() = if (isLightTheme) Color(0xFFFAF8FE) else Color(0xFF0A0B0C)
    val Surface get() = if (isLightTheme) Color(0xFFEFEDF3) else Color(0xFF141517)
    val SurfaceLow get() = if (isLightTheme) Color(0xFFF4F3F8) else Color(0xFF101113)
    val SurfaceHigh get() = if (isLightTheme) Color(0xFFE9E7ED) else Color(0xFF1C1E20)
    val SurfaceHigher get() = if (isLightTheme) Color(0xFFE3E2E7) else Color(0xFF26282B)
    val Outline get() = if (isLightTheme) Color(0xFFA09CA8) else Color(0xFF303236)
    val TextPrimary get() = if (isLightTheme) Color(0xFF1C1B1F) else Color(0xFFF1F2F3)
    val TextSecondary get() = if (isLightTheme) Color(0xFF3D3A44) else Color(0xFFA4A7AC)
    val TextTertiary get() = if (isLightTheme) Color(0xFF605D68) else Color(0xFF767A80)
    val Danger get() = if (isLightTheme) Color(0xFFB3261E) else Color(0xFFFF5252)
    val Success get() = if (isLightTheme) Color(0xFF4A4459) else Color(0xFF35D39A)
    val Info get() = if (isLightTheme) Color(0xFF524346) else Color(0xFF60A8FF)
    val Mono get() = if (isLightTheme) Color(0xFF524346) else Color(0xFF9CDCFE)
}

private fun zcColorScheme(light: Boolean) = if (light) {
    lightColorScheme(
        primary = Color(0xFF4A4459),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE7DFF8),
        onPrimaryContainer = Color(0xFF332D41),
        secondary = Color(0xFF48464D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE4E1EA),
        onSecondaryContainer = Color(0xFF312F36),
        tertiary = Color(0xFF524346),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF0DEE2),
        onTertiaryContainer = Color(0xFF3A2D30),
        background = Color(0xFFFAF8FE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFAF8FE),
        onSurface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFFE9E7ED),
        onSurfaceVariant = Color(0xFF3D3A44),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F3F8),
        surfaceContainer = Color(0xFFEFEDF3),
        surfaceContainerHigh = Color(0xFFE9E7ED),
        surfaceContainerHighest = Color(0xFFE3E2E7),
        outline = Color(0xFF605D68),
        outlineVariant = Color(0xFFA09CA8),
        inverseSurface = Color(0xFF313034),
        inverseOnSurface = Color(0xFFF2F0F5),
        inversePrimary = Color(0xFFCAC3DC),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
    )
} else {
    darkColorScheme(
        primary = Color(0xFFFFE600),
        onPrimary = Color(0xFF0A0B0C),
        primaryContainer = Color(0xFF3A3300),
        onPrimaryContainer = Color(0xFFFFE600),
        secondary = Color(0xFFFFE600),
        onSecondary = Color(0xFF0A0B0C),
        secondaryContainer = Color(0xFF26282B),
        onSecondaryContainer = Color(0xFFF1F2F3),
        background = Color(0xFF0A0B0C),
        onBackground = Color(0xFFF1F2F3),
        surface = Color(0xFF141517),
        onSurface = Color(0xFFF1F2F3),
        surfaceVariant = Color(0xFF1C1E20),
        onSurfaceVariant = Color(0xFFA4A7AC),
        surfaceContainerLowest = Color(0xFF0A0B0C),
        surfaceContainerLow = Color(0xFF101113),
        surfaceContainer = Color(0xFF141517),
        surfaceContainerHigh = Color(0xFF1C1E20),
        surfaceContainerHighest = Color(0xFF26282B),
        outline = Color(0xFF303236),
        outlineVariant = Color(0xFF2A2A2A),
        error = Color(0xFFFF5252),
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
