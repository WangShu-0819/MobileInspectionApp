package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.detection.FullImageInferResult
import com.wearable.inspection.mobile.detection.NanoDetDetection
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetModelContract
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.RoiSimilarityStatus
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * View 人工确认页面
 *
 * 流程：
 * 1. 显示当前 View 信息和进度
 * 2. 显示 ROI 裁剪子图列表，每个 ROI 选择 OK/NG
 * 3. 底部固定显示总体 OK/NG 选择
 * 4. 确认按钮（所有选择完成后可用）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewConfirmationScreen(
    viewModel: ViewConfirmationViewModel,
    partName: String,
    currentViewIndex: Int,
    totalViews: Int,
    onConfirmed: () -> Unit,
    onBack: () -> Unit
) {
    val rois = viewModel.rois
    val roiBitmaps = viewModel.roiBitmaps
    val roiResults = viewModel.roiResults
    val inferenceResults = viewModel.inferenceResults
    val overallResult = viewModel.overallResult
    val isSaving = viewModel.isSaving
    val errorMessage = viewModel.errorMessage
    val saveCompleted = viewModel.saveCompleted
    val isLoaded = viewModel.isLoaded
    val isAllConfirmed = viewModel.isAllConfirmed()

    // 保存完成事件只消费一次；返回/取消不会触发此事件。
    val completionHandled = remember(viewModel) { mutableStateOf(false) }
    LaunchedEffect(saveCompleted) {
        if (saveCompleted && !completionHandled.value) {
            completionHandled.value = true
            onConfirmed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回现场采集",
                            tint = TextPrimary,
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = partName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = TextPrimary
                        )
                        Text(
                            text = "视角 ${currentViewIndex + 1}/$totalViews",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                )
            )
        },
        bottomBar = {
            if (isLoaded && (rois.isNotEmpty() || viewModel.isFullImageMode) && !saveCompleted && !completionHandled.value) {
                // 保存完成后先移除本页操作栏，再通知导航层离开，避免确认按钮残留一帧。
                BottomConfirmBar(
                    overallResult = overallResult,
                    onOverallSelect = { viewModel.selectOverallResult(it) },
                    isAllConfirmed = isAllConfirmed,
                    isSaving = isSaving,
                    errorMessage = errorMessage,
                    onConfirm = { viewModel.saveConfirmation() }
                )
            }
        },
        containerColor = BackgroundVariant1
    ) { paddingValues ->
        when {
            !isLoaded -> {
                // 加载中
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("加载中…", color = TextSecondary)
                    }
                }
            }
            errorMessage != null && rois.isEmpty() -> {
                // 错误状态
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = FailColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage, color = FailColor)
                    }
                }
            }
            rois.isEmpty() -> {
                // 防御性状态：正常流程不会进入无 ROI 确认页，也不生成确认结果。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("当前视角无 ROI，无需人工确认", color = TextSecondary)
                }
            }
            viewModel.isFullImageMode -> {
                FullImageConfirmContent(
                    fullResult = viewModel.fullImageInferResult,
                    photoPath = viewModel.photoPath,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 12.dp),
                )
            }
            else -> {
                // ROI 列表（可滚动）；Scaffold 的 bottomBar 会为底部操作栏预留空间。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(rois, key = { it.id }) { roi ->
                        RoiConfirmCard(
                            roi = roi,
                            bitmap = roiBitmaps[roi.id],
                            inference = inferenceResults[roi.id],
                            selectedResult = roiResults[roi.id],
                            onSelect = { result ->
                                viewModel.setRoiResult(roi.id, result)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个 ROI 确认卡片
 *
 * 左侧：ROI 裁剪子图
 * 右侧：ROI 名称、属性、OK/NG 选择
 */
@Composable
internal fun RoiConfirmCard(
    roi: RoiDefinitionEntity,
    bitmap: Bitmap?,
    inference: NanoDetRoiInferenceResult?,
    selectedResult: String?,
    onSelect: (String) -> Unit
) {
    val targetType = RoiTargetType.fromName(roi.targetType)
    val statusHint = when (inference?.status) {
        NanoDetInferenceStatus.ROI_NOT_CONFIGURED,
        NanoDetInferenceStatus.FEATURE_UNSUPPORTED -> "部件类别暂不支持"
        NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED -> "当前模型不支持该目标类型"
        NanoDetInferenceStatus.ABI_UNSUPPORTED,
        NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
        NanoDetInferenceStatus.MODEL_UNAVAILABLE,
        NanoDetInferenceStatus.INFERENCE_ERROR -> "模型未执行"
        NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR,
        NanoDetInferenceStatus.IMAGE_UNREADABLE,
        NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE,
        NanoDetInferenceStatus.INVALID_ROI -> "模型未执行"
        NanoDetInferenceStatus.NO_DETECTION -> "未检出"
        NanoDetInferenceStatus.DETECTED,
        NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD -> null
        null -> "模型未执行"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "${roi.name} ROI 裁剪图",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("照片不可用", color = Color.White, fontSize = 10.sp)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "ROI ${roi.order + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "ROI 类型：${targetType?.displayName ?: "未配置"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                        fontWeight = FontWeight.Medium
                    )
                    if (statusHint != null) {
                        Text(
                            text = statusHint,
                            modifier = Modifier.testTag("inference-status-${roi.id}"),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            val nanodetStatus = inference?.let { inferenceStatusLabel(it.status) } ?: "模型未执行"
            val nanodetResult = inference?.modelSuggestion?.name ?: "无 OK/NG 结果"
            val similarity = inference?.similarity
            Text(
                text = "NanoDet：$nanodetStatus · 结果 $nanodetResult",
                modifier = Modifier.testTag("nanodet-result-${roi.id}"),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Text(
                text = "相似度：${similarity?.let { similarityStatusLabel(it.status) } ?: "未运行"}" +
                    " · 分数 ${similarity?.score?.let(::formatSimilarityValue) ?: "—"}" +
                    " · 阈值 ${similarity?.threshold?.let(::formatSimilarityValue) ?: "—"}" +
                    " · 候选 ${similarity?.candidate?.name ?: "无"}",
                modifier = Modifier.testTag("similarity-result-${roi.id}"),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("人工终审", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(selectedResult?.let { "已选择 $it" } ?: "请独立选择 OK / NG", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        "人工相对 NanoDet：${humanDecisionRelation(selectedResult, inference?.modelSuggestion?.name, "无 NanoDet 候选")}",
                        modifier = Modifier.testTag("human-vs-nanodet-${roi.id}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Text(
                        "人工相对相似度候选：${humanDecisionRelation(selectedResult, similarity?.candidate?.name, "无候选")}",
                        modifier = Modifier.testTag("human-vs-similarity-${roi.id}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ResultChip(
                    label = "OK",
                    selected = selectedResult == "OK",
                    color = PassColor,
                    testTag = "human-result-${roi.id}-OK",
                    onClick = { onSelect("OK") }
                )
                ResultChip(
                    label = "NG",
                    selected = selectedResult == "NG",
                    color = FailColor,
                    testTag = "human-result-${roi.id}-NG",
                    onClick = { onSelect("NG") }
                )
                }
            }
        }
    }
}

/**
 * 整图确认模式内容：检测框叠加 + 检测摘要卡片。
 *
 * 从 [ViewConfirmationScreen] 的 isFullImageMode 分支提取，便于独立测试。
 *
 * @param fullResult 整图推理结果；null 时显示"整图检测结果不可用"
 * @param photoPath 现场照片路径；null 时跳过照片加载
 * @param modifier 外层修饰符（由调用方传入 padding 等）
 */
@Composable
internal fun FullImageConfirmContent(
    fullResult: FullImageInferResult?,
    photoPath: String?,
    modifier: Modifier = Modifier,
) {
    // 加载现场照片 Bitmap（EXIF-aware upright，与 NanoDet imageBox 坐标空间一致）
    var photoBitmap by remember(photoPath) { mutableStateOf<Bitmap?>(null) }
    var photoLoadError by remember(photoPath) { mutableStateOf<String?>(null) }
    LaunchedEffect(photoPath) {
        if (photoPath != null) {
            val bitmap = withContext(Dispatchers.IO) {
                RoiCoordinateMapper.loadUprightBitmap(photoPath, maxTargetSize = 2048)
            }
            if (bitmap != null) {
                photoBitmap = bitmap
                photoLoadError = null
            } else {
                photoBitmap = null
                photoLoadError = "照片加载失败"
            }
        }
    }

    // 业务阈值过滤：只显示 score >= threshold 的检测框和标签
    // 有限且在 0..1 时使用原值；NaN、无穷、负数或 >1 时回退到 STARTING_BUSINESS_THRESHOLD
    val rawThreshold = fullResult?.threshold
    val threshold = if (rawThreshold != null && rawThreshold.isFinite() && rawThreshold in 0f..1f) {
        rawThreshold
    } else {
        NanoDetModelContract.STARTING_BUSINESS_THRESHOLD
    }
    val allDetections = fullResult?.detections ?: emptyList()
    val displayDetections = allDetections.filter { it.score >= threshold }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        // 检测框叠加图片（只绘制达到阈值的检测框）
        item {
            FullImageDetectionOverlay(
                photoBitmap = photoBitmap,
                photoLoadError = photoLoadError,
                detections = displayDetections,
                imageWidth = fullResult?.imageWidth ?: 0,
                imageHeight = fullResult?.imageHeight ?: 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 检测摘要卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (fullResult != null) {
                        Text(
                            text = "整图检出：${displayDetections.size} 个 · 阈值 ${"%.0f".format(threshold * 100)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                        if (fullResult.status == NanoDetInferenceStatus.INFERENCE_ERROR) {
                            Text(
                                text = "推理异常：${fullResult.detail ?: "未知错误"}",
                                color = FailColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            text = "整图检测结果不可用",
                            color = FailColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 整图检测框叠加组件。
 *
 * 在现场照片上绘制检测框、类别标签和置信度。
 * 使用 imageBox（整张 upright 现场照片中的像素坐标），不使用 roiBox。
 *
 * @param photoBitmap 已加载的 upright 现场照片 Bitmap；null 时不绘制图片
 * @param photoLoadError 图片加载失败时的错误信息
 * @param detections NanoDet 检测结果列表
 * @param imageWidth 推理输入图片宽度（upright 像素）
 * @param imageHeight 推理输入图片高度（upright 像素）
 */
@Composable
internal fun FullImageDetectionOverlay(
    photoBitmap: Bitmap?,
    photoLoadError: String?,
    detections: List<NanoDetDetection>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    // 无图片、无尺寸信息时仅显示占位
    if (photoBitmap == null || imageWidth <= 0 || imageHeight <= 0) {
        Box(
            modifier = modifier
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (photoLoadError != null) {
                Text(
                    text = "照片加载失败：$photoLoadError",
                    color = Color(0xFFB0B0B0),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
        }
        return
    }

    val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

    // 每个类别对应一种颜色（与 roiBox 无关，仅用于视觉区分）
    val classColors = listOf(
        Color(0xFFFF4444), // 类别 0 — 红
        Color(0xFF4488FF), // 类别 1 — 蓝
        Color(0xFF44DDFF), // 类别 2 — 青
        Color(0xFFFFBB33), // 类别 3 — 黄
    )

    // 预测量标签文字尺寸（在 Canvas 外测量，避免每帧重复测量）
    val textMeasurer = rememberTextMeasurer()
    val labelTexts = remember(detections) {
        detections.map { "${it.className} ${(it.score * 100).toInt()}%" }
    }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
    )

    // Box 宽高比与图片一致 → ContentScale.Fit 铺满，无留白
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // 现场照片：ContentScale.Fit 在宽高比匹配的 Box 中铺满
        Image(
            bitmap = photoBitmap.asImageBitmap(),
            contentDescription = "现场照片（整图检测）",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        // 检测框叠加层（框 + 标签全部在 Canvas 内绘制）
        if (detections.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Box 宽高比与图片一致 + ContentScale.Fit → fitScale 填满，offset 为零
                val fitResult = mapImageBoxToCanvas(
                    boxLeft = 0f, boxTop = 0f,
                    canvasWidth = size.width, canvasHeight = size.height,
                    imageWidth = imageWidth, imageHeight = imageHeight,
                )
                val sx = fitResult.scaleX
                val sy = fitResult.scaleY
                val ox = fitResult.offsetX
                val oy = fitResult.offsetY
                val strokeWidth = 2.dp.toPx()
                val labelPaddingH = 3.dp.toPx()
                val labelPaddingV = 1.dp.toPx()

                detections.forEachIndexed { index, det ->
                    val box = det.imageBox
                    val color = classColors.getOrElse(det.classIndex % classColors.size) { Color.Cyan }

                    val left = ox + (box.left * sx).toFloat()
                    val top = oy + (box.top * sy).toFloat()
                    val right = ox + (box.right * sx).toFloat()
                    val bottom = oy + (box.bottom * sy).toFloat()
                    val w = right - left
                    val h = bottom - top

                    if (w > 0f && h > 0f) {
                        // 检测框
                        drawRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = Size(w, h),
                            style = Stroke(width = strokeWidth),
                        )

                        // 类别 + 置信度标签
                        val labelResult = textMeasurer.measure(
                            text = labelTexts[index],
                            style = labelStyle,
                        )
                        val labelW = labelResult.size.width + labelPaddingH * 2
                        val labelH = labelResult.size.height + labelPaddingV * 2
                        // 标签放在框上方；如果上方空间不足则放在框内顶部
                        val labelTop = if (top >= labelH) {
                            top - labelH
                        } else {
                            top
                        }

                        // 标签背景
                        drawRect(
                            color = color.copy(alpha = 0.85f),
                            topLeft = Offset(left, labelTop),
                            size = Size(minOf(labelW, w), labelH),
                        )
                        // 标签文字
                        drawText(
                            textLayoutResult = labelResult,
                            topLeft = Offset(left + labelPaddingH, labelTop + labelPaddingV),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ContentScale.Fit 坐标映射结果。
 */
internal data class FitMappingResult(
    val scaleX: Double,
    val scaleY: Double,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * 将 imageBox 像素坐标映射到 Canvas 显示坐标，遵循 ContentScale.Fit 语义。
 *
 * ContentScale.Fit: 等比缩放使图片完整放入容器，居中放置。
 * - fitScale = min(canvasW / imageW, canvasH / imageH)
 * - displayedW = imageW * fitScale
 * - displayedH = imageH * fitScale
 * - offsetX = (canvasW - displayedW) / 2
 * - offsetY = (canvasH - displayedH) / 2
 *
 * 当容器宽高比与图片一致时，offsetX/offsetY 为零。
 *
 * @param boxLeft 容器左上角 x（通常 0）
 * @param boxTop  容器左上角 y（通常 0）
 * @param canvasWidth  容器宽度（px）
 * @param canvasHeight 容器高度（px）
 * @param imageWidth   图片宽度（upright px）
 * @param imageHeight  图片高度（upright px）
 */
internal fun mapImageBoxToCanvas(
    boxLeft: Float,
    boxTop: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): FitMappingResult {
    if (imageWidth <= 0 || imageHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) {
        return FitMappingResult(1.0, 1.0, boxLeft, boxTop)
    }
    val iw = imageWidth.toDouble()
    val ih = imageHeight.toDouble()
    val cw = canvasWidth.toDouble()
    val ch = canvasHeight.toDouble()
    val fitScale = min(cw / iw, ch / ih)
    val displayedW = iw * fitScale
    val displayedH = ih * fitScale
    val offsetX = boxLeft + ((cw - displayedW) / 2.0).toFloat()
    val offsetY = boxTop + ((ch - displayedH) / 2.0).toFloat()
    return FitMappingResult(
        scaleX = fitScale,
        scaleY = fitScale,
        offsetX = offsetX,
        offsetY = offsetY,
    )
}

internal fun inferenceStatusLabel(status: NanoDetInferenceStatus): String = when (status) {
    NanoDetInferenceStatus.DETECTED -> "已检出，达到模型阈值"
    NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD -> "已检出，低于模型阈值"
    NanoDetInferenceStatus.NO_DETECTION -> "未检出"
    NanoDetInferenceStatus.ROI_NOT_CONFIGURED -> "ROI 属性未配置"
    NanoDetInferenceStatus.FEATURE_UNSUPPORTED -> "部件类别暂不支持"
    NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED -> "模型不支持该目标类型"
    NanoDetInferenceStatus.INVALID_ROI -> "ROI 区域无效"
    NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR -> "照片关联错误"
    NanoDetInferenceStatus.IMAGE_UNREADABLE -> "照片不可读取"
    NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE -> "模板图不可读取"
    NanoDetInferenceStatus.ABI_UNSUPPORTED -> "当前设备 ABI 不支持"
    NanoDetInferenceStatus.RUNTIME_UNAVAILABLE -> "推理运行时不可用"
    NanoDetInferenceStatus.MODEL_UNAVAILABLE -> "模型不可用"
    NanoDetInferenceStatus.INFERENCE_ERROR -> "推理错误"
}

internal fun similarityStatusLabel(status: RoiSimilarityStatus): String = when (status) {
    RoiSimilarityStatus.NOT_RUN_NANODET_NOT_NG -> "未运行"
    RoiSimilarityStatus.TEMPLATE_UNREADABLE -> "模板图不可读"
    RoiSimilarityStatus.PHOTO_UNREADABLE -> "现场图不可读"
    RoiSimilarityStatus.SIMILARITY_RUNTIME_UNAVAILABLE -> "相似度运行库不可用"
    RoiSimilarityStatus.INVALID_ROI -> "ROI 无效"
    RoiSimilarityStatus.ROI_IMAGE_UNAVAILABLE -> "ROI 图片不可用"
    RoiSimilarityStatus.ROI_EVIDENCE_SAVE_FAILED -> "ROI 留图保存失败"
    RoiSimilarityStatus.REGISTRATION_FAILED -> "配准失败"
    RoiSimilarityStatus.COMPARISON_ERROR -> "相似度推理错误"
    RoiSimilarityStatus.SCORED_NO_THRESHOLD -> "已评分，无候选阈值"
    RoiSimilarityStatus.SCORED_WITH_CANDIDATE_THRESHOLD -> "已评分"
}

internal fun humanDecisionRelation(humanResult: String?, candidate: String?, noCandidateLabel: String): String = when {
    humanResult !in setOf("OK", "NG") -> "未选择"
    candidate !in setOf("OK", "NG") -> noCandidateLabel
    humanResult == candidate -> "一致"
    else -> "改判"
}

private fun formatSimilarityValue(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

internal fun modelClassLabel(classIndex: Int): String = when (classIndex) {
    0 -> "螺母（类别 0）"
    1 -> "螺纹（类别 1）"
    2 -> "螺栓（类别 2）"
    3 -> "铆螺母（类别 3）"
    else -> "类别 $classIndex"
}

private fun inferenceStatusColor(status: NanoDetInferenceStatus?): Color = when (status) {
    NanoDetInferenceStatus.DETECTED -> PassColor
    NanoDetInferenceStatus.NO_DETECTION,
    NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD -> FailColor
    null,
    NanoDetInferenceStatus.ROI_NOT_CONFIGURED,
    NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
    NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED,
    NanoDetInferenceStatus.INVALID_ROI,
    NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR,
    NanoDetInferenceStatus.IMAGE_UNREADABLE,
    NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE,
    NanoDetInferenceStatus.ABI_UNSUPPORTED,
    NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
    NanoDetInferenceStatus.MODEL_UNAVAILABLE,
    NanoDetInferenceStatus.INFERENCE_ERROR -> FailColor
}

/**
 * OK/NG 选择芯片
 */
@Composable
private fun ResultChip(
    label: String,
    selected: Boolean,
    color: Color,
    testTag: String? = null,
    onClick: () -> Unit
) {
    val isResultSelected = selected
    val bgColor = if (selected) color else Color.Transparent
    val textColor = if (selected) Color.White else color
    val borderColor = color

    Box(
        modifier = Modifier
            .height(32.dp)
            .width(48.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .semantics { this.selected = isResultSelected }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 底部确认栏
 *
 * 固定显示：总体 OK/NG + 确认按钮
 */
@Composable
internal fun BottomConfirmBar(
    overallResult: String?,
    onOverallSelect: (String) -> Unit,
    isAllConfirmed: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 总体结果选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "总体结果",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverallChip(
                        label = "OK",
                        selected = overallResult == "OK",
                        color = PassColor,
                        onClick = { onOverallSelect("OK") }
                    )
                    OverallChip(
                        label = "NG",
                        selected = overallResult == "NG",
                        color = FailColor,
                        onClick = { onOverallSelect("NG") }
                    )
                }
            }

            // 错误信息（固定高度，避免出现/消失时推动按钮移动）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    errorMessage != null -> Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = FailColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    !isAllConfirmed && !isSaving -> Text(
                        text = "请完成所有选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            // 确认按钮
            Button(
                onClick = onConfirm,
                enabled = isAllConfirmed && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Color.White,
                    disabledContainerColor = DividerColor,
                    disabledContentColor = TextSecondary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                // 按钮文案固定；未完成状态只改变 enabled 和上方提示，不改变按钮位置。
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            modifier = Modifier.height(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "确认并继续",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 总体 OK/NG 芯片（更大尺寸）
 */
@Composable
private fun OverallChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val bgColor = if (selected) color else Color.Transparent
    val textColor = if (selected) Color.White else color

    Box(
        modifier = Modifier
            .height(36.dp)
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.5.dp, color, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
