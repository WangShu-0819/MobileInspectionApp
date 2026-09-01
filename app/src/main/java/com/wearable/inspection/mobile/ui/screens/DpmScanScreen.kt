package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.dpm.DpmScanViewModel
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * DPM 扫码页面
 *
 * UI 结构：
 * - 顶部 AppBar（返回 + 闪光灯）
 * - 相机预览（全屏）
 * - 扫描框覆盖层（中心虚线矩形）
 * - 底部结果卡片（解码文本 + 计数）
 *
 * 生命周期：
 * - onAppear: 切换 CameraController 到 DPM_SCAN 模式，启动 DpmScanViewModel
 * - onDispose: 停止扫码，切回 IDLE 模式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpmScanScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit = {},
    viewModel: DpmScanViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { CameraController.getInstance(context) }
    val scanState by viewModel.scanState.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()

    // 切换到 DPM_SCAN 模式并启动扫码
    LaunchedEffect(Unit) {
        cameraController.switchMode(CameraMode.DPM_SCAN)
        viewModel.startScan()
    }

    // 退出时停止扫码
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopScan()
        }
    }

    // 解码结果回调
    LaunchedEffect(lastResult) {
        lastResult?.let { result ->
            onResult(result.rawValue)
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
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleTorch() }) {
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
            // 相机预览
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onCameraReady = {},
                onCameraError = {},
            )

            // 扫描框覆盖层
            ScanOverlay(
                modifier = Modifier.fillMaxSize()
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
 * 扫描框覆盖层 — 中心虚线矩形
 */
@Composable
private fun ScanOverlay(modifier: Modifier = Modifier) {
    val overlayColor = Color.Black.copy(alpha = 0.4f)
    val frameColor = Primary
    val frameSize = 0.6f // 占屏幕宽/高的比例

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val frameW = w * frameSize
        val frameH = h * frameSize
        val left = (w - frameW) / 2
        val top = (h - frameH) / 2

        // 半透明遮罩
        drawRect(overlayColor, Offset.Zero, Size(w, top))
        drawRect(overlayColor, Offset(0f, top), Size(left, frameH))
        drawRect(overlayColor, Offset(left + frameW, top), Size(w - left - frameW, frameH))
        drawRect(overlayColor, Offset(0f, top + frameH), Size(w, h - top - frameH))

        // 虚线扫描框
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = Size(frameW, frameH),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(width = 3f, pathEffect = dashEffect),
        )

        // 四角实线装饰
        val cornerLen = 30f
        val strokeWidth = 4f
        // 左上
        drawLine(frameColor, Offset(left, top + cornerLen), Offset(left, top), strokeWidth)
        drawLine(frameColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth)
        // 右上
        drawLine(frameColor, Offset(left + frameW - cornerLen, top), Offset(left + frameW, top), strokeWidth)
        drawLine(frameColor, Offset(left + frameW, top), Offset(left + frameW, top + cornerLen), strokeWidth)
        // 左下
        drawLine(frameColor, Offset(left, top + frameH - cornerLen), Offset(left, top + frameH), strokeWidth)
        drawLine(frameColor, Offset(left, top + frameH), Offset(left + cornerLen, top + frameH), strokeWidth)
        // 右下
        drawLine(frameColor, Offset(left + frameW - cornerLen, top + frameH), Offset(left + frameW, top + frameH), strokeWidth)
        drawLine(frameColor, Offset(left + frameW, top + frameH - cornerLen), Offset(left + frameW, top + frameH), strokeWidth)
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
            // 状态行
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

            // 解码结果
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
