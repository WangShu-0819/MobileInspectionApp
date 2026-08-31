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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1

/**
 * 应用设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit
) {
    val customColors = LocalCustomColors.current

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
            // 1. 相机与图像质量
            item {
                SettingsSection(
                    title = "相机与图像质量",
                    items = listOf(
                        SettingItem.SwitchItem(
                            icon = Icons.Default.CameraAlt,
                            title = "拍照质量",
                            subtitle = "使用高质量图像（文件更大）",
                            checked = true,
                            onCheckedChange = { /* TODO */ }
                        ),
                        SettingItem.InfoItem(
                            icon = Icons.Default.Info,
                            title = "相机分辨率",
                            subtitle = "1920 × 1080",
                            onClick = { /* TODO */ }
                        )
                    )
                )
            }

            // 2. 提示音与振动
            item {
                SettingsSection(
                    title = "提示音与振动",
                    items = listOf(
                        SettingItem.SwitchItem(
                            icon = Icons.Default.NotificationImportant,
                            title = "提示音",
                            subtitle = "检测完成时播放提示音",
                            checked = true,
                            onCheckedChange = { /* TODO */ }
                        ),
                        SettingItem.SwitchItem(
                            icon = Icons.Default.Vibration,
                            title = "振动反馈",
                            subtitle = "检测完成时振动",
                            checked = true,
                            onCheckedChange = { /* TODO */ }
                        )
                    )
                )
            }

            // 3. 权限与诊断
            item {
                SettingsSection(
                    title = "权限与诊断",
                    items = listOf(
                        SettingItem.InfoItem(
                            icon = Icons.Default.CameraAlt,
                            title = "相机权限",
                            subtitle = "已授权",
                            onClick = { /* TODO */ }
                        ),
                        SettingItem.InfoItem(
                            icon = Icons.Default.Info,
                            title = "应用版本",
                            subtitle = "v1.0.0 (Build 2026-08-31)",
                            onClick = { /* TODO */ }
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
                        is SettingItem.SwitchItem -> {
                            SwitchSettingRow(item = item)
                        }
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

sealed class SettingItem {
    data class SwitchItem(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit
    ) : SettingItem()

    data class InfoItem(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit
    ) : SettingItem()
}

@Composable
private fun SwitchSettingRow(item: SettingItem.SwitchItem) {
    val checkedState = remember { mutableStateOf(item.checked) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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

        Switch(
            checked = checkedState.value,
            onCheckedChange = {
                checkedState.value = it
                item.onCheckedChange(it)
            },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedThumbColor = DividerColor,
                uncheckedTrackColor = BackgroundVariant1
            )
        )
    }
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

        Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = null,
            tint = DividerColor,
            modifier = Modifier.size(20.dp)
        )
    }
}
