package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.material3.TextButton
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
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
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
    val deleteError = viewModel.deleteError
    val drawingTargetType = viewModel.drawingTargetType

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTargetTypeMenu by remember { mutableStateOf(false) }
    var showEditTargetTypeMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 删除错误反馈
    LaunchedEffect(deleteError) {
        if (deleteError != null) {
            snackbarHostState.showSnackbar(deleteError)
            viewModel.clearDeleteError()
        }
    }

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
                        IconButton(onClick = { showDeleteConfirm = true }) {
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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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

                    // 选中 ROI 的属性显示和编辑
                    val selectedRoi = rois.find { it.id == selectedRoiId }
                    if (selectedRoi != null && !isDrawingMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "目标属性：${RoiTargetType.fromName(selectedRoi.targetType)?.displayName ?: "未选择"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectedRoi.targetType != null) TextPrimary else PlaceholderColor,
                            )
                            Box {
                                TextButton(onClick = { showEditTargetTypeMenu = true }) {
                                    Text("修改", style = MaterialTheme.typography.bodySmall)
                                }
                                DropdownMenu(
                                    expanded = showEditTargetTypeMenu,
                                    onDismissRequest = { showEditTargetTypeMenu = false },
                                ) {
                                    RoiTargetType.entries.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type.displayName) },
                                            onClick = {
                                                showEditTargetTypeMenu = false
                                                viewModel.updateRoiTargetType(selectedRoi.id, type)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 绘制模式下的属性选择
                    if (isDrawingMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "目标属性：${drawingTargetType?.displayName ?: "请选择"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (drawingTargetType != null) TextPrimary else FailColor,
                            )
                            Box {
                                TextButton(onClick = { showTargetTypeMenu = true }) {
                                    Text("选择", style = MaterialTheme.typography.bodySmall)
                                }
                                DropdownMenu(
                                    expanded = showTargetTypeMenu,
                                    onDismissRequest = { showTargetTypeMenu = false },
                                ) {
                                    RoiTargetType.entries.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type.displayName) },
                                            onClick = {
                                                showTargetTypeMenu = false
                                                viewModel.updateDrawingTargetType(type)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isDrawingMode) {
                            // 绘制模式：保存/取消
                            Button(
                                onClick = {
                                    val saved = viewModel.saveDrawingRect()
                                    if (!saved && drawingTargetType == null) {
                                        // 提示用户选择属性
                                    }
                                },
                                enabled = drawingRect != null && drawingTargetType != null,
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

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除 ROI") },
            text = { Text("确定要删除当前选中的 ROI 吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSelectedRoi()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FailColor),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DividerColor),
                ) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * ROI 编辑 Canvas
 *
 * 显示模板图片作为背景，叠加显示已有 ROI 和正在绘制的 ROI。
 * 编辑模式支持：点击/长按选中已有 ROI、拖拽移动、四角缩放。
 * 绘制模式支持：拖拽绘制新矩形。
 *
 * 手势分离策略（编辑模式）：
 * - 普通点按（释放前移动距离 < touchSlop）→ 选中命中 ROI
 * - 长按（按住不动 ≥ LongPressTimeout）→ 选中命中 ROI
 * - 拖拽（移动距离 ≥ touchSlop）→ 移动或缩放已命中 ROI
 * 三者互不干扰：点按和长按不会触发移动/缩放，拖拽不会误触发选中。
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
    var drawDragStart by remember { mutableStateOf<Offset?>(null) }
    var drawDragCurrent by remember { mutableStateOf<Offset?>(null) }

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
                    if (isDrawingMode) {
                        // ── 绘制模式：拖拽绘制新矩形 ──
                        detectDragGestures(
                            onDragStart = { offset ->
                                drawDragStart = offset
                                drawDragCurrent = offset
                            },
                            onDrag = { change, _ ->
                                drawDragCurrent = change.position
                                val start = drawDragStart ?: return@detectDragGestures
                                val current = drawDragCurrent ?: return@detectDragGestures
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
                        // ── 编辑模式：tap 选中 + long-press 选中 + 拖拽移动/缩放 ──
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            val downPos = down.position
                            val cr = contentRect

                            // ── Step 1: 按下时做命中检测 ──
                            var actionRoiId: String? = null
                            var actionCornerIndex = -1
                            var isMoveAction = false

                            if (cr != null) {
                                // 优先检测已选中 ROI 的四角控制柄
                                val selRoi = rois.find { it.id == selectedRoiId }
                                if (selRoi != null) {
                                    val selRect = NormalizedRect.fromJsonString(selRoi.normalizedRect)
                                    if (selRect != null) {
                                        val selPixel = normalizedToPixel(selRect, cr)
                                        val corners = listOf(
                                            Offset(selPixel.left, selPixel.top),
                                            Offset(selPixel.right, selPixel.top),
                                            Offset(selPixel.left, selPixel.bottom),
                                            Offset(selPixel.right, selPixel.bottom),
                                        )
                                        val hitCorner = corners.indexOfFirst { corner ->
                                            val dx = downPos.x - corner.x
                                            val dy = downPos.y - corner.y
                                            kotlin.math.sqrt(dx * dx + dy * dy) <= handleRadiusPx
                                        }
                                        if (hitCorner >= 0) {
                                            actionRoiId = selectedRoiId
                                            actionCornerIndex = hitCorner
                                        } else if (selPixel.contains(downPos)) {
                                            actionRoiId = selectedRoiId
                                            isMoveAction = true
                                        }
                                    }
                                }

                                // 未命中已选中 ROI → 检测是否命中其他 ROI（最上层优先）
                                if (actionRoiId == null) {
                                    val hitRoi = rois.lastOrNull { roi ->
                                        val r = NormalizedRect.fromJsonString(roi.normalizedRect) ?: return@lastOrNull false
                                        val px = normalizedToPixel(r, cr)
                                        px.contains(downPos)
                                    }
                                    if (hitRoi != null) {
                                        actionRoiId = hitRoi.id
                                        isMoveAction = true
                                    }
                                }
                            }

                            // ── Step 2: 等待长按 ──
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                // 长按命中 → 选中 ROI（不触发移动/缩放）
                                if (actionRoiId != null) {
                                    onRoiSelected(actionRoiId)
                                }
                                // 长按后不再处理拖拽
                                return@awaitEachGesture
                            }

                            // ── Step 3: 非长按 → 等待拖拽或点击释放 ──
                            if (actionCornerIndex >= 0 || isMoveAction) {
                                // 命中了 ROI 或控制柄 → 跟踪拖拽直到释放
                                var didDrag = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        // 指针释放
                                        change.consume()
                                        break
                                    }

                                    val dragDx = change.position.x - change.previousPosition.x
                                    val dragDy = change.position.y - change.previousPosition.y
                                    if (dragDx == 0f && dragDy == 0f) continue

                                    didDrag = true
                                    if (cr != null) {
                                        if (actionCornerIndex >= 0) {
                                            val normX = ((change.position.x - cr.left) / cr.width).coerceIn(0f, 1f)
                                            val normY = ((change.position.y - cr.top) / cr.height).coerceIn(0f, 1f)
                                            onRoiResized(actionRoiId!!, actionCornerIndex, normX, normY)
                                        } else if (isMoveAction) {
                                            val deltaNormX = dragDx / cr.width
                                            val deltaNormY = dragDy / cr.height
                                            onRoiMoved(actionRoiId!!, deltaNormX, deltaNormY)
                                        }
                                    }
                                    change.consume()
                                }

                                // 释放后：如果是短拖拽（即点击），选中 ROI
                                if (!didDrag && actionRoiId != null) {
                                    onRoiSelected(actionRoiId)
                                }
                            } else {
                                // 空白区域：等待释放，取消选中
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    change.consume()
                                }
                                onRoiSelected(null)
                            }
                        }
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
