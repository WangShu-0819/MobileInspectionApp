package com.wearable.inspection.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================
// 自定义颜色 Token（用于底部导航等特殊场景）
// ============================================

data class CustomColors(
    val primary: Color,
    val primaryDark: Color,
    val bottomNavBackground: Color,
    val bottomNavContent: Color,
    val bottomNavInactive: Color,
    val bottomNavSelectedBg: Color,
    val pageBackground: Color,
    val surfaceWhite: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val dividerColor: Color,
    val passColor: Color,
    val failColor: Color,
    val pendingColor: Color
)

val LocalCustomColors = staticCompositionLocalOf {
    CustomColors(
        primary = Primary,
        primaryDark = PrimaryDark,
        bottomNavBackground = BottomNavBackground,
        bottomNavContent = BottomNavContent,
        bottomNavInactive = BottomNavInactive,
        bottomNavSelectedBg = BottomNavSelectedBg,
        pageBackground = PageBackground,
        surfaceWhite = SurfaceWhite,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        dividerColor = DividerColor,
        passColor = PassColor,
        failColor = FailColor,
        pendingColor = PendingColor
    )
}

// ============================================
// 明亮主题 Color Scheme
// ============================================

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = InfoBannerBg,
    onPrimaryContainer = PrimaryDark,

    secondary = PassColor,
    onSecondary = Color.White,

    tertiary = PendingColor,
    onTertiary = Color.White,

    background = PageBackground,
    onBackground = TextPrimary,

    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = BackgroundVariant1,
    onSurfaceVariant = TextSecondary,

    error = FailColor,
    onError = Color.White,

    outline = DividerColor,
    outlineVariant = BackgroundVariant2
)

@Composable
fun MobileInspectionTheme(
    darkTheme: Boolean = false,  // 强制使用明亮主题
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme

    // 提供自定义颜色 Token
    val customColors = CustomColors(
        primary = Primary,
        primaryDark = PrimaryDark,
        bottomNavBackground = BottomNavBackground,
        bottomNavContent = BottomNavContent,
        bottomNavInactive = BottomNavInactive,
        bottomNavSelectedBg = BottomNavSelectedBg,
        pageBackground = PageBackground,
        surfaceWhite = SurfaceWhite,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        dividerColor = DividerColor,
        passColor = PassColor,
        failColor = FailColor,
        pendingColor = PendingColor
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            CompositionLocalProvider(LocalCustomColors provides customColors) {
                content()
            }
        }
    )
}
