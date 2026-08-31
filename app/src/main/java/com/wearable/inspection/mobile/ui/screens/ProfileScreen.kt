package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.PendingColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1

/**
 * 我的页面
 * 采用台账式纵向分组，不虚构账号或云同步
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenTemplateConfig: () -> Unit,
    onOpenPartManagement: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val customColors = LocalCustomColors.current

    // TODO: 从数据库获取实际统计数据
    val stats = TemplateStats(
        partCount = 3,
        templateCount = 5,
        roiCount = 12,
        incompleteItems = 2
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(customColors.pageBackground)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. 顶部品牌区
        item {
            BrandHeader()
        }

        // 2. 检测配置
        item {
            ProfileSection(
                title = "检测配置",
                items = listOf(
                    ProfileItem(
                        icon = Icons.Default.PhotoLibrary,
                        title = "模板配置",
                        subtitle = "${stats.partCount} 个零件 · ${stats.templateCount} 个模板 · ${stats.roiCount} 个 ROI",
                        badge = if (stats.incompleteItems > 0) "${stats.incompleteItems} 项待完善" else null,
                        badgeColor = PendingColor,
                        onClick = onOpenTemplateConfig
                    ),
                    ProfileItem(
                        icon = Icons.Default.CameraAlt,
                        title = "零件管理",
                        subtitle = "创建、编辑、删除零件",
                        onClick = onOpenPartManagement
                    ),
                    ProfileItem(
                        icon = Icons.Default.Settings,
                        title = "默认检测参数",
                        subtitle = "阈值及预处理配置",
                        onClick = { /* TODO */ }
                    )
                )
            )
        }

        // 3. 数据管理
        item {
            ProfileSection(
                title = "数据管理",
                items = listOf(
                    ProfileItem(
                        icon = Icons.Default.FolderOpen,
                        title = "模板包导入/导出",
                        subtitle = "备份与迁移",
                        onClick = { /* TODO */ }
                    ),
                    ProfileItem(
                        icon = Icons.Default.Description,
                        title = "结果包管理",
                        subtitle = "导出与清理",
                        onClick = { /* TODO */ }
                    ),
                    ProfileItem(
                        icon = Icons.Default.Storage,
                        title = "存储空间",
                        subtitle = "查看使用情况",
                        onClick = { /* TODO */ }
                    ),
                    ProfileItem(
                        icon = Icons.Default.CleaningServices,
                        title = "数据清理",
                        subtitle = "清除缓存与临时文件",
                        onClick = { /* TODO */ }
                    )
                )
            )
        }

        // 4. 应用设置
        item {
            ProfileSection(
                title = "应用设置",
                items = listOf(
                    ProfileItem(
                        icon = Icons.Default.CameraAlt,
                        title = "相机与图像质量",
                        subtitle = "拍照质量与对焦模式",
                        onClick = onOpenSettings
                    ),
                    ProfileItem(
                        icon = Icons.Default.NotificationImportant,
                        title = "提示音与振动",
                        subtitle = "检测反馈设置",
                        onClick = { /* TODO */ }
                    ),
                    ProfileItem(
                        icon = Icons.Default.Info,
                        title = "权限与诊断",
                        subtitle = "权限状态与版本信息",
                        onClick = { /* TODO */ }
                    )
                )
            )
        }

        // 5. 底部版本信息
        item {
            VersionInfo()
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BrandHeader() {
    val customColors = LocalCustomColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // App 图标
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }

        // 应用名称
        Text(
            text = "视觉质检",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            color = TextPrimary
        )

        // 副标题
        Text(
            text = "现场工作台 · 离线可用",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun ProfileSection(
    title: String,
    items: List<ProfileItem>
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    ProfileItemRow(item = item)
                    if (index < items.lastIndex) {
                        Divider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = DividerColor,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

data class ProfileItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val badge: String? = null,
    val badgeColor: Color = PendingColor,
    val onClick: () -> Unit
)

@Composable
private fun ProfileItemRow(item: ProfileItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { item.onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(24.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.badge != null) {
                Text(
                    text = item.badge,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = item.badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(item.badgeColor.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = DividerColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun VersionInfo() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "视觉质检 v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = PlaceholderColor,
            textAlign = TextAlign.Center
        )
        Text(
            text = "构建时间：2026-08-31",
            style = MaterialTheme.typography.bodySmall,
            color = PlaceholderColor,
            textAlign = TextAlign.Center
        )
        Text(
            text = "数据仅保存在本机",
            style = MaterialTheme.typography.bodySmall,
            color = PlaceholderColor,
            textAlign = TextAlign.Center
        )
    }
}

// 数据模型
data class TemplateStats(
    val partCount: Int,
    val templateCount: Int,
    val roiCount: Int,
    val incompleteItems: Int
)
