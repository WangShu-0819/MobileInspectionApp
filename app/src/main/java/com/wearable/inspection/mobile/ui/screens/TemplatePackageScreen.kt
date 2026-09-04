package com.wearable.inspection.mobile.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.template.TemplateImportService
import com.wearable.inspection.mobile.template.TemplatePackageExporter
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 模板包管理：导入、导出和按零件删除完整模板配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePackageScreen(
    onBack: () -> Unit,
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { MobileInspectionApp.instance.database }
    val repository = remember { MobileInspectionApp.repository(context) }

    var importState by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var exportingPartId by remember { mutableStateOf<String?>(null) }
    var deletingPartId by remember { mutableStateOf<String?>(null) }
    var packageToDelete by remember { mutableStateOf<ConfiguredTemplatePackage?>(null) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }

    val parts by repository.observeParts().collectAsState(initial = emptyList())
    val templates by repository.observeAllTemplates().collectAsState(initial = emptyList())
    val packages = remember(parts, templates) {
        parts.mapNotNull { part ->
            val partTemplates = templates
                // 模板包应覆盖该零件的全部 View；enabled 作为配置字段原样导出，不能静默丢失停用 View。
                .filter { it.partId == part.id }
                .sortedWith(compareBy<InspectionTemplateEntity> { it.displayOrder }.thenBy { it.id })
            partTemplates.takeIf { it.isNotEmpty() }
                ?.let { ConfiguredTemplatePackage(part, it) }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        importState = ImportState.Importing
        operationMessage = null
        scope.launch {
            try {
                val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取文件")

                    val result = TemplateImportService(context).importFromZip(tempFile, database)
                    importState = if (result.success) {
                        ImportState.Success(
                            partId = result.partId,
                            templateCount = result.templateCount,
                            roiCount = result.roiCount,
                            warnings = result.warnings,
                        )
                    } else {
                        ImportState.Error(result.errorMessage ?: "导入失败")
                    }
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                importState = ImportState.Error(e.message ?: "导入失败")
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val tempFile = pendingExportFile
        pendingExportFile = null
        val partId = exportingPartId

        if (tempFile == null) {
            exportingPartId = null
            operationMessage = "导出失败：临时模板包不存在"
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            tempFile.delete()
            exportingPartId = null
            operationMessage = "已取消导出"
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法写入所选位置")
                operationMessage = "模板包已导出：${partId ?: "当前零件"}"
            } catch (e: Exception) {
                operationMessage = "导出失败：${e.message ?: "无法写入文件"}"
            } finally {
                tempFile.delete()
                exportingPartId = null
            }
        }
    }

    fun exportPackage(packageInfo: ConfiguredTemplatePackage) {
        if (exportingPartId != null || deletingPartId != null) return
        exportingPartId = packageInfo.part.id
        operationMessage = null
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val part = repository.getPartById(packageInfo.part.id)
                        ?: error("零件不存在")
                    val latestTemplates = repository.getTemplatesByPart(part.id)
                    val rois = latestTemplates.associate { it.id to repository.getRois(it.id) }
                    val outputFile = File(context.cacheDir, templatePackageFileName(part.id))
                    TemplatePackageExporter.export(part, latestTemplates, rois, outputFile)
                }
                pendingExportFile = summary.file
                exportPicker.launch(summary.file.name)
            } catch (e: Exception) {
                exportingPartId = null
                operationMessage = "导出失败：${e.message ?: "未知错误"}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "模板包",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            )
        },
        containerColor = customColors.pageBackground,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "导入模板包",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary,
                        )
                        Text(
                            text = "模板包包含零件、DPM、全部视角图片、顺序和 ROI 配置。导出的包可再次从这里导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        Button(
                            onClick = {
                                zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = importState !is ImportState.Importing &&
                                exportingPartId == null && deletingPartId == null,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("选择 ZIP 文件", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            operationMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (message.startsWith("模板包已导出") || message.startsWith("已删除")) {
                            PassColor
                        } else {
                            FailColor
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                when (val state = importState) {
                    is ImportState.Idle -> Unit
                    is ImportState.Importing -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("正在导入…", color = TextPrimary)
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Primary,
                                )
                            }
                        }
                    }
                    is ImportState.Success -> ImportSuccessCard(state)
                    is ImportState.Error -> ImportErrorCard(
                        message = state.message,
                        onRetry = {
                            importState = ImportState.Idle
                            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                        },
                    )
                }
            }

            item {
                Text(
                    text = "已配置模板包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
            }

            if (packages.isEmpty()) {
                item { EmptyTemplatePackagesCard() }
            } else {
                items(packages, key = { it.part.id }) { packageInfo ->
                    TemplatePackageCard(
                        packageInfo = packageInfo,
                        exporting = exportingPartId == packageInfo.part.id,
                        deleting = deletingPartId == packageInfo.part.id,
                        actionsEnabled = exportingPartId == null && deletingPartId == null,
                        onExport = { exportPackage(packageInfo) },
                        onDelete = { packageToDelete = packageInfo },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    packageToDelete?.let { packageInfo ->
        AlertDialog(
            onDismissRequest = { if (deletingPartId == null) packageToDelete = null },
            title = { Text("删除模板包") },
            text = {
                Text(
                    "确定删除「${packageInfo.part.name}」的模板包吗？将删除 ${packageInfo.templates.size} 个视角、参考图片和全部 ROI；历史采集批次与照片会保留。"
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { packageToDelete = null },
                    enabled = deletingPartId == null,
                ) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val partId = packageInfo.part.id
                        packageToDelete = null
                        deletingPartId = partId
                        operationMessage = null
                        scope.launch {
                            try {
                                repository.deleteTemplatePackage(partId)
                                operationMessage = "已删除模板包：${packageInfo.part.name}"
                            } catch (e: Exception) {
                                operationMessage = "删除失败：${e.message ?: "未知错误"}"
                            } finally {
                                deletingPartId = null
                            }
                        }
                    },
                    enabled = deletingPartId == null && exportingPartId == null,
                ) { Text("删除", color = FailColor) }
            },
        )
    }
}

private data class ConfiguredTemplatePackage(
    val part: PartEntity,
    val templates: List<InspectionTemplateEntity>,
)

private fun templatePackageFileName(partId: String): String {
    val safePartId = partId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "template_${safePartId}_$time.zip"
}

@Composable
private fun TemplatePackageCard(
    packageInfo: ConfiguredTemplatePackage,
    exporting: Boolean,
    deleting: Boolean,
    actionsEnabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = packageInfo.part.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "ID: ${packageInfo.part.id} · ${packageInfo.templates.size} 个视角",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (!packageInfo.part.dpmCode.isNullOrBlank()) {
                Text(
                    text = "DPM: ${packageInfo.part.dpmCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onExport,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) {
                    if (exporting) {
                        LinearProgressIndicator(
                            modifier = Modifier.width(48.dp),
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text("导出", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = onDelete,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceWhite,
                        contentColor = FailColor,
                        disabledContentColor = PlaceholderColor,
                    ),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("删除", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (deleting) {
                Text("正在删除…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ImportSuccessCard(state: ImportState.Success) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("导入成功", color = PassColor, fontWeight = FontWeight.Medium)
            Text("零件: ${state.partId}", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            Text("模板: ${state.templateCount} 个 · ROI: ${state.roiCount} 个", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            state.warnings.forEach { warning ->
                Text("· $warning", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImportErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("导入失败", color = FailColor, fontWeight = FontWeight.Medium)
            Text(message, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun EmptyTemplatePackagesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = PlaceholderColor, modifier = Modifier.size(40.dp))
            Text("暂无已配置模板包", color = TextSecondary)
            Text("导入 ZIP 或先在模板配置中创建视角", color = PlaceholderColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private sealed class ImportState {
    object Idle : ImportState()
    object Importing : ImportState()
    data class Success(
        val partId: String,
        val templateCount: Int,
        val roiCount: Int,
        val warnings: List<String>,
    ) : ImportState()
    data class Error(val message: String) : ImportState()
}
