package com.wearable.inspection.mobile.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import com.wearable.inspection.mobile.R
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraMode
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

    // 相机连接状态
    var cameraError by remember { mutableStateOf<CameraError?>(null) }

    // 相机执行器（页面级生命周期）
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 权限检查
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
        permissionState = if (isGranted) {
            PermissionState.GRANTED
        } else {
            // 检查是否被永久拒绝
            val activity = context as? Activity
            if (activity != null && activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                PermissionState.DENIED
            } else {
                PermissionState.PERMANENTLY_DENIED
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

        // 加载指示器
        if (permissionState == PermissionState.REQUESTING || cameraError == null && permissionState == PermissionState.GRANTED) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 错误覆盖层
        cameraError?.let { error ->
            CameraErrorOverlay(
                error = error,
                modifier = Modifier.fillMaxSize()
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
    val coroutineScope = rememberCoroutineScope()

    // PreviewView 引用和实际图像区域
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var contentRect by remember { mutableStateOf<android.graphics.Rect?>(null) }

    // 计算实际图像显示区域（用于后续 ROI、轮廓和点击对焦）
    LaunchedEffect(previewView) {
        val pv = previewView ?: return@LaunchedEffect
        // 等待 PreviewView 布局完成
        snapshotFlow { pv.width to pv.height }
            .filter { (w, h) -> w > 0 && h > 0 }
            .first()
            .let { (pvWidth, pvHeight) ->
                // 默认 4:3 比例，后续可从 CameraX 获取真实流尺寸
                val streamRatio = 4f / 3f

                // 根据 ScaleType.FIT_CENTER 计算 contentRect
                val previewRatio = pvWidth.toFloat() / pvHeight

                val rect = if (previewRatio > streamRatio) {
                    // 预览更宽，上下留边
                    val contentHeight = (pvWidth / streamRatio).toInt()
                    val top = (pvHeight - contentHeight) / 2
                    android.graphics.Rect(0, top, pvWidth, top + contentHeight)
                } else {
                    // 预览更高，左右留边
                    val contentWidth = (pvHeight * streamRatio).toInt()
                    val left = (pvWidth - contentWidth) / 2
                    android.graphics.Rect(left, 0, left + contentWidth, pvHeight)
                }

                contentRect = rect
            }
    }

    // 连接相机
    LaunchedEffect(previewView, lifecycleOwner) {
        if (previewView == null) return@LaunchedEffect

        try {
            val surfaceProvider = previewView!!.surfaceProvider

            // 绑定到生命周期
            cameraController.connect(
                lifecycleOwner = lifecycleOwner,
                surfaceProvider = surfaceProvider,
                mode = CameraMode.INSPECTION
            ).onSuccess {
                onCameraReady()
            }.onFailure { throwable ->
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
private fun CameraErrorOverlay(
    error: CameraError,
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
            if (error.recoverable) {
                Button(
                    onClick = {
                        // TODO: 重试按钮
                    }
                ) {
                    Text("重试")
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
