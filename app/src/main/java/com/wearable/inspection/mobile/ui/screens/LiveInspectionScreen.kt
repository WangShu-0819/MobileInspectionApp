package com.wearable.inspection.mobile.ui.screens

import android.graphics.Rect
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.settings.PreviewDisplayMode
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModel
import com.wearable.inspection.mobile.ui.screens.workbench.createWorkbenchViewModelFactory
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.PendingColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import androidx.compose.ui.platform.LocalContext
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.camera.CameraController
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
    onOpenTemplates: () -> Unit,
    onDpmScan: () -> Unit = {},
    onStampOcr: () -> Unit = {},
    onNavigateToConfirm: (
        batchId: String,
        photoId: Long,
        photoPath: String,
        viewIndex: Int,
        templateId: String,
        templateName: String,
        partId: String,
        totalViews: Int
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val inspectionState by viewModel.inspectionState.collectAsState()
    val parts by viewModel.parts.collectAsState()
    val selectedPart by viewModel.selectedPart.collectAsState()
    val settings = remember { MobileInspectionApp.settings(context) }
    val previewScaleType = when (settings.previewDisplayMode) {
        PreviewDisplayMode.FILL -> PreviewView.ScaleType.FILL_CENTER
        PreviewDisplayMode.ORIGINAL -> PreviewView.ScaleType.FIT_CENTER
    }
    val customColors = LocalCustomColors.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { MobileInspectionApp.repository(context) }

    // 相机会话
    var sessionId by remember { mutableStateOf<String?>(null) }
    var contentRect by remember { mutableStateOf<Rect?>(null) }

    // 模板叠加控制
    var overlayAlpha by remember { mutableStateOf(0.45f) }
    var templateVisible by remember { mutableStateOf(true) }

    // 拍照状态
    var captureState by remember { mutableStateOf(CaptureUiState.IDLE) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    // 采集批次
    var currentBatchId by remember { mutableStateOf<String?>(null) }

    // 模板选择 bottom sheet
    var showTemplateSheet by remember { mutableStateOf(false) }
    var showPartMenu by remember { mutableStateOf(false) }

    // CameraController 和 ImageStore
    val cameraController = remember { CameraController.getInstance(context) }
    val imageStore = remember { MobileImageStore(context) }

    // CameraState
    val cameraState by cameraController.cameraStateFlow.collectAsState()

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
                // 确保有采集批次
                val batchId = currentBatchId ?: run {
                    val newBatchId = "batch_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                    val part = selectedPart
                    repository.insertCaptureBatch(
                        com.wearable.inspection.mobile.data.entity.CaptureBatchEntity(
                            batchId = newBatchId,
                            partId = part?.id,
                            partName = part?.name,
                            startTime = System.currentTimeMillis(),
                            viewCount = inspectionState.totalViews
                        )
                    )
                    currentBatchId = newBatchId
                    newBatchId
                }

                val tempFile = imageStore.generateTempFile()
                val result = cameraController.takePhoto(currentSessionId, tempFile)
                result.fold(
                    onSuccess = { file ->
                        val storeResult = imageStore.storeCapturedImage(file)
                        if (storeResult != null) {
                            savedPath = storeResult.finalPath

                            // 记录照片到批次
                            val template = inspectionState.selectedTemplate
                            val capturedPhoto = com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity(
                                batchId = batchId,
                                filePath = storeResult.finalPath,
                                viewIndex = inspectionState.currentViewIndex,
                                templateId = template?.id,
                                templateName = template?.name,
                                capturedAt = storeResult.capturedAt
                            )
                            repository.insertCapturedPhoto(capturedPhoto)

                            captureState = CaptureUiState.SAVED

                            // 导航到人工确认页面
                            val tpl = template
                            val part = selectedPart
                            if (tpl != null && part != null) {
                                onNavigateToConfirm(
                                    batchId,
                                    capturedPhoto.photoId,
                                    storeResult.finalPath,
                                    inspectionState.currentViewIndex,
                                    tpl.id,
                                    tpl.name,
                                    part.id,
                                    inspectionState.totalViews
                                )
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

    // 重置采集批次（切换零件或手动重置时）
    val onResetBatch: () -> Unit = {
        currentBatchId = null
        onResetCapture()
    }

    // 切换零件时重置批次
    LaunchedEffect(selectedPart?.id) {
        currentBatchId = null
    }

    val captureEnabled = sessionId != null &&
        cameraState == CameraStateType.OPEN &&
        inspectionState.isTemplateReady &&
        captureState == CaptureUiState.IDLE &&
        !inspectionState.allViewsCaptured

    val captureLabel = when {
        inspectionState.allViewsCaptured -> "已完成"
        sessionId == null -> "相机未就绪"
        cameraState != CameraStateType.OPEN -> "相机初始化中"
        captureState == CaptureUiState.CAPTURING -> "拍摄中…"
        !inspectionState.isTemplateReady -> "请先配置模板"
        else -> "拍照"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "现场采集",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (parts.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            PartSelectionBar(
                                parts = parts,
                                selectedPart = selectedPart,
                                expanded = showPartMenu,
                                onExpandedChange = { showPartMenu = it },
                                onSelect = { partId ->
                                    viewModel.selectPart(partId)
                                    showPartMenu = false
                                    contentRect = null
                                    onResetCapture()
                                },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                    actionIconContentColor = Primary
                ),
                actions = {
                    // 扫一扫：手机相机实时 DPM 扫码入口
                    androidx.compose.material3.IconButton(onClick = onDpmScan) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "扫一扫",
                            tint = Primary
                        )
                    }
                    // OCR 钢印：钢印识别入口
                    androidx.compose.material3.IconButton(onClick = onStampOcr) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.TextFields,
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
            // 上方实时预览；下方参考图需要足够空间，避免被压缩成缩略图
            CameraPreviewSection(
                modifier = Modifier.weight(0.40f),
                template = inspectionState.selectedTemplate,
                rois = inspectionState.rois,
                previewScaleType = previewScaleType,
                overlayAlpha = if (templateVisible) overlayAlpha else 0f,
                contentRect = contentRect,
                onFrameInfo = { info -> contentRect = info.contentRect },
                onSessionReady = { id ->
                    contentRect = null
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

            // 唯一主操作：固定在预览下方，不覆盖实时画面
            CaptureActionBar(
                enabled = captureEnabled,
                label = captureLabel,
                onCapture = onCapture,
                modifier = Modifier.fillMaxWidth()
            )

            // 模板叠加控制栏
            TemplateOverlayControls(
                alpha = overlayAlpha,
                visible = templateVisible,
                onAlphaChange = { overlayAlpha = it },
                onToggleVisibility = { templateVisible = !templateVisible },
                modifier = Modifier.fillMaxWidth(),
            )

            // 下方模板参考 + 信息
            TemplateReferenceSection(
                modifier = Modifier.weight(0.60f),
                template = inspectionState.selectedTemplate,
                templates = inspectionState.templates,
                viewIndex = inspectionState.currentViewIndex,
                totalViews = inspectionState.totalViews,
                allViewsCaptured = inspectionState.allViewsCaptured,
                captureState = captureState,
                captureError = captureError,
                onTemplateMissing = onOpenTemplates,
                onRetry = onResetCapture,
                onShowTemplateSheet = { showTemplateSheet = true },
                onResetViews = {
                    viewModel.resetViewIndex()
                    onResetCapture()
                }
            )

            // 模板选择 Bottom Sheet
            if (showTemplateSheet) {
                TemplatePickerSheet(
                    templates = inspectionState.templates,
                    selectedTemplate = inspectionState.selectedTemplate,
                    onSelect = { templateId ->
                        viewModel.selectTemplate(templateId)
                        showTemplateSheet = false
                    },
                    onDismiss = { showTemplateSheet = false }
                )
            }
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
    previewScaleType: PreviewView.ScaleType = PreviewView.ScaleType.FIT_CENTER,
    overlayAlpha: Float = 0f,
    contentRect: Rect? = null,
    onFrameInfo: (FrameInfo) -> Unit = {},
    onSessionReady: (String?) -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 真实 CameraX 实时预览 + 模板叠加
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            templateImagePath = template?.mainImagePath,
            previewScaleType = previewScaleType,
            overlayAlpha = overlayAlpha,
            onCameraReady = {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("LiveInspection", "Camera ready")
                }
            },
            onCameraError = { error ->
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
                onSessionReady(null)
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("LiveInspection", "Camera permission permanently denied")
                }
            },
            onSessionReady = onSessionReady,
            onFrameInfo = onFrameInfo,
        )

        // 轮廓和 ROI 叠加层
        OverlayGraphics(
            template = template,
            rois = rois,
            contentRect = contentRect,
        )

    }
}

/**
 * 主拍照操作栏
 *
 * 使用固定高度和文字标签，保证按钮在小屏幕和单手操作时可见、可理解。
 */
@Composable
private fun CaptureActionBar(
    enabled: Boolean,
    label: String,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onCapture,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = Color.White,
                disabledContainerColor = DividerColor,
                disabledContentColor = TextSecondary
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 当前检测零件选择；选择零件后自动加载该零件的全部有序视角。 */
@Composable
private fun PartSelectionBar(
    parts: List<PartEntity>,
    selectedPart: PartEntity?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BackgroundVariant1)
                .clickable { onExpandedChange(true) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedPart?.name ?: "选择零件",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "选择零件",
                tint = Primary,
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            parts.forEach { part ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = part.name,
                            color = if (part.id == selectedPart?.id) Primary else TextPrimary,
                            fontWeight = if (part.id == selectedPart?.id) {
                                FontWeight.Medium
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    onClick = { onSelect(part.id) },
                )
            }
        }
    }
}

/**
 * 轮廓和 ROI 叠加层
 */
@Composable
private fun OverlayGraphics(
    template: InspectionTemplateEntity?,
    rois: List<RoiDefinitionEntity>,
    contentRect: Rect?,
) {
    if (template == null || contentRect == null || rois.isEmpty()) return

    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val imageLeft = contentRect.left.toFloat()
        val imageTop = contentRect.top.toFloat()
        val imageRight = contentRect.right.toFloat()
        val imageBottom = contentRect.bottom.toFloat()
        rois.filter { it.shapeType.equals("RECT", ignoreCase = true) }.forEach { roi ->
            val mappedRect = parseNormalizedRect(roi.normalizedRect)
                ?.let {
                    mapNormalizedRectToContentRect(
                        it,
                        ContentRectBounds(
                            left = contentRect.left,
                            top = contentRect.top,
                            right = contentRect.right,
                            bottom = contentRect.bottom,
                        )
                    )
                }
                ?: return@forEach

            val left = mappedRect.left.coerceIn(imageLeft, imageRight)
            val top = mappedRect.top.coerceIn(imageTop, imageBottom)
            val right = mappedRect.right.coerceIn(imageLeft, imageRight)
            val bottom = mappedRect.bottom.coerceIn(imageTop, imageBottom)
            if (right > left && bottom > top) {
                drawRect(
                    color = Primary,
                    style = Stroke(width = 2.dp.toPx()),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                )
            }
        }
    }
}

/**
 * 解析归一化矩形；无效数据不产生默认 ROI，避免把配置错误伪装成检测区域。
 */
internal fun parseNormalizedRect(json: String): NormalizedRect? = runCatching {
    val obj = org.json.JSONObject(json)
    val rect = NormalizedRect(
        left = obj.getDouble("left").toFloat(),
        top = obj.getDouble("top").toFloat(),
        right = obj.getDouble("right").toFloat(),
        bottom = obj.getDouble("bottom").toFloat(),
    )
    val values = listOf(rect.left, rect.top, rect.right, rect.bottom)
    if (values.any { !it.isFinite() || it !in 0f..1f }) null
    else if (rect.left >= rect.right || rect.top >= rect.bottom) null
    else rect
}.getOrNull()

internal fun mapNormalizedRectToContentRect(
    rect: NormalizedRect,
    contentRect: ContentRectBounds,
): OverlayRect? {
    val width = contentRect.right - contentRect.left
    val height = contentRect.bottom - contentRect.top
    if (width <= 0 || height <= 0) return null
    return OverlayRect(
        contentRect.left + rect.left * width,
        contentRect.top + rect.top * height,
        contentRect.left + rect.right * width,
        contentRect.top + rect.bottom * height,
    )
}

internal data class OverlayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 下方模板参考区域
 */
@Composable
private fun TemplateReferenceSection(
    modifier: Modifier = Modifier,
    template: InspectionTemplateEntity?,
    templates: List<InspectionTemplateEntity> = emptyList(),
    viewIndex: Int = 0,
    totalViews: Int = 0,
    allViewsCaptured: Boolean = false,
    captureState: CaptureUiState = CaptureUiState.IDLE,
    captureError: String? = null,
    onTemplateMissing: () -> Unit = {},
    onRetry: () -> Unit = {},
    onShowTemplateSheet: () -> Unit = {},
    onResetViews: () -> Unit = {}
) {
    var referenceFill by remember(template?.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 只保留当前视角和切换入口，不再用大标题占用参考图空间
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (totalViews > 0) {
                    Text(
                        text = "视角 ${viewIndex + 1}/$totalViews",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (template != null) {
                    IconButton(
                        onClick = { referenceFill = !referenceFill },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = if (referenceFill) "显示原比例" else "撑满参考图",
                            tint = Primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // 当前视角切换器；一个零件下的全部视角仍会按顺序采集
                if (templates.size > 1 && template != null) {
                    TemplateSelector(
                        currentTemplate = template,
                        onClick = onShowTemplateSheet
                    )
                }
            }
        }

        // 全部完成提示
        if (allViewsCaptured) {
            AllViewsCapturedCard(onReset = onResetViews)
        }
        // 模板内容或空状态
        else if (template == null) {
            TemplateEmptyState(
                hasTemplates = templates.isNotEmpty(),
                onGoToConfig = onTemplateMissing
            )
        } else {
            TemplateContent(
                modifier = Modifier.weight(1f),
                template = template,
                fillImage = referenceFill,
            )
        }

        // 拍照状态提示（固定最小高度，避免状态切换时模板参考图上移）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            when (captureState) {
                CaptureUiState.CAPTURING -> {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "拍摄中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                CaptureUiState.SAVED -> {
                    if (!allViewsCaptured) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = PassColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "已保存，切换下一视角",
                                style = MaterialTheme.typography.bodySmall,
                                color = PassColor
                            )
                        }
                    }
                }
                CaptureUiState.ERROR -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = captureError ?: "拍照失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = FailColor
                        )
                        TextButton(onClick = onRetry, modifier = Modifier.height(24.dp)) {
                            Text("重试", color = Primary, fontSize = 12.sp)
                        }
                    }
                }
                CaptureUiState.IDLE -> { /* 不显示额外内容 */ }
            }
        }

    }
}

