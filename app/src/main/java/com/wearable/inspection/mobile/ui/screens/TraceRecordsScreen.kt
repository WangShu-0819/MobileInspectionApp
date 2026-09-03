package com.wearable.inspection.mobile.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import com.wearable.inspection.mobile.data.entity.InspectionSessionEntity
import com.wearable.inspection.mobile.data.export.ExportResult
import com.wearable.inspection.mobile.data.export.PhotoExportService
import com.wearable.inspection.mobile.domain.model.InspectionStatus
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
    val batches by repository.observeCaptureBatches().collectAsState(initial = emptyList())
    val todayRange = currentDayRange()
    val todaySessions = sessions.filter { it.startTime in todayRange }
    val todayPass = todaySessions.count { it.effectiveStatus() == InspectionStatus.PASS }
    val todayFail = todaySessions.count { it.effectiveStatus() == InspectionStatus.FAIL }
    val todayPending = todaySessions.size - todayPass - todayFail

    // 当前正在导出的批次 ID
    var exportingBatchId by remember { mutableStateOf<String?>(null) }
    // 导出结果消息（key=batchId, value=message）
    var exportMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val exportService = remember { PhotoExportService(context, repository) }

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
                val exportResult = exportService.exportBatchToZip(batchId, tempFile)
                if (exportResult is ExportResult.Success) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            tempFile.inputStream().use { it.copyTo(os) }
                        }
                        tempFile.delete()
                        exportResult
                    } catch (e: Exception) {
                        tempFile.delete()
                        ExportResult.Failure("写入文件失败：${e.localizedMessage}")
                    }
                } else {
                    tempFile.delete()
                    exportResult
                }
            }
            exportingBatchId = null
            exportMessages = exportMessages + (batchId to when (result) {
                is ExportResult.Success -> {
                    val msg = "导出成功：${result.photoCount} 张照片"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    msg
                }
                is ExportResult.Failure -> result.message
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

            if (batches.isNotEmpty()) {
                item {
                    Text(
                        text = "采集批次",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(batches.size) { index ->
                    val batch = batches[index]
                    CaptureBatchCard(
                        batch = batch,
                        exporting = exportingBatchId == batch.batchId,
                        exportMessage = exportMessages[batch.batchId],
                        onExport = {
                            exportingBatchId = batch.batchId
                            createZipLauncher.launch("batch_${batch.batchId.take(8)}.zip")
                        }
                    )
                }
            } else {
                item {
                    EmptyRecordsState()
                }
            }
        }
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
    exporting: Boolean,
    exportMessage: String?,
    onExport: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 批次标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = batch.partName ?: "未关联零件",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = dateFormat.format(Date(batch.startTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Text(
                    text = "${batch.viewCount} 视角",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary
                )
            }

            // 批次信息
            Text(
                text = "批次 ID: ${batch.batchId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor
            )

            // 导出按钮
            Button(
                onClick = onExport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                enabled = !exporting,
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
                } else {
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
                }
            }

            // 导出结果消息
            exportMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("导出成功")) PassColor else FailColor
                )
            }
        }
    }
}

@Composable
private fun EmptyRecordsState() {
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
                text = "暂无检测记录",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Text(
                text = "完成检测后，记录将在此显示",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor
            )
        }
    }
}
