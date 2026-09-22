package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private enum class ComparisonMode {
    PHOTO,
    TEMPLATE,
    OVERLAY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureComparisonScreen(
    viewModel: CaptureComparisonViewModel = viewModel(),
    partName: String,
    currentViewIndex: Int,
    totalViews: Int,
    onBack: () -> Unit,
    onProceed: () -> Unit,
) {
    var mode by remember { mutableStateOf(ComparisonMode.OVERLAY) }
    var overlayAlpha by remember { mutableFloatStateOf(0.5f) }
    var blinkEnabled by remember { mutableStateOf(false) }
    var blinkTemplateVisible by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(blinkEnabled) {
        if (!blinkEnabled) {
            blinkTemplateVisible = true
        } else {
            while (true) {
                blinkTemplateVisible = !blinkTemplateVisible
                delay(450)
            }
        }
    }

    val result = viewModel.registrationResult
    val isTemplateVisible = when {
        blinkEnabled -> blinkTemplateVisible
        mode == ComparisonMode.PHOTO -> false
        else -> true
    }
    val templateAlpha = when {
        !isTemplateVisible -> 0f
        mode == ComparisonMode.TEMPLATE -> 1f
        else -> overlayAlpha
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回现场采集", tint = TextPrimary)
                    }
                },
                title = {
                    Column {
                        Text(partName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("拍后比对 · 视角 ${currentViewIndex + 1}/$totalViews", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite),
            )
        },
        containerColor = BackgroundVariant1,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ComparisonToolbar(
                mode = mode,
                overlayAlpha = overlayAlpha,
                blinkEnabled = blinkEnabled,
                onModeChange = { mode = it },
                onOverlayAlphaChange = { overlayAlpha = it },
                onBlinkChange = { blinkEnabled = it },
                onReset = { zoom = 1f; panX = 0f; panY = 0f },
                onZoomStep = { factor -> zoom = (zoom * factor).coerceIn(1f, 4f) },
            )

            if (!viewModel.isLoaded) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (viewModel.errorMessage != null && viewModel.photoBitmap == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(viewModel.errorMessage ?: "图片不可用", color = FailColor)
                }
            } else {
                ComparisonViewport(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    zoom = zoom,
                    panX = panX,
                    panY = panY,
                    templateAlpha = templateAlpha,
                    onZoom = { factor, dx, dy ->
                        zoom = (zoom * factor).coerceIn(1f, 4f)
                        panX += dx
                        panY += dy
                    },
                )
            }

            RegistrationSummary(result = result, errorMessage = viewModel.errorMessage)
            if (viewModel.isLoaded && viewModel.isFullImageFallback && viewModel.canProceed) {
                Text(
                    text = "配准不可靠，将使用整图检测模式",
                    color = FailColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("返回") }
                Button(
                    onClick = onProceed,
                    enabled = viewModel.canProceed,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) { Text(if (viewModel.isFullImageFallback) "整图检测确认" else "进入人工确认") }
            }
        }
    }
}

@Composable
private fun ComparisonToolbar(
    mode: ComparisonMode,
    overlayAlpha: Float,
    blinkEnabled: Boolean,
    onModeChange: (ComparisonMode) -> Unit,
    onOverlayAlphaChange: (Float) -> Unit,
    onBlinkChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onZoomStep: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceWhite).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeButton("现场", mode == ComparisonMode.PHOTO) { onModeChange(ComparisonMode.PHOTO) }
            ModeButton("模板", mode == ComparisonMode.TEMPLATE) { onModeChange(ComparisonMode.TEMPLATE) }
            ModeButton("叠加", mode == ComparisonMode.OVERLAY) { onModeChange(ComparisonMode.OVERLAY) }
            TextButton(onClick = { onBlinkChange(!blinkEnabled) }) { Text(if (blinkEnabled) "停止 blink" else "blink") }
            TextButton(onClick = { onZoomStep(0.8f) }) { Text("−") }
            TextButton(onClick = { onZoomStep(1.25f) }) { Text("+") }
            IconButton(onClick = onReset) { Icon(Icons.Default.Refresh, contentDescription = "重置缩放") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模板透明度", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = overlayAlpha,
                onValueChange = onOverlayAlphaChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            Text("${(overlayAlpha * 100).toInt()}%", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text("双指缩放/平移", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = if (selected) Primary else TextSecondary),
    ) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun RegistrationSummary(
    result: com.wearable.inspection.mobile.registration.RegistrationResult?,
    errorMessage: String?,
) {
    val text = when {
        result == null && errorMessage == null -> "未执行配准"
        result?.isSuccess == true -> "静态配准成功：已自动投影 Session ROI"
        result != null -> "静态配准未通过：${result.failureReason ?: "未知原因"}；当前不伪造对齐结果"
        else -> errorMessage ?: "图片不可用"
    }
    Text(
        text = text,
        color = if (result?.isSuccess == true) Primary else TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ComparisonViewport(
    viewModel: CaptureComparisonViewModel,
    modifier: Modifier,
    zoom: Float,
    panX: Float,
    panY: Float,
    templateAlpha: Float,
    onZoom: (factor: Float, dx: Float, dy: Float) -> Unit,
) {
    val photoBitmap = viewModel.photoBitmap
    val templateBitmap = viewModel.alignedTemplateBitmap ?: viewModel.templateBitmap
    if (photoBitmap == null) return

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onZoom(1f, dragAmount.x, dragAmount.y)
                }
            },
    ) {
        val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
        val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
        val baseScale = minOf(viewportWidth / photoBitmap.width, viewportHeight / photoBitmap.height)
        val drawWidth = photoBitmap.width * baseScale
        val drawHeight = photoBitmap.height * baseScale
        val drawLeft = (viewportWidth - drawWidth) / 2f
        val drawTop = (viewportHeight - drawHeight) / 2f
        val density = LocalDensity.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                },
        ) {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = "现场照片",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            if (templateBitmap != null && templateAlpha > 0f) {
                Image(
                    bitmap = templateBitmap.asImageBitmap(),
                    contentDescription = "模板图叠加",
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = templateAlpha },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            viewModel.sessionRois.forEach { roi ->
                SessionRoiOverlay(
                    roi = roi,
                    left = drawLeft,
                    top = drawTop,
                    width = drawWidth,
                    height = drawHeight,
                    density = density,
                )
            }
        }
    }
}

@Composable
private fun SessionRoiOverlay(
    roi: SessionRoi,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    density: androidx.compose.ui.unit.Density,
) {
    val rectLeft = left + roi.rect.left * width
    val rectTop = top + roi.rect.top * height
    val rectWidth = (roi.rect.right - roi.rect.left) * width
    val rectHeight = (roi.rect.bottom - roi.rect.top) * height
    val border = BorderStroke(2.dp, Color.Yellow)

    Box(
        modifier = Modifier
            .offset { IntOffset(rectLeft.toInt(), rectTop.toInt()) }
            .size(with(density) { rectWidth.toDp() }, with(density) { rectHeight.toDp() })
            .border(border),
    ) {
        Text(
            text = roi.name,
            color = Color.Yellow,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 3.dp),
        )
    }
}