/**
 * 当前视角切换器
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
            .heightIn(min = 28.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = currentTemplate.name,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        androidx.compose.material3.Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(14.dp)
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
    modifier: Modifier = Modifier,
    template: InspectionTemplateEntity,
    fillImage: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 全宽参考图：保留完整画面，便于与实时预览对照
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 210.dp)
                .clip(RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = remember(template.mainImagePath) {
                    try {
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = 2 // 保留更高分辨率，适配放大的参考图
                        }
                        android.graphics.BitmapFactory.decodeFile(template.mainImagePath, opts)
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "模板参考图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = if (fillImage) ContentScale.Crop else ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = "模板参考图",
                        tint = PlaceholderColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // 视角名称
        Text(
            text = template.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 所有视角采集完成提示
 */
@Composable
private fun AllViewsCapturedCard(onReset: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BackgroundVariant1),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = PassColor,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "零件采集完成",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
                Text(
                    text = "所有视角已拍摄完毕",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
            }
            TextButton(onClick = onReset, modifier = Modifier.height(28.dp)) {
                Text("重新开始", color = Primary, fontSize = 12.sp)
            }
        }
    }
}

/**
 * 模板选择 Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePickerSheet(
    templates: List<InspectionTemplateEntity>,
    selectedTemplate: InspectionTemplateEntity?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "切换视角",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            templates.forEachIndexed { index, tpl ->
                val isSelected = tpl.id == selectedTemplate?.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(tpl.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) BackgroundVariant1 else SurfaceWhite
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "视角 ${index + 1}：${tpl.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = PassColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
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
 * 模板叠加控制栏：透明度 Slider + 显示/隐藏切换
 *
 * 范围 0.0f ~ 0.8f，默认 0.45f。
 * 调节不触发 CameraX rebind。
 */
@Composable
private fun TemplateOverlayControls(
    alpha: Float,
    visible: Boolean,
    onAlphaChange: (Float) -> Unit,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(BackgroundVariant1)
            .heightIn(min = 36.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 显示/隐藏按钮
        IconButton(onClick = onToggleVisibility, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (visible) "隐藏模板" else "显示模板",
                tint = if (visible) Primary else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        // 透明度标签
        Column(
            modifier = Modifier.width(48.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = "透明度",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                fontSize = 10.sp,
            )
            Text(
                text = "${(alpha * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Primary,
                maxLines = 1,
            )
        }

        // Slider
        Slider(
            value = alpha,
            onValueChange = onAlphaChange,
            valueRange = 0f..0.8f,
            modifier = Modifier.weight(1f).height(24.dp),
            enabled = visible,
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary,
                inactiveTrackColor = DividerColor,
                disabledThumbColor = TextSecondary,
                disabledActiveTrackColor = DividerColor,
                disabledInactiveTrackColor = DividerColor,
            ),
        )
    }
}


// NormalizedRect 已移至 RoiEditorViewModel.kt
