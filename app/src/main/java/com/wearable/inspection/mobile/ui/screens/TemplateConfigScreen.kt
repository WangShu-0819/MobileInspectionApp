package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1

/**
 * 模板配置二级页
 * 入口层级：我的 -> 模板配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateConfigScreen(
    onTemplateClick: (Long) -> Unit
) {
    val customColors = LocalCustomColors.current

    // TODO: 从数据库获取零件和模板列表
    val templates = emptyList<TemplateItem>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "模板配置",
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
                    IconButton(onClick = { /* TODO: 返回 */ }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        containerColor = customColors.pageBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO: 新建模板 */ },
                containerColor = Primary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建模板",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (templates.isEmpty()) {
                item {
                    EmptyTemplatesState()
                }
            } else {
                // TODO: 实现模板列表
            }

            item {
                Spacer(modifier = Modifier.height(80.dp)) // FAB spacing
            }
        }
    }
}

@Composable
private fun EmptyTemplatesState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = PlaceholderColor,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "暂无检测模板",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Text(
                text = "点击下方按钮创建第一个模板",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor
            )
        }
    }
}

// 数据模型（临时）
data class TemplateItem(
    val id: Long,
    val partName: String,
    val templateName: String,
    val roiCount: Int,
    val updatedAt: String,
    val isEnabled: Boolean = true
)
