package com.wearable.inspection.mobile.ui.screens

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.wearable.inspection.mobile.BuildConfig
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.camera.CameraStateType
import kotlinx.coroutines.launch

/**
 * 相机预览（可复用组件）
 *
 * 核心行为：
 * - PreviewView.ScaleType = FIT_CENTER，竖屏 3:4 完整显示，允许 letterbox
 * - connect() 成功只表示 BOUND；CameraStateType.OPEN 后才隐藏加载并触发 onCameraReady
 * - 权限请求、临时拒绝、永久拒绝（系统设置入口）、错误重试
 * - 实际 streamResolution/streamRotation + ContentRectCalculator
 * - 诊断日志（DEBUG）+ 四角/中央圆校准覆盖层（DEBUG）
 * - 使用 sessionId 防止异步 disconnect 竞态
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onCameraReady: () -> Unit = {},
    onCameraError: (CameraError) -> Unit = {},
    onPermissionDenied: () -> Unit = {},
    onPermissionPermanentlyDenied: () -> Unit = {},
    onSessionReady: (sessionId: String?) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { CameraController.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // 状态
    var permissionRequested by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }
    var hasCalledReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<CameraError?>(null) }
    var contentRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }

    // CameraState 观察
    val cameraState by cameraController.cameraStateFlow.collectAsState()

    // PreviewView（FIT_CENTER）
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
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
            CameraMode.INSPECTION
        )
        result.fold(
            onSuccess = { session ->
                currentSessionId = session.sessionId
                onSessionReady(session.sessionId)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CameraPreview", "连接成功，sessionId: ${session.sessionId}")
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

    // CameraState 驱动加载状态（仅 OPEN 触发 onCameraReady）
    LaunchedEffect(cameraState) {
        when (cameraState) {
            CameraStateType.OPEN -> {
                if (!hasCalledReady) {
                    hasCalledReady = true
                    cameraError = null

                    // 计算 contentRect
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
                                val bounds = calculateContentRectBounds(viewW, viewH, rotatedW, rotatedH)
                                contentRect = android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)

                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("CameraPreview", "=== 画幅诊断 ===")
                                    android.util.Log.d("CameraPreview", "PreviewView: ${viewW}x${viewH}")
                                    android.util.Log.d("CameraPreview", "流尺寸: ${streamRes.width}x${streamRes.height}")
                                    android.util.Log.d("CameraPreview", "旋转: ${streamRot}°")
                                    android.util.Log.d("CameraPreview", "旋转后: ${rotatedW}x${rotatedH}")
                                    android.util.Log.d("CameraPreview", "contentRect: $contentRect")
                                }
                            }
                        }
                    }

                    onCameraReady()
                }
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
            hasCalledReady = false
            contentRect = null
            currentSessionId = null
        }
    }

    // 重试函数
    val onRetry: () -> Unit = {
        cameraError = null
        hasCalledReady = false
        contentRect = null
        currentSessionId = null
        // 触发重新连接
        hasCameraPermission = false
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier) {
        // 相机预览
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

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

                    // 中央圆（不变形检测）
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    val radius = minOf(rect.width(), rect.height()) / 4f
                    drawCircle(Color.Yellow, radius, Offset(cx, cy), style = Stroke(2f))
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
