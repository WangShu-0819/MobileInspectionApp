package com.wearable.inspection.mobile.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import com.wearable.inspection.mobile.data.entity.InspectionSessionEntity
import com.wearable.inspection.mobile.data.export.InspectionExportResult
import com.wearable.inspection.mobile.data.export.InspectionZipExportService
import com.wearable.inspection.mobile.domain.model.InspectionStatus
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.PendingColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 批次时间筛选选项
 */
enum class BatchTimeFilter(val label: String) {
    TODAY("今日"),
    LAST_3_DAYS("近 3 天"),
    LAST_7_DAYS("近 7 天"),
    ALL("所有");

    /**
     * 计算筛选起始时间戳（本地时间）
     * 返回 null 表示不设下限（"所有"）
     */
    fun sinceMillis(): Long? {
        if (this == ALL) return null
        val daysBack = when (this) {
            TODAY -> 0
            LAST_3_DAYS -> 2
            LAST_7_DAYS -> 6
            ALL -> return null
        }
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysBack)
        }.timeInMillis
    }
}

/**
 * 追溯记录页
 * 统一承载历史查询、复核和结果移交
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceRecordsScreen() {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = androidx.compose.runtime.remember {
        MobileInspectionApp.repository(context)
    }
    val scope = rememberCoroutineScope()
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    val todayRange = currentDayRange()
    val todaySessions = sessions.filter { it.startTime in todayRange }
    val todayPass = todaySessions.count { it.effectiveStatus() == InspectionStatus.PASS }
    val todayFail = todaySessions.count { it.effectiveStatus() == InspectionStatus.FAIL }
    val todayPending = todaySessions.size - todayPass - todayFail

    // 时间筛选状态 — 默认"近 7 天"
    var activeFilter by remember { mutableStateOf(BatchTimeFilter.LAST_7_DAYS) }
    var filterMenuExpanded by remember { mutableStateOf(false) }

    // 根据筛选条件获取批次 Flow
    val batches by remember(activeFilter) {
        val since = activeFilter.sinceMillis()
        if (since != null) repository.observeCaptureBatchesSince(since)
        else repository.observeCaptureBatches()
    }.collectAsState(initial = emptyList())

    // 当前正在导出的批次 ID
    var exportingBatchId by remember { mutableStateOf<String?>(null) }
    // 导出结果消息（key=batchId, value=message）
    var exportMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 批次多选与删除状态；只保存稳定 batchId，不依赖列表位置
    var selectedBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingBatch by remember { mutableStateOf(false) }

    // 切换筛选时清除选中状态
    LaunchedEffect(activeFilter) {
        selectedBatchIds = emptySet()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // 追溯记录和采集完成页统一使用同一套“照片 + 检测结果”导出服务。
    val exportService = remember { InspectionZipExportService(context, repository) }

    // SAF 文件创建器（按批次导出）
    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val batchId = exportingBatchId ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            exportingBatchId = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            exportingBatchId = batchId
            val result = withContext(Dispatchers.IO) {
                val tempFile = File(context.cacheDir, "batch_${batchId.take(8)}.zip")
                val batch = repository.getCaptureBatch(batchId)
                val exportResult = if (batch == null) {
                    InspectionExportResult.Failure("采集批次不存在")
                } else {
                    exportService.exportInspectionZip(
                        batchId = batchId,
                        partId = batch.partId.orEmpty(),
                        outputFile = tempFile,
                    )
                }
                if (exportResult is InspectionExportResult.Success) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            tempFile.inputStream().use { it.copyTo(os) }
                        }
                        tempFile.delete()
                        exportResult
                    } catch (e: Exception) {
                        tempFile.delete()
                        InspectionExportResult.Failure("写入文件失败：${e.localizedMessage}")
                    }
                } else {
                    tempFile.delete()
                    exportResult
                }
            }
            exportingBatchId = null
            exportMessages = exportMessages + (batchId to when (result) {
                is InspectionExportResult.Success -> {
                    val msg = "导出成功：${result.photoCount} 张照片，${result.csvRowCount} 条检测记录"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    msg
                }
                is InspectionExportResult.Failure -> result.message
            })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "追溯记录",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                )
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (
                        data.visuals.message.startsWith("已删除") && !data.visuals.message.contains("失败")
                    ) PassColor else FailColor,
                    contentColor = SurfaceWhite,
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        containerColor = customColors.pageBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TodayStatsCard(
                    passCount = todayPass,
                    failCount = todayFail,
                    pendingCount = todayPending
                )
            }

            // 标题栏：采集批次 + 筛选器 + 垃圾桶（固定槽位，始终显示）
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：标题
                    Text(
                        text = "采集批次",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp),
                    )

                    // 中间偏右：时间筛选器（固定宽度）
                    Box(
                        modifier = Modifier
                            .width(104.dp)
                            .height(36.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { filterMenuExpanded = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = activeFilter.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(min = 0.dp),
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "筛选",
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false }
                        ) {
                            BatchTimeFilter.entries.forEach { filter ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = filter.label,
                                            fontWeight = if (filter == activeFilter) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (filter == activeFilter) Primary else TextPrimary
                                        )
                                    },
                                    onClick = {
                                        activeFilter = filter
                                        filterMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 右侧：垃圾桶（固定槽位，始终占位）
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = selectedBatchIds.isNotEmpty() && !deletingBatch
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除选中批次",
                            tint = if (selectedBatchIds.isNotEmpty() && !deletingBatch) FailColor else PlaceholderColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (batches.isNotEmpty()) {
                items(
                    count = batches.size,
                    key = { index -> batches[index].batchId },
                ) { index ->
                    val batch = batches[index]
                    CaptureBatchCard(
                        batch = batch,
                        selected = batch.batchId in selectedBatchIds,
                        exporting = exportingBatchId == batch.batchId,
                        completed = batch.endTime != null,
                        exportMessage = exportMessages[batch.batchId],
                        onExport = {
                            exportingBatchId = batch.batchId
                            createZipLauncher.launch("batch_${batch.batchId.take(8)}.zip")
                        },
                        onSelect = {
                            selectedBatchIds = if (batch.batchId in selectedBatchIds) {
                                selectedBatchIds - batch.batchId
                            } else {
                                selectedBatchIds + batch.batchId
                            }
                        }
                    )
                }
            } else {
                item {
                    BatchEmptyState(
                        filter = activeFilter,
                        onViewAll = { activeFilter = BatchTimeFilter.ALL }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    val selectedBatches = batches.filter { it.batchId in selectedBatchIds }
    if (showDeleteDialog && selectedBatches.isNotEmpty()) {
        DeleteBatchDialog(
            batches = selectedBatches,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                val batchIdsToDelete = selectedBatches.map { it.batchId }
                if (batchIdsToDelete.isEmpty()) return@DeleteBatchDialog
                // 选中批次中只要有一个正在导出，就整体等待，避免部分删除
                if (exportingBatchId in batchIdsToDelete) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "选中批次中有批次正在导出，请等待导出完成后再删除",
                            duration = SnackbarDuration.Short
                        )
                    }
                    return@DeleteBatchDialog
                }
                deletingBatch = true
                scope.launch {
                    val deletedBatchIds = mutableListOf<String>()
                    try {
                        batchIdsToDelete.forEach { batchId ->
                            repository.deleteCaptureBatchCompletely(batchId)
                            deletedBatchIds += batchId
                        }
                        selectedBatchIds = emptySet()
                        snackbarHostState.showSnackbar(
                            message = "已删除 ${deletedBatchIds.size} 个采集批次",
                            duration = SnackbarDuration.Short
                        )
                    } catch (e: Exception) {
                        // 删除接口按批次执行；失败时保留尚未删除的选中项，便于重试
                        selectedBatchIds = selectedBatchIds - deletedBatchIds.toSet()
                        snackbarHostState.showSnackbar(
                            message = if (deletedBatchIds.isEmpty()) {
                                "删除失败：${e.localizedMessage ?: "未知错误"}"
                            } else {
                                "已删除 ${deletedBatchIds.size} 个，剩余批次删除失败：${e.localizedMessage ?: "未知错误"}"
                            },
                            duration = SnackbarDuration.Short
                        )
                    } finally {
                        deletingBatch = false
                    }
                }
            }
        )
    }
}

private fun currentDayRange(): LongRange {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val end = (start.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 1)
    }
    return start.timeInMillis until end.timeInMillis
}

private fun InspectionSessionEntity.effectiveStatus(): InspectionStatus? {
    val statusName = finalOverallStatus ?: autoOverallStatus
    return InspectionStatus.values().firstOrNull { it.name == statusName }
}

@Composable
private fun TodayStatsCard(
    passCount: Int,
    failCount: Int,
    pendingCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "今日统计",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("通过", passCount.toString(), PassColor)
                StatItem("不通过", failCount.toString(), FailColor)
                StatItem("待复核", pendingCount.toString(), PendingColor)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun CaptureBatchCard(
    batch: CaptureBatchEntity,
    selected: Boolean,
    exporting: Boolean,
    completed: Boolean,
    exportMessage: String?,
    onExport: () -> Unit,
    onSelect: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(
                    width = 2.dp,
                    color = Primary,
                    shape = RoundedCornerShape(8.dp)
                ) else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) BackgroundVariant1 else SurfaceWhite
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 批次标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp),
                ) {
                    Text(
                        text = batch.partName ?: "未关联零件",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = dateFormat.format(Date(batch.startTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${batch.viewCount} 视角",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 52.dp),
                )
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.size(48.dp),
                )
            }

            // 批次信息
            Text(
                text = "批次 ID: ${batch.batchId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor
            )

            if (!completed) {
                Text(
                    text = "采集中，拍完全部视角后才能导出 ZIP",
                    style = MaterialTheme.typography.bodySmall,
                    color = PendingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 导出按钮
            Button(
                onClick = onExport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                enabled = !exporting && completed,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = SurfaceWhite,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "导出中…",
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 14.sp
                    )
                } else if (completed) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "导出 ZIP",
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = "完成后导出 ZIP",
                        fontSize = 14.sp,
                    )
                }
            }

            // 导出结果消息固定占位，不因出现/消失推动卡片和 ZIP 按钮。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                exportMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("导出成功")) PassColor else FailColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 按筛选条件显示的空状态
 */
@Composable
private fun BatchEmptyState(
    filter: BatchTimeFilter,
    onViewAll: () -> Unit
) {
    val message = when (filter) {
        BatchTimeFilter.TODAY -> "今日暂无采集批次"
        BatchTimeFilter.LAST_3_DAYS -> "近 3 天暂无采集批次"
        BatchTimeFilter.LAST_7_DAYS -> "近 7 天暂无采集批次"
        BatchTimeFilter.ALL -> "暂无采集批次"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = PlaceholderColor,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            if (filter != BatchTimeFilter.ALL) {
                TextButton(onClick = onViewAll) {
                    Text(
                        text = "查看所有",
                        color = Primary,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteBatchDialog(
    batches: List<CaptureBatchEntity>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除 ${batches.size} 个采集批次",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            val shownBatches = batches.take(3)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shownBatches.forEach { batch ->
                    Text(
                        text = "${batch.partName ?: "未关联零件"} · ${batch.batchId.take(8)}…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (batches.size > shownBatches.size) {
                    Text(text = "及其他 ${batches.size - shownBatches.size} 个批次")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "将删除所选批次的所有照片和确认记录，此操作无法恢复。",
                    color = FailColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认删除", color = FailColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
