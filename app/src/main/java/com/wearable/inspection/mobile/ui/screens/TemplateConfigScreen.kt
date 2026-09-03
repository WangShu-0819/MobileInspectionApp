package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.template.TemplateImportService
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.PassColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.DividerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 零件模板分组
 */
private data class PartTemplateGroup(
    val partId: String,
    val partName: String,
    val dpmCode: String?,
    val templates: List<InspectionTemplateEntity>
)

/**
 * 模板配置二级页
 * 入口层级：我的 -> 模板配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateConfigScreen(
    onBack: () -> Unit,
    onTemplateClick: (String) -> Unit,
    onBindDpm: (String) -> Unit,
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val database = remember { MobileInspectionApp.instance.database }
    val scope = rememberCoroutineScope()

    var templates by remember { mutableStateOf<List<InspectionTemplateEntity>>(emptyList()) }
    var parts by remember { mutableStateOf<List<PartEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var partIdInput by remember { mutableStateOf("") }
    var partNameInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var templateToDelete by remember { mutableStateOf<InspectionTemplateEntity?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
            partIdInput = ""
            partNameInput = ""
            importError = null
            showImportDialog = true
        }
    }

    fun reloadTemplates() {
        scope.launch {
            templates = withContext(Dispatchers.IO) { repository.getAllTemplates() }
            parts = withContext(Dispatchers.IO) { repository.getParts() }
        }
    }

    LaunchedEffect(Unit) {
        templates = withContext(Dispatchers.IO) { repository.getAllTemplates() }
        parts = withContext(Dispatchers.IO) { repository.getParts() }
        loaded = true
    }

    // 从 DPM 绑定页返回时刷新卡片，立即显示新的绑定码。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadTemplates()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 按零件分组
    val groups = remember(templates, parts) {
        templates
            .filter { it.enabled }
            .groupBy { it.partId }
            .map { (partId, tplList) ->
            val part = parts.firstOrNull { it.id == partId }
            PartTemplateGroup(
                partId = partId,
                partName = part?.name ?: partId,
                dpmCode = part?.dpmCode,
                templates = tplList
            )
        }
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
                            imageVector = Icons.Default.ArrowBack,
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

            if (loaded && groups.isEmpty()) {
                item {
                    EmptyTemplatesState()
                }
            } else if (loaded) {
                items(groups, key = { it.partId }) { group ->
                    PartGroupCard(
                        group = group,
                        onTemplateClick = onTemplateClick,
                        onTemplateDelete = { templateToDelete = it },
                        onBindDpm = onBindDpm,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    templateToDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text("删除视角") },
            text = {
                Text("确定删除“${template.name}”吗？该视角的参考图片和已有模板 ROI 记录也会删除。")
            },
            dismissButton = {
                TextButton(onClick = { templateToDelete = null }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        templateToDelete = null
                        scope.launch {
                            repository.deleteTemplate(template.id)
                            reloadTemplates()
                        }
                    }
                ) {
                    Text("删除", color = FailColor)
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!importing) showImportDialog = false
            },
            title = { Text("导入模板照片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "已选择 ${selectedUris.size} 张照片，将按选择顺序创建为同一零件的多个视角。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = partIdInput,
                        onValueChange = {
                            partIdInput = it
                            importError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("零件 ID") },
                        singleLine = true,
                        enabled = !importing
                    )
                    OutlinedTextField(
                        value = partNameInput,
                        onValueChange = { partNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("零件名称") },
                        singleLine = true,
                        enabled = !importing
                    )
                    importError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = FailColor
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
                        val partId = partIdInput.trim()
                        if (!partId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                            importError = "零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）"
                            return@Button
                        }
                        val uris = selectedUris
                        showImportDialog = false
                        importing = true
                        importMessage = null
                        scope.launch {
                            val result = TemplateImportService(context).importFromImageUris(
                                uris = uris,
                                partId = partId,
                                partName = partNameInput,
                                database = database,
                            )
                            importMessage = if (result.success) {
                                "导入成功：${result.partId}，${result.templateCount} 个视角"
                            } else {
                                "导入失败：${result.errorMessage ?: "未知错误"}"
                            }
                            importing = false
                            reloadTemplates()
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
private fun PartGroupCard(
    group: PartTemplateGroup,
    onTemplateClick: (String) -> Unit,
    onTemplateDelete: (InspectionTemplateEntity) -> Unit,
    onBindDpm: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, DividerColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 零件标题 + 视角数
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.partName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Text(
                    text = "${group.templates.size} 个视角",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "DPM 绑定",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Text(
                        text = group.dpmCode ?: "未绑定",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (group.dpmCode == null) TextSecondary else TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { onBindDpm(group.partId) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Primary),
                ) {
                    Text(if (group.dpmCode == null) "扫码绑定" else "更换绑定")
                }
            }

            Divider()

            // 各视角列表
            group.templates.forEachIndexed { index, template ->
                TemplateSwipeRow(
                    index = index,
                    template = template,
                    onClick = { onTemplateClick(template.id) },
                    onDelete = { onTemplateDelete(template) },
                )
                if (index < group.templates.lastIndex) {
                    Divider(color = DividerColor)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSwipeRow(
    index: Int,
    template: InspectionTemplateEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(FailColor)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除视角",
                    tint = SurfaceWhite,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "删除",
                    color = SurfaceWhite,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClick() }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}. ${template.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = PlaceholderColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
}

@Composable
private fun EmptyTemplatesState() {
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
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = PlaceholderColor,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "暂无检测模板",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Text(
                text = "点击上方按钮导入模板照片",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor
            )
        }
    }
}
