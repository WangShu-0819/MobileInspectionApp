package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary

/**
 * View 人工确认页面
 *
 * 流程：
 * 1. 显示当前 View 信息和进度
 * 2. 显示 ROI 裁剪子图列表，每个 ROI 选择 OK/NG
 * 3. 底部固定显示总体 OK/NG 选择
 * 4. 确认按钮（所有选择完成后可用）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewConfirmationScreen(
    viewModel: ViewConfirmationViewModel,
    partName: String,
    currentViewIndex: Int,
    totalViews: Int,
    onConfirmed: () -> Unit,
    onBack: () -> Unit
) {
    val rois = viewModel.rois
    val roiBitmaps = viewModel.roiBitmaps
    val roiResults = viewModel.roiResults
    val overallResult = viewModel.overallResult
    val isSaving = viewModel.isSaving
    val errorMessage = viewModel.errorMessage
    val saveCompleted = viewModel.saveCompleted
    val isLoaded = viewModel.isLoaded
    val isAllConfirmed = viewModel.isAllConfirmed()

    // 保存完成事件只消费一次；返回/取消不会触发此事件。
    val completionHandled = remember(viewModel) { mutableStateOf(false) }
    LaunchedEffect(saveCompleted) {
        if (saveCompleted && !completionHandled.value) {
            completionHandled.value = true
            onConfirmed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回现场采集",
                            tint = TextPrimary,
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = partName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = TextPrimary
                        )
                        Text(
                            text = "视角 ${currentViewIndex + 1}/$totalViews",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                )
            )
        },
        bottomBar = {
            if (isLoaded && rois.isNotEmpty() && !saveCompleted && !completionHandled.value) {
                // 保存完成后先移除本页操作栏，再通知导航层离开，避免确认按钮残留一帧。
                BottomConfirmBar(
                    overallResult = overallResult,
                    onOverallSelect = { viewModel.selectOverallResult(it) },
                    isAllConfirmed = isAllConfirmed,
                    isSaving = isSaving,
                    errorMessage = errorMessage,
                    onConfirm = { viewModel.saveConfirmation() }
                )
            }
        },
        containerColor = BackgroundVariant1
    ) { paddingValues ->
        when {
            !isLoaded -> {
                // 加载中
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("加载中…", color = TextSecondary)
                    }
                }
            }
            errorMessage != null && rois.isEmpty() -> {
                // 错误状态
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = FailColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage, color = FailColor)
                    }
                }
            }
            rois.isEmpty() -> {
                // 防御性状态：正常流程不会进入无 ROI 确认页，也不生成确认结果。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("当前视角无 ROI，无需人工确认", color = TextSecondary)
                }
            }
            else -> {
                // ROI 列表（可滚动）；Scaffold 的 bottomBar 会为底部操作栏预留空间。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(rois, key = { it.id }) { roi ->
                        RoiConfirmCard(
                            roi = roi,
                            bitmap = roiBitmaps[roi.id],
                            selectedResult = roiResults[roi.id],
                            onSelect = { result ->
                                viewModel.setRoiResult(roi.id, result)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个 ROI 确认卡片
 *
 * 左侧：ROI 裁剪子图
 * 右侧：ROI 名称、属性、OK/NG 选择
 */
@Composable
private fun RoiConfirmCard(
    roi: RoiDefinitionEntity,
    bitmap: Bitmap?,
    selectedResult: String?,
    onSelect: (String) -> Unit
) {
    val targetType = RoiTargetType.fromName(roi.targetType)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 左侧：ROI 裁剪子图
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = roi.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "无图",
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            // 中间：ROI 信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = roi.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "ID: ${roi.id.take(8)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = targetType?.displayName ?: "未选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 右侧：OK/NG 选择
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ResultChip(
                    label = "OK",
                    selected = selectedResult == "OK",
                    color = PassColor,
                    onClick = { onSelect("OK") }
                )
                ResultChip(
                    label = "NG",
                    selected = selectedResult == "NG",
                    color = FailColor,
                    onClick = { onSelect("NG") }
                )
            }
        }
    }
}

/**
 * OK/NG 选择芯片
 */
@Composable
private fun ResultChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val bgColor = if (selected) color else Color.Transparent
    val textColor = if (selected) Color.White else color
    val borderColor = color

    Box(
        modifier = Modifier
            .height(32.dp)
            .width(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 底部确认栏
 *
 * 固定显示：总体 OK/NG + 确认按钮
 */
@Composable
private fun BottomConfirmBar(
    overallResult: String?,
    onOverallSelect: (String) -> Unit,
    isAllConfirmed: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 总体结果选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "总体结果",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverallChip(
                        label = "OK",
                        selected = overallResult == "OK",
                        color = PassColor,
                        onClick = { onOverallSelect("OK") }
                    )
                    OverallChip(
                        label = "NG",
                        selected = overallResult == "NG",
                        color = FailColor,
                        onClick = { onOverallSelect("NG") }
                    )
                }
            }

            // 错误信息（固定高度，避免出现/消失时推动按钮移动）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    errorMessage != null -> Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = FailColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    !isAllConfirmed && !isSaving -> Text(
                        text = "请完成所有选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            // 确认按钮
            Button(
                onClick = onConfirm,
                enabled = isAllConfirmed && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Color.White,
                    disabledContainerColor = DividerColor,
                    disabledContentColor = TextSecondary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                // 按钮文案固定；未完成状态只改变 enabled 和上方提示，不改变按钮位置。
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            modifier = Modifier.height(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "确认并继续",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 总体 OK/NG 芯片（更大尺寸）
 */
@Composable
private fun OverallChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val bgColor = if (selected) color else Color.Transparent
    val textColor = if (selected) Color.White else color

    Box(
        modifier = Modifier
            .height(36.dp)
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.5.dp, color, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
