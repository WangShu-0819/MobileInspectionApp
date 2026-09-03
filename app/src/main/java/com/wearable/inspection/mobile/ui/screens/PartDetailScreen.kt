package com.wearable.inspection.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.template.TemplateImportService
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
 * 零件详情页
 *
 * 展示该零件的所有视角（网格布局），支持：
 * - 点击视角进入 TemplateDetail
 * - 长按删除视角
 * - 拍摄新视角
 * - 从相册导入
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PartDetailScreen(
    partId: String,
    onBack: () -> Unit,
    onTemplateClick: (String) -> Unit,
    onCaptureNew: (String) -> Unit,
    onBindDpm: (String) -> Unit,
) {
    val customColors = LocalCustomColors.current
    val context = LocalContext.current
    val repository = remember { MobileInspectionApp.repository(context) }
    val database = remember { MobileInspectionApp.instance.database }
    val scope = rememberCoroutineScope()

    var part by remember { mutableStateOf<PartEntity?>(null) }
    var templates by remember { mutableStateOf<List<InspectionTemplateEntity>>(emptyList()) }
    var rois by remember { mutableStateOf<List<RoiDefinitionEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var templateToDelete by remember { mutableStateOf<InspectionTemplateEntity?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            importMessage = null
            scope.launch {
                val result = TemplateImportService(context).importFromImageUris(
                    uris = uris,
                    partId = partId,
                    partName = part?.name ?: partId,
                    database = database,
                )
                importMessage = if (result.success) {
                    "导入成功：${result.templateCount} 个视角"
                } else {
                    "导入失败：${result.errorMessage ?: "未知错误"}"
                }
                importing = false
                reload(partId, repository) { p, t, r ->
                    part = p
                    templates = t
                    rois = r
                }
            }
        }
    }

    fun reload() {
        scope.launch {
            reload(partId, repository) { p, t, r ->
                part = p
                templates = t
                rois = r
            }
        }
    }

    LaunchedEffect(partId) {
        reload(partId, repository) { p, t, r ->
            part = p
            templates = t
            rois = r
        }
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

    // 每个 template 的 ROI 数量
    val roiCounts = remember(rois) {
        rois.groupBy { it.templateId }.mapValues { it.value.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = part?.name ?: "零件详情",
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
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // DPM 绑定
                    IconButton(onClick = { onBindDpm(partId) }) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "绑定 DPM",
                            tint = Primary,
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // 从相册导入
                FloatingActionButton(
                    onClick = { imagePicker.launch("image/*") },
                    containerColor = SurfaceWhite,
                    contentColor = Primary,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "从相册导入",
                    )
                }
                // 拍摄新视角
                FloatingActionButton(
                    onClick = { onCaptureNew(partId) },
                    containerColor = Primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "拍摄新视角",
                    )
                }
            }
        },
        containerColor = customColors.pageBackground,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // 导入状态
            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                )
            }
            importMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (message.startsWith("导入成功")) Primary else FailColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 零件信息
            part?.let { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "${templates.size} 个视角",
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary,
                    )
                    Text(
                        text = if (p.dpmCode != null) "DPM: ${p.dpmCode}" else "未绑定 DPM",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.dpmCode != null) TextSecondary else PlaceholderColor,
                    )
                }
            }

            // 视角网格
            if (loaded && templates.isEmpty()) {
                EmptyViewsState()
            } else if (loaded) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(templates, key = { it.id }) { template ->
                        ViewGridCard(
                            template = template,
                            roiCount = roiCounts[template.id] ?: 0,
                            onClick = { onTemplateClick(template.id) },
                            onDelete = { templateToDelete = template },
                        )
                    }
                    // 底部间距
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // 删除确认对话框
    templateToDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text("删除视角") },
            text = {
                Text("确定删除「${template.name}」吗？该视角的参考图片和已有 ROI 配置也会删除。")
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
                            reload()
                        }
                    }
                ) {
                    Text("删除", color = FailColor)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewGridCard(
    template: InspectionTemplateEntity,
    roiCount: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val imageExists = remember(template.mainImagePath) {
        java.io.File(template.mainImagePath).exists()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete,
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, DividerColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            // 缩略图
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = remember(template.mainImagePath) {
                    try {
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = 4
                        }
                        android.graphics.BitmapFactory.decodeFile(template.mainImagePath, opts)
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "视角缩略图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = null,
                        tint = PlaceholderColor,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // 信息
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (roiCount > 0) "${roiCount} 个 ROI" else "无 ROI",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (roiCount > 0) Primary else PlaceholderColor,
                )
            }
        }
    }
}

@Composable
private fun EmptyViewsState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
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
                text = "暂无视角",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            Text(
                text = "点击右下角按钮拍摄或导入",
                style = MaterialTheme.typography.bodySmall,
                color = PlaceholderColor,
            )
        }
    }
}

private suspend fun reload(
    partId: String,
    repository: com.wearable.inspection.mobile.data.repository.InspectionRepository,
    onUpdate: (PartEntity?, List<InspectionTemplateEntity>, List<RoiDefinitionEntity>) -> Unit,
) {
    withContext(Dispatchers.IO) {
        val p = repository.getPartById(partId)
        val t = repository.getTemplatesByPart(partId).filter { it.enabled }
        val r = t.flatMap { repository.getRois(it.id) }
        onUpdate(p, t, r)
    }
}
