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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.repository.DuplicatePartIdException
import com.wearable.inspection.mobile.ui.theme.BackgroundVariant1
import com.wearable.inspection.mobile.ui.theme.DividerColor
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.InfoBannerBg
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
    var createPartColor by remember { mutableStateOf<PartCreationValidator.PartColor?>(null) }

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
                        createPartColor = null
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
                PartCreateFormFields(
                    partIdInput = createPartId,
                    onPartIdChange = { createPartId = it },
                    partNameInput = createPartName,
                    onPartNameChange = { createPartName = it },
                    selectedColor = createPartColor,
                    onColorSelected = { createPartColor = it },
                    createError = createPartError,
                    onClearError = { createPartError = null },
                )
            },
            dismissButton = {
                TextButton(onClick = { showCreatePartDialog = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val baseId = createPartId.trim()
                        val name = createPartName.trim()
                        val color = createPartColor
                        // 先做客户端校验（不需要网络/DB）
                        val localError = PartCreationValidator.validateBaseId(baseId, color, name, finalIdExists = false)
                        if (localError != null) {
                            createPartError = localError
                            return@Button
                        }
                        val finalId = PartCreationValidator.generateFinalId(baseId, color!!)
                        // 异步检查重复最终 ID
                        scope.launch {
                            val existing = withContext(Dispatchers.IO) {
                                repository.getPartById(finalId)
                            }
                            val dupError = PartCreationValidator.validateBaseId(baseId, color, name, finalIdExists = existing != null)
                            if (dupError != null) {
                                createPartError = dupError
                                return@launch
                            }
                            val now = System.currentTimeMillis()
                            val insertError = try {
                                withContext(Dispatchers.IO) {
                                    repository.insertNewPart(
                                        PartEntity(
                                            id = finalId,
                                            name = name,
                                            createdAt = now,
                                            updatedAt = now,
                                        )
                                    )
                                }
                                null // 插入成功
                            } catch (e: DuplicatePartIdException) {
                                "该零件 ID 已存在"
                            } catch (e: Exception) {
                                "保存失败：${e.message}"
                            }
                            if (insertError != null) {
                                createPartError = insertError
                                return@launch
                            }
                            // 成功后的刷新和导航放在冲突捕获范围之外
                            showCreatePartDialog = false
                            reload()
                            onPartCreated(finalId)
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = part.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    PartColorLabel(partId = part.id)
                }
                Text(
                    text = part.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
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

// ── 共享：件色标签 ──

/**
 * 件色标签小徽章。
 *
 * 使用 InfoBannerBg 底色 + Primary 文字表示白件/黑件；
 * BackgroundVariant1 底色 + TextSecondary 文字表示未标记（旧零件）。
 * 不使用黑/白实色背景。
 */
@Composable
fun PartColorLabel(partId: String) {
    val label = when {
        partId.startsWith(PartCreationValidator.PartColor.WHITE.prefix) -> "白件"
        partId.startsWith(PartCreationValidator.PartColor.BLACK.prefix) -> "黑件"
        else -> "未标记"
    }
    val bgColor = if (label == "未标记") BackgroundVariant1 else InfoBannerBg
    val textColor = if (label == "未标记") TextSecondary else Primary

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ── 共享：件色选择器 ──

/**
 * 件色选择器：两个并排、等宽、互斥选择项。
 *
 * - 未选中：SurfaceWhite 底色、DividerColor 描边、TextPrimary 文字。
 * - 选中：InfoBannerBg 底色、Primary 描边、Primary 文字。
 * - 初始不选中任何一项。
 */
@Composable
fun PartColorSelector(
    selectedColor: PartCreationValidator.PartColor?,
    onColorSelected: (PartCreationValidator.PartColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PartCreationValidator.PartColor.entries.forEach { color ->
            val selected = selectedColor == color
            val bgColor = if (selected) InfoBannerBg else SurfaceWhite
            val borderColor = if (selected) Primary else DividerColor
            val textColor = if (selected) Primary else TextPrimary

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = bgColor,
                border = BorderStroke(1.5.dp, borderColor),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        this.selected = selected
                        testTag = "color_${color.name}"
                    }
                    .clickable {
                        onColorSelected(color)
                    },
            ) {
                Text(
                    text = color.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── 共享：新建零件表单字段 ──

/**
 * 新建零件表单内容字段（件色选择、编号、名称、可选型号/DPM 码、ID 预览、错误提示）。
 *
 * PartListScreen 和 PartManagementScreen 的新建零件对话框共用此组件。
 * 各入口的保存按钮风格（Button vs TextButton）由调用方在 AlertDialog 级别控制。
 *
 * @param partIdInput 零件编号基础 ID
 * @param onPartIdChange 编号变更回调
 * @param partNameInput 零件名称
 * @param onPartNameChange 名称变更回调
 * @param selectedColor 当前选中件色
 * @param onColorSelected 件色选择回调
 * @param createError 当前错误消息
 * @param onClearError 清除错误回调
 * @param showOptionalFields 是否显示型号和 DPM 码字段（PartManagementScreen 使用）
 * @param modelInput 型号输入值（showOptionalFields=true 时有效）
 * @param onModelChange 型号变更回调
 * @param dpmCodeInput DPM 码输入值（showOptionalFields=true 时有效）
 * @param onDpmCodeChange DPM 码变更回调
 */
@Composable
fun PartCreateFormFields(
    partIdInput: String,
    onPartIdChange: (String) -> Unit,
    partNameInput: String,
    onPartNameChange: (String) -> Unit,
    selectedColor: PartCreationValidator.PartColor?,
    onColorSelected: (PartCreationValidator.PartColor) -> Unit,
    createError: String?,
    onClearError: () -> Unit,
    showOptionalFields: Boolean = false,
    modelInput: String = "",
    onModelChange: (String) -> Unit = {},
    dpmCodeInput: String = "",
    onDpmCodeChange: (String) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 件色选择（必选，无默认）
        Text(
            text = "件色",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        PartColorSelector(
            selectedColor = selectedColor,
            onColorSelected = { color ->
                onColorSelected(color)
                onClearError()
            },
        )
        // 零件编号（基础 ID）
        OutlinedTextField(
            value = partIdInput,
            onValueChange = {
                onPartIdChange(it)
                onClearError()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("零件编号") },
            singleLine = true,
        )
        // 最终 ID 预览（低强调辅助信息）
        if (partIdInput.isNotBlank() && selectedColor != null) {
            val finalId = PartCreationValidator.generateFinalId(partIdInput.trim(), selectedColor)
            Text(
                text = "生成编号：$finalId",
                style = MaterialTheme.typography.bodySmall,
                color = if (finalId.length <= 64) TextSecondary else FailColor,
            )
        }
        // 零件名称
        OutlinedTextField(
            value = partNameInput,
            onValueChange = { onPartNameChange(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("名称") },
            singleLine = true,
        )
        // 型号和 DPM 码（PartManagementScreen 入口使用）
        if (showOptionalFields) {
            OutlinedTextField(
                value = modelInput,
                onValueChange = { onModelChange(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("型号（可选）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = dpmCodeInput,
                onValueChange = { onDpmCodeChange(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DPM 码（可选）") },
                singleLine = true,
            )
        }
        createError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = FailColor,
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
    private val BASE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,58}")

    /** 件色枚举。 */
    enum class PartColor(val prefix: String, val displayName: String) {
        WHITE("White_", "白件"),
        BLACK("Black_", "黑件"),
    }

    /**
     * 校验零件创建输入（旧接口，保持向后兼容）。
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

    /**
     * 生成最终零件 ID。
     *
     * @param baseId 基础 ID（已 trim）
     * @param color 件色
     * @return 最终带前缀的零件 ID
     */
    fun generateFinalId(baseId: String, color: PartColor): String {
        return "${color.prefix}$baseId"
    }

    /**
     * 校验新建零件输入（件色 + 基础 ID）。
     *
     * @param baseId 基础 ID（已 trim）
     * @param color 件色（null 表示未选择）
     * @param name 零件名称（已 trim）
     * @param finalIdExists 最终 ID 是否已存在
     * @return 错误消息；null 表示校验通过
     */
    fun validateBaseId(baseId: String, color: PartColor?, name: String, finalIdExists: Boolean): String? {
        return when {
            color == null -> "请选择件色（白件/黑件）"
            baseId.isBlank() -> "请输入基础 ID"
            name.isBlank() -> "请输入零件名称"
            !baseId.matches(BASE_ID_REGEX) -> "基础 ID 仅支持字母、数字、下划线和连字符（1~58 位）"
            generateFinalId(baseId, color).length > 64 -> "最终 ID 超过 64 位上限"
            finalIdExists -> "该零件 ID 已存在"
            else -> null
        }
    }
}
