# 修复 DPM 扫码证据绑定采集批次 ZIP

## Context

当用户在 DPM 扫码阶段解码成功后切换零件模板再开始采集，DPM 成功源帧没有绑定到采集批次。原因是：
1. 扫码时 `activeCaptureBatchId` 为 null，证据以 `batchId=null` 存储
2. `PartSelectionBus` 触发 `selectPart()` 后清除旧批次上下文
3. 第一张照片创建新 batchId 后，没有机制将之前的扫码证据关联上去

## 方案概述

以稳定 `scanSessionId` 为唯一绑定依据，通过「暂存 → 消费」模式实现跨步骤关联。

## 实现步骤

### 1. DAO：新增批量绑定方法

**文件**: [DpmScanEvidenceDao.kt](app/src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt)

添加方法：
```kotlin
@Query("""
    UPDATE dpm_scan_evidence 
    SET batchId = :batchId 
    WHERE scanSessionId = :scanSessionId 
      AND batchId IS NULL 
      AND status = 'SUCCESS'
""")
suspend fun bindSessionToBatch(scanSessionId: String, batchId: String): Int
```

- 条件 `batchId IS NULL` 保证幂等：已绑定的不重复覆盖
- 条件 `status = 'SUCCESS'` 只绑定成功证据

### 2. Repository：暴露绑定方法

**文件**: [InspectionRepository.kt](app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt)

添加方法：
```kotlin
suspend fun bindDpmScanSessionToBatch(scanSessionId: String, batchId: String): Int {
    return dpmScanEvidenceDao.bindSessionToBatch(scanSessionId, batchId)
}
```

### 3. WorkbenchViewModel：暂存和消费绑定信息

**文件**: [WorkbenchViewModel.kt](app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt)

添加：
```kotlin
// 数据类
private data class PendingDpmBatchBinding(
    val scanSessionId: String,
    val partId: String,
)

// 新字段
private val _pendingDpmBatchBinding = MutableStateFlow<PendingDpmBatchBinding?>(null)

// 设置方法（扫码成功后调用）
fun setPendingDpmBatchBinding(scanSessionId: String, partId: String) {
    _pendingDpmBatchBinding.value = PendingDpmBatchBinding(scanSessionId, partId)
    Log.i("DpmBinding", "setPendingDpmBatchBinding: sid=$scanSessionId, part=$partId")
}

// 消费方法（首次拍照创建批次后调用）
suspend fun applyPendingDpmBinding(batchId: String, repository: InspectionRepository): Boolean {
    val binding = _pendingDpmBatchBinding.value ?: return false
    if (binding.partId != repository.getCaptureBatch(batchId)?.partId) {
        Log.w("DpmBinding", "part mismatch: pending=${binding.partId}, batch=$batchId")
        return false
    }
    val updated = repository.bindDpmScanSessionToBatch(binding.scanSessionId, batchId)
    _pendingDpmBatchBinding.value = null
    Log.i("DpmBinding", "applyPendingDpmBinding: updated=$updated rows, batch=$batchId")
    return updated > 0
}
```

**不修改** `selectPart()` 清除 `activeCaptureBatchId` 的逻辑 — 旧批次在切换零件时理应清除。

### 4. DpmScanScreen：onResult 回调签名扩展

**文件**: [DpmScanScreen.kt](app/src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt)

将 `onResult: (String) -> Unit` 改为 `onResult: (String, String?) -> Unit`

在 LaunchedEffect(lastResult) 中传递 connectedSessionId：
```kotlin
onResult(result.rawValue, connectedSessionId)
```

### 5. AppNavigation：在 onResult 中设置待绑定信息

**文件**: [AppNavigation.kt](app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt)

修改 DpmScan composable 的 onResult 回调：
```kotlin
onResult = { code, sessionId ->
    scope.launch {
        val part = withContext(Dispatchers.IO) {
            repository.getPartByDpmCode(code)
        }
        if (part == null) {
            Toast.makeText(context, "未找到匹配零件", Toast.LENGTH_SHORT).show()
        } else {
            // 暂存扫码绑定信息（在 PartSelectionBus.emit 之前设置）
            if (sessionId != null) {
                viewModel.setPendingDpmBatchBinding(sessionId, part.id)
            }
            MobileInspectionApp.settings(context).selectedPartId = part.id
            PartSelectionBus.emit(part.id)
            navController.popBackStack(Screen.LiveInspection.route, false)
        }
    }
}
```

同步修改 DpmBind 路由的 onResult 回调（忽略 sessionId）：
```kotlin
onResult = { code, _ ->
    scope.launch { /* 现有逻辑不变 */ }
}
```

### 6. LiveInspectionScreen：首批拍照时消费待绑定信息

**文件**: [LiveInspectionScreen.kt](app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt)

在 CaptureMode 分支中，batchId 赋值后、`viewModel.setActiveCaptureBatchId(batchId)` 之前，添加：
```kotlin
// 绑定 DPM 扫码证据到新创建的批次
viewModel.applyPendingDpmBinding(batchId, repository)
```

这段代码在以下位置插入（约第243行后）：
```kotlin
} ?: run {
    val newBatchId = "batch_${System.currentTimeMillis()}_${part.name.take(4)}"
    repository.insertCaptureBatch(
        CaptureBatchEntity(
            batchId = newBatchId,
            partId = part.id,
            templateId = part.templateId,
            viewIndex = currentViewIndex,
            startTime = System.currentTimeMillis(),
            endTime = null,
        )
    )
    newBatchId
}
// >>> 插入位置：消费待绑定 DPM 扫码证据 <<<
viewModel.applyPendingDpmBinding(batchId, repository)
viewModel.setActiveCaptureBatchId(batchId)
```

### 7. 自动化测试

**文件**: [InspectionZipExportArchiveTest.kt](app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportArchiveTest.kt)

新增测试用例：验证先扫码后创建批次场景下 DPM 证据出现在 ZIP 中。

**文件**: [DpmScanEvidenceContractTest.kt](app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt)

新增源码契约测试：
- 验证 DAO 存在 `bindSessionToBatch` 方法
- 验证 Repository 存在 `bindDpmScanSessionToBatch` 方法
- 验证 WorkbenchViewModel 存在 `setPendingDpmBatchBinding` 和 `applyPendingDpmBinding`
- 验证 LiveInspectionScreen 调用 `applyPendingDpmBinding`
- 验证 DpmScanScreen onResult 签名包含 scanSessionId 参数

## 不修改的文件

- `DpmScanEvidenceEntity.kt` — 无需新字段
- `AppDatabase.kt` — 无需新 migration
- `Migrations.kt` — 无需新 migration
- `InspectionZipExportService.kt` — 保持严格 batchId 过滤
- `DpmEvidenceExportService.kt` — 不受影响
- DPM 解码算法、ECC 逻辑、CameraX 架构、NanoDet 相关代码

## 验证

1. `./gradlew test` 全部通过
2. 构建 release APK
3. 真机验证：扫码 → 切换零件 → 拍照 → 导出 ZIP → 确认 DPM 图片在 ZIP 中
