package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.R
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModel
import com.wearable.inspection.mobile.ui.screens.workbench.createWorkbenchViewModelFactory
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PendingColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 现场采集页（上实时 + 下模板）
 * 上方 55-65%：CameraX 实时预览 + 轮廓/ROI 叠加
 * 下方 35-45%：模板参考图 + 检测信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveInspectionScreen(
    viewModel: WorkbenchViewModel = viewModel(
        factory = createWorkbenchViewModelFactory(LocalContext.current.applicationContext as android.app.Application)
    ),
    onStartInspection: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    onViewRecord: (Long) -> Unit
) {
    val inspectionState by viewModel.inspectionState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val customColors = LocalCustomColors.current

    // 拍照确认对话框状态
    var showCaptureConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "现场采集",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                    actionIconContentColor = Primary
                ),
                actions = {
                    // DPM 扫码
                    androidx.compose.material3.IconButton(onClick = { /* TODO: DPM 扫码 */ }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "DPM 扫码",
                            tint = Primary
                        )
                    }
                    // 钢印 OCR
                    androidx.compose.material3.IconButton(onClick = { /* TODO: OCR */ }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = "钢印 OCR",
                            tint = Primary
                        )
                    }
                }
            )
        },
        containerColor = customColors.pageBackground
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Primary)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // 上方 60%：实时预览 + 轮廓叠加
                CameraPreviewSection(
                    modifier = Modifier.weight(0.6f),
                    template = inspectionState.selectedTemplate,
                    rois = inspectionState.rois,
                    isReady = inspectionState.isTemplateReady
                )

                // 下方 40%：模板参考 + 信息
                TemplateReferenceSection(
                    modifier = Modifier.weight(0.4f),
                    template = inspectionState.selectedTemplate,
                    rois = inspectionState.rois,
                    isReady = inspectionState.isTemplateReady,
                    onTemplateMissing = onOpenTemplates
                )
            }
        }
    }

    // 拍照确认对话框
    if (showCaptureConfirm) {
        ModalBottomSheet(
            onDismissRequest = { showCaptureConfirm = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = PendingColor,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "当前模板未对齐",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "检测到当前零件与模板存在偏差，继续拍摄可能导致检测结果不准确。是否仍要继续？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCaptureConfirm = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceWhite,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            showCaptureConfirm = false
                            // TODO: 执行拍照并保存 alignmentOverride=true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("仍要继续")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 上方实时预览区域
 */
@Composable
private fun CameraPreviewSection(
    modifier: Modifier = Modifier,
    template: InspectionTemplateEntity?,
    rois: List<RoiDefinitionEntity>,
    isReady: Boolean
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // CameraX 实时预览占位（阶段 B 实现）
        CameraPreviewPlaceholder()

        // 轮廓和 ROI 叠加层（阶段 B 实现）
        OverlayGraphics(
            template = template,
            rois = rois,
            isReady = isReady
        )
    }
}

/**
 * CameraX 预览占位
 */
@Composable
private fun CameraPreviewPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "相机预览（阶段 B 实现）",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 轮廓和 ROI 叠加层占位
 */
@Composable
private fun OverlayGraphics(
    template: InspectionTemplateEntity?,
    rois: List<RoiDefinitionEntity>,
    isReady: Boolean
) {
    if (template == null || !isReady) return

    // V1: 使用 Canvas 绘制轮廓和 ROI
    // 阶段 B 将实现实际的姿态估计和投影
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        // 绘制轮廓（简化：白色矩形轮廓）
        drawRect(
            color = Color.White,
            style = Stroke(width = 2.dp.toPx()),
            topLeft = androidx.compose.ui.geometry.Offset(50.dp.toPx(), 50.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(200.dp.toPx(), 300.dp.toPx())
        )

        // 绘制 ROI
        rois.forEach { roi ->
            try {
                val rect = parseNormalizedRect(roi.normalizedRect)
                drawRect(
                    color = PassColor,
                    style = Stroke(width = 2.dp.toPx()),
                    topLeft = androidx.compose.ui.geometry.Offset(
                        x = rect.left * size.width,
                        y = rect.top * size.height
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        width = (rect.right - rect.left) * size.width,
                        height = (rect.bottom - rect.top) * size.height
                    )
                )
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }

        // 对齐状态文字
        if (isReady) {
            drawContext.canvas.nativeCanvas.drawText(
                "已对齐，可拍摄",
                20.dp.toPx(),
                40.dp.toPx(),
                android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 16.dp.toPx()
                    isFakeBoldText = true
                }
            )
        }
    }
}

/**
 * 解析归一化矩形（简化版，使用正则提取）
 */
private fun parseNormalizedRect(json: String): NormalizedRect {
    val pattern = """"left"\s*:\s*([\d.]+).*"top"\s*:\s*([\d.]+).*"right"\s*:\s*([\d.]+).*"bottom"\s*:\s*([\d.]+)""".toRegex()
    val matchResult = pattern.find(json)
    return if (matchResult != null) {
        NormalizedRect(
            left = matchResult.groupValues[1].toFloatOrNull() ?: 0.1f,
            top = matchResult.groupValues[2].toFloatOrNull() ?: 0.1f,
            right = matchResult.groupValues[3].toFloatOrNull() ?: 0.9f,
            bottom = matchResult.groupValues[4].toFloatOrNull() ?: 0.9f
        )
    } else {
        NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
    }
}

/**
 * 下方模板参考区域
 */
@Composable
private fun TemplateReferenceSection(
    modifier: Modifier = Modifier,
    template: InspectionTemplateEntity?,
    rois: List<RoiDefinitionEntity>,
    isReady: Boolean,
    onTemplateMissing: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "模板参考",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            // 模板选择器
            if (template != null) {
                TemplateSelector(
                    currentTemplate = template,
                    onClick = { /* TODO: 打开模板选择器 */ }
                )
            }
        }

        // 模板内容或空状态
        if (template == null || !isReady) {
            TemplateEmptyState(
                hasTemplates = true, // TODO: 从 ViewModel 获取
                onGoToConfig = onTemplateMissing
            )
        } else {
            TemplateContent(
                template = template,
                rois = rois,
                isReady = isReady
            )
        }

        // 拍照按钮
        Button(
            onClick = {
                if (isReady) {
                    // TODO: 执行拍照
                } else {
                    // 显示确认对话框
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = true, // 始终可用，未对齐时显示确认
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = Color.White,
                disabledContainerColor = DividerColor,
                disabledContentColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isReady) "开始检测" else "强制拍照",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 模板选择器（只读）
 */
@Composable
private fun TemplateSelector(
    currentTemplate: InspectionTemplateEntity,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = currentTemplate.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        androidx.compose.material3.Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 模板空状态
 */
@Composable
private fun TemplateEmptyState(
    hasTemplates: Boolean,
    onGoToConfig: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = PendingColor,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = if (hasTemplates) "请选择模板" else "暂无模板",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary
        )
        Text(
            text = if (hasTemplates) {
                "当前零件未配置检测模板\n请在模板配置中添加"
            } else {
                "前往我的 > 模板配置创建第一个模板"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (!hasTemplates) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onGoToConfig,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Color.White
                )
            ) {
                Text("前往模板配置")
            }
        }
    }
}

/**
 * 模板内容显示
 */
@Composable
private fun TemplateContent(
    template: InspectionTemplateEntity,
    rois: List<RoiDefinitionEntity>,
    isReady: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 左侧：模板参考图
        Card(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = BackgroundVariant1),
            onClick = { /* TODO: 放大查看 */ }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Photo,
                    contentDescription = "模板参考图",
                    tint = PlaceholderColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 右侧：模板信息
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 模板名称和状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = "就绪状态",
                        tint = if (isReady) PassColor else FailColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isReady) "就绪" else "未就绪",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isReady) PassColor else FailColor
                    )
                }
            }

            // 统计信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoItem(
                    label = "ROI",
                    value = "${rois.size} 个"
                )
                InfoItem(
                    label = "轮廓",
                    value = if (template.outlineData != null) "已提取" else "未提取"
                )
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}


/**
 * 归一化矩形数据类
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)