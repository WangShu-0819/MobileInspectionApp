package com.wearable.inspection.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModel
import com.wearable.inspection.mobile.ui.screens.workbench.createWorkbenchViewModelFactory

@Composable
fun WorkbenchScreen(
    viewModel: WorkbenchViewModel = viewModel(
        factory = createWorkbenchViewModelFactory(LocalContext.current.applicationContext as Application)
    ),
    onStartInspection: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    onViewRecord: (Long) -> Unit
) {
    val parts by viewModel.parts.collectAsState()
    val selectedPart by viewModel.selectedPart.collectAsState()
    val todayStats by viewModel.todayStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 顶部：零件选择器
        Text(
            text = "工作台",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (parts.isEmpty()) {
            // 空状态：无零件
            EmptyPartState(onCreatePart = { /* TODO */ })
        } else {
            // 零件选择器
            PartSelector(
                parts = parts,
                selectedPart = selectedPart,
                onPartSelected = { viewModel.selectPart(it.id) }
            )

            Spacer(modifier = Modifier.padding(8.dp))

            // 上方：实时检测卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "实时检测",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(label = "模板就绪", value = "${todayStats.templateCount}")
                        StatItem(label = "今日通过", value = "${todayStats.passCount}")
                        StatItem(label = "今日不通过", value = "${todayStats.failCount}")
                    }

                    Spacer(modifier = Modifier.padding(16.dp))

                    Button(
                        onClick = { selectedPart?.id?.let { onStartInspection(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedPart != null && todayStats.templateCount > 0
                    ) {
                        Text("开始检测")
                    }
                }
            }

            Spacer(modifier = Modifier.padding(8.dp))

            // 下方：模板配置卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "模板配置",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ROI: ${todayStats.roiCount}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.padding(8.dp))

                    Text(
                        text = "当前零件: ${selectedPart?.name ?: "未选择"}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.padding(16.dp))

                    Button(
                        onClick = onOpenTemplates,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("管理模板")
                    }
                }
            }
        }
    }
}

@Composable
private fun PartSelector(
    parts: List<PartEntity>,
    selectedPart: PartEntity?,
    onPartSelected: (PartEntity) -> Unit
) {
    var expanded by androidx.compose.runtime.remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "当前零件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedPart?.name ?: "请选择",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null
            )
        }

        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            parts.forEach { part ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(part.name) },
                    onClick = {
                        onPartSelected(part)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyPartState(onCreatePart: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无零件", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onCreatePart) {
                Text("创建零件")
            }
        }
    }
}
