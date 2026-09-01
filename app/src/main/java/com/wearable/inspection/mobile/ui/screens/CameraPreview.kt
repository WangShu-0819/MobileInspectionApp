package com.wearable.inspection.mobile.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.wearable.inspection.mobile.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.wearable.inspection.mobile.R
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.camera.CameraStateType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 相机预览组件
 *
 * 职责：
 * 1. 请求相机权限
 * 2. 绑定 PreviewView 到 CameraController
 * 3. 管理生命周期（进入/退出页面）
 * 4. 处理错误状态（权限拒绝、无相机、超时等）
 *
 * 不自行创建 CameraController 实例，使用外部提供的单例
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onPermissionDenied: () -> Unit = {},
    onPermissionPermanentlyDenied: () -> Unit = {},
    onCameraError: (CameraError) -> Unit = {},
    onCameraReady: () -> Unit = {}
) {
    val context = LocalContext.current
    val cameraController = CameraController.getInstance(context)

    // 权限状态
    var permissionState by remember { mutableStateOf( PermissionState.REQUESTING) }
    var hasRequestedPermission by remember { mutableStateOf(false) }

    // 相机连接状态
    var cameraError by remember { mutableStateOf<CameraError?>(null) }

    // 相机执行器（页面级生命周期）
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 调试模式日志
    if (BuildConfig.DEBUG) {
        DisposableEffect(Unit) {
            onDispose {
                android.util.Log.d("CameraPreview", "CameraPreview disposed")
            }
        }
    }

    // 生命周期事件处理：ON_RESUME 时重新检查权限
    var isResumed by remember(lifecycleOwner) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isResumed = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            // 从系统设置返回后，重新检查权限
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                permissionState = PermissionState.GRANTED
            } else if (hasRequestedPermission) {
                // 已请求过权限，检查是否能再次请求
                val activity = context as? Activity
                if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    permissionState = PermissionState.PERMANENTLY_DENIED
                }
            }
        }
    }

    // 权限检查（首次进入）
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        permissionState = if (hasPermission) {
            PermissionState.GRANTED
        } else {
            PermissionState.REQUESTING
        }
    }

    // 处理权限结果（从 Activity Result 回调）
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRequestedPermission = true

        permissionState = if (isGranted) {
            PermissionState.GRANTED
        } else {
            // 检查是否被永久拒绝
            val activity = context as? Activity
            if (activity != null && activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                // shouldShowRequestPermissionRationale 返回 true 表示用户拒绝但未勾选"不再询问"
                PermissionState.DENIED
            } else {
                // shouldShowRequestPermissionRationale 返回 false 可能表示：
                // 1. 用户首次拒绝
                // 2. 用户勾选了"不再询问"
                // 不直接判断为 PERMANENTLY_DENIED，而是在 ON_RESUME 时再次检查
                PermissionState.DENIED
            }
        }
    }

    // 请求权限（当状态变为 REQUESTING 时）
    LaunchedEffect(permissionState) {
        if (permissionState == PermissionState.REQUESTING) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 处理权限拒绝
    LaunchedEffect(permissionState) {
        when (permissionState) {
            PermissionState.DENIED -> onPermissionDenied()
            PermissionState.PERMANENTLY_DENIED -> onPermissionPermanentlyDenied()
            else -> { /* 不处理 */ }
        }
    }

    // 打开系统设置（供外部调用）
    val openSystemSettings: () -> Unit = {
        val activity = context as? Activity
        if (activity != null) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${activity.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("CameraPreview", "Failed to open system settings", e)
                }
            }
        }
    }

    Box(modifier = modifier) {
        // 权限已授予
        if (permissionState == PermissionState.GRANTED) {
            // 相机预览
            CameraPreviewContent(
                lifecycleOwner = lifecycleOwner,
                cameraController = cameraController,
                cameraExecutor = cameraExecutor,
                onCameraError = { error ->
                    cameraError = error
                    onCameraError(error)
                },
                onCameraReady = onCameraReady
            )
        }

        // 加载指示器（权限请求中或相机初始化中）
        if (permissionState == PermissionState.REQUESTING ||
            (permissionState == PermissionState.GRANTED && cameraError == null && !cameraController.isActive)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 错误覆盖层
        cameraError?.let { error ->
            CameraErrorOverlay(
                error = error,
                onRetry = {
                    cameraError = null
                    // 重新连接相机
                },
                onOpenSettings = if (error == CameraError.PermissionPermanentlyDenied) {
                    { openSystemSettings() }
                } else null
            )
        }
    }

    // 页面离开时清理
    DisposableEffect(lifecycleOwner) {
        onDispose {
            // 停止相机预览（页面级，不影响全局 CameraController）
            cameraController.disconnect()
            cameraExecutor.shutdown()
        }
    }
}


