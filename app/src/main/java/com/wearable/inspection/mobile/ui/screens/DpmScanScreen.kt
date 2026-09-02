package com.wearable.inspection.mobile.ui.screens

import android.graphics.Rect
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.dpm.DpmScanRoiMapper
import com.wearable.inspection.mobile.dpm.DpmScanViewModel
import com.wearable.inspection.mobile.ui.theme.Primary
import kotlinx.coroutines.launch
import kotlin.math.min

/** 扫描框边长占预览短边的比例。 */
private const val SCAN_FRAME_RATIO = 0.65f

/**
 * DPM 扫码页面 — 全屏相机 + 浮层控制
 *
 * ROI 映射流程：
 * 1. CameraPreview 以 DPM_SCAN 模式连接，onFrameInfo 报告 contentRect + stream 信息
 * 2. ScanOverlay 绘制居中的 1:1 扫描框，返回屏幕坐标 Rect
 * 3. DpmScanRoiMapper 将屏幕 Rect 映射到旋转后 Bitmap 坐标
 * 4. 动态 scanRoi 传给 DpmFrameAnalyzer（随布局/旋转变化更新）
 */
@Composable
fun DpmScanScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit = {},
    viewModel: DpmScanViewModel = viewModel(),
) {
    val context = LocalContext.current
    val cameraController = remember { CameraController.getInstance(context) }
    val scanState by viewModel.scanState.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var connectedSessionId by remember { mutableStateOf<String?>(null) }
    var frameInfo by remember { mutableStateOf<FrameInfo?>(null) }
    var scanFrameScreenRect by remember { mutableStateOf<Rect?>(null) }

    // 动态计算 bitmap ROI 并更新 ViewModel
    LaunchedEffect(frameInfo, scanFrameScreenRect) {
        val fi = frameInfo
        val screenRect = scanFrameScreenRect
        if (fi != null && screenRect != null) {
            val bitmapW: Int
            val bitmapH: Int
            if (fi.streamRotation == 90 || fi.streamRotation == 270) {
                bitmapW = fi.streamResolution.height
                bitmapH = fi.streamResolution.width
            } else {
                bitmapW = fi.streamResolution.width
                bitmapH = fi.streamResolution.height
            }
            val roi = DpmScanRoiMapper.mapToBitmap(
                screenRect = screenRect,
                contentRect = fi.contentRect,
                bitmapWidth = bitmapW,
                bitmapHeight = bitmapH,
            )
            viewModel.updateScanRoi(roi)
        }
    }

    // 解码结果回调
    LaunchedEffect(lastResult) {
        lastResult?.let { result ->
            onResult(result.rawValue)
        }
    }

    // 退出时清理
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopScan()
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
            cameraMode = CameraMode.DPM_SCAN,
            onConnected = { controller, sessionId ->
                connectedSessionId = sessionId
                viewModel.startScan(controller = controller, sessionId = sessionId)
            },
            onFrameInfo = { info -> frameInfo = info },
        )

        // 扫描框覆盖层
        ScanOverlay(
            modifier = Modifier.fillMaxSize(),
            frameRatio = SCAN_FRAME_RATIO,
            onFrameRect = { rect -> scanFrameScreenRect = rect },
        )

        // 顶部渐变 + 返回/闪光灯
        TopControls(
            modifier = Modifier.align(Alignment.TopCenter),
            torchOn = scanState.torchOn,
            onBack = onBack,
            onToggleTorch = { coroutineScope.launch { viewModel.toggleTorch() } },
        )

        // 底部提示
        BottomHint(
            modifier = Modifier.align(Alignment.BottomCenter),
            lastResult = lastResult?.rawValue,
            scanning = scanState.scanning,
        )
    }
}

// ─────────────────────────────────────────────
// 顶部控制栏（渐变背景 + 返回 + 闪光灯）
// ─────────────────────────────────────────────

@Composable
private fun TopControls(
    modifier: Modifier = Modifier,
    torchOn: Boolean,
    onBack: () -> Unit,
    onToggleTorch: () -> Unit,
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
            text = "扫一扫",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )

        // 闪光灯按钮
        IconButton(
            onClick = onToggleTorch,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(40.dp),
        ) {
            Icon(
                imageVector = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (torchOn) "关闭闪光灯" else "开启闪光灯",
                tint = if (torchOn) Color(0xFFFFD600) else Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────
// 扫描框覆盖层
// ─────────────────────────────────────────────

@Composable
private fun ScanOverlay(
    modifier: Modifier = Modifier,
    frameRatio: Float,
    onFrameRect: (Rect) -> Unit,
) {
    val overlayColor = Color.Black.copy(alpha = 0.35f)
    val cornerColor = Color.White
    val borderColor = Color.White.copy(alpha = 0.15f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val frameSize = min(w, h) * frameRatio.coerceIn(0.5f, 0.85f)
        val left = (w - frameSize) / 2
        val top = (h - frameSize) / 2

        onFrameRect(Rect(
            left.toInt(),
            top.toInt(),
            (left + frameSize).toInt(),
            (top + frameSize).toInt(),
        ))

        // 半透明遮罩（四块）
        drawRect(overlayColor, Offset.Zero, Size(w, top))
        drawRect(overlayColor, Offset(0f, top), Size(left, frameSize))
        drawRect(overlayColor, Offset(left + frameSize, top), Size(w - left - frameSize, frameSize))
        drawRect(overlayColor, Offset(0f, top + frameSize), Size(w, h - top - frameSize))

        // 淡边框
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(frameSize, frameSize),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 1f),
        )

        // 四角高亮
        val cornerLen = 28f
        val strokeW = 3f
        val r = CornerRadius(2f, 2f)

        // 左上
        drawLine(cornerColor, Offset(left, top + cornerLen), Offset(left, top), strokeW)
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLen, top), strokeW)
        // 右上
        drawLine(cornerColor, Offset(left + frameSize - cornerLen, top), Offset(left + frameSize, top), strokeW)
        drawLine(cornerColor, Offset(left + frameSize, top), Offset(left + frameSize, top + cornerLen), strokeW)
        // 左下
        drawLine(cornerColor, Offset(left, top + frameSize - cornerLen), Offset(left, top + frameSize), strokeW)
        drawLine(cornerColor, Offset(left, top + frameSize), Offset(left + cornerLen, top + frameSize), strokeW)
        // 右下
        drawLine(cornerColor, Offset(left + frameSize - cornerLen, top + frameSize), Offset(left + frameSize, top + frameSize), strokeW)
        drawLine(cornerColor, Offset(left + frameSize, top + frameSize - cornerLen), Offset(left + frameSize, top + frameSize), strokeW)
    }
}

// ─────────────────────────────────────────────
// 底部提示（简洁，无卡片）
// ─────────────────────────────────────────────

@Composable
private fun BottomHint(
    modifier: Modifier = Modifier,
    lastResult: String?,
    scanning: Boolean,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.45f),
                    )
                )
            )
            .padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lastResult != null) {
            Text(
                text = lastResult,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = if (scanning) "将 DPM 码对准扫描框" else "已停止",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
