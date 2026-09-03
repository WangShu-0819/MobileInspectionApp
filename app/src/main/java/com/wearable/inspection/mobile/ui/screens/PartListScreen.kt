package com.wearable.inspection.mobile.ui.screens

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
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
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
    onPartCreated: (String) -> Unit = {},
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val scope = rememberCoroutineScope()

    var parts by remember { mutableStateOf<List<PartEntity>>(emptyList()) }
    var templates by remember { mutableStateOf<List<InspectionTemplateEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showCreatePartDialog by remember { mutableStateOf(false) }
    var createPartId by remember { mutableStateOf("") }
    var createPartName by remember { mutableStateOf("") }
    var createPartError by remember { mutableStateOf<String?>(null) }

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
                },
                actions = {
                    IconButton(onClick = {
                        createPartId = ""
                        createPartName = ""
                        createPartError = null
                        showCreatePartDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新建零件",
                            tint = Primary,
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

    // 新建零件对话框
    if (showCreatePartDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePartDialog = false },
            title = { Text("新建零件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "创建零件后可从详情页导入模板照片或拍摄视角。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    OutlinedTextField(
                        value = createPartId,
                        onValueChange = {
                            createPartId = it
                            createPartError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("零件 ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = createPartName,
                        onValueChange = { createPartName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("零件名称") },
                        singleLine = true,
                    )
                    createPartError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = FailColor,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePartDialog = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = createPartId.trim()
                        val name = createPartName.trim()
                        // 先做客户端校验（不需要网络/DB）
                        val localError = PartCreationValidator.validate(id, name, idExists = false)
                        if (localError != null) {
                            createPartError = localError
                            return@Button
                        }
                        // 异步检查重复 ID
                        scope.launch {
                            val existing = withContext(Dispatchers.IO) {
                                repository.getPartById(id)
                            }
                            val dupError = PartCreationValidator.validate(id, name, idExists = existing != null)
                            if (dupError != null) {
                                createPartError = dupError
                                return@launch
                            }
                            val now = System.currentTimeMillis()
                            withContext(Dispatchers.IO) {
                                repository.upsertPart(
                                    PartEntity(
                                        id = id,
                                        name = name,
                                        createdAt = now,
                                        updatedAt = now,
                                    )
                                )
                            }
                            showCreatePartDialog = false
                            reload()
                            onPartCreated(id)
                        }
                    },
                ) {
                    Text("创建")
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
                text = "点击右上角 + 新建零件",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor,
            )
        }
    }
}

/**
 * 零件创建校验工具
 *
 * 提取为独立对象以便单元测试。
 */
object PartCreationValidator {
    private val ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * 校验零件创建输入。
     *
     * @param id 零件 ID（已 trim）
     * @param name 零件名称（已 trim）
     * @param idExists 同 ID 零件是否已存在
     * @return 错误消息；null 表示校验通过
     */
    fun validate(id: String, name: String, idExists: Boolean): String? {
        return when {
            id.isBlank() -> "请输入零件 ID"
            name.isBlank() -> "请输入零件名称"
            !id.matches(ID_REGEX) -> "零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）"
            idExists -> "该零件 ID 已存在"
            else -> null
        }
    }
}
