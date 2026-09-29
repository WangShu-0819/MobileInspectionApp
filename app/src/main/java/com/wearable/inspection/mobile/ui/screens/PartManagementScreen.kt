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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.ui.theme.FailColor
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors
import com.wearable.inspection.mobile.ui.theme.PlaceholderColor
import com.wearable.inspection.mobile.ui.theme.Primary
import com.wearable.inspection.mobile.ui.theme.SurfaceWhite
import com.wearable.inspection.mobile.ui.theme.TextPrimary
import com.wearable.inspection.mobile.ui.theme.TextSecondary
import com.wearable.inspection.mobile.data.repository.DuplicatePartIdException
import kotlinx.coroutines.launch

/**
 * 零件管理页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartManagementScreen(
    onBack: () -> Unit
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val scope = rememberCoroutineScope()

    var parts by remember { mutableStateOf<List<PartEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var partIdInput by remember { mutableStateOf("") }
    var partNameInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var dpmCodeInput by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    var createPartColor by remember { mutableStateOf<PartCreationValidator.PartColor?>(null) }
    var partToDelete by remember { mutableStateOf<PartEntity?>(null) }

    LaunchedEffect(Unit) {
        parts = repository.getParts()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "零件管理",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
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
                },
                actions = {
                    IconButton(
                        onClick = {
                            partIdInput = ""
                            partNameInput = ""
                            modelInput = ""
                            dpmCodeInput = ""
                            createError = null
                            createPartColor = null
                            showCreateDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新建零件",
                            tint = Primary
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
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (loaded && parts.isEmpty()) {
                item {
                    EmptyPartsState()
                }
            } else if (loaded) {
                items(parts, key = { it.id }) { part ->
                    PartRow(
                        part = part,
                        onDelete = { partToDelete = part },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    partToDelete?.let { part ->
        AlertDialog(
            onDismissRequest = { partToDelete = null },
            title = { Text("删除零件") },
            text = {
                Text("确定删除“${part.name}”吗？该零件下的模板和视角也会一并删除，历史检测记录会保留。")
            },
            dismissButton = {
                TextButton(onClick = { partToDelete = null }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        partToDelete = null
                        scope.launch {
                            repository.deletePart(part.id)
                            parts = repository.getParts()
                        }
                    }
                ) {
                    Text("删除", color = FailColor)
                }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建零件") },
            text = {
                PartCreateFormFields(
                    partIdInput = partIdInput,
                    onPartIdChange = { partIdInput = it },
                    partNameInput = partNameInput,
                    onPartNameChange = { partNameInput = it },
                    selectedColor = createPartColor,
                    onColorSelected = { createPartColor = it },
                    createError = createError,
                    onClearError = { createError = null },
                    showOptionalFields = true,
                    modelInput = modelInput,
                    onModelChange = { modelInput = it },
                    dpmCodeInput = dpmCodeInput,
                    onDpmCodeChange = { dpmCodeInput = it },
                )
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val baseId = partIdInput.trim()
                        val name = partNameInput.trim()
                        val color = createPartColor
                        // 客户端校验
                        val localError = PartCreationValidator.validateBaseId(baseId, color, name, finalIdExists = false)
                        if (localError != null) {
                            createError = localError
                            return@TextButton
                        }
                        val finalId = PartCreationValidator.generateFinalId(baseId, color!!)
                        scope.launch {
                            val existing = repository.getPartById(finalId)
                            val dupError = PartCreationValidator.validateBaseId(baseId, color, name, finalIdExists = existing != null)
                            if (dupError != null) {
                                createError = dupError
                                return@launch
                            }
                            val insertError = try {
                                repository.insertNewPart(
                                    PartEntity(
                                        id = finalId,
                                        name = name,
                                        model = modelInput.trim().ifBlank { null },
                                        dpmCode = dpmCodeInput.trim().ifBlank { null },
                                    )
                                )
                                null // 插入成功
                            } catch (e: DuplicatePartIdException) {
                                "该零件 ID 已存在"
                            } catch (e: Exception) {
                                "保存失败：${e.message}"
                            }
                            if (insertError != null) {
                                createError = insertError
                                return@launch
                            }
                            // 成功后的刷新放在冲突捕获范围之外
                            parts = repository.getParts()
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("保存")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartRow(
    part: PartEntity,
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(FailColor)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除零件",
                    tint = SurfaceWhite,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "删除",
                    color = SurfaceWhite,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
        content = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = part.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            PartColorLabel(partId = part.id)
                        }
                        Text(
                            text = part.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        if (!part.model.isNullOrBlank()) {
                            Text(
                                text = "型号: ${part.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        if (!part.dpmCode.isNullOrBlank()) {
                            Text(
                                text = "DPM: ${part.dpmCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun EmptyPartsState() {
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
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = PlaceholderColor,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "暂无零件",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Text(
                text = "点击右上角 + 创建第一个零件",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor
            )
        }
    }
}
