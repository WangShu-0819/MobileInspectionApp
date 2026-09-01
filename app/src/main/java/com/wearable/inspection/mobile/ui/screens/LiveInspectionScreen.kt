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
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraStateType
import com.wearable.inspection.mobile.data.image.MobileImageStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 拍照 UI 状态
 */
enum class CaptureUiState {
    IDLE,       // 空闲，可拍照
    CAPTURING,  // 拍摄中，禁用按钮
    SAVED,      // 已保存，短暂显示后回到 IDLE
    ERROR       // 出错，显示错误信息，可重试
}

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
    val context = LocalContext.current
    val inspectionState by viewModel.inspectionState.collectAsState()
    val customColors = LocalCustomColors.current
    val coroutineScope = rememberCoroutineScope()

    // 相机会话
    var sessionId by remember { mutableStateOf<String?>(null) }

    // 拍照状态
    var captureState by remember { mutableStateOf(CaptureUiState.IDLE) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    // CameraController 和 ImageStore
    val cameraController = remember { CameraController.getInstance(context) }
    val imageStore = remember { MobileImageStore(context) }

    // CameraState
    val cameraState by cameraController.cameraStateFlow.collectAsState()

    // 拍照确认对话框状态
    var showCaptureConfirm by remember { mutableStateOf(false) }

    // 拍照函数
    val onCapture: () -> Unit = {
        val currentSessionId = sessionId
        if (currentSessionId == null) {
            captureError = "相机未就绪"
            captureState = CaptureUiState.ERROR
        } else if (captureState == CaptureUiState.CAPTURING) {
            // 已在拍摄中，忽略
        } else {
            captureState = CaptureUiState.CAPTURING
            captureError = null
            savedPath = null

            coroutineScope.launch {
                val tempFile = imageStore.generateTempFile()
                val result = cameraController.takePhoto(currentSessionId, tempFile)
                result.fold(
                    onSuccess = { file ->
                        val storeResult = imageStore.storeCapturedImage(file)
                        if (storeResult != null) {
                            savedPath = storeResult.finalPath
                            captureState = CaptureUiState.SAVED
                            // 2秒后回到 IDLE
                            delay(2000)
                            if (captureState == CaptureUiState.SAVED) {
                                captureState = CaptureUiState.IDLE
                            }
                        } else {
                            imageStore.deleteTempFile(file)
                            captureError = "图片保存失败"
                            captureState = CaptureUiState.ERROR
                        }
                    },
                    onFailure = { error ->
                        imageStore.deleteTempFile(tempFile)
                        captureError = "拍照失败"
                        captureState = CaptureUiState.ERROR
                        if (BuildConfig.DEBUG) {
                            android.util.Log.e("LiveInspection", "Capture failed", error)
                        }
                    }
                )
            }
        }
    }

    // 重置拍照状态
    val onResetCapture: () -> Unit = {
        captureState = CaptureUiState.IDLE
        captureError = null
        savedPath = null
    }

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
                    // 扫一扫：手机相机实时 DPM 扫码入口
                    androidx.compose.material3.IconButton(onClick = { /* TODO: 扫一扫 DPM 扫码 */ }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "扫一扫",
                            tint = Primary
                        )
                    }
                    // OCR 钢印
                    androidx.compose.material3.IconButton(onClick = { /* TODO: OCR 钢印 */ }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = "OCR 钢印",
                            tint = Primary
                        )
                    }
                }
            )
        },
        containerColor = customColors.pageBackground
    ) { paddingValues ->
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
                isReady = inspectionState.isTemplateReady,
                onSessionReady = { id ->
                    sessionId = id
                    if (id == null) {
                        captureState = CaptureUiState.ERROR
                        captureError = "相机连接失败"
                    } else {
                        // 新会话就绪，重置拍照状态
                        onResetCapture()
                    }
                }
            )

            // 下方 40%：模板参考 + 信息
            TemplateReferenceSection(
                modifier = Modifier.weight(0.4f),
                template = inspectionState.selectedTemplate,
                rois = inspectionState.rois,
                isReady = inspectionState.isTemplateReady,
                captureState = captureState,
                captureError = captureError,
                cameraState = cameraState,
                sessionId = sessionId,
                onTemplateMissing = onOpenTemplates,
                onCapture = onCapture,
                onRetry = onResetCapture
            )
        }
    }
}

/**
 * 上方实时预览区域
 *
 * 直接显示真实 CameraX 预览，首屏即可见实时画面，无需进入二级页面。
 */
@Composable
private fun CameraPreviewSection(
    modifier: Modifier = Modifier,
    template: InspectionTemplateEntity?,
    rois: List<RoiDefinitionEntity>,
    isReady: Boolean,
    onSessionReady: (String?) -> Unit = {}
) {
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<CameraError?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 真实 CameraX 实时预览
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onCameraReady = {
                cameraReady = true
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("LiveInspection", "Camera ready")
                }
            },
            onCameraError = { error ->
                cameraError = error
                onSessionReady(null)
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("LiveInspection", "Camera error: ${error.message}")
                }
            },
            onPermissionDenied = {
                onSessionReady(null)
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("LiveInspection", "Camera permission denied")
                }
            },
            onPermissionPermanentlyDenied = {
                cameraError = CameraError.PermissionPermanentlyDenied
                onSessionReady(null)
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("LiveInspection", "Camera permission permanently denied")
                }
            },
            onSessionReady = onSessionReady
        )

        // 轮廓和 ROI 叠加层
        OverlayGraphics(
            template = template,
            rois = rois,
            isReady = isReady
        )
    }
}

/**
 * 轮廓和 ROI 叠加层
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
    captureState: CaptureUiState = CaptureUiState.IDLE,
    captureError: String? = null,
    cameraState: CameraStateType? = null,
    sessionId: String? = null,
    onTemplateMissing: () -> Unit = {},
    onCapture: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    // 快门是否可用
    val canCapture = sessionId != null &&
        cameraState == CameraStateType.OPEN &&
        captureState == CaptureUiState.IDLE

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

        // 拍照状态提示
        when (captureState) {
            CaptureUiState.CAPTURING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "拍摄中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
            CaptureUiState.SAVED -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PassColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "原图已保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PassColor
                    )
                }
            }
            CaptureUiState.ERROR -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = captureError ?: "拍照失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FailColor
                    )
                    TextButton(onClick = onRetry) {
                        Text("重试", color = Primary)
                    }
                }
            }
            CaptureUiState.IDLE -> { /* 不显示额外内容 */ }
        }

        // 拍照按钮
        Button(
            onClick = onCapture,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = canCapture,
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
                text = when {
                    sessionId == null -> "相机未就绪"
                    cameraState != CameraStateType.OPEN -> "相机初始化中"
                    captureState == CaptureUiState.CAPTURING -> "拍摄中..."
                    else -> "拍照"
                },
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