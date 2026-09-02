package com.wearable.inspection.mobile.ui.screens

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.min

/** 扫描框边长占预览短边的比例。 */
private const val SCAN_FRAME_RATIO = 0.72f

/**
 * DPM 扫码页面
 *
 * ROI 映射流程：
 * 1. CameraPreview 以 DPM_SCAN 模式连接，onFrameInfo 报告 contentRect + stream 信息
 * 2. ScanOverlay 绘制居中的 1:1 扫描框，返回屏幕坐标 Rect
 * 3. DpmScanRoiMapper 将屏幕 Rect 映射到旋转后 Bitmap 坐标
 * 4. 动态 scanRoi 传给 DpmFrameAnalyzer（随布局/旋转变化更新）
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // 追踪已连接的会话 ID
    var connectedSessionId by remember { mutableStateOf<String?>(null) }

    // 帧信息（contentRect + stream）和扫描框屏幕坐标
    var frameInfo by remember { mutableStateOf<FrameInfo?>(null) }
    var scanFrameScreenRect by remember { mutableStateOf<Rect?>(null) }

    // 动态计算 bitmap ROI 并更新 ViewModel
    LaunchedEffect(frameInfo, scanFrameScreenRect) {
        val fi = frameInfo
        val screenRect = scanFrameScreenRect
        if (fi != null && screenRect != null) {
            // 计算旋转后的 bitmap 尺寸
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

    // 退出时按 sessionId 清理
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "DPM 扫码",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = Primary,
                    actionIconContentColor = Primary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { viewModel.toggleTorch() } }) {
                        Icon(
                            imageVector = if (scanState.torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (scanState.torchOn) "关闭闪光灯" else "开启闪光灯"
                        )
                    }
                }
            )
        },
        containerColor = Color.Black,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 相机预览：以 DPM_SCAN 模式连接
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                cameraMode = CameraMode.DPM_SCAN,
                onConnected = { controller, sessionId ->
                    connectedSessionId = sessionId
                    viewModel.startScan(controller = controller, sessionId = sessionId)
                },
                onFrameInfo = { info ->
                    frameInfo = info
                },
            )

            // 扫描框覆盖层：返回屏幕坐标 Rect
            ScanOverlay(
                modifier = Modifier.fillMaxSize(),
                frameRatio = SCAN_FRAME_RATIO,
                onFrameRect = { rect -> scanFrameScreenRect = rect },
            )

            // 底部结果卡片
            ResultCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                scanState = scanState,
                lastResult = lastResult?.rawValue,
            )
        }
    }
}

/**
 * 扫描框覆盖层：居中正方形、框外遮罩和四角定位线。
 *
 * @param frameRatio 扫描框边长占预览短边的比例
 * @param onFrameRect 回调扫描框在屏幕坐标系中的 Rect（相对于 Canvas 左上角）
 */
@Composable
private fun ScanOverlay(
    modifier: Modifier = Modifier,
    frameRatio: Float = 0.6f,
    onFrameRect: (Rect) -> Unit = {},
) {
    val overlayColor = Color.Black.copy(alpha = 0.4f)
    val frameColor = Primary

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val frameSize = min(w, h) * frameRatio.coerceIn(0.5f, 0.85f)
        val left = (w - frameSize) / 2
        val top = (h - frameSize) / 2

        // 回调屏幕坐标 Rect（px）
        onFrameRect(Rect(
            left.toInt(),
            top.toInt(),
            (left + frameSize).toInt(),
            (top + frameSize).toInt(),
        ))

        // 半透明遮罩
        drawRect(overlayColor, Offset.Zero, Size(w, top))
        drawRect(overlayColor, Offset(0f, top), Size(left, frameSize))
        drawRect(overlayColor, Offset(left + frameSize, top), Size(w - left - frameSize, frameSize))
        drawRect(overlayColor, Offset(0f, top + frameSize), Size(w, h - top - frameSize))

        // 低对比度完整边界负责界定识别区域，四角负责快速对准。
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(left, top),
            size = Size(frameSize, frameSize),
            cornerRadius = CornerRadius(10f, 10f),
            style = Stroke(width = 2f),
        )

        // 四角实线装饰
        val cornerLen = 42f
        val strokeWidth = 6f
        drawLine(frameColor, Offset(left, top + cornerLen), Offset(left, top), strokeWidth)
        drawLine(frameColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth)
        drawLine(frameColor, Offset(left + frameSize - cornerLen, top), Offset(left + frameSize, top), strokeWidth)
        drawLine(frameColor, Offset(left + frameSize, top), Offset(left + frameSize, top + cornerLen), strokeWidth)
        drawLine(frameColor, Offset(left, top + frameSize - cornerLen), Offset(left, top + frameSize), strokeWidth)
        drawLine(frameColor, Offset(left, top + frameSize), Offset(left + cornerLen, top + frameSize), strokeWidth)
        drawLine(frameColor, Offset(left + frameSize - cornerLen, top + frameSize), Offset(left + frameSize, top + frameSize), strokeWidth)
        drawLine(frameColor, Offset(left + frameSize, top + frameSize - cornerLen), Offset(left + frameSize, top + frameSize), strokeWidth)
    }
}

/**
 * 底部结果卡片
 */
@Composable
private fun ResultCard(
    modifier: Modifier = Modifier,
    scanState: com.wearable.inspection.mobile.dpm.DpmScanState,
    lastResult: String?,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceWhite.copy(alpha = 0.95f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = if (scanState.scanning) Primary else TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (scanState.scanning) "扫描中…" else "已停止",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (scanState.scanning) Primary else TextSecondary,
                )
                if (scanState.decodeCount > 0) {
                    Text(
                        text = "· 已识别 ${scanState.decodeCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }

            if (lastResult != null) {
                Text(
                    text = lastResult,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "将 DPM 码对准扫描框",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
