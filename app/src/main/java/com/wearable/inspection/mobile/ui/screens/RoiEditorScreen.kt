package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary

/**
 * ROI 编辑器页
 *
 * 基于 Canvas 的 ROI 编辑器，支持：
 * - 绘制模式：点击+拖拽绘制新矩形
 * - 编辑模式：拖拽移动矩形、拖拽四角缩放
 * - 边界约束：矩形不超出图片内容区域
 * - 保存时将像素坐标转换为 normalizedRect（0-1 范围）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoiEditorScreen(
    templateId: String,
    onBack: () -> Unit,
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val viewModel = remember {
        RoiEditorViewModel(repository, templateId)
    }

    // 加载模板图片
    var templateImagePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(templateId) {
        val template = repository.getTemplate(templateId)
        templateImagePath = template?.mainImagePath
    }

    val rois = viewModel.rois
    val drawingRect = viewModel.drawingRect
    val selectedRoiId = viewModel.selectedRoiId
    val isDrawingMode = viewModel.isDrawingMode

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ROI 编辑器",
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
                },
                actions = {
                    if (selectedRoiId != null) {
                        IconButton(onClick = { viewModel.deleteSelectedRoi() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除选中 ROI",
                                tint = FailColor,
                            )
                        }
                    }
                }
            )
        },
        containerColor = customColors.pageBackground,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ROI Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                RoiCanvas(
                    imagePath = templateImagePath,
                    rois = rois,
                    drawingRect = drawingRect,
                    selectedRoiId = selectedRoiId,
                    isDrawingMode = isDrawingMode,
                    onDrawingRectUpdate = { viewModel.updateDrawingRect(it) },
                    onRoiSelected = { viewModel.selectRoi(it) },
                    onRoiMoved = { roiId, deltaNormX, deltaNormY ->
                        viewModel.moveRoi(roiId, deltaNormX, deltaNormY)
                    },
                    onRoiResized = { roiId, cornerIndex, newCornerNormX, newCornerNormY ->
                        viewModel.resizeRoi(roiId, cornerIndex, newCornerNormX, newCornerNormY)
                    },
                )
            }

            // 底部工具栏
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ROI 列表
                    if (rois.isNotEmpty()) {
                        Text(
                            text = "已定义 ${rois.size} 个 ROI",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }

                    // 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isDrawingMode) {
                            // 绘制模式：保存/取消
                            Button(
                                onClick = { viewModel.saveDrawingRect() },
                                enabled = drawingRect != null,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("保存")
                            }
                            Button(
                                onClick = { viewModel.cancelDrawing() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = DividerColor),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("取消")
                            }
                        } else {
                            // 普通模式：添加 ROI
                            Button(
                                onClick = { viewModel.toggleDrawingMode() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加 ROI")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ROI 编辑 Canvas
 *
 * 显示模板图片作为背景，叠加显示已有 ROI 和正在绘制的 ROI。
 * 编辑模式支持：点击选中已有 ROI、拖拽移动、四角缩放。
 * 绘制模式支持：拖拽绘制新矩形。
 */
