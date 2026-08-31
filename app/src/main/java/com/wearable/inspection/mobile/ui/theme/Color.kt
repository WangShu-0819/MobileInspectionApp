package com.wearable.inspection.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================
// 明亮工业台账风格 - 颜色系统
// ============================================

// 页面背景
val PageBackground = Color(0xFFF3F7FA)

// 内容表面
val SurfaceWhite = Color(0xFFFFFFFF)

// 顶部浅色信息带
val InfoBannerBg = Color(0xFFE7F3F8)

// 主色/选中态
val Primary = Color(0xFF0F5B85)

// 深色强调
val PrimaryDark = Color(0xFF0A466B)

// 分隔线/描边
val DividerColor = Color(0xFFD8E3E9)

// 主文字
val TextPrimary = Color(0xFF202A33)

// 次级文字
val TextSecondary = Color(0xFF74808A)

// 状态颜色
val PassColor = Color(0xFF218657)          // 通过 - 绿色
val FailColor = Color(0xFFC44747)          // 不通过 - 红色
val PendingColor = Color(0xFFB7791F)       // 待复核 - 黄色

// 底部导航
val BottomNavBackground = Color(0xFFF8FAFB)
val BottomNavContent = Color(0xFF74808A)
val BottomNavInactive = Color(0xFF9BA5AD)
val BottomNavSelectedBg = Color(0xFFE7F3F8)

// 浅色背景变体
val BackgroundVariant1 = Color(0xFFF0F5F8)
val BackgroundVariant2 = Color(0xFFE0EBF0)


// 辅助颜色
val PlaceholderColor = Color(0xFFB0BEC5)
val DisabledColor = Color(0xFFCFD8DC)
val FocusBorder = Color(0xFF0F5B85)
val ErrorRed = Color(0xFFD32F2F)
val SuccessGreen = Color(0xFF388E3C)

// 旧颜色标记为废弃（保留以避免编译错误，新代码不应使用）
@Deprecated("使用明亮主题颜色", ReplaceWith("Primary"))
val Purple80 = Color(0xFFD0BCFF)

@Deprecated("使用明亮主题颜色", ReplaceWith("Primary"))
val PurpleGrey80 = Color(0xFFCCC2DC)

@Deprecated("使用明亮主题颜色", ReplaceWith("Primary"))
val Pink80 = Color(0xFFEFB8C8)

@Deprecated("使用明亮主题颜色", ReplaceWith("PrimaryDark"))
val Purple40 = Color(0xFF6650a4)

@Deprecated("使用明亮主题颜色", ReplaceWith("PrimaryDark"))
val PurpleGrey40 = Color(0xFF625b71)

@Deprecated("使用明亮主题颜色", ReplaceWith("PrimaryDark"))
val Pink40 = Color(0xFF7D5260)

@Deprecated("使用明亮主题颜色，改用 Primary")
val IndustrialBlue = Color(0xFF2196F3)

@Deprecated("使用明亮主题颜色，改用 PassColor")
val IndustrialGreen = Color(0xFF4CAF50)

@Deprecated("使用明亮主题颜色", ReplaceWith("PendingColor"))
val IndustrialOrange = Color(0xFFFF9800)

@Deprecated("使用明亮主题颜色，改用 FailColor")
val IndustrialRed = Color(0xFFF44336)
