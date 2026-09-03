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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 我的页面 — MVP 简化版
 *
 * 结构：
 * 检测配置
 *   模板配置
 *   零件管理
 * 数据
 *   模板包
 *   检测结果
 * 设置
 *   应用设置
 * 关于
 *   版本信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenTemplateConfig: () -> Unit,
    onOpenPartManagement: () -> Unit,
    onOpenTemplatePackages: () -> Unit,
    onOpenResultManagement: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }

    // 从数据库获取真实统计
    var roiCount by remember { mutableIntStateOf(0) }
    var statsLoaded by remember { mutableStateOf(false) }
    val allTemplates by repository.observeAllTemplates().collectAsState(initial = emptyList())
    val configuredTemplates = remember(allTemplates) {
        allTemplates.filter { it.enabled }
    }
    val partCount = remember(configuredTemplates) {
        configuredTemplates.map { it.partId }.distinct().size
    }
    val templateCount = configuredTemplates.size

    LaunchedEffect(configuredTemplates.map { it.id }) {
        roiCount = withContext(Dispatchers.IO) {
            configuredTemplates.sumOf { repository.getRois(it.id).size }
        }
        statsLoaded = true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(customColors.pageBackground)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. 顶部标题
        item {
            ProfileHeader()
        }

        // 2. 检测配置
        item {
            ProfileSection(
                title = "检测配置",
                items = listOf(
                    ProfileItem(
                        icon = Icons.Default.PhotoLibrary,
                        title = "模板配置",
                        subtitle = if (statsLoaded) {
                            "$partCount 个零件 · $templateCount 个视角 · $roiCount 个 ROI"
                        } else {
                            "加载中…"
                        },
                        onClick = onOpenTemplateConfig
                    ),
                    ProfileItem(
                        icon = Icons.Default.Build,
                        title = "零件管理",
                        subtitle = "创建、编辑零件",
                        onClick = onOpenPartManagement
                    )
                )
            )
        }

        // 3. 数据
        item {
            ProfileSection(
                title = "数据",
                items = listOf(
                    ProfileItem(
                        icon = Icons.Default.FolderOpen,
                        title = "模板包",
                        subtitle = "导入模板数据",
                        onClick = onOpenTemplatePackages
                    ),
                    ProfileItem(
                        icon = Icons.Default.Description,
                        title = "检测结果",
                        subtitle = "查看与导出结果",
                        onClick = onOpenResultManagement
                    )
                )
            )
        }

        // 4. 设置
        item {
            ProfileSection(
                title = "设置",
                items = listOf(
                    ProfileItem(
                        icon = Icons.Default.Settings,
                        title = "应用设置",
                        subtitle = "相机、DPM、诊断",
                        onClick = onOpenSettings
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
private fun ProfileHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            color = TextPrimary
        )
        Text(
            text = "视觉质检 · 离线可用",
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

private data class ProfileItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
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

        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = null,
            tint = DividerColor,
            modifier = Modifier.size(20.dp)
        )
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
            text = "数据仅保存在本机",
            style = MaterialTheme.typography.bodySmall,
            color = PlaceholderColor,
            textAlign = TextAlign.Center
        )
    }
}