/**
 * 相机预览内容
 */
@Composable
private fun CameraPreviewContent(
    lifecycleOwner: LifecycleOwner,
    cameraController: CameraController,
    cameraExecutor: java.util.concurrent.Executor,
    onCameraError: (CameraError) -> Unit,
    onCameraReady: () -> Unit
) {
    val context = LocalContext.current

    // PreviewView 引用和实际图像区域
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var contentRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var previewViewSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var hasCalledReady by remember { mutableStateOf(false) }

    // CameraState 状态（用于触发 contentRect 重算和 ready/error 回调）
    val cameraState by cameraController.cameraStateFlow.collectAsState()

    // 计算实际图像显示区域（用于后续 ROI、轮廓和点击对焦）
    // cameraState 作为 key：当相机 OPEN 时 stream info 可用，触发重算
    LaunchedEffect(previewViewSize, cameraState) {
        val sizePair = previewViewSize ?: return@LaunchedEffect
        val pvWidth = sizePair.first
        val pvHeight = sizePair.second

        if (BuildConfig.DEBUG) {
            android.util.Log.d("CameraPreview", "PreviewView size: ${pvWidth}x${pvHeight}")
        }

        // 获取实际流分辨率和旋转（来自 UseCase 的 ResolutionInfo）
        val streamSize = cameraController.streamResolution ?: return@LaunchedEffect
        val rotation = cameraController.streamRotation

        // 根据旋转调整流尺寸（90/270 度交换宽高）
        val rotatedWidth = if (rotation == 90 || rotation == 270) {
            streamSize.height
        } else {
            streamSize.width
        }
        val rotatedHeight = if (rotation == 90 || rotation == 270) {
            streamSize.width
        } else {
            streamSize.height
        }

        // 使用生产函数计算 contentRect
        val bounds = calculateContentRectBounds(
            viewWidth = pvWidth,
            viewHeight = pvHeight,
            rotatedStreamWidth = rotatedWidth,
            rotatedStreamHeight = rotatedHeight
        )
        val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)

        // 边界断言（Debug 模式）
        if (BuildConfig.DEBUG) {
            assert(rect.left >= 0) { "left 必须 >= 0，实际: ${rect.left}" }
            assert(rect.top >= 0) { "top 必须 >= 0，实际: ${rect.top}" }
            assert(rect.right <= pvWidth) { "right 必须 <= viewWidth，实际: ${rect.right} > ${pvWidth}" }
            assert(rect.bottom <= pvHeight) { "bottom 必须 <= viewHeight，实际: ${rect.bottom} > ${pvHeight}" }

            val streamRatio = rotatedWidth.toFloat() / rotatedHeight
            val contentRectRatio = rect.width().toFloat() / rect.height()
            val tolerance = 0.01f
            assert(kotlin.math.abs(streamRatio - contentRectRatio) < tolerance) {
                "contentRect 比例 (${"%.4f".format(contentRectRatio)}) 应与流比例 (${"%.4f".format(streamRatio)}) 一致"
            }

            android.util.Log.d("CameraPreview", "contentRect: ${rect.width()} x ${rect.height()}")
        }

        contentRect = rect
    }

    // 连接相机（connect 成功只表示 BOUND，不触发 onCameraReady）
    LaunchedEffect(previewView, lifecycleOwner) {
        if (previewView == null) return@LaunchedEffect

        try {
            val surfaceProvider = previewView!!.surfaceProvider

            cameraController.connect(
                lifecycleOwner = lifecycleOwner,
                surfaceProvider = surfaceProvider,
                mode = CameraMode.INSPECTION
            ).onFailure { throwable ->
                val error = when (throwable) {
                    is SecurityException -> CameraError.PermissionDenied
                    else -> CameraError.Unknown(throwable.message ?: "Unknown error")
                }
                onCameraError(error)
            }
        } catch (e: Exception) {
            onCameraError(CameraError.Unknown(e.message ?: "Connection failed"))
        }
    }

    // 观察 CameraState：仅 OPEN 触发 onCameraReady，ERROR 触发 onCameraError
    LaunchedEffect(cameraState) {
        when (cameraState) {
            CameraStateType.OPEN -> {
                if (!hasCalledReady) {
                    hasCalledReady = true
                    onCameraReady()
                }
            }
            CameraStateType.ERROR -> {
                val err = cameraController.error
                onCameraError(CameraError.Unknown(err ?: "Camera error"))
            }
            else -> { /* PENDING_OPEN / CLOSED / null → 保持加载状态 */ }
        }
    }

    // PreviewView
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 使用 FIT_CENTER 保持比例，不裁切边缘
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                // 保存引用
                previewView = this

                // 使用 ViewTreeObserver 监听尺寸变化
                viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val width = this@apply.width
                        val height = this@apply.height
                        if (width > 0 && height > 0) {
                            previewViewSize = width to height
                        }
                    }
                })
            }
        },
        update = { view ->
            // PreviewView 配置更新
        }
    )
}

