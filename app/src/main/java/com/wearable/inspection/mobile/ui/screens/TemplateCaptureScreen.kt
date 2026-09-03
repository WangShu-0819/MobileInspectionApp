package com.wearable.inspection.mobile.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.camera.CameraStateType
import com.wearable.inspection.mobile.template.TemplateCaptureViewModel
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 模板拍摄页面
 *
 * 入口：
 * - 模板配置 → 新增视角拍摄（templateId = null）
 * - 模板配置/详情 → 指定 View 重拍（templateId != null）
 *
 * 复用唯一 CameraController 的 TEMPLATE_CAPTURE 模式。
 * CameraPreview 的 DisposableEffect 负责在页面离开时 disconnect。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateCaptureScreen(
    partId: String,
    templateId: String?,
    onBack: () -> Unit,
    onCaptureSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val viewModel: TemplateCaptureViewModel = viewModel(
        factory = TemplateCaptureViewModel.factory(repository, partId, templateId)
    )

    val captureState by viewModel.state.collectAsState()
    val cameraController = remember { CameraController.getInstance(context) }
    val cameraState by cameraController.cameraStateFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var sessionId by remember { mutableStateOf<String?>(null) }

    // 拍摄成功后返回
    LaunchedEffect(captureState) {
        if (captureState is TemplateCaptureViewModel.CaptureState.Saved) {
            kotlinx.coroutines.delay(800) // 短暂显示成功状态
            onCaptureSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (viewModel.isRecapture) "重拍视角" else "拍摄模板",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = Primary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                }
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
        ) {
            // 相机预览区域（占满剩余空间）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    cameraMode = CameraMode.TEMPLATE_CAPTURE,
                    onSessionReady = { id ->
                        sessionId = id
                    },
                    onCameraError = { error ->
                        sessionId = null
                    },
                )

                // 加载指示器（相机未就绪时）
                if (cameraState != CameraStateType.OPEN) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                    )
                }
            }

            // 底部操作栏
            CaptureControlBar(
                captureState = captureState,
                cameraReady = sessionId != null && cameraState == CameraStateType.OPEN,
                onCapture = {
                    val currentSessionId = sessionId
                    if (currentSessionId != null) {
                        viewModel.onCapture(currentSessionId, cameraController)
                    }
                },
                onRetry = { viewModel.resetState() },
            )
        }
    }
}

/**
 * 底部拍摄控制栏
 */
@Composable
private fun CaptureControlBar(
    captureState: TemplateCaptureViewModel.CaptureState,
    cameraReady: Boolean,
    onCapture: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceWhite)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (captureState) {
            is TemplateCaptureViewModel.CaptureState.Idle -> {
                Button(
                    onClick = onCapture,
                    enabled = cameraReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Color.White,
                        disabledContainerColor = Color.Gray,
                        disabledContentColor = Color.LightGray,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (cameraReady) "拍摄" else "相机初始化中…",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            is TemplateCaptureViewModel.CaptureState.Capturing -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "拍摄中…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            is TemplateCaptureViewModel.CaptureState.Saved -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PassColor,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "保存成功",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PassColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            is TemplateCaptureViewModel.CaptureState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = FailColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = captureState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FailColor,
                        )
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}
