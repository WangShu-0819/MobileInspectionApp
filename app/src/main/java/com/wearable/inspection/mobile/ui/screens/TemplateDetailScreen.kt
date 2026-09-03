package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.DividerColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模板详情页 — 只读展示
 *
 * 显示模板名称、零件、图片路径、ROI 数量、创建/更新时间。
 * 后续可扩展为 ROI 编辑器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailScreen(
    templateId: String,
    onBack: () -> Unit,
    onRecapture: (String, String) -> Unit = { _, _ -> },
    onEditRoi: (String) -> Unit = {},
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }

    var template by remember { mutableStateOf<InspectionTemplateEntity?>(null) }
    var partViews by remember { mutableStateOf<List<InspectionTemplateEntity>>(emptyList()) }
    var rois by remember { mutableStateOf<List<RoiDefinitionEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(templateId) {
        val loadedTemplate = repository.getTemplate(templateId)
        template = loadedTemplate
        partViews = loadedTemplate?.let { repository.getTemplatesByPart(it.partId) } ?: emptyList()
        rois = repository.getRois(templateId)
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "模板详情",
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        containerColor = customColors.pageBackground
    ) { paddingValues ->
        if (!loaded) {
            // Loading
            return@Scaffold
        }

        val t = template
        if (t == null) {
            // Template not found
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "模板不存在",
                    style = MaterialTheme.typography.bodyLarge,
                    color = PlaceholderColor
                )
            }
            return@Scaffold
        }

        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 基本信息
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DetailRow("视角名称", t.name)
                        DetailRow("零件 ID", t.partId)
                        DetailRow("视角", "${t.displayOrder + 1} / ${partViews.size}")
                        DetailRow("状态", if (t.enabled) "启用" else "已停用")
                        DetailRow("创建时间", dateFormat.format(Date(t.createdAt)))
                        DetailRow("更新时间", dateFormat.format(Date(t.updatedAt)))
                    }
                }
            }

            // 模板图片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "参考图片",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Button(
                                onClick = { onRecapture(t.partId, t.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Primary,
                                    contentColor = androidx.compose.ui.graphics.Color.White,
                                ),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重拍", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            text = t.mainImagePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // ROI 列表
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "ROI 区域 (${rois.size})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Button(
                                onClick = { onEditRoi(t.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Primary,
                                    contentColor = androidx.compose.ui.graphics.Color.White,
                                ),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("编辑 ROI", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (rois.isEmpty()) {
                            Text(
                                text = "暂无 ROI 配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = PlaceholderColor
                            )
                        } else {
                            rois.forEachIndexed { index, roi ->
                                if (index > 0) {
                                    Divider(
                                        color = DividerColor,
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                                DetailRow("${index + 1}. ${roi.name}", roi.inspectionType)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier,
        contentAlignment = contentAlignment
    ) {
        content()
    }
}