/**
 * 相机错误覆盖层
 */
@Composable
fun CameraErrorOverlay(
    error: CameraError,
    onRetry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = error.message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (error.recoverable && onRetry != null) {
                    Button(
                        onClick = onRetry
                    ) {
                        Text("重试")
                    }
                }
                if (error == CameraError.PermissionPermanentlyDenied && onOpenSettings != null) {
                    Button(
                        onClick = onOpenSettings
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("设置")
                    }
                }
            }
        }
    }
}

/**
 * 权限状态
 */
private enum class PermissionState {
    REQUESTING,    // 正在请求权限
    GRANTED,       // 权限已授予
    DENIED,        // 权限被拒绝
    PERMANENTLY_DENIED  // 权限被永久拒绝
}

/**
 * 相机预览页面（现场采集）
 *
 * @param partId 零件 ID（当前未使用，保留供后续扩展）
 * @param onBack 返回回调
 */
@Composable
fun CameraPreviewScreen(
    partId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 权限状态管理
    var permissionDeniedCount by remember { mutableStateOf(0) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // 相机状态
    var cameraError by remember { mutableStateOf<CameraError?>(null) }
    var isCameraReady by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 相机预览
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onPermissionDenied = {
                permissionDeniedCount++
                if (permissionDeniedCount >= 2) {
                    showPermissionDeniedDialog = true
                }
            },
            onPermissionPermanentlyDenied = {
                cameraError = CameraError.PermissionPermanentlyDenied
            },
            onCameraError = { error ->
                cameraError = error
            },
            onCameraReady = {
                isCameraReady = true
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CameraPreviewScreen", "Camera ready")
                }
            }
        )

        // 权限被拒绝确认对话框
        if (showPermissionDeniedDialog) {
            PermissionDeniedDialog(
                onDismiss = { showPermissionDeniedDialog = false },
                onConfirm = onBack
            )
        }
    }
}

/**
 * 权限被拒绝确认对话框
 */
@Composable
fun PermissionDeniedDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "相机权限被拒绝")
        },
        text = {
            Text(text = "相机权限是现场采集功能的必要权限。您可以在设置中重新开启权限。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("返回")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
