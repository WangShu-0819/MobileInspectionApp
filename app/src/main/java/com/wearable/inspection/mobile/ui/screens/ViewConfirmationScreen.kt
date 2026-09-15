package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary

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
            if (isLoaded && rois.isNotEmpty() && !saveCompleted && !completionHandled.value) {
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
    val thresholdWasApplied = inference?.status in setOf(
        NanoDetInferenceStatus.DETECTED,
        NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
        NanoDetInferenceStatus.NO_DETECTION
    )
    val thresholdText = if (thresholdWasApplied) {
        "%.1f%%".format((inference?.threshold ?: 0f) * 100f)
    } else {
        "未执行"
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
                        if (inference?.detections?.isNotEmpty() == true) {
                            DetectionBoxOverlay(bitmap, inference)
                        }
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
                        text = roi.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("ID: ${roi.id}", style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "ROI 属性：${targetType?.displayName ?: "未配置"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "检测状态：${inference?.let { inferenceStatusLabel(it.status) } ?: "未执行"}",
                        modifier = Modifier.testTag("inference-status-${roi.id}"),
                        style = MaterialTheme.typography.labelMedium,
                        color = inferenceStatusColor(inference?.status),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "目标类别：${inference?.targetClassIndex?.let(::modelClassLabel) ?: "无"}　模型建议（仅参考）：${inference?.modelSuggestion?.name ?: "无"}",
                        modifier = Modifier.testTag("model-suggestion-${roi.id}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "最高匹配分数：${inference?.matchingScore?.let { "%.1f%%".format(it * 100f) } ?: "—"}　阈值起始值（未校准）：$thresholdText",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (inference?.modelVersion != null) {
                        Text("模型：${inference.modelVersion}", style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!inference?.detail.isNullOrBlank()) {
                        Text(
                            text = inference?.detail.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = FailColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (inference?.detections?.isNotEmpty() == true) {
                Text(
                    text = "检测框 ${inference.detections.size} 个，已叠加显示全部保留框（绿色为匹配类别，橙色为其他类别）",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            } else if (inference?.status == NanoDetInferenceStatus.NO_DETECTION) {
                Text("没有检测框；模型建议 NG，匹配分数为空。", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("人工终审", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(selectedResult?.let { "已选择 $it" } ?: "请独立选择 OK / NG", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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

@Composable
private fun DetectionBoxOverlay(bitmap: Bitmap, inference: NanoDetRoiInferenceResult) {
    Canvas(Modifier.fillMaxSize().testTag("detection-box-overlay-${inference.roiId}")) {
        val bounds = inference.roiBounds
        val sourceWidth = bounds?.let { it.getOrNull(2)?.minus(it.getOrNull(0) ?: 0) }?.takeIf { it > 0 }?.toFloat()
            ?: bitmap.width.toFloat()
        val sourceHeight = bounds?.let { it.getOrNull(3)?.minus(it.getOrNull(1) ?: 0) }?.takeIf { it > 0 }?.toFloat()
            ?: bitmap.height.toFloat()
        val fitScale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val imageWidth = bitmap.width * fitScale
        val imageHeight = bitmap.height * fitScale
        val offsetX = (size.width - imageWidth) / 2f
        val offsetY = (size.height - imageHeight) / 2f
        val sx = imageWidth / sourceWidth
        val sy = imageHeight / sourceHeight
        val nativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.dp.toPx()
            style = Paint.Style.FILL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        inference.detections.forEach { detection ->
            val matching = detection.classIndex == inference.targetClassIndex
            val color = if (matching) android.graphics.Color.GREEN else android.graphics.Color.rgb(255, 145, 0)
            nativePaint.color = color
            nativePaint.style = Paint.Style.STROKE
            nativePaint.strokeWidth = 1.5.dp.toPx()
            val box = detection.roiBox
            val rect = RectF(
                offsetX + box.left.toFloat() * sx,
                offsetY + box.top.toFloat() * sy,
                offsetX + box.right.toFloat() * sx,
                offsetY + box.bottom.toFloat() * sy
            )
            drawContext.canvas.nativeCanvas.drawRect(rect, nativePaint)
            nativePaint.style = Paint.Style.FILL
            nativePaint.textSize = 8.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(
                "${detection.className} ${(detection.score * 100f).toInt()}%",
                rect.left,
                (rect.top - 1.dp.toPx()).coerceAtLeast(nativePaint.textSize),
                nativePaint
            )
        }
    }
}

internal fun inferenceStatusLabel(status: NanoDetInferenceStatus): String = when (status) {
    NanoDetInferenceStatus.DETECTED -> "已检出，达到模型阈值"
    NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD -> "已检出，低于模型阈值"
    NanoDetInferenceStatus.NO_DETECTION -> "未检出"
    NanoDetInferenceStatus.ROI_NOT_CONFIGURED -> "ROI 属性未配置"
    NanoDetInferenceStatus.FEATURE_UNSUPPORTED -> "部件类别暂不支持"
    NanoDetInferenceStatus.INVALID_ROI -> "ROI 区域无效"
    NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR -> "照片关联错误"
    NanoDetInferenceStatus.IMAGE_UNREADABLE -> "照片不可读取"
    NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE -> "模板图不可读取"
    NanoDetInferenceStatus.ABI_UNSUPPORTED -> "当前设备 ABI 不支持"
    NanoDetInferenceStatus.RUNTIME_UNAVAILABLE -> "推理运行时不可用"
    NanoDetInferenceStatus.MODEL_UNAVAILABLE -> "模型不可用"
    NanoDetInferenceStatus.INFERENCE_ERROR -> "推理错误"
}

internal fun modelClassLabel(classIndex: Int): String = when (classIndex) {
    0 -> "螺母（类别 0）"
    1 -> "螺纹（类别 1）"
    else -> "类别 $classIndex"
}

private fun inferenceStatusColor(status: NanoDetInferenceStatus?): Color = when (status) {
    NanoDetInferenceStatus.DETECTED -> PassColor
    NanoDetInferenceStatus.NO_DETECTION,
    NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD -> FailColor
    null,
    NanoDetInferenceStatus.ROI_NOT_CONFIGURED,
    NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
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
private fun BottomConfirmBar(
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
