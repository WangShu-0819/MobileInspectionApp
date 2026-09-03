package com.wearable.inspection.mobile.ui.screens

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.camera.CameraStateType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 相机帧信息 — 用于 ROI 映射
 */
data class FrameInfo(
    val contentRect: android.graphics.Rect,
    val streamResolution: android.util.Size,
    val streamRotation: Int,
    val previewWidth: Int,
    val previewHeight: Int,
)

/**
 * 相机预览（可复用组件）
 *
 * 核心行为：
 * - PreviewView.ScaleType 可选 FIT_CENTER（原比例）或 FILL_CENTER（铺满并裁边）
 * - connect() 成功只表示 BOUND；CameraStateType.OPEN 后才隐藏加载并触发 onCameraReady
 * - 权限请求、临时拒绝、永久拒绝（系统设置入口）、错误重试
 * - 实际 streamResolution/streamRotation + ContentRectCalculator
 * - 诊断日志（DEBUG）+ 四角校准覆盖层（DEBUG）
 * - 使用 sessionId 防止异步 disconnect 竞态
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraMode: CameraMode = CameraMode.INSPECTION,
    templateImagePath: String? = null,
    overlayAlpha: Float = 0f,
    previewScaleType: PreviewView.ScaleType = PreviewView.ScaleType.FIT_CENTER,
    onCameraReady: () -> Unit = {},
    onCameraError: (CameraError) -> Unit = {},
    onPermissionDenied: () -> Unit = {},
    onPermissionPermanentlyDenied: () -> Unit = {},
    onSessionReady: (sessionId: String?) -> Unit = {},
    onConnected: ((CameraController, String) -> Unit)? = null,
    onFrameInfo: ((FrameInfo) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { CameraController.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // 状态
    var permissionRequested by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<CameraError?>(null) }
    var contentRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }

    // 模板叠加图片（原子更新：templateImagePath 变化时重新加载）
    var templateBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var templateLoadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(templateImagePath) {
        templateBitmap = null
        templateLoadError = null
        if (templateImagePath != null) {
            try {
                val file = java.io.File(templateImagePath)
                if (!file.exists() || file.length() == 0L) {
                    templateLoadError = "模板图片不存在"
                } else {
                    val bmp = BitmapFactory.decodeFile(templateImagePath)
                    if (bmp == null) {
                        templateLoadError = "模板图片解码失败"
                    } else {
                        templateBitmap = bmp
                    }
                }
            } catch (e: Exception) {
                templateLoadError = "模板图片加载失败"
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("CameraPreview", "Template overlay load failed", e)
                }
            }
        }
    }

    // CameraState 观察
    val cameraState by cameraController.cameraStateFlow.collectAsState()

    // PreviewView；显示模式变化时只调整缩放，不重新绑定 CameraX
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = previewScaleType
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    LaunchedEffect(previewScaleType) {
        previewView.scaleType = previewScaleType
    }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        permissionRequested = true
        if (!isGranted) {
            // 检查是否永久拒绝
            val shouldShowRationale = androidx.core.app.ActivityCompat
                .shouldShowRequestPermissionRationale(
                    context as android.app.Activity,
                    Manifest.permission.CAMERA
                )
            isPermanentlyDenied = !shouldShowRationale
            if (isPermanentlyDenied) {
                onPermissionPermanentlyDenied()
            } else {
                onPermissionDenied()
            }
        }
    }

    // 初始权限检查
    LaunchedEffect(Unit) {
        val currentPermission = androidx.core.content.ContextCompat
            .checkSelfPermission(context, Manifest.permission.CAMERA)
        if (currentPermission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 连接相机（权限就绪后）
    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect

        val result = cameraController.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            cameraMode,
        )
        result.fold(
            onSuccess = { session ->
                currentSessionId = session.sessionId
                onSessionReady(session.sessionId)
                onConnected?.invoke(cameraController, session.sessionId)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CameraPreview", "连接成功，mode=$cameraMode, sessionId: ${session.sessionId}")
                }
            },
            onFailure = { error ->
                onSessionReady(null)
                cameraError = when (error) {
                    is SecurityException -> CameraError.PermissionDenied
                    else -> CameraError.Unknown("相机启动失败")
                }
                onCameraError(cameraError!!)
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("CameraPreview", "连接失败", error)
                }
            }
        )
    }

    // CameraState / 显示模式驱动加载状态（模式变化不重新绑定 CameraX）
    LaunchedEffect(cameraState, previewScaleType) {
        when (cameraState) {
            CameraStateType.OPEN -> {
                cameraError = null

                // 计算当前显示模式下的内容区域
                val streamRes = cameraController.streamResolution
                val streamRot = cameraController.streamRotation
                if (streamRes != null) {
                    val rotatedW: Int
                    val rotatedH: Int
                    if (streamRot == 90 || streamRot == 270) {
                        rotatedW = streamRes.height
                        rotatedH = streamRes.width
                    } else {
                        rotatedW = streamRes.width
                        rotatedH = streamRes.height
                    }

                    // 等待 PreviewView 布局
                    previewView.post {
                        val viewW = previewView.width
                        val viewH = previewView.height
                        if (viewW > 0 && viewH > 0 && rotatedW > 0 && rotatedH > 0) {
                            val bounds = if (previewScaleType == PreviewView.ScaleType.FIT_CENTER) {
                                calculateContentRectBounds(viewW, viewH, rotatedW, rotatedH)
                            } else {
                                ContentRectBounds(0, 0, viewW, viewH)
                            }
                            val rect = android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                            contentRect = rect

                            // 通知帧信息（用于 ROI 映射）
                            onFrameInfo?.invoke(FrameInfo(
                                contentRect = rect,
                                streamResolution = streamRes,
                                streamRotation = streamRot,
                                previewWidth = viewW,
                                previewHeight = viewH,
                            ))

                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("CameraPreview", "=== 画幅诊断 ===")
                                android.util.Log.d("CameraPreview", "PreviewView: ${viewW}x${viewH}")
                                android.util.Log.d("CameraPreview", "流尺寸: ${streamRes.width}x${streamRes.height}")
                                android.util.Log.d("CameraPreview", "旋转: ${streamRot}°")
                                android.util.Log.d("CameraPreview", "旋转后: ${rotatedW}x${rotatedH}")
                                android.util.Log.d("CameraPreview", "scaleType: $previewScaleType")
                                android.util.Log.d("CameraPreview", "contentRect: $contentRect")
                            }
                        }
                    }
                }

                onCameraReady()
            }
            CameraStateType.ERROR -> {
                cameraError = CameraError.Unknown("相机启动失败")
                onCameraError(cameraError!!)
            }
            else -> { /* PENDING_OPEN / CLOSED → 保持加载 */ }
        }
    }

    // 断开连接（使用 sessionId 防止竞态）
    DisposableEffect(Unit) {
        onDispose {
            val sessionId = currentSessionId
            if (sessionId != null) {
                coroutineScope.launch {
                    val disconnected = cameraController.disconnect(sessionId)
                    if (BuildConfig.DEBUG) {
                        if (disconnected) {
                            android.util.Log.d("CameraPreview", "已断开会话: $sessionId")
                        } else {
                            android.util.Log.d("CameraPreview", "会话已过期，忽略 disconnect: $sessionId")
                        }
                    }
                }
            }
            contentRect = null
            currentSessionId = null
        }
    }

    // 重试函数
    val onRetry: () -> Unit = {
        cameraError = null
        contentRect = null
        currentSessionId = null
        // 触发重新连接
        hasCameraPermission = false
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier.clipToBounds()) {
        // 相机预览
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 模板图片叠加（仅在 contentRect 内绘制，保持原始纵横比）
        val currentContentRect = contentRect
        val currentBitmap = templateBitmap
        if (currentContentRect != null && currentBitmap != null && overlayAlpha > 0f) {
            val rectWidth = currentContentRect.width().toFloat()
            val rectHeight = currentContentRect.height().toFloat()
            if (rectWidth > 0f && rectHeight > 0f) {
                val bmpWidth = currentBitmap.width.toFloat()
                val bmpHeight = currentBitmap.height.toFloat()
                // 原比例完整显示；填充模式与相机一样放大并裁切边缘
                val scale = if (previewScaleType == PreviewView.ScaleType.FILL_CENTER) {
                    maxOf(rectWidth / bmpWidth, rectHeight / bmpHeight)
                } else {
                    minOf(rectWidth / bmpWidth, rectHeight / bmpHeight)
                }
                val drawWidth = bmpWidth * scale
                val drawHeight = bmpHeight * scale
                // 居中
                val offsetX = currentContentRect.left + (rectWidth - drawWidth) / 2f
                val offsetY = currentContentRect.top + (rectHeight - drawHeight) / 2f

                // 在父坐标中直接绘制，允许 FILL_CENTER 的裁切区域为负坐标，
                // 避免使用负 offset 的 Compose Image 时 overlay 被 SurfaceView 遮挡。
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = currentBitmap.asImageBitmap(),
                        dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                        dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
                        alpha = overlayAlpha.coerceIn(0f, 0.8f),
                    )
                }
            }
        }

        // 模板加载错误提示（不 crash，仅显示文字）
        if (templateLoadError != null && templateImagePath != null) {
            Text(
                text = templateLoadError!!,
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 加载指示器（OPEN 前显示）
        val isOpen = cameraState == CameraStateType.OPEN
        if (!isOpen && cameraError == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }

        // 错误状态（简洁 UI，不显示原始异常）
        cameraError?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = error.message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                // 权限问题时显示设置入口
                if (error is CameraError.PermissionPermanentlyDenied) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("前往设置")
                    }
                }

                // 重试按钮
                if (error.recoverable) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("重试")
                    }
                }
            }
        }

        // DEBUG 校准覆盖层
        if (BuildConfig.DEBUG) {
            contentRect?.let { rect ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 四角标记
                    val cornerLen = 20f
                    val strokeW = 2f
                    val color = Color.Green

                    // 左上
                    drawLine(color, Offset(rect.left.toFloat(), rect.top.toFloat()),
                        Offset(rect.left.toFloat() + cornerLen, rect.top.toFloat()), strokeW)
                    drawLine(color, Offset(rect.left.toFloat(), rect.top.toFloat()),
                        Offset(rect.left.toFloat(), rect.top.toFloat() + cornerLen), strokeW)

                    // 右上
                    drawLine(color, Offset(rect.right.toFloat(), rect.top.toFloat()),
                        Offset(rect.right.toFloat() - cornerLen, rect.top.toFloat()), strokeW)
                    drawLine(color, Offset(rect.right.toFloat(), rect.top.toFloat()),
                        Offset(rect.right.toFloat(), rect.top.toFloat() + cornerLen), strokeW)

                    // 左下
                    drawLine(color, Offset(rect.left.toFloat(), rect.bottom.toFloat()),
                        Offset(rect.left.toFloat() + cornerLen, rect.bottom.toFloat()), strokeW)
                    drawLine(color, Offset(rect.left.toFloat(), rect.bottom.toFloat()),
                        Offset(rect.left.toFloat(), rect.bottom.toFloat() - cornerLen), strokeW)

                    // 右下
                    drawLine(color, Offset(rect.right.toFloat(), rect.bottom.toFloat()),
                        Offset(rect.right.toFloat() - cornerLen, rect.bottom.toFloat()), strokeW)
                    drawLine(color, Offset(rect.right.toFloat(), rect.bottom.toFloat()),
                        Offset(rect.right.toFloat(), rect.bottom.toFloat() - cornerLen), strokeW)

                }
            }
        }
    }
}

/**
 * 相机预览全屏页面（导航路由用）
 */
@Composable
fun CameraPreviewScreen(
    partId: String,
    onBack: () -> Unit
) {
    CameraPreview(modifier = Modifier.fillMaxSize())
}
