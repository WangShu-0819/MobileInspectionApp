package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.ocr.OcrResultStatus
import com.wearable.inspection.mobile.ocr.StampOcrState
import com.wearable.inspection.mobile.ocr.StampOcrStatus
import com.wearable.inspection.mobile.ocr.StampOcrViewModel
import com.wearable.inspection.mobile.ocr.SteelStampResult
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.PendingColor
import com.wearable.inspection.mobile.ui.theme.Primary
import kotlinx.coroutines.launch

/**
 * 钢印 OCR 页面 — 全屏相机 + 拍照 + OCR 识别 + 人工确认
 *
 * 流程：
 * 1. CameraPreview 以 STAMP_OCR 模式连接
 * 2. 用户点击拍照按钮
 * 3. 拍照 → OCR 分析 → 结果展示
 * 4. EXACT → 直接显示结果
 * 5. NEED_CONFIRMATION → 显示结果 + 编辑确认
 * 6. FAILED → 显示失败原因 + 重试
 * 7. ERROR → 显示错误 + 重试
 */
@Composable
fun StampOcrScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit = {},
    viewModel: StampOcrViewModel = viewModel(),
) {
    val context = LocalContext.current
    val cameraController = remember { CameraController.getInstance(context) }
    val ocrState by viewModel.ocrState.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val confirmedText by viewModel.confirmedText.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var connectedSessionId by remember { mutableStateOf<String?>(null) }

    // 退出时清理
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopOcr()
            val sid = connectedSessionId
            if (sid != null) {
                coroutineScope.launch {
                    cameraController.clearFrameAnalyzer()
                    cameraController.disconnect(sid)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 全屏相机预览
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraMode = CameraMode.STAMP_OCR,
            onConnected = { controller, sessionId ->
                connectedSessionId = sessionId
                viewModel.startOcr(controller = controller, sessionId = sessionId)
            },
        )

        // 顶部控制栏
        TopOcrControls(
            modifier = Modifier.align(Alignment.TopCenter),
            onBack = onBack,
            status = ocrState.status,
        )

        // 底部区域：拍照按钮 / 结果展示 / 确认编辑
        BottomOcrPanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            ocrState = ocrState,
            confirmedText = confirmedText,
            onCapture = { viewModel.captureAndRecognize() },
            onRetry = { viewModel.retry() },
            onConfirm = { text -> viewModel.confirmResult(text) },
            onResultConfirmed = { text ->
                onResult(text)
                onBack()
            },
        )
    }
}

// ─────────────────────────────────────────────
// 顶部控制栏
// ─────────────────────────────────────────────

@Composable
private fun TopOcrControls(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    status: StampOcrStatus,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        // 返回按钮
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }

        // 标题
        Text(
            text = "OCR 钢印",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )

        // 状态指示
        val statusText = when (status) {
            StampOcrStatus.IDLE -> "就绪"
            StampOcrStatus.CAPTURING -> "拍摄中…"
            StampOcrStatus.PROCESSING -> "识别中…"
            StampOcrStatus.EXACT -> "识别成功"
            StampOcrStatus.NEED_CONFIRMATION -> "需确认"
            StampOcrStatus.FAILED -> "识别失败"
            StampOcrStatus.ERROR -> "出错"
        }
        val statusColor = when (status) {
            StampOcrStatus.EXACT -> PassColor
            StampOcrStatus.NEED_CONFIRMATION -> PendingColor
            StampOcrStatus.FAILED, StampOcrStatus.ERROR -> FailColor
            else -> Color.White.copy(alpha = 0.7f)
        }
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
        )
    }
}

// ─────────────────────────────────────────────
// 底部面板：拍照按钮 / 结果 / 确认
// ─────────────────────────────────────────────

