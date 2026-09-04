package com.wearable.inspection.mobile.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.LaunchedEffect
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
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.export.InspectionExportResult
import com.wearable.inspection.mobile.data.export.InspectionZipExportService
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 检测结果导出页面
 *
 * 自动为该批次生成 ZIP，显示结果统计，提供下载和分享功能。
 * 一个零件一个 ZIP，不混入其他零件或批次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportResultScreen(
    batchId: String,
    partId: String,
    partName: String,
    onBack: () -> Unit,
    onDone: () -> Unit = onBack
) {
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val scope = rememberCoroutineScope()
    val exportService = remember { InspectionZipExportService(context, repository) }

    var isGenerating by remember { mutableStateOf(true) }
    var exportResult by remember { mutableStateOf<InspectionExportResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 持久化 ZIP 路径（用于分享/下载）
    var zipFilePath by remember { mutableStateOf<String?>(null) }

    // SAF 下载
    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = zipFilePath ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val zipFile = File(path)
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        zipFile.inputStream().use { it.copyTo(os) }
                    }
                }
                Toast.makeText(context, "下载成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 自动生成 ZIP
    LaunchedEffect(batchId) {
        withContext(Dispatchers.IO) {
            try {
                val outputFile = File(context.cacheDir, exportService.generateZipFileName(partId, batchId))
                val result = exportService.exportInspectionZip(batchId, partId, outputFile)
                exportResult = result
                if (result is InspectionExportResult.Success) {
                    zipFilePath = result.zipFile.absolutePath
                }
            } catch (e: Exception) {
                errorMessage = "生成 ZIP 失败：${e.message}"
            } finally {
                isGenerating = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "检测结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                )
            )
        },
        containerColor = BackgroundVariant1
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                isGenerating -> {
                    CircularProgressIndicator(color = Primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在生成检测报告…", color = TextSecondary)
                }
                errorMessage != null -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = FailColor,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(errorMessage!!, color = FailColor, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("返回")
                    }
                }
                exportResult is InspectionExportResult.Success -> {
                    val success = exportResult as InspectionExportResult.Success

                    // 成功图标
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PassColor,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "检测报告已生成",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // 统计卡片
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
                            StatRow("零件", partName)
                            StatRow("照片数量", "${success.photoCount} 张")
                            StatRow("检测记录", "${success.csvRowCount} 条")
                            StatRow("批次 ID", success.batchId.take(12) + "…")
                            if (success.skippedCount > 0) {
                                StatRow("跳过", "${success.skippedCount} 张", FailColor)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 下载按钮
                    Button(
                        onClick = {
                            val fileName = exportService.generateZipFileName(partId, batchId)
                            createZipLauncher.launch(fileName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载 ZIP", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 分享按钮
                    Button(
                        onClick = {
                            val path = zipFilePath ?: return@Button
                            val file = File(path)
                            if (!file.exists()) {
                                Toast.makeText(context, "ZIP 文件不存在", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val uri = repository.getFileUri(file)
                            if (uri == null) {
                                Toast.makeText(context, "无法生成分享链接", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享检测报告"))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Primary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "分享",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 完成按钮
                    Button(
                        onClick = onDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("返回采集页", fontSize = 14.sp)
                    }
                }
                exportResult is InspectionExportResult.Failure -> {
                    val failure = exportResult as InspectionExportResult.Failure
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = FailColor,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(failure.message, color = FailColor, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
