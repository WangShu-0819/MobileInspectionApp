package com.wearable.inspection.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.HorizontalDivider
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
import com.wearable.inspection.mobile.dpm.DpmOperationGuard
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import com.wearable.inspection.mobile.data.entity.ExportedPackageEntity
import com.wearable.inspection.mobile.data.entity.InspectionSessionEntity
import com.wearable.inspection.mobile.data.export.DpmEvidenceExportResult
import com.wearable.inspection.mobile.data.repository.DpmEvidenceStats
import com.wearable.inspection.mobile.data.export.DpmEvidenceExportService
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
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 将已完成的本地 ZIP 写入 SAF；目标流为空或写入字节数不完整时抛错。 */
internal fun copyZipToSafUri(context: Context, uri: Uri, source: File): Long {
    check(source.isFile && source.length() > 0L) { "ZIP 文件不存在或为空" }
    val expectedBytes = source.length()
    val copiedBytes = context.contentResolver.openOutputStream(uri)
        ?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
        ?: throw IllegalStateException("无法打开所选保存位置")
    check(copiedBytes == expectedBytes && copiedBytes > 0L) { "ZIP 写入为空或不完整" }
    return copiedBytes
}

/** CreateDocument 已预创建目标；Empty/Failure/写入异常都必须删除该文档和临时 ZIP。 */
internal fun cleanupSafExportFailure(context: Context, uri: Uri, tempFile: File) {
    tempFile.delete()
    runCatching { context.contentResolver.delete(uri, null, null) }
}

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

    // 零件表（用于取 dpmCode 生成批次名称）
    val allParts by repository.observeParts().collectAsState(initial = emptyList())
    val partDpmCodes = remember(allParts) { allParts.associate { it.id to it.dpmCode } }

    // 批次显示名称: <安全零件码>_yyyyMMdd_HHmmss_SSS
    val batchNames = remember(batches, partDpmCodes) {
        batches.associate { b ->
            b.batchId to batchDisplayName(
                dpmCode = b.partId?.let { partDpmCodes[it] },
                partId = b.partId,
                partName = b.partName,
                batchId = b.batchId,
                startTime = b.startTime,
            )
        }
    }

    // 当前正在导出的批次 ID
    var exportingBatchId by remember { mutableStateOf<String?>(null) }
    // 导出结果消息（key=batchId, value=message）
    var exportMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 批次多选与删除状态；只保存稳定 batchId，不依赖列表位置
    var selectedBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingBatch by remember { mutableStateOf(false) }

    // 删除确认框中每个批次的实际照片数量（异步加载）
    var photoCountState by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }

    // 切换筛选时清除选中状态
    LaunchedEffect(activeFilter) {
        selectedBatchIds = emptySet()
    }

    // ---- 导出包管理状态 ----
    val exportedPackages by repository.observeExportedPackages().collectAsState(initial = emptyList())
    var selectedPackageIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeletePackageDialog by remember { mutableStateOf(false) }
    var deletingPackage by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // 删除确认框打开时异步加载每个选中批次的实际照片数量
    LaunchedEffect(showDeleteDialog, selectedBatchIds) {
        if (showDeleteDialog && selectedBatchIds.isNotEmpty()) {
            val counts = mutableMapOf<String, Int?>()
            selectedBatchIds.forEach { batchId ->
                counts[batchId] = try {
                    repository.getCapturedPhotos(batchId).size
                } catch (_: Exception) {
                    null // null 表示加载失败
                }
            }
            photoCountState = counts
        }
    }

    // 追溯记录和采集完成页统一使用同一套"照片 + 检测结果"导出服务。
    val exportService = remember { InspectionZipExportService(context, repository) }
    val dpmExportService = remember { DpmEvidenceExportService(context, repository) }

    // DPM 证据导出状态
    var dpmExporting by remember { mutableStateOf(false) }
    var dpmExportMessage by remember { mutableStateOf<String?>(null) }
    // 当前导出中的包 ID（在 CreateDocument 回调中使用）
    var pendingDpmPackageId by remember { mutableStateOf<Long?>(null) }

    // DPM 原始证据统计与清理状态
    var dpmStats by remember { mutableStateOf<DpmEvidenceStats?>(null) }
    var dpmCleaning by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var dpmCleanupMessage by remember { mutableStateOf<String?>(null) }

    // 加载 DPM 证据统计
    LaunchedEffect(Unit) {
        dpmStats = withContext(Dispatchers.IO) {
            repository.getDpmEvidenceStats()
        }
    }

    // SAF 文件创建器（DPM 证据导出）
    val createDpmZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val pkgId = pendingDpmPackageId
        if (uri == null) {
            dpmExporting = false
            // 用户取消：更新包状态为 CANCELLED
            if (pkgId != null) {
                scope.launch {
                    repository.getExportedPackage(pkgId)?.let { pkg ->
                        repository.updateExportedPackage(pkg.copy(status = ExportedPackageEntity.STATUS_CANCELLED))
                    }
                }
            }
            pendingDpmPackageId = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            dpmExporting = true
            // 尝试持久化 SAF URI 权限
            var persisted = false
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                persisted = true
            } catch (_: Exception) {
                // 部分 SAF provider 不支持持久化，降级处理
            }
            // 更新包记录的 SAF URI
            if (pkgId != null) {
                repository.getExportedPackage(pkgId)?.let { pkg ->
                    repository.updateExportedPackage(pkg.copy(
                        safUri = uri.toString(),
                        persistedPermission = persisted
                    ))
                }
            }
            val result = withContext(Dispatchers.IO) {
                val lease = DpmOperationGuard.acquireLease()
                    ?: return@withContext DpmEvidenceExportResult.Failure("有其他 DPM 操作正在进行中")
                try {
                    val tempFile = File(context.cacheDir, "dpm_evidence_export.zip")
                    val exportResult = dpmExportService.exportEvidenceZip(tempFile)
                    if (exportResult is DpmEvidenceExportResult.Success) {
                        try {
                            val byteSize = copyZipToSafUri(context, uri, tempFile)
                            tempFile.delete()
                            // 更新包状态为成功
                            if (pkgId != null) {
                                repository.getExportedPackage(pkgId)?.let { pkg ->
                                    repository.updateExportedPackage(pkg.copy(
                                        status = ExportedPackageEntity.STATUS_SUCCESS,
                                        byteSize = byteSize
                                    ))
                                }
                            }
                            exportResult
                        } catch (e: Exception) {
                            cleanupSafExportFailure(context, uri, tempFile)
                            // 更新包状态为失败
                            if (pkgId != null) {
                                repository.getExportedPackage(pkgId)?.let { pkg ->
                                    repository.updateExportedPackage(pkg.copy(
                                        status = ExportedPackageEntity.STATUS_FAILED,
                                        errorMessage = "写入文件失败：${e.localizedMessage ?: "未知错误"}"
                                    ))
                                }
                            }
                            DpmEvidenceExportResult.Failure("写入文件失败：${e.localizedMessage ?: "未知错误"}")
                        }
                    } else {
                        cleanupSafExportFailure(context, uri, tempFile)
                        // 更新包状态为失败
                        if (pkgId != null) {
                            val errorMsg = when (exportResult) {
                                is DpmEvidenceExportResult.Empty -> "暂无 DPM 扫码证据"
                                is DpmEvidenceExportResult.Failure -> exportResult.message
                                else -> "导出失败"
                            }
                            repository.getExportedPackage(pkgId)?.let { pkg ->
                                repository.updateExportedPackage(pkg.copy(
                                    status = ExportedPackageEntity.STATUS_FAILED,
                                    errorMessage = errorMsg
                                ))
                            }
                        }
                        exportResult
                    }
                } finally {
                    lease.release()
                }
            }
            dpmExporting = false
            pendingDpmPackageId = null
            dpmExportMessage = when (result) {
                is DpmEvidenceExportResult.Success -> {
                    val msg = buildString {
                        append("DPM 证据导出成功：${result.sessionCount} 个会话，${result.exportedCount} 个文件")
                        if (result.missingCount > 0) append("（${result.missingCount} 个文件缺失）")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    msg
                }
                is DpmEvidenceExportResult.Empty -> "暂无 DPM 扫码证据"
                is DpmEvidenceExportResult.Failure -> result.message
            }
        }
    }

    // 当前按批次导出中的包 ID
    var pendingBatchPackageId by remember { mutableStateOf<Long?>(null) }

    // SAF 文件创建器（按批次导出）
    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val batchId = exportingBatchId ?: return@rememberLauncherForActivityResult
        val pkgId = pendingBatchPackageId
        if (uri == null) {
            exportingBatchId = null
            // 用户取消：更新包状态为 CANCELLED
            if (pkgId != null) {
                scope.launch {
                    repository.getExportedPackage(pkgId)?.let { pkg ->
                        repository.updateExportedPackage(pkg.copy(status = ExportedPackageEntity.STATUS_CANCELLED))
                    }
                }
            }
            pendingBatchPackageId = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            exportingBatchId = batchId
            // 尝试持久化 SAF URI 权限
            var persisted = false
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                persisted = true
            } catch (_: Exception) {}
            // 更新包记录的 SAF URI
            if (pkgId != null) {
                repository.getExportedPackage(pkgId)?.let { pkg ->
                    repository.updateExportedPackage(pkg.copy(
                        safUri = uri.toString(),
                        persistedPermission = persisted
                    ))
                }
            }
            val result = withContext(Dispatchers.IO) {
                val lease = DpmOperationGuard.acquireLease()
                    ?: return@withContext InspectionExportResult.Failure("有其他 DPM 操作正在进行中")
                try {
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
                            val byteSize = copyZipToSafUri(context, uri, tempFile)
                            tempFile.delete()
                            // 更新包状态为成功
                            if (pkgId != null) {
                                repository.getExportedPackage(pkgId)?.let { pkg ->
                                    repository.updateExportedPackage(pkg.copy(
                                        status = ExportedPackageEntity.STATUS_SUCCESS,
                                        byteSize = byteSize
                                    ))
                                }
                            }
                            exportResult
                        } catch (e: Exception) {
                            cleanupSafExportFailure(context, uri, tempFile)
                            // 更新包状态为失败
                            if (pkgId != null) {
                                repository.getExportedPackage(pkgId)?.let { pkg ->
                                    repository.updateExportedPackage(pkg.copy(
                                        status = ExportedPackageEntity.STATUS_FAILED,
                                        errorMessage = "写入文件失败：${e.localizedMessage}"
                                    ))
                                }
                            }
                            InspectionExportResult.Failure("写入文件失败：${e.localizedMessage}")
                        }
                    } else {
                        cleanupSafExportFailure(context, uri, tempFile)
                        // 更新包状态为失败
                        if (pkgId != null) {
                            val errorMsg = (exportResult as? InspectionExportResult.Failure)?.message ?: "导出失败"
                            repository.getExportedPackage(pkgId)?.let { pkg ->
                                repository.updateExportedPackage(pkg.copy(
                                    status = ExportedPackageEntity.STATUS_FAILED,
                                    errorMessage = errorMsg
                                ))
                            }
                        }
                        exportResult
                    }
                } finally {
                    lease.release()
                }
            }
            exportingBatchId = null
            pendingBatchPackageId = null
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

            // DPM 扫码证据导出卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DPM 扫码证据",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                dpmExporting = true
                                dpmExportMessage = null
                                scope.launch {
                                    val fileName = dpmExportService.generateZipFileName()
                                    val pkgId = repository.insertExportedPackage(
                                        ExportedPackageEntity(
                                            packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
                                            displayName = fileName,
                                            status = ExportedPackageEntity.STATUS_EXPORTING
                                        )
                                    )
                                    pendingDpmPackageId = pkgId
                                    createDpmZipLauncher.launch(fileName)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            enabled = !dpmExporting,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (dpmExporting) {
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
                                Text(text = "导出全部扫码证据 ZIP", fontSize = 14.sp)
                            }
                        }
                        // 导出结果消息
                        if (dpmExportMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dpmExportMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dpmExportMessage!!.startsWith("DPM 证据导出成功") || dpmExportMessage!!.startsWith("暂无"))
                                    PassColor else FailColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // ---- 原始证据统计与清理 ----
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(8.dp))

                        // 统计行
                        val stats = dpmStats
                        if (stats != null) {
                            val sizeDisplay = formatBytes(stats.displayTotalBytes)
                            Text(
                                text = "原始证据: ${stats.rowCount} 条 · ${stats.fileCount} 张（原图+ROI） · $sizeDisplay",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            Text(
                                text = "原始证据: 加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = PlaceholderColor
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 清理按钮
                        val isAnyExportActive = dpmExporting || exportingBatchId != null ||
                            exportedPackages.any { it.status == ExportedPackageEntity.STATUS_EXPORTING }
                        val canClean = stats != null && stats.hasData && !dpmCleaning &&
                            !isAnyExportActive && !DpmOperationGuard.isAnyActive

                        Button(
                            onClick = { showCleanupDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            enabled = canClean,
                            colors = ButtonDefaults.buttonColors(containerColor = FailColor),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (dpmCleaning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = SurfaceWhite,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "清理中…",
                                    modifier = Modifier.padding(start = 8.dp),
                                    fontSize = 13.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "清理原始证据",
                                    modifier = Modifier.padding(start = 4.dp),
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // 清理结果消息
                        if (dpmCleanupMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dpmCleanupMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dpmCleanupMessage!!.startsWith("清理完成")) PassColor else FailColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                        batchName = batchNames[batch.batchId] ?: batchDisplayName(
                            dpmCode = null,
                            partId = batch.partId,
                            partName = batch.partName,
                            batchId = batch.batchId,
                            startTime = batch.startTime,
                        ),
                        selected = batch.batchId in selectedBatchIds,
                        exporting = exportingBatchId == batch.batchId,
                        completed = batch.endTime != null,
                        exportMessage = exportMessages[batch.batchId],
                        onExport = {
                            exportingBatchId = batch.batchId
                            scope.launch {
                                val safeCode = resolveBatchPartCode(
                                    dpmCode = batch.partId?.let { partDpmCodes[it] },
                                    partId = batch.partId,
                                    partName = batch.partName,
                                    batchId = batch.batchId,
                                )
                                val fileName = exportService.generateZipFileName(
                                    partId = batch.partId.orEmpty(),
                                    batchId = batch.batchId,
                                    partCode = safeCode,
                                    timestamp = batch.startTime,
                                )
                                val pkgId = repository.insertExportedPackage(
                                    ExportedPackageEntity(
                                        packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
                                        displayName = fileName,
                                        batchId = batch.batchId,
                                        status = ExportedPackageEntity.STATUS_EXPORTING
                                    )
                                )
                                pendingBatchPackageId = pkgId
                                createZipLauncher.launch(fileName)
                            }
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

            // ---- 已导出包列表 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已导出包",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showDeletePackageDialog = true },
                        enabled = selectedPackageIds.isNotEmpty() && !deletingPackage
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除选中包",
                            tint = if (selectedPackageIds.isNotEmpty() && !deletingPackage) FailColor else PlaceholderColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (exportedPackages.isNotEmpty()) {
                items(
                    count = exportedPackages.size,
                    key = { index -> exportedPackages[index].id },
                ) { index ->
                    val pkg = exportedPackages[index]
                    val isSelected = pkg.id in selectedPackageIds
                    val isExporting = pkg.status == ExportedPackageEntity.STATUS_EXPORTING
                    ExportedPackageCard(
                        pkg = pkg,
                        selected = isSelected,
                        onSelect = {
                            if (!isExporting) {
                                selectedPackageIds = if (pkg.id in selectedPackageIds) {
                                    selectedPackageIds - pkg.id
                                } else {
                                    selectedPackageIds + pkg.id
                                }
                            }
                        }
                    )
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "暂无导出包",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    val selectedBatches = batches.filter { it.batchId in selectedBatchIds }
    if (showDeleteDialog && selectedBatches.isNotEmpty()) {
        DeleteBatchDialog(
            batches = selectedBatches,
            batchNames = batchNames,
            photoCounts = photoCountState,
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
                            val result = repository.deleteCaptureBatchCompletely(batchId)
                            if (!result.success) {
                                throw IllegalStateException(result.error ?: "删除失败")
                            }
                            deletedBatchIds += batchId
                        }
                        selectedBatchIds = emptySet()
                        snackbarHostState.showSnackbar(
                            message = "已删除 ${deletedBatchIds.size} 个采集批次的批次记录、确认记录和现场照片；当前没有受管理 ZIP 文件可删除",
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

    // ---- 导出包删除确认对话框 ----
    val selectedPackages = exportedPackages.filter { it.id in selectedPackageIds }
    if (showDeletePackageDialog && selectedPackages.isNotEmpty()) {
        val hasExporting = selectedPackages.any { it.status == ExportedPackageEntity.STATUS_EXPORTING }
        AlertDialog(
            onDismissRequest = { showDeletePackageDialog = false },
            title = {
                Text(
                    text = "删除 ${selectedPackages.size} 个导出包",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectedPackages.take(3).forEach { pkg ->
                        val statusLabel = when (pkg.status) {
                            ExportedPackageEntity.STATUS_EXPORTING -> "导出中"
                            ExportedPackageEntity.STATUS_SUCCESS -> "成功"
                            ExportedPackageEntity.STATUS_FAILED -> "失败"
                            ExportedPackageEntity.STATUS_CANCELLED -> "已取消"
                            else -> pkg.status
                        }
                        Text(
                            text = "${pkg.displayName} · $statusLabel",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selectedPackages.size > 3) {
                        Text(text = "及其他 ${selectedPackages.size - 3} 个包")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (hasExporting) {
                        Text(
                            text = "导出中的包无法删除，请等待导出完成。",
                            color = PendingColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = "将删除所选导出包的 SAF 文件和本地记录。URI 权限失效时需手动清理。此操作无法恢复。",
                            color = FailColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeletePackageDialog = false
                        if (hasExporting) return@TextButton
                        deletingPackage = true
                        scope.launch {
                            val deletedIds = mutableListOf<Long>()
                            val errors = mutableListOf<String>()
                            selectedPackages.forEach { pkg ->
                                try {
                                    // 1. 删除 SAF 文件（如果有 URI）
                                    if (!pkg.safUri.isNullOrBlank()) {
                                        val uri = Uri.parse(pkg.safUri)
                                        // 优先 DocumentsContract.deleteDocument；返回 false 也必须尝试兼容删除。
                                        val safDeleted = try {
                                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                                        } catch (_: Exception) {
                                            false
                                        } || runCatching {
                                            context.contentResolver.delete(uri, null, null) > 0
                                        }.getOrDefault(false)
                                        if (!safDeleted) {
                                            errors.add("${pkg.displayName}: SAF 文件删除失败或权限已失效，请手动清理文件")
                                            return@forEach
                                        }
                                    }
                                    // 2. 只有 SAF 文件删除成功（或没有 URI）后才删除本地记录。
                                    if (repository.deleteExportedPackage(pkg.id) == 1) {
                                        deletedIds += pkg.id
                                    } else {
                                        errors.add("${pkg.displayName}: 本地包记录删除失败")
                                    }
                                } catch (e: Exception) {
                                    errors.add("${pkg.displayName}: 记录删除失败: ${e.localizedMessage}")
                                }
                            }
                            selectedPackageIds = selectedPackageIds - deletedIds.toSet()
                            deletingPackage = false
                            if (errors.isEmpty()) {
                                snackbarHostState.showSnackbar(
                                    message = "已删除 ${deletedIds.size} 个导出包",
                                    duration = SnackbarDuration.Short
                                )
                            } else {
                                snackbarHostState.showSnackbar(
                                    message = errors.joinToString("; "),
                                    duration = SnackbarDuration.Long
                                )
                            }
                        }
                    },
                    enabled = !hasExporting
                ) {
                    Text("确认删除", color = if (hasExporting) PlaceholderColor else FailColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePackageDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---- DPM 原始证据清理确认对话框 ----
    if (showCleanupDialog) {
        val currentStats = dpmStats
        AlertDialog(
            onDismissRequest = { if (!dpmCleaning) showCleanupDialog = false },
            title = {
                Text(text = "清理 DPM 原始证据", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "将删除 App 内原始证据，已导出的 ZIP 不受影响。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupDialog = false
                        dpmCleaning = true
                        dpmCleanupMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                repository.cleanupAllDpmEvidence()
                            }
                            dpmCleaning = false
                            dpmCleanupMessage = if (result.success) {
                                val sizeDisplay = formatBytes(result.releasedBytes)
                                "已清理 ${result.deletedDbRows} 条，释放 $sizeDisplay"
                            } else if (result.deletedDbRows > 0) {
                                "已清理 ${result.deletedDbRows} 条，${result.failedFiles} 个文件删除失败"
                            } else {
                                "清理失败"
                            }
                            // 刷新统计
                            dpmStats = withContext(Dispatchers.IO) {
                                repository.getDpmEvidenceStats()
                            }
                        }
                    },
                    enabled = !dpmCleaning
                ) {
                    Text("确认清理", color = if (dpmCleaning) PlaceholderColor else FailColor)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCleanupDialog = false },
                    enabled = !dpmCleaning
                ) {
                    Text("取消")
                }
            }
        )
    }
}

/** 格式化字节数为人类可读格式 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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
    batchName: String,
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
                        text = batchName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            batch.partName,
                            dateFormat.format(Date(batch.startTime)),
                        ).joinToString(" · "),
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
    batchNames: Map<String, String>,
    photoCounts: Map<String, Int?>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // 检查照片数量加载状态：key 不存在=加载中，value=null=加载失败，非 null=就绪
    val allReady = batches.all { it.batchId in photoCounts && photoCounts[it.batchId] != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除 ${batches.size} 个采集批次",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                batches.take(3).forEach { batch ->
                    val count = photoCounts[batch.batchId]
                    val countText = when {
                        count == null && batch.batchId !in photoCounts -> "加载中…"
                        count == null -> "加载失败"
                        else -> "$count 张照片"
                    }
                    Text(
                        text = "${batchNames[batch.batchId] ?: batch.partName ?: "未关联零件"} · $countText",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (batches.size > 3) {
                    Text(text = "及其他 ${batches.size - 3} 个批次")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "将删除所选批次的批次记录、确认记录和现场照片文件。当前没有受管理 ZIP 文件需要删除。此操作无法恢复。",
                    color = FailColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = allReady
            ) {
                Text("确认删除", color = if (allReady) FailColor else PlaceholderColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ExportedPackageCard(
    pkg: ExportedPackageEntity,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isExporting = pkg.status == ExportedPackageEntity.STATUS_EXPORTING
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pkg.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val typeLabel = when (pkg.packageType) {
                        ExportedPackageEntity.TYPE_DPM_EVIDENCE -> "DPM 证据"
                        ExportedPackageEntity.TYPE_BATCH_INSPECTION -> "批次检测"
                        else -> pkg.packageType
                    }
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary,
                    )
                    Text(
                        text = dateFormat.format(Date(pkg.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                // 状态和错误信息
                val statusColor = when (pkg.status) {
                    ExportedPackageEntity.STATUS_SUCCESS -> PassColor
                    ExportedPackageEntity.STATUS_FAILED -> FailColor
                    ExportedPackageEntity.STATUS_EXPORTING -> PendingColor
                    else -> PlaceholderColor
                }
                val statusText = when (pkg.status) {
                    ExportedPackageEntity.STATUS_EXPORTING -> "导出中…"
                    ExportedPackageEntity.STATUS_SUCCESS -> {
                        val sizeKb = pkg.byteSize / 1024
                        if (sizeKb > 1024) "成功 · ${sizeKb / 1024} MB" else "成功 · ${sizeKb} KB"
                    }
                    ExportedPackageEntity.STATUS_FAILED -> "失败：${pkg.errorMessage ?: "未知错误"}"
                    ExportedPackageEntity.STATUS_CANCELLED -> "已取消"
                    else -> pkg.status
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isExporting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.size(48.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = PendingColor
                )
            }
        }
    }
}