@Composable
private fun BottomOcrPanel(
    modifier: Modifier = Modifier,
    ocrState: StampOcrState,
    confirmedText: String?,
    onCapture: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (String) -> Unit,
    onResultConfirmed: (String) -> Unit,
) {
    when (ocrState.status) {
        StampOcrStatus.IDLE -> {
            // 拍照按钮
            CaptureButton(
                modifier = modifier.padding(bottom = 48.dp),
                onClick = onCapture,
                enabled = true,
            )
        }

        StampOcrStatus.CAPTURING, StampOcrStatus.PROCESSING -> {
            // 处理中
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                            )
                        )
                    )
                    .padding(horizontal = 32.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (ocrState.status == StampOcrStatus.CAPTURING) "拍摄中…" else "正在识别钢印…",
                    color = Color.White,
                    fontSize = 15.sp,
                )
            }
        }

        StampOcrStatus.EXACT -> {
            // 识别成功
            ResultPanel(
                modifier = modifier,
                ocrState = ocrState,
                confirmedText = confirmedText,
                onConfirm = onConfirm,
                onResultConfirmed = onResultConfirmed,
                onRetry = onRetry,
            )
        }

        StampOcrStatus.NEED_CONFIRMATION -> {
            // 需要人工确认
            ConfirmationPanel(
                modifier = modifier,
                ocrState = ocrState,
                onConfirm = onConfirm,
                onRetry = onRetry,
            )
        }

        StampOcrStatus.FAILED -> {
            // 识别失败
            ErrorPanel(
                modifier = modifier,
                message = ocrState.result?.let {
                    when {
                        it.stage == "BlurCheck" -> "图像模糊，请重新拍照"
                        it.stage == "PreProcess" -> "未找到钢印区域"
                        it.detectedLineCount == 0 -> "未识别到文字"
                        else -> "识别失败: ${it.stage}"
                    }
                } ?: "识别失败",
                onRetry = onRetry,
            )
        }

        StampOcrStatus.ERROR -> {
            // 系统错误
            ErrorPanel(
                modifier = modifier,
                message = ocrState.error ?: "未知错误",
                onRetry = onRetry,
            )
        }
    }
}

// ─────────────────────────────────────────────
// 拍照按钮
// ─────────────────────────────────────────────

@Composable
private fun CaptureButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f)),
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "拍照识别",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────
// 结果展示面板（EXACT 状态）
// ─────────────────────────────────────────────

@Composable
private fun ResultPanel(
    modifier: Modifier = Modifier,
    ocrState: StampOcrState,
    confirmedText: String?,
    onConfirm: (String) -> Unit,
    onResultConfirmed: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val result = ocrState.result ?: return
    var editText by remember(confirmedText) {
        mutableStateOf(confirmedText ?: result.lines.joinToString("\n") { it.text })
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.7f),
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        // 结果标题
        Text(
            text = "识别结果",
            color = PassColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 逐行显示
        result.lines.forEachIndexed { index, line ->
            val hasUncertain = line.chars.any { it.uncertain }
            Text(
                text = line.text,
                color = if (hasUncertain) PendingColor else Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        // 版式信息
        result.matchedSchema?.let { schema ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "版式: ${schema.name} (${result.detectedLineCount}行)",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRetry) {
                Text("重拍", color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onResultConfirmed(editText) },
                colors = ButtonDefaults.buttonColors(containerColor = PassColor),
            ) {
                Text("使用结果")
            }
        }
    }
}

// ─────────────────────────────────────────────
// 人工确认面板（NEED_CONFIRMATION 状态）
// ─────────────────────────────────────────────

@Composable
private fun ConfirmationPanel(
    modifier: Modifier = Modifier,
    ocrState: StampOcrState,
    onConfirm: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val result = ocrState.result ?: return
    val initialText = result.lines.joinToString("\n") { it.text }
    var editText by remember { mutableStateOf(initialText) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.8f),
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        // 标题
        Text(
            text = "请确认识别结果",
            color = PendingColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // 不确定字符提示
        if (result.uncertainPositions.isNotEmpty()) {
            Text(
                text = "有 ${result.uncertainPositions.size} 个字符不确定，请检查标黄文字",
                color = PendingColor.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
        }

        // 漏行提示
        if (result.expectedLineCount != null && result.detectedLineCount < result.expectedLineCount) {
            Text(
                text = "预期 ${result.expectedLineCount} 行，仅识别 ${result.detectedLineCount} 行，可能漏行",
                color = PendingColor.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 逐行显示（不确定字符高亮）
        result.lines.forEach { line ->
            val hasUncertain = line.chars.any { it.uncertain }
            Text(
                text = line.text,
                color = if (hasUncertain) PendingColor else Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 编辑框
        OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("识别文本", color = Color.White.copy(alpha = 0.7f)) },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 14.sp,
            ),
            minLines = 2,
            maxLines = 5,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRetry) {
                Text("重拍", color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onConfirm(editText) },
                colors = ButtonDefaults.buttonColors(containerColor = PendingColor),
            ) {
                Text("确认")
            }
        }
    }
}

// ─────────────────────────────────────────────
// 错误面板（FAILED / ERROR）
// ─────────────────────────────────────────────

@Composable
private fun ErrorPanel(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.7f),
                    )
                )
            )
            .padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = FailColor,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("重试")
        }
    }
}
