package com.wearable.inspection.mobile.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraStateType
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.image.MobileImageStore
import com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModel
import com.wearable.inspection.mobile.ui.screens.workbench.createWorkbenchViewModelFactory
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.PendingColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "LiveInspection"

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
 * 现场采集页 — 相机优先布局
 *
 * 设计原则：
 * - CameraX 实时预览占满全屏（绝对主体）
 * - 最小化 TopAppBar（透明浮动）
 * - 紧凑 overlay chips：零件名 + 视角进度（左上）
 * - 圆形快门按钮（底部居中，≥64dp）
 * - 小缩略图模板参考（左下角）
 * - 紧凑叠加控制（右下角）
 * - 无状态文字遮挡相机视野
 */
@Composable
fun LiveInspectionScreen(
    viewModel: WorkbenchViewModel = viewModel(
        factory = createWorkbenchViewModelFactory(LocalContext.current.applicationContext as android.app.Application)
    ),
    onStartInspection: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    onViewRecord: (String) -> Unit,
    onDpmScan: () -> Unit = {},
    onStampOcr: () -> Unit = {},
) {
    val context = LocalContext.current
    val inspectionState by viewModel.inspectionState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 相机会话
    var sessionId by remember { mutableStateOf<String?>(null) }

    // 模板叠加控制
    var overlayAlpha by remember { mutableFloatStateOf(0.45f) }
    var templateVisible by remember { mutableStateOf(true) }

    // 拍照状态
    var captureState by remember { mutableStateOf(CaptureUiState.IDLE) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    // 模板选择 bottom sheet
    var showTemplateSheet by remember { mutableStateOf(false) }

    // CameraController 和 ImageStore
    val cameraController = remember { CameraController.getInstance(context) }
    val imageStore = remember { MobileImageStore(context) }

    // CameraState
    val cameraState by cameraController.cameraStateFlow.collectAsState()

    // 清理临时文件
    DisposableEffect(Unit) {
        onDispose {
            imageStore.cleanTempDir()
        }
    }

    // 调试日志：状态变化
    if (BuildConfig.DEBUG) {
        androidx.compose.runtime.SideEffect {
            android.util.Log.d(TAG, "State: sessionId=$sessionId, cameraState=$cameraState, captureState=$captureState, viewIndex=${inspectionState.currentViewIndex}/${inspectionState.totalViews}, allCaptured=${inspectionState.allViewsCaptured}")
        }
    }

    // 拍照函数
    val onCapture: () -> Unit = {
        val currentSessionId = sessionId
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onCapture clicked: sessionId=$currentSessionId, cameraState=$cameraState, captureState=$captureState")
        }

        if (currentSessionId == null) {
            captureError = "相机未就绪"
            captureState = CaptureUiState.ERROR
            if (BuildConfig.DEBUG) {
                android.util.Log.e(TAG, "onCapture: sessionId is null")
            }
        } else if (captureState == CaptureUiState.CAPTURING) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w(TAG, "onCapture: already capturing, ignoring")
            }
        } else {
            captureState = CaptureUiState.CAPTURING
            captureError = null
            savedPath = null

            coroutineScope.launch {
                val tempFile = imageStore.generateTempFile()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "takePhoto: sessionId=$currentSessionId, tempFile=${tempFile.absolutePath}")
                }

                val result = cameraController.takePhoto(currentSessionId, tempFile)
                result.fold(
                    onSuccess = { file ->
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(TAG, "takePhoto success: ${file.absolutePath}, size=${file.length()}")
                        }
                        val storeResult = imageStore.storeCapturedImage(file)
                        if (storeResult != null) {
                            savedPath = storeResult.finalPath
                            captureState = CaptureUiState.SAVED
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(TAG, "Image stored: ${storeResult.finalPath}, ${storeResult.width}x${storeResult.height}")
                            }
                            // 推进到下一视角
                            val hasNext = viewModel.advanceToNextView()
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(TAG, "advanceToNextView: hasNext=$hasNext")
                            }
                            if (!hasNext) {
                                // 所有视角完成，保持 SAVED 状态
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d(TAG, "All views captured!")
                                }
                            } else {
                                // 短暂显示后回到 IDLE（下一视角已切换）
                                delay(800)
                                if (captureState == CaptureUiState.SAVED) {
                                    captureState = CaptureUiState.IDLE
                                }
                            }
                        } else {
                            imageStore.deleteTempFile(file)
                            captureError = "图片保存失败"
                            captureState = CaptureUiState.ERROR
                            if (BuildConfig.DEBUG) {
                                android.util.Log.e(TAG, "storeCapturedImage returned null")
                            }
                        }
                    },
                    onFailure = { error ->
                        imageStore.deleteTempFile(tempFile)
                        captureError = "拍照失败: ${error.message}"
                        captureState = CaptureUiState.ERROR
                        if (BuildConfig.DEBUG) {
                            android.util.Log.e(TAG, "takePhoto failed", error)
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

    // 全部完成时的重置
    val onResetAllViews: () -> Unit = {
        viewModel.resetViewIndex()
        onResetCapture()
    }

    // 全屏相机预览（绝对主体）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // CameraX 实时预览 + 模板叠加
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            templateImagePath = inspectionState.selectedTemplate?.mainImagePath,
            overlayAlpha = if (templateVisible) overlayAlpha else 0f,
            onCameraReady = {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "Camera ready")
                }
            },
            onCameraError = { error ->
                sessionId = null
                captureState = CaptureUiState.ERROR
                captureError = "相机错误: ${error.message}"
                if (BuildConfig.DEBUG) {
                    android.util.Log.e(TAG, "Camera error: ${error.message}")
                }
            },
            onPermissionDenied = {
                sessionId = null
                captureState = CaptureUiState.ERROR
                captureError = "相机权限被拒绝"
            },
            onPermissionPermanentlyDenied = {
                sessionId = null
                captureState = CaptureUiState.ERROR
                captureError = "相机权限被永久拒绝，请在设置中开启"
            },
            onSessionReady = { id ->
                sessionId = id
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "Session ready: $id")
                }
                if (id == null) {
                    captureState = CaptureUiState.ERROR
                    captureError = "相机连接失败"
                } else {
                    onResetCapture()
                }
            }
        )

        // ─── 顶部浮动栏（透明） ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：返回/标题（保留空间，但不显示 TopAppBar）
            Spacer(modifier = Modifier.width(48.dp))

            // 右侧：功能按钮
            Row {
                IconButton(onClick = onDpmScan, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "扫一扫",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = onStampOcr, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.TextFields,
                        contentDescription = "OCR 钢印",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // ─── 左上角：零件名 + 视角进度 chips ───
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 12.dp, top = 52.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 零件名 chip
            val partName = inspectionState.selectedTemplate?.partId ?: "未选择"
            CompactChip(
                text = partName,
                onClick = onOpenTemplates
            )

            // 视角进度 chip
            if (inspectionState.totalViews > 0) {
                CompactChip(
                    text = "视角 ${inspectionState.currentViewIndex + 1}/${inspectionState.totalViews}",
                    onClick = { showTemplateSheet = true }
                )
            }
        }

        // ─── 左下角：小缩略图模板参考 ───
        val currentTemplate = inspectionState.selectedTemplate
        if (currentTemplate != null) {
            TemplateThumbnail(
                template = currentTemplate,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 100.dp)
            )
        }

        // ─── 右下角：模板叠加控制 ───
        if (currentTemplate != null) {
            CompactOverlayControls(
                alpha = overlayAlpha,
                visible = templateVisible,
                onAlphaChange = { overlayAlpha = it },
                onToggleVisibility = { templateVisible = !templateVisible },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 100.dp)
            )
        }

        // ─── 底部控制区 ───
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 拍照状态提示（紧凑）
            when (captureState) {
                CaptureUiState.CAPTURING -> {
                    CompactStatusBadge(
                        text = "拍摄中...",
                        color = Color.White.copy(alpha = 0.8f),
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        }
                    )
                }
                CaptureUiState.SAVED -> {
                    CompactStatusBadge(
                        text = if (inspectionState.allViewsCaptured) "全部完成" else "已保存",
                        color = PassColor,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = PassColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
                CaptureUiState.ERROR -> {
                    CompactStatusBadge(
                        text = captureError ?: "拍照失败",
                        color = FailColor,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = FailColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
                CaptureUiState.IDLE -> { /* 不显示 */ }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 快门按钮区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧占位（平衡布局）
                Spacer(modifier = Modifier.weight(1f))

                // 中央：圆形快门按钮
                ShutterButton(
                    enabled = sessionId != null &&
                        cameraState == CameraStateType.OPEN &&
                        captureState == CaptureUiState.IDLE &&
                        !inspectionState.allViewsCaptured,
                    isCapturing = captureState == CaptureUiState.CAPTURING,
                    onClick = onCapture
                )

                // 右侧：重试/重置按钮
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        captureState == CaptureUiState.ERROR -> {
                            IconButton(onClick = onResetCapture) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "重试",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        inspectionState.allViewsCaptured -> {
                            IconButton(onClick = onResetAllViews) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "重新采集",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 全部完成提示
            if (inspectionState.allViewsCaptured) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "本轮 ${inspectionState.totalViews} 个视角采集完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

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

/**
 * 紧凑 chip（半透明背景）
 */
@Composable
private fun CompactChip(
    text: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * 紧凑状态徽章
 */
@Composable
private fun CompactStatusBadge(
    text: String,
    color: Color,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

/**
 * 圆形快门按钮（≥64dp）
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 外圈白色环
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2f
            drawCircle(
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                radius = radius,
                style = Stroke(strokeWidth)
            )
        }

        // 内圈填充
        Canvas(modifier = Modifier.size(58.dp)) {
            val radius = size.minDimension / 2f
            drawCircle(
                color = when {
                    isCapturing -> Color.White.copy(alpha = 0.5f)
                    enabled -> Color.White
                    else -> Color.White.copy(alpha = 0.2f)
                },
                radius = radius
            )
        }

        // 拍摄中指示
        if (isCapturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
                color = Primary
            )
        }
    }
}

/**
 * 模板缩略图（小尺寸，左下角）
 */
@Composable
private fun TemplateThumbnail(
    template: InspectionTemplateEntity,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.size(64.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(template.mainImagePath) {
                try {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 8 // 强降采样缩略图
                    }
                    BitmapFactory.decodeFile(template.mainImagePath, opts)
                } catch (_: Exception) { null }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "模板参考",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Photo,
                    contentDescription = "模板参考",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 紧凑叠加控制（右下角）
 */
@Composable
private fun CompactOverlayControls(
    alpha: Float,
    visible: Boolean,
    onAlphaChange: (Float) -> Unit,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 显示/隐藏按钮
        IconButton(
            onClick = onToggleVisibility,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = if (visible) Icons.Default.Photo else Icons.Default.Close,
                contentDescription = if (visible) "隐藏模板" else "显示模板",
                tint = if (visible) Color.White else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        // 透明度滑块（竖直）
        if (visible) {
            Slider(
                value = alpha,
                onValueChange = onAlphaChange,
                valueRange = 0f..0.8f,
                modifier = Modifier
                    .height(100.dp)
                    .width(32.dp),
            )
        }
    }
}

/**
 * 解析归一化矩形
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
                text = "选择视角",
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

/**
 * 归一化矩形数据类
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
