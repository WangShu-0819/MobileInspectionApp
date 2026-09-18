# DPM 原始证据清理报告

状态：**USER_ACCEPTED**（2026-09-18 v3.1；用户已确认真机扫码不卡顿且不再出现紫色加载圈）

## 目标

清理 App 私有目录 `filesDir/dpm_evidence` 下的原始证据文件和 `dpm_scan_evidence` 数据库行，释放存储空间。已导出的独立 ZIP 不受影响。

## v3 修复（人工验收反馈）

### v3.0

1. **DpmOperationGuard begin 同步化**：`DpmScanViewModel.startScan()` 中 guard 获取从 fire-and-forget 改为同步等待；guard 拒绝时清理已创建资源并中止，不设置 analyzer
2. **WorkbenchViewModel guard 同步化**：`setPendingDpmBatchBinding()` 同步获取 guard；guard 拒绝时清除 pending binding
3. **DPM 解码成功后不清理相机**：新增 `saveCurrentEvidence()`（仅保存证据，不停止扫描、不断开相机）；`LaunchedEffect(lastResult)` 改用此方法；页面导航后 `DpmScanExitEffect` 的 `onDispose` 负责清理，避免紫色 CircularProgressIndicator 闪现
4. **TraceRecordsScreen UI 精简**：统计行精简为一行（"N 条 · M 张（原图+ROI） · X MB"）；确认对话框精简为一句话；成功/失败消息精简

### v3.1

1. **OperationLease 令牌机制**：`DpmOperationGuard` 新增 `OperationLease` 数据类和 `acquireLease()` 方法；lease 的 `release()` 幂等（`AtomicBoolean`），防止异步错配导致的重复 end
2. **startScan 使用 lease**：`scanLease` 字段替代 `deferredGuardRelease`；startScan 获取 lease，stopScan 条件释放（有 pending save 时转移 lease 所有权给 saveEvidenceInScope），saveEvidenceInScope finally 块释放 lease
3. **WorkbenchViewModel 使用 lease**：`bindingLease` 字段；`setPendingDpmBatchBinding` 在 begin 成功后才发布 pending 状态（防止孤立 pending）；`releasePendingBinding` 释放 lease
4. **TraceRecordsScreen 导出使用 lease**：两处 `begin()/end()` 替换为 `acquireLease()/lease.release()`
5. **DpmScanScreen 退出清理使用 applicationScope**：`runDpmScanExit` 签名简化为 `cleanupAndDisconnect` 回调（不再直接操作 CameraController）；ViewModel 新增 `cleanupAndDisconnect()` 使用自有 `applicationScope`，不受 Compose 生命周期取消影响
6. **CameraPreview 诊断日志**：LaunchedEffect 记录 cameraState 非 OPEN 时的状态（cameraState/cameraMode/error/sessionId），辅助定位紫色圈根因
7. **DpmOperationGuard.resetForTesting()**：新增测试专用重置方法
8. **删除根目录未跟踪 plan.md**

## v2 改进（相比 v1）

1. **DpmOperationGuard**：`DpmScanViewModel` 静态门禁标志替换为应用级共享 `DpmOperationGuard` 对象，cleanup/scan/save/binding/export 共享同一并发门禁
2. **逐 evidenceId 清理**：从 `deleteAll()` 改为逐行处理，仅所有文件成功删除后才删 DB 行
3. **文件缺失幂等**：缺失文件视为成功，不阻塞其他证据清理
4. **孤立清理安全**：孤立文件清理基于剩余 DB 行（partial cleanup 后），不误删仍被引用的文件
5. **绑定 TTL**：`PendingDpmBatchBinding` 增加 5 分钟 TTL，超时自动清理并释放门禁
6. **DAO 精确查询**：新增 `getByEvidenceId()`、`deleteByEvidenceId()`、`getAllEvidenceIds()`
7. **测试扩展**：JVM 测试从 42 项扩展到 53 项

## 实现概述

### DpmOperationGuard（新增文件）

应用级 singleton `object DpmOperationGuard`，协调所有 DPM 操作的并发：

- `activeOperations: AtomicInteger` — 当前活跃操作计数
- `cleanupInProgress: AtomicBoolean` — cleanup exclusive 标志
- `gateMutex: Mutex` — 保护 counter 和 flag 的原子性
- `cleanupMutex: Mutex` — cleanup 互斥

| 方法 | 行为 |
|---|---|
| `begin()` | 检查 cleanupInProgress → 增加计数 |
| `end()` | 减少计数 |
| `cleanupExclusive()` | gateMutex 内检查 counter==0 + 设 cleanupInProgress=true → 获取 cleanupMutex → 返回 release 函数 |
| `isAnyActive` | counter > 0 或 cleanupInProgress |

### DAO 新增方法

