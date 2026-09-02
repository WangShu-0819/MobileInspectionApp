package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.dpm.DpmDimensionMode
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor

/**
 * 应用设置页面
 *
 * 只展示已生效的真实设置，不展示未接入业务的开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val settings = remember { MobileInspectionApp.settings(context) }

    var dpmMode by remember { mutableStateOf(settings.dpmDimensionMode) }
    var dpmMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "应用设置",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = Primary
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        containerColor = customColors.pageBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. DPM 设置
            item {
                SettingsSection(
                    title = "DPM 扫码",
                    items = listOf(
                        SettingItem.InfoItem(
                            icon = Icons.Default.QrCode,
                            title = "网格重建尺寸",
                            subtitle = dpmMode.label,
                            onClick = { dpmMenuExpanded = true }
                        )
                    )
                )
            }

            // DPM 尺寸模式下拉菜单
            item {
                Box {
                    DropdownMenu(
                        expanded = dpmMenuExpanded,
                        onDismissRequest = { dpmMenuExpanded = false }
                    ) {
                        DpmDimensionMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = mode.label,
                                        fontWeight = if (mode == dpmMode) FontWeight.Bold else FontWeight.Normal,
                                        color = if (mode == dpmMode) Primary else TextPrimary
                                    )
                                },
                                onClick = {
                                    dpmMode = mode
                                    settings.dpmDimensionMode = mode
                                    dpmMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 2. 相机信息（只读）
            item {
                SettingsSection(
                    title = "相机",
                    items = listOf(
                        SettingItem.InfoItem(
                            icon = Icons.Default.CameraAlt,
                            title = "预览分辨率",
                            subtitle = "由设备自动协商",
                            onClick = { /* 只读 */ }
                        )
                    )
                )
            }

            // 3. 版本与诊断
            item {
                SettingsSection(
                    title = "版本与诊断",
                    items = listOf(
                        SettingItem.InfoItem(
                            icon = Icons.Default.Info,
                            title = "应用版本",
                            subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                            onClick = { /* 只读 */ }
                        ),
                        SettingItem.InfoItem(
                            icon = Icons.Default.Info,
                            title = "包名",
                            subtitle = context.packageName,
                            onClick = { /* 只读 */ }
                        )
                    )
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    items: List<SettingItem>
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
                    when (item) {
                        is SettingItem.InfoItem -> {
                            InfoSettingRow(item = item)
                        }
                    }
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

private sealed class SettingItem {
    data class InfoItem(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit
    ) : SettingItem()
}

@Composable
private fun InfoSettingRow(item: SettingItem.InfoItem) {
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
    }
}
