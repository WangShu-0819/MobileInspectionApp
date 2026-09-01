package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.view.PreviewView
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraError
import com.wearable.inspection.mobile.camera.CameraMode
import kotlinx.coroutines.launch

/**
 * 相机预览（可复用组件）
 *
 * 生命周期：LaunchedEffect 连接相机，DisposableEffect 销毁时断开。
 * isCameraReady 为 mutableStateOf，相机状态变化时自动触发 Compose 重组。
 *
 * @param modifier 修饰符
 * @param onCameraReady 相机就绪回调
 * @param onCameraError 相机错误回调
 * @param onPermissionDenied 权限拒绝回调
 * @param onPermissionPermanentlyDenied 权限永久拒绝回调
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onCameraReady: () -> Unit = {},
    onCameraError: (CameraError) -> Unit = {},
    onPermissionDenied: () -> Unit = {},
    onPermissionPermanentlyDenied: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { CameraController.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var isCameraReady by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    LaunchedEffect(Unit) {
        val result = cameraController.connect(lifecycleOwner, previewView.surfaceProvider, CameraMode.INSPECTION)
        result.fold(
            onSuccess = {
                isCameraReady = true
                onCameraReady()
            },
            onFailure = { error ->
                isCameraReady = false
                when (error) {
                    is SecurityException -> onPermissionDenied()
                    else -> onCameraError(CameraError.Unknown(error.message ?: "相机连接失败"))
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                cameraController.disconnect()
            }
            isCameraReady = false
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        if (!isCameraReady) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
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