`DpmScanEvidenceDao.kt` 新增 7 个方法：

| 方法 | SQL | 用途 |
|---|---|---|
| `getByEvidenceId(evidenceId)` | `SELECT * FROM dpm_scan_evidence WHERE id = :evidenceId` | 按 ID 精确查询 |
| `deleteByEvidenceId(evidenceId)` | `DELETE FROM dpm_scan_evidence WHERE id = :evidenceId` | 按 ID 精确删除 |
| `getAllEvidenceIds()` | `SELECT id FROM dpm_scan_evidence ORDER BY id ASC` | 获取全部 ID |
| `count()` | `SELECT COUNT(*) FROM dpm_scan_evidence` | 统计行数 |
| `getAllPathProjections()` | `SELECT id, originalImagePath, roiImagePath FROM dpm_scan_evidence` | 文件路径投影 |
| `deleteAll()` | `DELETE FROM dpm_scan_evidence` | 全部删除（保留但 cleanup 不使用） |
| `getAllForCleanup()` | `SELECT * FROM dpm_scan_evidence ORDER BY id ASC` | 清理快照 |

新增 `DpmEvidencePathProjection` 数据类。

### MobileImageStore 公开方法

- `listDpmEvidenceFiles(): List<File>` — 列出 `dpm_evidence/` 目录下所有实际文件，用于孤立文件检测
- `isDpmEvidencePath(path): Boolean` — canonical path 校验路径是否在受管理目录内

### Repository 清理功能

**数据类：**
- `DpmEvidenceStats` — 统计信息（行数、文件数、字节数、缺失文件、孤立文件/字节、越界文件数、共享路径数）
- `DpmCleanupResult` — 清理结果（成功/失败、DB 行数、文件数、缺失数、失败数、释放字节、失败路径）

**方法：**
- `getDpmEvidenceStats()` — 统计 DB 行数、实际文件数、总字节数、缺失文件、孤立文件、越界文件和共享路径
- `cleanupAllDpmEvidence()` — 委托 `DpmOperationGuard.cleanupExclusive()` + `cleanupAllDpmEvidenceInternal()`

**清理流程（v2 逐 evidenceId）：**
1. `DpmOperationGuard.cleanupExclusive()` 获取 exclusive 锁
2. 查询全部 evidenceId（`getAllEvidenceIds()`）
3. **逐 evidenceId 处理：**
   a. 查询完整记录（`getByEvidenceId()`）
   b. 路径验证（`isDpmEvidencePath()` canonical 校验）
   c. 删除所有关联文件（原图 + ROI）
   d. 文件缺失视为成功（幂等）
   e. 任何文件删除失败 → 保留 DB 行，记录失败
   f. 全部文件成功 → 删除 DB 行（`deleteByEvidenceId()`）
4. **孤立文件清理**（基于剩余 DB 行）：
   a. 查询当前 DB 所有路径（`getAllPathProjections()`）
   b. 构建引用路径集（canonical）
   c. 列出磁盘文件（`listDpmEvidenceFiles()`）
   d. 未被引用的文件 → 删除

### DpmScanViewModel 改动

- **移除**静态 `isDpmScanActive`、`isPendingDpmBinding`、`setPendingBindingActive()`
- `isDpmScanActive` 改为实例属性（仅 UI 状态）
- `startScan()` 通过 `scope.launch { DpmOperationGuard.begin(); setFrameAnalyzer(); collect results }` 同步获取门禁；guard 拒绝时清理资源并中止
- `stopScan()` → `releaseScanResources()`（清理 analyzer）+ 条件释放 `DpmOperationGuard.end()`
- `saveEvidenceInScope()` 使用 `guardHeld = true` 模式：独立 begin/end，finally 中释放
- `saveCurrentEvidence()`（v3 新增）：仅保存证据，不停止扫描、不断开相机

### WorkbenchViewModel 改动

- `PendingDpmBatchBinding` 增加 `createdAtMs: Long = System.currentTimeMillis()`
- 新增 `pendingBindingTtlMs = 300_000L`（5 分钟）
- `setPendingDpmBatchBinding()` → `DpmOperationGuard.begin()`
- `applyPendingDpmBinding()` → TTL 检查（超时则清理并释放门禁）
- 新增 `releasePendingBinding()` — 清空 flow + `DpmOperationGuard.end()`
- 新增 `onCleared()` — 释放待处理绑定

### TraceRecordsScreen 改动

- DPM 导出使用 `DpmOperationGuard.begin()/end()` 包裹
- 批量导出使用 `DpmOperationGuard.begin()/end()` 包裹
- `canClean` 检查 `!DpmOperationGuard.isAnyActive`

### UI 入口

