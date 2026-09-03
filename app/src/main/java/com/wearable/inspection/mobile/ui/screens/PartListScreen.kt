package com.wearable.inspection.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.template.TemplateImportService
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 零件列表页
 *
 * 入口层级：我的 → 模板配置 → 零件列表
 * 显示所有零件卡片，每张卡片展示：零件名称、视角数量、DPM 绑定状态。
 * 点击零件卡片导航到 PartDetail（视角网格）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartListScreen(
    onBack: () -> Unit,
    onPartClick: (String) -> Unit,
    onBindDpm: (String) -> Unit,
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val database = remember { MobileInspectionApp.instance.database }
    val scope = rememberCoroutineScope()

    var parts by remember { mutableStateOf<List<PartEntity>>(emptyList()) }
    var templates by remember { mutableStateOf<List<InspectionTemplateEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var showNewPartDialog by remember { mutableStateOf(false) }
    var newPartId by remember { mutableStateOf("") }
    var newPartName by remember { mutableStateOf("") }
    var newPartError by remember { mutableStateOf<String?>(null) }
    var selectedExistingPartId by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
            selectedExistingPartId = null
            newPartId = ""
            newPartName = ""
            newPartError = null
            showImportDialog = true
        }
    }

    fun reload() {
        scope.launch {
            parts = withContext(Dispatchers.IO) { repository.getParts() }
            templates = withContext(Dispatchers.IO) { repository.getAllTemplates() }
        }
    }

    LaunchedEffect(Unit) {
        parts = withContext(Dispatchers.IO) { repository.getParts() }
        templates = withContext(Dispatchers.IO) { repository.getAllTemplates() }
        loaded = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 每个零件的视角数量
    val partViewCounts = remember(templates) {
        templates.filter { it.enabled }.groupBy { it.partId }.mapValues { it.value.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "模板配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = Primary
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        containerColor = customColors.pageBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 导入按钮
            item {
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !importing,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "导入模板照片",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // 导入状态
            if (importing) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DividerColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("正在导入…", color = TextPrimary)
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Primary
                            )
                        }
                    }
                }
            }
            if (importMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DividerColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Text(
                            text = importMessage!!,
                            modifier = Modifier.padding(16.dp),
                            color = if (importMessage!!.startsWith("导入成功")) PassColor else FailColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 空状态
            if (loaded && parts.isEmpty()) {
                item {
                    EmptyPartsState()
                }
            } else if (loaded) {
                // 零件列表
                items(parts, key = { it.id }) { part ->
                    PartCard(
                        part = part,
                        viewCount = partViewCounts[part.id] ?: 0,
                        dpmCode = part.dpmCode,
                        onClick = { onPartClick(part.id) },
                        onBindDpm = { onBindDpm(part.id) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 导入对话框：选择已有零件或新建零件
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!importing) showImportDialog = false
            },
            title = { Text("导入模板照片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "已选择 ${selectedUris.size} 张照片，请选择目标零件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // 已有零件选择
                    if (parts.isNotEmpty()) {
                        Text(
                            text = "选择已有零件",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                        )
                        parts.forEach { part ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedExistingPartId = part.id
                                        newPartId = ""
                                        newPartName = ""
                                        newPartError = null
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = selectedExistingPartId == part.id,
                                    onClick = {
                                        selectedExistingPartId = part.id
                                        newPartId = ""
                                        newPartName = ""
                                        newPartError = null
                                    },
                                )
                                Column {
                                    Text(
                                        text = part.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary,
                                    )
                                    Text(
                                        text = "ID: ${part.id}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                    )
                                }
                            }
                        }
                    }

                    // 新建零件
                    Text(
                        text = "或新建零件",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                    )
                    OutlinedTextField(
                        value = newPartId,
                        onValueChange = {
                            newPartId = it
                            selectedExistingPartId = null
                            newPartError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("零件 ID") },
                        singleLine = true,
                        enabled = !importing,
                    )
                    OutlinedTextField(
                        value = newPartName,
                        onValueChange = { newPartName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("零件名称") },
                        singleLine = true,
                        enabled = !importing,
                    )
                    newPartError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = FailColor,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    enabled = !importing
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetPartId = selectedExistingPartId
                        val targetPartName: String
                        val actualPartId: String

                        if (targetPartId != null) {
                            // 使用已有零件
                            actualPartId = targetPartId
                            targetPartName = parts.firstOrNull { it.id == targetPartId }?.name ?: targetPartId
                        } else {
                            // 新建零件
                            actualPartId = newPartId.trim()
                            if (!actualPartId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                                newPartError = "零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）"
                                return@Button
                            }
                            targetPartName = newPartName.trim().ifBlank { actualPartId }
                        }

                        val uris = selectedUris
                        showImportDialog = false
                        importing = true
                        importMessage = null
                        scope.launch {
                            val result = TemplateImportService(context).importFromImageUris(
                                uris = uris,
                                partId = actualPartId,
                                partName = targetPartName,
                                database = database,
                            )
                            importMessage = if (result.success) {
                                "导入成功：${result.partId}，${result.templateCount} 个视角"
                            } else {
                                "导入失败：${result.errorMessage ?: "未知错误"}"
                            }
                            importing = false
                            reload()
                        }
                    },
                    enabled = !importing
                ) {
                    Text("导入")
                }
            }
        )
    }
}

@Composable
private fun PartCard(
    part: PartEntity,
    viewCount: Int,
    dpmCode: String?,
    onClick: () -> Unit,
    onBindDpm: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, DividerColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 零件信息
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = part.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${viewCount} 个视角",
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary,
                    )
                    Text(
                        text = if (dpmCode != null) "DPM: $dpmCode" else "未绑定 DPM",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dpmCode != null) TextSecondary else PlaceholderColor,
                    )
                }
            }

            // DPM 绑定按钮
            IconButton(
                onClick = onBindDpm,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = "绑定 DPM",
                    tint = Primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 进入箭头
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = PlaceholderColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyPartsState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = PlaceholderColor,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "暂无检测零件",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            Text(
                text = "点击上方按钮导入模板照片",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor,
            )
        }
    }
}