@Composable
private fun RoiCanvas(
    imagePath: String?,
    rois: List<com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity>,
    drawingRect: NormalizedRect?,
    selectedRoiId: String?,
    isDrawingMode: Boolean,
    onDrawingRectUpdate: (NormalizedRect?) -> Unit,
    onRoiSelected: (String?) -> Unit,
    onRoiMoved: (String, Float, Float) -> Unit,
    onRoiResized: (String, Int, Float, Float) -> Unit,
) {
    // 加载图片
    val bitmap = remember(imagePath) {
        try {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 2
            }
            android.graphics.BitmapFactory.decodeFile(imagePath, opts)
        } catch (_: Exception) { null }
    }

    // 绘制模式拖拽状态
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    // 图片显示区域（FIT_CENTER 计算）
    var contentRect by remember { mutableStateOf<Rect?>(null) }

    // 四角控制柄半径（像素）
    val handleRadiusPx = 24f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isDrawingMode, rois, selectedRoiId) {
                    // 编辑模式共享状态
                    var actionRoiId: String? = null
                    var actionCornerIndex = -1
                    var isMoveAction = false

                    if (isDrawingMode) {
                        // ── 绘制模式：拖拽绘制新矩形 ──
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragCurrent = offset
                            },
                            onDrag = { change, _ ->
                                dragCurrent = change.position
                                val start = dragStart ?: return@detectDragGestures
                                val current = dragCurrent ?: return@detectDragGestures
                                val cr = contentRect ?: return@detectDragGestures
                                val normalized = pixelToNormalized(
                                    left = minOf(start.x, current.x),
                                    top = minOf(start.y, current.y),
                                    right = maxOf(start.x, current.x),
                                    bottom = maxOf(start.y, current.y),
                                    contentRect = cr,
                                )
                                onDrawingRectUpdate(normalized)
                            },
                            onDragEnd = { },
                        )
                    } else {
                        // ── 编辑模式：点击选中 + 拖拽移动/缩放 ──
                        detectDragGestures(
                            onDragStart = { startPos ->
                                val cr = contentRect ?: return@detectDragGestures

                                // 命中检测：角点控制柄 → 缩放；ROI 内部 → 移动；空白 → 无操作
                                actionRoiId = null
                                actionCornerIndex = -1
                                isMoveAction = false

                                val selectedRoi = rois.find { it.id == selectedRoiId }
                                if (selectedRoi != null) {
                                    val selRect = NormalizedRect.fromJsonString(selectedRoi.normalizedRect)
                                    if (selRect != null) {
                                        val selPixel = normalizedToPixel(selRect, cr)
                                        val corners = listOf(
                                            Offset(selPixel.left, selPixel.top),
                                            Offset(selPixel.right, selPixel.top),
                                            Offset(selPixel.left, selPixel.bottom),
                                            Offset(selPixel.right, selPixel.bottom),
                                        )
                                        val hitCorner = corners.indexOfFirst { corner ->
                                            val distX = startPos.x - corner.x
                                            val distY = startPos.y - corner.y
                                            kotlin.math.sqrt(distX * distX + distY * distY) <= handleRadiusPx
                                        }
                                        if (hitCorner >= 0) {
                                            actionRoiId = selectedRoiId
                                            actionCornerIndex = hitCorner
                                        } else if (selPixel.contains(startPos)) {
                                            actionRoiId = selectedRoiId
                                            isMoveAction = true
                                        }
                                    }
                                }

                                // 未命中已选中 ROI → 检测是否命中其他 ROI
                                if (actionRoiId == null) {
                                    val hitRoi = rois.lastOrNull { roi ->
                                        val r = NormalizedRect.fromJsonString(roi.normalizedRect) ?: return@lastOrNull false
                                        val px = normalizedToPixel(r, cr)
                                        px.contains(startPos)
                                    }
                                    if (hitRoi != null) {
                                        actionRoiId = hitRoi.id
                                        isMoveAction = true
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                val cr = contentRect ?: return@detectDragGestures
                                val roiId = actionRoiId ?: return@detectDragGestures

                                if (actionCornerIndex >= 0) {
                                    // 缩放：将当前累计位置转换为 normalized 传给 ViewModel
                                    val curX = change.position.x
                                    val curY = change.position.y
                                    val normX = ((curX - cr.left) / cr.width).coerceIn(0f, 1f)
                                    val normY = ((curY - cr.top) / cr.height).coerceIn(0f, 1f)
                                    onRoiResized(roiId, actionCornerIndex, normX, normY)
                                } else if (isMoveAction) {
                                    // 移动：将像素 delta 转换为 normalized delta
                                    val deltaNormX = dragAmount.x / cr.width
                                    val deltaNormY = dragAmount.y / cr.height
                                    onRoiMoved(roiId, deltaNormX, deltaNormY)
                                }
                            },
                            onDragEnd = {
                                // 短拖拽视为点击选中
                                if (actionRoiId != null) {
                                    onRoiSelected(actionRoiId)
                                } else {
                                    onRoiSelected(null)
                                }
                            },
                        )
                    }
                }
        ) {
            val cr = calculateContentRect(bitmap, size)
            contentRect = cr

            // 绘制图片
            if (bitmap != null && cr != null) {
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = IntOffset(cr.left.toInt(), cr.top.toInt()),
                    dstSize = IntSize(cr.width.toInt(), cr.height.toInt()),
                )
            }

            // 绘制已有 ROI
            rois.forEach { roi ->
                val roiRect = NormalizedRect.fromJsonString(roi.normalizedRect) ?: return@forEach
                val pixelRect = normalizedToPixel(roiRect, cr ?: return@forEach)
                val isSelected = roi.id == selectedRoiId

                drawRect(
                    color = if (isSelected) Color.Yellow else Color.Green,
                    topLeft = Offset(pixelRect.left, pixelRect.top),
                    size = Size(pixelRect.width, pixelRect.height),
                    style = Stroke(
                        width = if (isSelected) 4f else 2f,
                        pathEffect = if (isSelected) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                    ),
                )

                // 选中 ROI 的四角控制柄
                if (isSelected) {
                    val corners = listOf(
                        Offset(pixelRect.left, pixelRect.top),
                        Offset(pixelRect.right, pixelRect.top),
                        Offset(pixelRect.left, pixelRect.bottom),
                        Offset(pixelRect.right, pixelRect.bottom),
                    )
                    corners.forEach { corner ->
                        drawCircle(
                            color = Color.Yellow,
                            radius = handleRadiusPx * 0.6f,
                            center = corner,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = handleRadiusPx * 0.6f,
                            center = corner,
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }

            // 绘制正在绘制的 ROI
            if (drawingRect != null && cr != null) {
                val pixelRect = normalizedToPixel(drawingRect, cr)
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(pixelRect.left, pixelRect.top),
                    size = Size(pixelRect.width, pixelRect.height),
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}

/**
 * 判断像素点是否在 Rect 内
 */
private fun Rect.contains(offset: Offset): Boolean {
    return offset.x >= left && offset.x <= right &&
        offset.y >= top && offset.y <= bottom
}

/**
 * 计算 FIT_CENTER 模式下图片的显示区域
 */
private fun calculateContentRect(bitmap: android.graphics.Bitmap?, canvasSize: Size): Rect? {
    if (bitmap == null || canvasSize.width == 0f || canvasSize.height == 0f) return null

    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()
    val canvasWidth = canvasSize.width
    val canvasHeight = canvasSize.height

    val scale = minOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale

    val left = (canvasWidth - scaledWidth) / 2f
    val top = (canvasHeight - scaledHeight) / 2f

    return Rect(left, top, left + scaledWidth, top + scaledHeight)
}

/**
 * 像素坐标 → normalizedRect（0-1）
 */
private fun pixelToNormalized(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    contentRect: Rect,
): NormalizedRect {
    val width = contentRect.width
    val height = contentRect.height
    return NormalizedRect(
        left = ((left - contentRect.left) / width).coerceIn(0f, 1f),
        top = ((top - contentRect.top) / height).coerceIn(0f, 1f),
        right = ((right - contentRect.left) / width).coerceIn(0f, 1f),
        bottom = ((bottom - contentRect.top) / height).coerceIn(0f, 1f),
    )
}

/**
 * normalizedRect（0-1）→ 像素坐标
 */
private fun normalizedToPixel(
    rect: NormalizedRect,
    contentRect: Rect,
): Rect {
    val width = contentRect.width
    val height = contentRect.height
    return Rect(
        left = contentRect.left + rect.left * width,
        top = contentRect.top + rect.top * height,
        right = contentRect.left + rect.right * width,
        bottom = contentRect.top + rect.bottom * height,
    )
}