`TraceRecordsScreen` 在 DPM 导出卡片内新增：
- 统计行（v3 精简）：`"原始证据: N 条 · M 张（原图+ROI） · X.X MB"`
- 清理按钮（红色，导出中/扫码中/绑定中禁用）
- 确认对话框（v3 精简）：`"将删除 App 内原始证据，已导出的 ZIP 不受影响。"`
- 清理结果消息（v3 精简）：`"已清理 N 条，释放 X.X MB"` / `"已清理 N 条，M 个文件删除失败"` / `"清理失败"`

## 安全边界

| 门禁 | 检查 | 行为 |
|---|---|---|
| cleanup exclusive | `DpmOperationGuard.cleanupExclusive()` | counter==0 时获取，否则挂起等待 |
| 扫码中 | `DpmOperationGuard.begin()` 设 counter>0 | cleanupExclusive 挂起等待 |
| 待绑定 | `DpmOperationGuard.begin()` 设 counter>0 | cleanupExclusive 挂起等待 |
| 导出中 | `DpmOperationGuard.begin()` 设 counter>0 | cleanupExclusive 挂起等待 |
| 路径越界 | `isDpmEvidencePath()` canonical 校验 | 跳过删除，保留 DB 行 |
| 文件缺失 | `File.exists()` | 视为成功（幂等） |
| 文件删除失败 | `File.delete()` 返回 false | 报告，保留 DB 行 |
| 绑定超时 | `createdAtMs + 5min` | 自动清理并释放门禁 |

## 测试

### JVM 测试（DpmEvidenceCleanupTest.kt）— 53 项

- 统计语义：hasData、displayTotalBytes、默认值、outOfBoundsFiles、sharedPathCount
- 清理结果语义：成功/失败/门禁阻塞
- 路径投影语义：字段和可空性
- DAO 新增方法源码契约：getByEvidenceId/deleteByEvidenceId/getAllEvidenceIds/count/getAllPathProjections/deleteAll/getAllForCleanup
- Repository 清理流程源码契约：DpmOperationGuard.cleanupExclusive、逐 evidenceId 处理、路径验证、文件缺失幂等、孤立清理基于剩余行
- MobileImageStore 源码契约：listDpmEvidenceFiles、isDpmEvidencePath
- TraceRecordsScreen UI 源码契约：统计行、清理按钮、DpmOperationGuard 阻塞、formatBytes、结果消息
- 独立 ZIP 不受影响：不操作 exported_packages 表、不操作 captures/roi_evidence 目录
- 原有 deleteDpmEvidenceSafely 保留
- DpmOperationGuard 反射验证：volatile 字段、suspend 方法、双 mutex

### Instrumented 测试（DpmEvidenceCleanupInstrumentedTest.kt）— 13 项

- 统计：空表、有文件、孤立文件、缺失文件、越界文件、共享路径
- 清理：空表、有文件、缺失文件幂等、孤立文件、带 ROI 清理
- 安全：路径越界保护、共享路径处理、guard 阻塞
- 正确性：逐 evidenceId 处理、孤立清理基于剩余行

## 验证命令与结果

```bash
# 编译
./gradlew :app:compileDebugKotlin --no-daemon           # BUILD SUCCESSFUL
./gradlew :app:compileDebugAndroidTestKotlin --no-daemon # BUILD SUCCESSFUL

# JVM 定向测试（53 项，全部通过）
./gradlew :app:testDebugUnitTest --no-daemon \
  --tests "com.wearable.inspection.mobile.dpm.*" \
  --tests "com.wearable.inspection.mobile.data.dao.DpmScanEvidencePersistenceContractTest" \
  --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" \
  --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest" \
  --tests "com.wearable.inspection.mobile.dpm.DpmEvidenceFrameTrackerTest" \
  --tests "com.wearable.inspection.mobile.dpm.DpmFrameAnalyzerEvidenceTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.SafZipExportTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModelAdvanceTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.LiveInspectionCaptureStateTest"

# Instrumented 测试（13 项，需要设备）
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.data.repository.DpmEvidenceCleanupInstrumentedTest

# APK 构建
./gradlew :app:assembleDebug --no-daemon
```

全量 JVM 测试：16 项既有预存失败（与本次修改无关），0 项新增失败。

### v3.1 测试结果

```
# 编译
./gradlew :app:compileDebugKotlin --no-daemon           # BUILD SUCCESSFUL
./gradlew :app:compileDebugAndroidTestKotlin --no-daemon # BUILD SUCCESSFUL

# DPM 定向 JVM 测试（全量通过）
./gradlew :app:testDebugUnitTest --no-daemon \
  --tests "com.wearable.inspection.mobile.dpm.*" \
  --tests "com.wearable.inspection.mobile.data.dao.DpmScanEvidencePersistenceContractTest" \
  --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" \
  --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest" \
  --tests "com.wearable.inspection.mobile.dpm.DpmEvidenceFrameTrackerTest" \
  --tests "com.wearable.inspection.mobile.dpm.DpmFrameAnalyzerEvidenceTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.SafZipExportTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.DpmScanExitFlowTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.LiveInspectionCaptureStateTest"
# BUILD SUCCESSFUL

# Instrumented 测试（20 项全部通过，设备 YAL-AL10）
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.data.repository.DpmEvidenceCleanupInstrumentedTest
# BUILD SUCCESSFUL

# APK 构建
./gradlew :app:assembleDebug --no-daemon                # BUILD SUCCESSFUL
```

用户真机验收：DPM 扫码连续操作不卡顿，未再出现紫色 CircularProgressIndicator；DPM 原始证据清理行为通过。

### APK 信息（v3.1 final）
- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-18 16:19:24
- 大小：232,800,070 bytes
- SHA-256：`00aa3e43074f9ebac522ebbfdc3b38777595f49c1fefd0217e222aa10e87e85f`

### v3.1 修改文件清单

| 文件 | 改动 |
|---|---|
| `DpmOperationGuard.kt` | 新增 `OperationLease` 数据类（幂等 release）、`acquireLease()`、`cleanupExclusive()` 增加 `cleanupInProgress` 快速拒绝、`resetForTesting()` |
| `DpmScanViewModel.kt` | `startScan` 使用 `acquireLease()`；`scanLease` 替代 `deferredGuardRelease`；stopScan 条件释放/转移 lease；saveEvidenceInScope finally 释放 lease；新增 `saveCurrentEvidence()`、`cleanupAndDisconnect()` |
| `WorkbenchViewModel.kt` | `setPendingDpmBatchBinding` 使用 `acquireLease()`，begin 成功后才发布 pending；`bindingLease` 字段；`releasePendingBinding` 释放 lease |
| `DpmScanScreen.kt` | `LaunchedEffect(lastResult)` 改用 `saveCurrentEvidence()`；`runDpmScanExit` 简化为 `cleanupAndDisconnect` 回调；退出清理使用 ViewModel 的 applicationScope |
| `TraceRecordsScreen.kt` | DPM 统计行精简；确认对话框精简；结果消息精简；导出使用 `acquireLease()/lease.release()` |
| `CameraPreview.kt` | 新增诊断日志：cameraState 非 OPEN 时记录 state/mode/error/sessionId |
| `DpmEvidenceCleanupTest.kt` | UI 契约断言更新；guard 契约更新为 acquireLease |
| `DpmScanEvidenceContractTest.kt` | `runDpmScanExit` 签名更新为 `cleanupAndDisconnect`；测试更新 |
| `DpmScanExitFlowTest.kt` | 签名更新为 `cleanupAndDisconnect`；新增 frames 场景测试 |
| `WorkbenchViewModelAdvanceTest.kt` | 新增 guard 拒绝测试；advanceUntilIdle 同步；tearDown guard reset |
| `DpmEvidenceCleanupInstrumentedTest.kt` | `runBlocking` 包装为 `runTest`（修复 JUnit void 返回类型） |
| `DpmScanExitFlowTest.kt` | 增加 `cleanupScope`；新增 frames 场景测试 |
| `WorkbenchViewModelAdvanceTest.kt` | 新增 guard 拒绝测试；tearDown 增加 guard reset |

## 前序功能回归

| 功能 | 验证方式 | 状态 |
|---|---|---|
| DPM 扫码 | JVM 定向测试 | ✅ 通过 |
| 证据落库 | JVM 定向测试 | ✅ 通过 |
| 独立 ZIP 导出 | JVM 定向测试 | ✅ 通过 |
| 批次 ZIP | JVM 定向测试 | ✅ 通过 |
| 批次清理 | JVM 定向测试 | ✅ 通过 |
| CameraX | 未改动 | ✅ 不受影响 |
| DpmEvidenceFrameTracker | JVM 定向测试 | ✅ 通过 |
| WorkbenchViewModel 绑定 | JVM 定向测试 | ✅ 通过 |

## Git 状态

本次 DPM 收口已由主协调选择性提交：`67cc68a6`；现场采集标题字号/OCR 入口的独立 UI 改动不纳入本次提交。

**源码：**
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmOperationGuard.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt`

**测试：**
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmEvidenceCleanupTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/DpmScanExitFlowTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModelAdvanceTest.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/data/repository/DpmEvidenceCleanupInstrumentedTest.kt`

**文档：**
- `tasks/todo.md`
- `tasks/plan.md`
- `docs/reports/b3/DPM_EVIDENCE_CLEANUP_REPORT.md`

## 未完成项

- 本次 DPM 功能和 v3.1 真机验收已完成，收口提交为 `67cc68a6`。
- 现场采集标题字号和 OCR 入口隐藏属于独立 UI 改动，不纳入本次 DPM 收口。
