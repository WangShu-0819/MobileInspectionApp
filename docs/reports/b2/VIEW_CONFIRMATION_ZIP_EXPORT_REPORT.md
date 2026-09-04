# 单零件多 View 人工确认 + ZIP 导出

**日期**：2026-09-04
**状态**：IN_PROGRESS（2026-09-04，人工验收多 View、导航和布局整改完成，自动化/真机验收按范围暂停）

## 1. 业务流程

```
选择零件 → 按模板顺序逐 View 拍照
  → 保存原始照片 → 插入并回读 CapturedPhotoEntity（真实 photoId）
  → 只按当前 templateId 查询 enabled ROI
    ├─ ROI 非空 → ViewConfirmationScreen
    │             → ROI OK/NG + 总体 OK/NG → 确认记录落库并校验
    │             → 同一个 WorkbenchViewModel 按 viewIndex 推进一次
    └─ ROI 为空 → 不进入确认页、不生成确认行
                  → 同一个 WorkbenchViewModel 按 viewIndex 直接推进一次
  → 非最后 View：回到现场采集页继续拍照
  → 最后 View：更新批次 endTime → ExportResultScreen
    → ZIP 按 View 分目录包含该 batchId 全部原始照片 + 一个综合 inspection_result.csv；无 ROI 时仅有照片索引行
```

## 2. 实际修改文件

### 新增文件（12 个）

| 文件 | 职责 |
|------|------|
| `data/entity/ViewRoiConfirmEntity.kt` | 逐 ROI 人工确认 Room 实体 |
| `data/dao/ViewRoiConfirmDao.kt` | DAO：Flow 观察、按 View 删除、已确认 View 索引查询 |
| `data/export/InspectionExcelExporter.kt` | 15 列 CSV 生成器（UTF-8 BOM，Excel 兼容） |
| `data/export/InspectionZipExportService.kt` | ZIP 打包服务（按 View 分目录照片 + 综合检测 CSV） |
| `ui/screens/RoiCoordinateMapper.kt` | normalizedRect → contentRect → imagePixels 坐标映射 |
| `ui/screens/ViewConfirmationViewModel.kt` | 确认页 ViewModel（裁剪、选择、保存） |
| `ui/screens/ViewConfirmationScreen.kt` | 确认页 Compose UI（ROI 列表 + OK/NG 选择） |
| `ui/screens/ExportResultScreen.kt` | 导出结果页（统计 + 下载 + 分享） |
| `ui/screens/ContentRectBounds.kt` | 纯 Kotlin 坐标数据类（替代 android.graphics.Rect） |

### 测试文件（5 个）

| 文件 | 测试数 |
|------|--------|
| `RoiCoordinateMapperTest.kt` | 16 项 |
| `ViewRoiConfirmEntityTest.kt` | 16 项 |
| `InspectionExcelExporterTest.kt` | 15 项 |
| `InspectionZipExportServiceTest.kt` | 6 项 |
| `ViewConfirmationFlowTest.kt` | 11 项 |

### 修改文件（6 个）

| 文件 | 变更 |
|------|------|
| `data/db/AppDatabase.kt` | 版本 5→6，新增 ViewRoiConfirmEntity 和 DAO |
| `data/db/Migrations.kt` | MIGRATION_5_6（view_roi_confirms 表，18 列） |
| `data/repository/InspectionRepository.kt` | 新增 6 个方法（observe/get/insert/delete） |
| `MobileInspectionApp.kt` | repository 构造参数新增 viewRoiConfirmDao |
| `ui/navigation/Screen.kt` | ViewConfirmation（8 参数）+ ExportResult（3 参数）路由 |
| `ui/navigation/AppNavigation.kt` | 注册 ViewConfirmation 和 ExportResult composable |
| `ui/screens/LiveInspectionScreen.kt` | 拍照后导航确认页 + 显式 View 推进 + 稳定采集布局 |

## 3. 数据库 Migration（v5 → v6）

```sql
CREATE TABLE IF NOT EXISTS `view_roi_confirms` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `batchId` TEXT NOT NULL,
    `photoId` INTEGER NOT NULL,
    `photoPath` TEXT NOT NULL,
    `viewIndex` INTEGER NOT NULL,
    `templateId` TEXT NOT NULL,
    `templateName` TEXT NOT NULL,
    `roiId` TEXT NOT NULL,
    `roiName` TEXT NOT NULL,
    `roiTargetType` TEXT,
    `roiNormalizedRect` TEXT NOT NULL,
    `roiPixelRect` TEXT NOT NULL,
    `softwareResult` TEXT,
    `humanResult` TEXT NOT NULL,
    `confirmTime` INTEGER NOT NULL,
    `overallResult` TEXT NOT NULL,
    `overallConfirmTime` INTEGER NOT NULL,
    FOREIGN KEY(`batchId`) REFERENCES `capture_batches`(`batchId`) ON DELETE CASCADE
)
```

- 外键指向 `capture_batches.batchId`，CASCADE 删除
- `humanResult`：`"OK"` 或 `"NG"`
- `overallResult`：`"OK"` 或 `"NG"`
- `roiPixelRect`：格式 `{"left":100,"top":200,"right":500,"bottom":600}`

## 4. 坐标映射

```
normalizedRect (JSON 0-1)
  → RoiCoordinateMapper.parseNormalizedRect → NormalizedRect
  → mapToImagePixels(contentRect, imageWidth, imageHeight) → ContentRectBounds
  → cropRoiBitmap(photoPath, bounds) → Bitmap
```

- `contentRect`：相机预览实际内容区域（FIT_CENTER letterbox 后的坐标）
- `normalizedRect`：模板配置时相对于 contentRect 的归一化坐标（0-1）
- `imagePixels`：实拍照片上的绝对像素坐标
- ContentRectBounds 为纯 Kotlin 数据类（非 android.graphics.Rect），支持 JVM 单元测试

## 5. UI 流程

### ViewConfirmationScreen
- 顶部：零件名称 + View 进度（如 "2/5"）
- 中部（可滚动）：ROI 列表
  - 左侧：ROI 裁剪图
  - 右侧：OK/NG Chip + roiTargetType 标签 + ROI 名称
- 底部固定：总体 OK/NG 选择 + "确认并继续" 按钮
- 按钮禁用直到所有 ROI + 总体均选择
- 正常流程只由有 ROI 的 View 进入；无 ROI View 不打开空确认页，也不要求总体结果。

### ExportResultScreen
- 自动 ZIP 生成（LaunchedEffect）
- 统计卡片：零件名、照片数、CSV 行数、批次 ID
- 下载按钮：SAF `ACTION_CREATE_DOCUMENT`
- 分享按钮：`Intent.ACTION_SEND` + FileProvider URI

## 6. ZIP 文件结构

```
inspection_{partId}_{batchId前8位}_{yyyyMMdd_HHmmss}.zip
├── captures/
│   ├── {batchId}_v0_1725432000000.jpg
│   ├── {batchId}_v1_1725432060000.jpg
│   └── ...
└── inspection_{partId}_{batchId前8位}_{yyyyMMdd_HHmmss}.csv
```

## 7. CSV 15 列

| # | 列名 | 示例 |
|---|------|------|
| 1 | 图片名称 | captures/batch_12345_v0_1725432000000.jpg |
| 2 | 视角索引 | 0 |
| 3 | 模板ID | tpl_001 |
| 4 | 模板名称 | 视角1-正面 |
| 5 | ROI ID | roi_a1 |
| 6 | ROI 名称 | 螺纹区域 |
| 7 | ROI 目标类型 | THREAD |
| 8 | ROI 像素坐标 | {"left":100,"top":200,"right":500,"bottom":600} |
| 9 | 软件检测结果 | （空） |
| 10 | 人工确认结果 | OK |
| 11 | 确认时间 | 2026-09-04 15:30:00 |
| 12 | 总体判定 | OK |
| 13 | 总体确认时间 | 2026-09-04 15:30:05 |
| 14 | 零件ID | part_001 |
| 15 | 批次ID | batch_12345678 |

- UTF-8 BOM 编码（`﻿`），Excel 直接打开不乱码
- NG 结果始终保留，不丢弃

## 8. 测试命令及结果

本轮按用户执行限制未运行 Gradle、ADB、APK 或真机测试，状态为 `NOT_RUN_BY_SCOPE`。以下是修改前已记录的基线，不能替代本轮回归：

**修改前基线**：550 项（545 passed / 0 failed / 5 skipped）。

本轮仅完成源码审计、源码级测试契约补充和文档更新；待解除执行限制后，至少重新运行 `:app:testDebugUnitTest` 并补充 Room/导出真实 ZIP 回归。

原始 64 项测试：
- `RoiCoordinateMapperTest`: 16 passed
- `ViewRoiConfirmEntityTest`: 16 passed
- `InspectionExcelExporterTest`: 15 passed
- `InspectionZipExportServiceTest`: 6 passed
- `ViewConfirmationFlowTest`: 11 passed

Bug Fix 新增 23 项测试：
- `LiveInspectionCaptureStateTest`: 11 passed
- `WorkbenchViewModelAdvanceTest`: 5 passed
- `ViewConfirmationNavigationTest`: 7 passed

## 9. 前序软件基线

- 前序人工确认、坐标映射、CSV/ZIP 结构和界面压缩回归基线：550 项（545 passed / 0 failed / 5 skipped）。
- 本轮保留原有 CameraPreview、模板参考图、透明度控制栏、拍照按钮、ROI 坐标映射、确认选择和导出结构；仅调整照片关联、分支推进和空 ROI 导出门禁。
- 当前确认完成由 AppNavigation 使用根级共享 WorkbenchViewModel 的 `completeView(viewIndex)` 处理；本轮真机验收仍待执行。

## 10. 无 ROI View 直接推进修复（2026-09-04）

### 本轮实际逻辑

| 当前 View ROI 查询结果 | 拍照成功后的唯一流程 |
|---|---|
| `repository.getRois(capturedTemplateId).filter { it.enabled }` 非空 | 保存原始照片 → 插入并回读真实 photoId → 进入 ViewConfirmationScreen → 确认记录保存并回读校验 → AppNavigation 使用同一个 WorkbenchViewModel 按 `viewIndex` 推进一次 |
| 查询结果为空 | 保存原始照片 → 插入并回读真实 photoId → 不进入 ViewConfirmationScreen、不写 ROI/人工结果 → 按 `viewIndex` 直接推进一次 |

无 ROI 的中间 View 推进到下一个现场采集页，下一 View 的模板和序号由 WorkbenchViewModel 状态实际变化驱动；最后一个无 ROI View 调用 `finishCaptureBatch(batchId)` 更新 `endTime` 后进入 `ExportResultScreen`。失败或未完成拍照时不会调用完成推进。

### 照片关联和导出

- `CapturedPhotoDao.insert()` 与 `InspectionRepository.insertCapturedPhoto()` 返回 Room 实际生成的 `Long photoId`。
- 拍照流程按 `photoId` 回读，并校验 `batchId`、`viewIndex`、`templateId`、文件路径后才进行 ROI 分支。
- `ViewConfirmationViewModel` 进入确认页前再次校验照片属于传入 batch/View/template；确认落库后回读当前 View 的确认行。
- `InspectionZipExportService` 先要求批次有照片，确认行允许为空；因此全无 ROI 批次也会生成 ZIP，综合 CSV 会写入照片索引行，照片位于 `views/view_XX/`。
- 无 ROI View 不生成 `ViewRoiConfirmEntity`，不伪造 softwareResult、ROI OK/NG 或总体 OK/NG。

### 本轮实际修改文件

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/CapturedPhotoDao.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModelAdvanceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/CapturedPhotoPersistenceContractTest.kt`

## 11. 未实现的能力（同前）

- 自动 Thread/Nut/Feature 检测（当前 softwareResult 始终为 null）
- PASS/FAIL 自动判定
- Homography / 自动对齐
- 自动轮廓提取
- Session ROI 编辑

## 12. 真机测试状态

`NOT_RUN_BY_SCOPE`（本轮禁止 adb）。需真机验证：
1. 拍照后确认页 ROI 裁剪图正确显示
2. OK/NG 选择和保存
3. 多 View 自动推进
4. ZIP 生成、下载和分享
5. CSV 内容在 Excel 中正确打开
6. 拍照成功提示文案正确（Bug Fix A）
7. 确认后自动推进到下一 View（Bug Fix B）
8. 无 ROI View 直接推进（不显示确认页）
9. 有 ROI View 走正常确认流程（无回归）
10. 无 ROI 最后 View 直接进入导出页

## 13. Git 状态

`NOT_COMMITTED`（本轮不提交）。所有变更在 working tree 中，等待用户验收后提交。

## 14. 无 ROI View 修复审计与补充测试（2026-09-04）

### 审计结论

该节记录无 ROI View 推进逻辑的阶段性审计；后续人工验收发现的拍摄复位和布局问题已在第 18 节完成整改。

### 源码审计详情

| 文件 | 审计项 | 结果 |
|---|---|---|
| `LiveInspectionScreen.kt` | 无 ROI 路径调用 completeView 直接推进 | ✅ 正确 |
| `LiveInspectionScreen.kt` | 有 ROI 路径导航 ViewConfirmationScreen | ✅ 正确 |
| `LiveInspectionScreen.kt` | 拍照失败/保存失败不调用 completeView | ✅ 正确 |
| `LiveInspectionScreen.kt` | 照片插入后回读 photoId 并校验 batchId/viewIndex/templateId | ✅ 正确 |
| `LiveInspectionScreen.kt` | 只按 capturedTemplateId 查询 enabled ROI | ✅ 正确 |
| `LiveInspectionScreen.kt` | 已移除 DisposableEffect/LifecycleEventObserver/pendingAdvance | ✅ 正确 |
| `LiveInspectionScreen.kt` | COMPLETED 路径先 finishCaptureBatch 再 onNavigateToExport | ✅ 正确 |
| `WorkbenchViewModel.kt` | completeView 幂等：stale/duplicate 返回 IGNORED | ✅ 正确 |
| `WorkbenchViewModel.kt` | ADVANCED/COMPLETED/IGNORED 三态逻辑正确 | ✅ 正确 |
| `AppNavigation.kt` | 根级共享 WorkbenchViewModel 传入 LiveInspection | ✅ 正确 |
| `AppNavigation.kt` | onConfirmed 使用 workbenchViewModel.completeView(viewIndex) | ✅ 正确 |
| `AppNavigation.kt` | onBack 只 popBackStack，不调用 completeView | ✅ 正确 |
| `AppNavigation.kt` | onNavigateToExport 导航 ExportResult 路由 | ✅ 正确 |
| `ViewConfirmationScreen.kt` | completionHandled 一次性消费 saveCompleted 事件 | ✅ 正确 |
| `ViewConfirmationViewModel.kt` | 校验 photo batchId/viewIndex/templateId 后才加载 ROI | ✅ 正确 |
| `ViewConfirmationViewModel.kt` | rois.isEmpty() 拒绝空确认，不生成虚假结果 | ✅ 正确 |
| `CapturedPhotoDao.kt` | insert 返回 Long，getById 支持回读 | ✅ 正确 |
| `InspectionRepository.kt` | insertCapturedPhoto 返回 Room 真实 photoId | ✅ 正确 |
| `InspectionRepository.kt` | finishCaptureBatch 仅当 endTime==null 时更新 | ✅ 正确 |
| `InspectionZipExportService.kt` | 照片为空才失败，确认行允许为空；照片按 View 分目录并合并写入 inspection_result.csv | ✅ 正确 |

### 有 ROI View 处理逻辑

1. 拍照 → 保存原始照片到 DCIM → `imageStore.storeCapturedImage()` 返回 `finalPath`
2. 构造 `CapturedPhotoEntity(batchId, filePath, capturedViewIndex, capturedTemplateId, templateName, capturedAt)`
3. `repository.insertCapturedPhoto()` 返回 Room 真实 `photoId`
4. `repository.getCapturedPhoto(photoId)` 回读并校验 photoId/batchId/viewIndex/templateId/filePath
5. `repository.getRois(capturedTemplateId).filter { it.enabled }` 查询当前 View 的 ROI
6. ROI 非空 → `captureState = SAVED` → `onNavigateToConfirm(batchId, photoId, filePath, viewIndex, templateId, templateName, partId, totalViews)`
7. AppNavigation 接收参数，创建 `ViewConfirmationViewModel`，显示 `ViewConfirmationScreen`
8. 用户逐 ROI 选择 OK/NG + 选择总体 OK/NG → `viewModel.saveConfirmation()`
9. 保存 `ViewRoiConfirmEntity` 列表并回读校验 → `saveCompleted = true`
10. `LaunchedEffect(saveCompleted)` 触发 `onConfirmed()`（`completionHandled` 保证只消费一次）
11. AppNavigation `onConfirmed` 回调：`workbenchViewModel.completeView(viewIndex)`
    - 非最后 View → `ADVANCED` → `navController.popBackStack()` 返回采集页
    - 最后 View → `COMPLETED` → `finishCaptureBatch(batchId)` → 导航 ExportResultScreen

### 无 ROI View 处理逻辑

1. 拍照 → 保存原始照片 → 插入并回读 photoId → 校验关联（同有 ROI 步骤 1-4）
2. `repository.getRois(capturedTemplateId).filter { it.enabled }` 查询结果为空
3. `viewModel.completeView(capturedViewIndex)` 直接调用：
   - `ADVANCED`：`captureState = IDLE`，WorkbenchViewModel 自动切换下一模板和 viewIndex
   - `COMPLETED`：`repository.finishCaptureBatch(batchId)` 更新 `endTime` → `onNavigateToExport(batchId, part.id, part.name)`
   - `IGNORED`：显示错误"当前视角已变化，照片已保存但未推进"
4. **不**进入 ViewConfirmationScreen、**不**生成 ViewRoiConfirmEntity、**不**伪造 softwareResult/PASS/FAIL

### 最后一个无 ROI View 处理逻辑

1. 同无 ROI View 步骤 1-2
2. `viewModel.completeView(capturedViewIndex)` 返回 `COMPLETED`
3. `repository.finishCaptureBatch(batchId)` — 若 `endTime` 已非 null 则不重复更新
4. `captureState = CaptureUiState.IDLE`
5. `onNavigateToExport(batchId, part.id, part.name)` → AppNavigation 导航到 `Screen.ExportResult.createRoute(batchId, partId, partName)`

### 本轮新增测试

新增 `NoRoiViewAdvancementTest.kt`（20 项），覆盖：

| 测试 | 覆盖场景 |
|---|---|
| `consecutive no-ROI views advance 0 then 1 then 2 in order` | 场景 6：连续无 ROI |
| `middle no-ROI view is not skipped and next view loads correctly` | 场景 7：中间无 ROI |
| `stale view index after middle no-ROI advance is correctly ignored` | 场景 9：幂等性 |
| `last no-ROI view completion returns COMPLETED` | 场景 8：最后无 ROI |
| `live inspection source prevents view completion on capture failure` | 场景 4：拍照失败 |
| `live inspection source prevents view completion when photo insert fails` | 场景 4：保存失败 |
| `photo insert and validation happen before ROI branch` | 场景 5：照片保存 |
| `photo entity uses captured view index and template id from snapshot` | 场景 11：一致性 |
| `zip export service iterates all photos regardless of confirms` | 场景 12：ZIP 全照片 |
| `zip keeps all photos including views without roi confirms` | 场景 12：ZIP 全照片 |
| `no-ROI path in live inspection does not create ViewRoiConfirmEntity` | 场景 13：无虚假 ROI |
| `view confirmation ViewModel rejects empty ROI list` | 场景 13：拒绝空确认 |
| `CSV with zero confirms produces header-only output without fake rows` | 场景 13：CSV 无虚假 |
| `softwareResult in entity defaults to null not PASS or FAIL` | 场景 13：null 软件结果 |
| `live inspection screen does not use lifecycle observer for advancement` | 结构检查 |
| `live inspection uses explicit completeView method` | 结构检查 |
| `view completion result enum has all three states` | 结构检查 |
| `batch endTime is updated before export navigation for last view` | 场景 8：时序 |

### 14 项测试要求覆盖矩阵

| # | 要求 | 测试文件 | 状态 |
|---|---|---|---|
| 1 | 有 ROI 进入确认页 | ViewConfirmationNavigationTest | ✅ |
| 2 | 无 ROI 不进入确认页 | LiveInspectionCaptureStateTest + CapturedPhotoPersistenceContractTest | ✅ |
| 3 | 无 ROI 自动进入下一 View | LiveInspectionCaptureStateTest | ✅ |
| 4 | 无 ROI 拍照失败不推进 | NoRoiViewAdvancementTest | ✅ |
| 5 | 无 ROI 照片真实保存到 batchId | CapturedPhotoPersistenceContractTest + NoRoiViewAdvancementTest | ✅ |
| 6 | 连续多个无 ROI View 按顺序拍摄 | NoRoiViewAdvancementTest | ✅ |
| 7 | 无 ROI 中间 View 不被跳过 | NoRoiViewAdvancementTest | ✅ |
| 8 | 最后无 ROI 进入 ExportResultScreen | LiveInspectionCaptureStateTest + NoRoiViewAdvancementTest | ✅ |
| 9 | 有 ROI 确认完成只推进一次 | WorkbenchViewModelAdvanceTest | ✅ |
| 10 | 返回/取消确认不推进 | ViewConfirmationNavigationTest | ✅ |
| 11 | viewIndex/templateId 一致 | LiveInspectionCaptureStateTest | ✅ |
| 12 | ZIP 包含所有 View 原始照片 | InspectionZipExportServiceTest + NoRoiViewAdvancementTest | ✅ |
| 13 | 无 ROI 不生成虚假 ROI/PASS/FAIL | NoRoiViewAdvancementTest + ViewConfirmationFlowTest | ✅ |
| 14 | 前序功能不回归 | CapturedPhotoPersistenceContractTest + InspectionZipExportServiceTest | ✅ |

### SAVED 文案区分修复（本轮补充）

上一轮实现中，无 ROI 路径直接将 `captureState` 设为 `IDLE`，用户看不到任何保存成功提示。本轮修改：

1. 无 ROI 路径先设 `captureState = SAVED` 显示短暂提示，再调用 `completeView`
2. 新增 `captureSavedMessage` 状态变量，区分两种文案：
   - 有 ROI：`"照片已保存，进入人工确认"`
   - 无 ROI：`"照片已保存，进入下一视角"`
3. `captureSavedMessage` 传入 `TemplateReferenceSection` 的 `captureSavedMessage` 参数
4. `onResetCapture` 重置 `captureSavedMessage` 为默认值

修改文件：`LiveInspectionScreen.kt`（新增 `captureSavedMessage` 状态、两处赋值、参数传递、重置）

### 测试状态

- 自动化测试：**未执行**，原因是本轮执行限制（用户明确禁止 Gradle）
- 预期新增 20 项测试（`NoRoiViewAdvancementTest`）+ 本轮补充 4 项（LiveInspectionCaptureStateTest）+ 1 项（ViewConfirmationFlowTest），历史基线 550 项，预期总计 575 项
- APK：未构建（`NOT_RUN_BY_SCOPE`）
- 真机：未执行（`NOT_RUN_BY_SCOPE`）

### 未完成的真机验收项

1. 有 ROI View 拍照后确认页 ROI 裁剪图正确显示
2. OK/NG 选择和保存真实落库
3. 多 View 自动推进（有 ROI → 无 ROI → 有 ROI 混合）
4. 连续无 ROI View 按顺序拍摄，不跳过中间 View
5. 无 ROI 最后 View 直接进入导出页
6. ZIP 生成包含所有 View 原始照片（含无 ROI View）
7. CSV 在 Excel 中正确打开（UTF-8 BOM）
8. 返回/取消确认页不推进 View
9. 确认完成后只推进一次（不重复推进）
10. 拍照失败时不保存不推进

## 15. 最终 Git 状态

`NOT_COMMITTED`。所有变更在 working tree 中，等待用户验收后提交。

工作区脏改动文件：
- 源码 8 个（同上一轮）
- 测试 6 个（含本轮新增 `NoRoiViewAdvancementTest.kt`）
- 文档 3 个（`tasks/todo.md`、`docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`、`AGENTS.md`）
- 其他脏改动来自 B3 离线工具和 B1/B2 历史任务，与本轮无关

---

## 16. 采集批次筛选、删除交互与布局稳定性优化（2026-09-04）

状态：**IN_PROGRESS**（源码和测试已写入，因执行限制未运行 Gradle/ADB）。

> 注：本节记录最初的单选版本；当前多选与批量删除实现以第 20 节为准。

### 16.1 时间筛选实现

**筛选规则**：

| 选项 | label | `sinceMillis()` 逻辑 |
|------|-------|---------------------|
| `TODAY` | 今日 | 本地当日 00:00:00.000 |
| `LAST_3_DAYS` | 近 3 天 | 本地当日 00:00 减 2 天（含今天共 3 个自然日） |
| `LAST_7_DAYS` | 近 7 天 | 本地当日 00:00 减 6 天（含今天共 7 个自然日） |
| `ALL` | 所有 | 返回 `null`，不设时间下限 |

**默认筛选值**：`LAST_7_DAYS`（近 7 天）。

**数据库查询**：
- `CaptureBatchDao.observeByStartTimeSince(sinceMillis: Long)` — `WHERE startTime >= :sinceMillis ORDER BY startTime DESC`
- `InspectionRepository.observeCaptureBatchesSince(sinceMillis: Long)` — 透传 DAO
- "所有"使用现有 `observeCaptureBatches()`（无 WHERE 子句）

**筛选行为**：
- 基于 `CaptureBatchEntity.startTime` 字段筛选，不按 ZIP 文件名、文件修改时间或列表位置
- 空时间（`startTime=0` 或极小值）的历史记录在今日/近 3 天/近 7 天中不显示，仅在"所有"中显示
- 筛选只影响列表展示，不删除、修改或重新生成批次数据
- 切换筛选后按 `startTime` 倒序排列

### 16.2 标题栏布局稳定性

**结构**：
```
Row(fillMaxWidth) {
    Text("采集批次")          // 左侧固定
    Spacer(weight=1f)         // 弹性空间
    Box { FilterDropdown }    // 固定槽位（紧凑 padding）
    Spacer(width=4dp)
    IconButton(Delete)        // 固定槽位（始终占位）
}
```

- 筛选器使用 `DropdownMenu`，当前选项始终显示在标题栏
- 垃圾桶 `IconButton` 始终存在（`enabled` 控制，不使用 `visible`/`gone`），避免布局跳动
- 未选中时垃圾桶灰色禁用，选中后变为 `FailColor` 启用
- 不插入"已选择 1 个"新行，选中状态仅通过卡片高亮 + 垃圾桶状态表达
- 卡片选中通过 `border(2.dp, Primary)` + `BackgroundVariant1` 背景实现，不改变卡片实际尺寸

### 16.3 批次选择与删除

**选中绑定**：点击卡片 → `selectedBatchId = batch.batchId`（toggle），绑定稳定 `batchId`。

**筛选切换清除**：`LaunchedEffect(activeFilter) { selectedBatchId = null }`。

**删除确认框**：
- 零件名称：`batch.partName`
- 采集时间：`SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(batch.startTime))`
- 视角数：`batch.viewCount`
- 批次 ID：`batch.batchId.take(8)…`
- 删除内容说明："将删除该批次的所有照片和确认记录，此操作无法恢复。"

**删除执行**：
- 只作用于 `selectedBatchId`，不按列表位置或名称
- 导出进行中通过 `SnackbarHostState` 提示（不使用列表内 Text）
- 成功：清除选中 → `snackbarHostState.showSnackbar("已删除采集批次")`
- 失败：保留选中 → `snackbarHostState.showSnackbar("删除失败：...")`
- 删除期间 `deletingBatch=true` 禁用垃圾桶按钮

### 16.4 Snackbar 浮层避免列表跳动

**改动**：将原来列表内 `item { Text(deleteMessage) }` 替换为 Scaffold 级 `SnackbarHost`。

- Snackbar 由 `SnackbarHostState` 管理，悬浮于 LazyColumn 之上
- 出现和消失不改变 LazyColumn 布局高度
- 不遮挡垃圾桶和筛选器（Snackbar 默认底部显示）
- 成功提示绿色背景，失败提示红色背景
- 不新增永久性成功状态卡片

### 16.5 空状态

| 筛选条件 | 空状态文案 |
|---------|-----------|
| 今日 | 今日暂无采集批次 |
| 近 3 天 | 近 3 天暂无采集批次 |
| 近 7 天 | 近 7 天暂无采集批次 |
| 所有 | 暂无采集批次 |

非"所有"筛选为空时，显示"查看所有"TextButton 切换到 `ALL`。

### 16.6 实际修改文件

**源码文件（3 个）**：

| 文件 | 变更 |
|------|------|
| `TraceRecordsScreen.kt` | 重写：`BatchTimeFilter` 枚举、`DropdownMenu` 筛选器、稳定布局 Row、`SnackbarHost`、`BatchEmptyState`、`LaunchedEffect` 清除选中 |
| `CaptureBatchDao.kt` | 新增 `observeByStartTimeSince(sinceMillis)` |
| `InspectionRepository.kt` | 新增 `observeCaptureBatchesSince(sinceMillis)` |

**测试文件（1 个新增）**：

| 文件 | 测试数 |
|------|--------|
| `BatchFilterAndDeleteTest.kt` | 35 项（时间筛选逻辑、选中绑定、删除状态、空状态、布局稳定性概念） |

### 16.7 测试覆盖（18 项要求对照）

| # | 要求 | JVM 测试 | instrumented |
|---|------|---------|-------------|
| 1 | 今日筛选按本地日期 | `today filter sinceMillis is local midnight` ✅ | 待补充 |
| 2 | 近 3 天包含三个自然日 | `last 3 days includes today/2 days ago/excludes 3 days ago` ✅ | 待补充 |
| 3 | 近 7 天包含七个自然日 | `last 7 days includes 6 days ago/excludes 7 days ago` ✅ | 待补充 |
| 4 | 所有筛选包含全部 | `all filter sinceMillis is null` ✅ | 待补充 |
| 5 | 按采集时间倒序 | `batches sort descending` ✅ | DAO ORDER BY |
| 6 | 空时间只在所有显示 | `batch with startTime 0 is below all filters` ✅ | 待补充 |
| 7 | 筛选不修改数据库 | `sinceMillis is pure computation` ✅ | DAO 只读 |
| 8 | 选中绑定 batchId | `selected batchId matches exact entity` ✅ | 待补充 |
| 9 | 切换筛选清除选中 | `selection clear on filter change concept` ✅ | 待补充 |
| 10 | 删除只删当前 batchId | `delete target determined by selectedBatchId only` ✅ | 待补充 |
| 11 | 其他零件批次不受影响 | `removing one batch preserves others` ✅ | 待补充 |
| 12 | 删除成功刷新+清除 | `after successful delete selection null` ✅ | 待补充 |
| 13 | 删除失败保留选中 | `after failed delete selection preserved` ✅ | 待补充 |
| 14 | 提示不插入列表 | `snackbar separate from batch list` ✅ | Snackbar 架构 |
| 15 | 未选中禁用删除 | `delete button disabled when no selection` ✅ | 待补充 |
| 16 | 选中不改标题栏高度 | 代码结构保证（固定 Row） | 待 instrumented |
| 17 | 筛选器垃圾桶不遮挡 | 代码结构保证（Spacer + 固定槽位） | 待 instrumented |
| 18 | 前序不回归 | 现有 22 项删除测试 + 本轮新增 35 项 | 待 Gradle 运行 |

### 16.8 UI 布局测试覆盖说明

Compose 布局 bounds 断言（#16 标题栏高度不变、#17 筛选器与垃圾桶不遮挡）需要 instrumented 测试或 Screenshot 测试框架。本轮仅在 JVM 层覆盖状态逻辑（35 项测试），布局稳定性通过代码结构保证：
- 标题栏 Row 使用 `Spacer(weight=1f)` 分隔标题和控件
- 垃圾桶 `IconButton` 始终存在（不使用 `if (selected) { ... }` 条件渲染）
- 筛选器使用固定 `padding(horizontal=10.dp, vertical=6.dp)`
- 卡片选中通过 `border` + `containerColor` 实现，不改变尺寸

### 16.9 测试状态

- 自动化测试：**未执行**（`NOT_RUN_BY_SCOPE`，用户禁止 Gradle）
- 修改前基线：550 项（545 passed / 0 failed / 5 skipped）
- 本轮新增 `BatchFilterAndDeleteTest` 35 项测试，预期总测试数 585 项
- APK：未构建（`NOT_RUN_BY_SCOPE`）
- 真机：未执行（`NOT_RUN_BY_SCOPE`）

### 16.10 未完成的人工验收项

1. 今日筛选只显示今日批次
2. 近 3 天/近 7 天筛选正确包含/排除日期边界
3. "所有"显示全部批次
4. 切换筛选时列表刷新、选中清除
5. 删除确认框显示完整信息
6. 删除成功后 Snackbar 浮层提示、列表刷新
7. 删除失败后保留选中、Snackbar 错误提示
8. 标题栏/筛选器/垃圾桶位置不因选中状态变化而跳动
9. 卡片选中样式不改变卡片尺寸
10. 空状态显示对应文案和"查看所有"操作

### 16.11 Git 状态

`NOT_COMMITTED`。所有变更在 working tree 中，等待用户验收后提交。

工作区脏改动文件（本轮）：
- 源码 3 个：`TraceRecordsScreen.kt`（重写）、`CaptureBatchDao.kt`（新增方法）、`InspectionRepository.kt`（新增方法）
- 测试 1 个新增：`BatchFilterAndDeleteTest.kt`
- 文档 2 个：`tasks/todo.md`、本报告

---

## 18. 人工验收问题二次整改：拍照可继续与布局稳定（2026-09-04）

本轮针对现场验收中“拍摄两张实际只保留/只能继续一张”“采集完成卡片遮挡”“拍照后操作栏跳动”“确认并继续按钮移动”等问题进行最小修复。未接入 ROI 自动检测算法，未伪造软件检测结果。

### 18.1 拍照状态与多 View 继续拍摄

本节记录上一轮的阶段性修复；本轮对导航窗口内的按钮状态进一步收口，最终行为以第 19 节为准。

有 ROI 照片完成真实文件保存、`photoId` 回读和 batch/View/template 关联校验后，现场页在导航确认页之前保留固定的 `SAVED`/“进入确认…”状态，禁用主操作按钮，避免路由切换窗口重新出现可点击的“拍照”。返回现场采集页时按 `isScreenVisible` 统一复位为 `IDLE`，不依赖确认页返回时的生命周期回调；确认完成和取消返回后都可以稳定继续拍摄，取消确认仍停留在当前 `viewIndex`。

每次拍照继续使用独立最终文件名和 Room 真实自增 `photoId`。无 ROI View 仍保存照片并直接推进，不创建 ROI 确认行。当前尚未实现自动 ROI 检测，因此 `RoiInspectionRecordEntity` 仍不应生成；人工确认记录只能关联真实的确认照片。

### 18.2 采集页布局

- 视角标题栏固定 `height(32.dp)`。
- `TemplateOverlayControls` 固定 `height(48.dp)`。
- `CaptureActionBar` 放入现场页 Scaffold 的固定 `bottomBar`，内部固定 `height(52.dp)`，拍照按钮固定高度，不再参与参考图内容重排。
- `AllViewsCapturedCard` 改为固定 `height(64.dp)`，文字区域使用可收缩宽度，`重新开始` 使用固定宽度和触控区域，避免与完成文案遮挡。
- 拍照状态区域固定 `height(28.dp)`；错误提示改为单行布局，避免超出状态槽位。

### 18.3 确认页布局

- ROI 列表与确认栏分离。
- 确认栏由 Scaffold `bottomBar` 固定承载，固定 `height(140.dp)`。
- 错误提示保留固定 `height(18.dp)`。
- 确认按钮文案固定为“确认并继续”；未完成提示放在独立固定高度槽位，未完成、保存中和可确认状态不再改变按钮所在区域。
- ROI 信息列允许收缩，避免长名称挤压 OK/NG 操作。

追溯批次页同步修正了三处会造成遮挡或跳动的布局：标题栏使用可收缩标题和固定筛选/删除槽位，批次卡片的零件名称与视角数量使用稳定宽度约束，导出结果消息使用固定高度占位而不是动态插入卡片内容；批次列表项使用稳定 `batchId` key。

### 18.4 本轮实际修改文件

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/NoRoiViewAdvancementTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/MultiViewPhotoPersistenceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `tasks/todo.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

### 18.5 验证状态

- 自动化测试：未执行，原因是当前任务明确禁止运行 Gradle。
- ADB、APK 构建、安装和真机复验：未执行。
- Git：未提交，保留 working tree 脏改动。
- 待人工验收：连续拍摄多个 View、包含有 ROI 和无 ROI 的批次、采集完成卡片、拍照操作栏、确认页底部按钮在不同状态下的位置稳定性。

---

## 19. 两 View ZIP 完整性与确认流程导航整改（2026-09-04）

本轮针对人工验收中“零件配置了两个 View，但 ZIP 只有一张照片”以及“拍照页、确认页与根级底部导航同时出现”的问题继续整改。未接入 ROI 自动检测算法，不伪造软件检测结果。

### 19.1 两个 View 使用同一采集批次

根因是活动 `batchId` 原先只存在现场采集页的 Compose `remember` 状态。进入 ROI 确认页后现场页可能销毁并重建，第二个 View 会误创建新的批次；最终导出页按最后一个批次导出，于是 ZIP 只包含一张照片。

现在由根级共享的 `WorkbenchViewModel` 保存活动 `batchId`。现场页拍摄每个 View 时优先复用同一零件下未结束的批次；切换零件、手动“重新开始”和最后一个 View 完成时清除活动批次。照片仍按真实 `photoId`、`batchId`、`viewIndex` 和 `templateId` 写入并校验。

### 19.2 ZIP 按 View 导出全部照片

`InspectionZipExportService` 现在按指定 `batchId` 查询全部 `captured_photos`，每张有效照片均写入对应目录：

```text
views/view_01/...
views/view_02/...
```

同一 View 的重拍照片全部保留，不用零件名、列表位置或最后一张照片覆盖其他 View。照片索引不再单独生成第二个 CSV，而是作为“照片”记录行合并写入 `inspection_result.csv`，逐行列出 View、模板、拍摄时间、ZIP 路径和导出状态。

`inspection_result.csv` 同时包含“照片”和“ROI确认”两种记录类型。没有 ROI 的 View 只写照片索引行，不生成 ROI 确认行；没有任何 ROI 确认时不伪造 PASS/FAIL 或检测记录。当前文件格式仍为 UTF-8 BOM 的 Excel 兼容 CSV，不是原生 `.xlsx`。

追溯记录页的 ZIP 导出已统一改用 `InspectionZipExportService`，不再使用只导出照片的旧 `PhotoExportService`，避免两个入口产生不同 ZIP 内容。

### 19.3 确认/导出流程不显示根级底部导航

`AppNavigation` 增加采集子流程路由门禁：`view_confirmation/...` 和 `export_result/...` 路由不显示根级“现场采集 / 追溯记录 / 我的”底部导航；同时把当前 Live 页面可见状态传回现场页，配合现场页 Scaffold 固定的“进入确认…”操作槽位，避免过渡期间出现两套操作层。确认页继续由自身固定确认栏承载固定文案“确认并继续”，未完成提示放在固定高度槽位；导出页继续独立显示，避免两个导航层叠加和按钮位置变化。

### 19.4 本轮实际修改文件

源码：

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`

测试：

- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/MultiViewPhotoPersistenceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/NoRoiViewAdvancementTest.kt`

文档：

- `tasks/todo.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

### 19.5 验证状态

- 已完成源码静态审计：批次复用、按 View 打包、单一综合 CSV、追溯记录统一导出、确认/导出路由底部导航隔离。
- 自动化测试、Gradle、ADB、APK 构建安装和真机复验：按当前任务限制未执行，状态 `NOT_RUN_BY_SCOPE`。
- Git：未提交，保留 working tree 脏改动。
- 待人工验收：两个 View 各拍一张后 ZIP 应包含 `view_01` 与 `view_02`；确认页只显示自身确认栏；导出页不显示根级底部导航；无 ROI View 仍只产生照片索引行，不产生虚假检测行。

## 20. 追溯记录批次多选删除整改（2026-09-04）

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**。本次仅修改源码、JVM 测试和文档，未运行 Gradle、ADB、APK 构建或真机操作。

### 20.1 用户问题

追溯记录页原先只保存一个 `selectedBatchId`，批次卡片只能单选，因此无法一次选择多个 ZIP/采集批次并批量清理。

### 20.2 实现结果

- `TraceRecordsScreen.kt` 将选中状态改为 `selectedBatchIds: Set<String>`，每个卡片和复选框都通过真实稳定的 `batchId` toggle，不依赖列表位置、零件名称或 ZIP 文件名。
- 标题栏垃圾桶仍占固定槽位；选中一个或多个批次时启用，未选中或删除中禁用，不新增动态提示行，避免筛选器和垃圾桶位置跳动。
- 删除确认框显示选中批次数量、最多 3 个批次的零件名/短 `batchId` 摘要，以及“所有照片和确认记录”删除说明，保持弹窗内容紧凑。
- 批量删除先快照选中的 `batchId`。只要其中一个批次正在导出，就整体阻止删除；否则按 `batchId` 逐个调用现有 `deleteCaptureBatchCompletely`，不新增第二套批次删除逻辑。
- 全部成功后清空选中集合并通过 Snackbar 显示实际删除数量；中途失败时仅移除已成功删除的 ID，保留未删除批次的选中状态，方便重试并避免误报“全部删除”。
- 时间筛选切换仍清除整个选中集合，避免跨筛选结果保留不可见批次。

### 20.3 测试与文件

实际修改文件：

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `tasks/todo.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

`BatchFilterAndDeleteTest.kt` 当前包含 47 项 JVM 测试，新增/调整覆盖：多选集合增删、稳定 `batchId` 过滤、批量删除快照、导出冲突、全成功清空选中、部分失败保留未删除项、复选框和多选删除源码契约。测试按本轮执行限制未运行，状态为 `NOT_RUN_BY_SCOPE`。

### 20.4 未完成验收项

- 待真机人工确认：连续点击多个批次卡片/复选框后，卡片选中状态是否清晰；批量删除确认框、成功提示和失败保留状态是否符合现场使用习惯。
- 仍未运行 Gradle、ADB、APK 构建/安装和真机测试；Git 未提交，保留 working tree 脏改动。

## 21. 采集过渡与模板包闭环优化（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。本节针对用户反馈的现场采集过渡残留操作栏，以及模板包无法导出、删除和回导的问题；没有改变多 View 批次、人工确认、无 ROI 和结果 ZIP 的既有边界。

### 21.1 采集页面过渡

- `LiveInspectionScreen` 在照片已保存并准备进入确认页时立即隐藏自身 `CaptureActionBar`；`SAVED` 只作为导航过渡状态，不再把“拍照/进入确认…”按钮渲染在现场页底部。
- `ViewConfirmationScreen` 在保存完成事件的一次性消费标记生效后立即移除 `BottomConfirmBar`，再回调导航层，避免“确认并继续”残留一帧。
- `AppNavigation` 先取当前路由主键，再识别 `view_confirmation` 和 `export_result` 采集子流程；这两个页面不显示根级“现场采集 / 追溯记录 / 我的”导航，也不对内层页面施加根级 Scaffold padding。
- 有 ROI 确认完成后，非最后 View 使用 `popBackStack()` 直接回到现场采集；最后 View 先结束批次，再进入导出结果页。无 ROI View 仍保持拍照落库后直接推进的原流程。

### 21.2 模板包格式、导出和回导

新增 `TemplatePackageExporter`，模板包清单使用 `formatVersion: 2`，包含：

- 零件 `partId`、名称和 DPM 码；
- 零件下全部 View 的稳定模板 ID、名称、顺序、启用状态、轮廓及时间字段；
- 每个 View 的参考图片，写入 `images/view_01.ext`、`images/view_02.ext` 等稳定目录；
- 每个 View 的全部 ROI 配置，包括归一化坐标、形状、顺序、检测配置、预处理配置、启用状态、创建时间和 `targetType`。

`TemplatePackageImporter` 和目录导入 adapter 复用同一 manifest 解析逻辑。导入本应用导出的 ZIP 时会恢复 View 图片、顺序、模板元数据和 ROI；旧包没有 `rois` 字段时仍可读取，旧的单个 `roi` 字段继续只做兼容校验，不伪造新的 ROI 记录。导入服务保留导出包中的 DPM、模板时间字段和 ROI 字段；缺失的历史字段才使用当前时间或默认值。

### 21.3 模板包页面与删除语义

`TemplatePackageScreen` 现在按真实 `partId` 观察和展示已配置模板包，提供：

- SAF ZIP 导入；
- SAF ZIP 导出；
- 显示零件、DPM 和 View 数量；
- 删除二次确认，删除该零件的全部模板、ROI 和受 `MobileImageStore` 管理的模板图片。

当前数据模型没有独立的 `TemplatePackageEntity`，因此“删除模板包”使用其零件配置作为包的稳定所有者：删除对应 `partId` 的零件、模板和 ROI；采集批次通过 `SET_NULL` 保留，历史照片和采集记录不被删除。删除失败会显示错误，导出或删除进行中会禁用重复操作。

### 21.4 本轮实际修改文件

源码：

- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TemplatePackageScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ProfileScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplatePackageExporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplatePackageImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplateImportService.kt`

测试：

- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplatePackageExporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplatePackageManagementTest.kt`

文档：

- `tasks/todo.md`
- `tasks/plan.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

### 21.5 验证与未完成边界

- `git diff --check`：通过；仅有工作区现存的 LF/CRLF 警告，无 whitespace error。
- Gradle/JVM 测试：`NOT_RUN_BY_SCOPE`；本轮新增和修改的测试未执行，因为当前任务明确禁止 Gradle。
- ADB、APK 构建、安装、启动、真机交互和视觉验收：`NOT_RUN_BY_SCOPE`，没有新的 APK 路径、时间、大小或 SHA-256。
- 未实现 Detector、自动 ROI、PASS/FAIL 自动计算、自动对齐或其他检测算法；人工 OK/NG 及无 ROI 仅照片记录边界保持不变。
- 待人工验收：拍照后是否直接进入 ROI 确认页、确认后是否只显示现场采集或导出页、模板包导出 ZIP 内容、导出包再次导入后 View/ROI 是否一致，以及删除后历史采集批次是否仍可追溯。
- Git 未提交；工作区中的历史 B2/B3 脏改动均保留。

## 22. 现场拍照后操作栏二次修复（2026-09-04）

用户复测确认“ROI 确认返回现场”已正常，但拍照后进入 ROI 确认页时现场页底部“拍照”栏仍可能出现。根因是现场页在成功分支设置 `SAVED` 后，`CameraPreview` 的相机就绪/错误回调仍可能无条件重置 `captureState`，把过渡状态覆盖为 `IDLE` 或 `ERROR`。

本次仅修改 `LiveInspectionScreen`：

- 增加 `captureNavigationPending`，有 ROI 照片保存成功后先锁定现场页操作栏，再触发确认页导航；
- 操作栏同时受 `isScreenVisible`、`captureNavigationPending` 和 `SAVED` 状态约束；
- 相机回调在导航过渡期间不再覆盖拍照状态；相机新会话只在没有拍照/导航过渡时重置状态；
- 返回现场采集时由 `LaunchedEffect(isScreenVisible)` 清除过渡锁并恢复 `IDLE`。

同步补充 `LiveInspectionCaptureStateTest` 的源码契约，覆盖相机回调不能重置确认页导航过渡。测试、Gradle、ADB、APK 和真机验收仍按当前范围标记 `NOT_RUN_BY_SCOPE`；Git 未提交。

## 23. 采集批次勾选框位置优化（2026-09-04）

追溯记录的采集批次卡片标题行现在按“零件/时间 → View 数量 → Checkbox”排列，勾选框显示在“X 视角”计数右侧。仅调整视觉顺序，仍使用真实 `batchId` 集合进行多选、导出和删除；没有改变批次状态逻辑。

修改文件：

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `tasks/todo.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

`git diff --check` 通过；Gradle、ADB、APK 和真机验收仍为 `NOT_RUN_BY_SCOPE`，Git 未提交。

## 24. 拍照后现场页重复视角与成功中间态修复（2026-09-04）

用户复测截图显示：拍照成功后，现场页仍短暂展示实时采集内容、`视角 2/2`、模板名称 `视角 2` 和“照片已保存，进入人工确认”，随后才导航到 ROI 确认页。这样既产生重复视角标题，也让用户误以为先进入了一个独立的人工确认中间页。

本次修复：

- `captureNavigationPending` 置为 `true` 后，现场页只隐藏拍照操作栏和 `SAVED` 成功文案，保留稳定的 TopAppBar、相机预览和模板布局，避免切页期间出现白屏。
- 多视角时模板名称由顶部切换器统一显示，移除模板图片下方重复的大号视角名称。
- 保留导航过渡状态作为切页保护，避免相机回调重置状态或操作栏重新出现；无 ROI 的保存提示和原有真实照片落库、批次关联流程不变。
- ROI 确认页仍由现有 `onNavigateToConfirm` 直接打开，没有新增页面或数据模型。

### 实际修改文件

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `tasks/todo.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

### 验证与边界

- 新增源码契约覆盖：导航过渡状态传入模板区域、成功文案隐藏、多视角名称不重复。
- `git diff --check`：通过；仅有工作区既存的 LF/CRLF 警告，无 whitespace error。
- Gradle/JVM 测试：`NOT_RUN_BY_SCOPE`。
- ADB、APK 构建/安装、真机验收：`NOT_RUN_BY_SCOPE`。
- 自动 ROI 检测、Detector、PASS/FAIL 自动计算和结果导出边界未改变。
- Git 未提交；等待用户人工验收。

## 28. 拍照后确认页卡顿、现场页残影与未完成批次导出门禁（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。

### 28.1 根因

确认页的 `ViewConfirmationViewModel.loadData()` 虽然使用协程，但照片尺寸读取和每个 ROI 的 Bitmap 裁剪仍在默认主线程执行。高分辨率现场 JPEG 进入确认页时会阻塞 Compose，导致现场页 CameraX 预览、模板参考图和透明度控制栏在路由切换期间持续可见。

现场拍照还会先将 JPEG 写到 cache 临时目录，再执行校验、复制到 captures、原子重命名和再次校验；其中大文件复制和重复解码延长了进入确认页的等待时间。

采集批次在第一张照片保存时创建，追溯记录通过 Flow 立即显示该批次；原导出按钮没有要求 `endTime`，因此未完成批次也可以发起 ZIP 导出。

### 28.2 修复结果

- `ViewConfirmationViewModel` 将照片尺寸读取和 ROI 裁剪放入 `Dispatchers.IO`，Bitmap 在后台线程生成后批量回填 Compose 状态；保存确认时的尺寸读取也放入 IO。
- `MobileImageStore.generateCaptureFile()` 为 CameraX 提供受管理的 `files/captures` 临时路径。`atomicMoveToFinal()` 同目录优先直接重命名，跨目录才使用 `.part` 回退；`storeCapturedImage()` 复用移动前校验结果，不再次解码最终文件。
- `TemplateContent` 的模板卡片使用父布局剩余高度，不再使用 `maxHeight = 210.dp` 限制图片，消除模板图片下方到透明度栏之间的异常空白；模板参考图解码也在 IO 执行。
- 确认/导出子流程路由禁用 NavHost 淡入淡出，切换时不显示现场页残影。确认页拥有自己的顶部返回按钮和底部“确认并继续”栏；根级现场采集/追溯记录/我的导航继续隐藏。
- `InspectionZipExportService.exportInspectionZip()` 先读取并校验目标 `batchId` 的批次、零件和 `endTime`，再校验 `viewCount` 个视角索引均有照片。`TraceRecordsScreen` 对 `endTime == null` 的卡片禁用导出并显示“采集中，拍完全部视角后才能导出 ZIP”。
- `TemplateCaptureViewModel` 原先在异步协程内部才设置 `Capturing`，连续点击可能并发新增两个同编号 View；现在在启动协程前立即锁定状态，后续点击会被忽略。

### 28.3 实际修改文件

源码：

- `app/src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplateCaptureViewModel.kt`

测试：

- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationPerformanceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/image/MobileImageStoreCapturePathTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplateCaptureConcurrencyTest.kt`

### 28.4 验证与边界

- `git diff --check`：通过；无 whitespace error。
- Gradle/JVM 自动化测试：`NOT_RUN_BY_SCOPE`，因为当前任务明确禁止 Gradle。
- ADB、APK 构建/安装、真机交互与视觉验收：`NOT_RUN_BY_SCOPE`；无新的 APK 路径、时间、大小或 SHA-256。
- Git：未提交；工作区其他历史改动未触碰。
- 未实现 Detector、自动 ROI、自动对齐、PASS/FAIL 自动计算、Session ROI 或结果包扩展。
- 待人工验收：确认页是否立即替换现场页、模板卡片高度、确认栏/返回入口、采集中批次导出按钮门禁和全部 View 完成后的 ZIP 内容。

## 26. LiveInspectionScreen 语法回归修复（2026-09-04）

用户反馈 `LiveInspectionScreen.kt:507` 出现 `Expecting a top level declaration`。根因是现场页 Scaffold 内容块调整后残留一个多余的闭合大括号，导致函数在顶层声明前提前结束。

本次仅删除该多余闭合大括号，恢复 `Scaffold` 内容 lambda 与 `LiveInspectionScreen` 函数的正确嵌套；拍照、ROI、导航和持久化逻辑未改动。

- 修改文件：`app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `git diff --check`：通过；仅有工作区既存的 LF/CRLF 警告，无 whitespace error。
- Gradle/JVM 测试、ADB、APK 构建/安装、真机验收：`NOT_RUN_BY_SCOPE`。
- Git 未提交，等待人工验收。

## 27. 拍照后主线程卡顿修复（2026-09-04）

用户反馈点击拍照后会短暂卡顿，随后才进入 ROI 选择界面。审计发现 `MobileImageStore.storeCapturedImage()` 在拍照回调后的 Compose 主线程中同步执行，包含 JPEG 可解码校验、EXIF 读取、文件复制和原子重命名；高分辨率现场照片会造成明显帧阻塞。

本次仅将现有真实照片存储调用包裹在 `withContext(Dispatchers.IO)` 中，照片存储完成后仍在原有协程上下文继续执行状态更新、真实 photoId 回读校验、ROI 查询和导航。没有改变文件路径、批次/照片关联、ROI 确认或导出语义，也没有新增图片处理链。

- 修改文件：`app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- 测试契约：`app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `git diff --check`：待本轮执行。
- Gradle/JVM 测试、ADB、APK 构建/安装、真机验收：`NOT_RUN_BY_SCOPE`。
- Git 未提交，等待人工验收。

## 25. 页面切换白屏与导航栏卡顿优化（2026-09-04）

上一轮为消除拍照后重复视角，曾在 `captureNavigationPending` 期间隐藏现场页全部内容；用户复测出现白屏，且 ROI 确认页返回现场时相机页面重新占位造成切换不顺。本次回到稳定页面骨架：不销毁现场相机预览，不用空白页占位，只保留拍照栏/成功提示的过渡保护。

同时利用现有 `navigation-compose 2.8.4` 的 NavHost 过渡参数，为页面进入/离开及返回统一设置短时淡入淡出（180ms/120ms），避免一级导航栏切换时内容瞬间跳变。重复点击当前 Tab 不再重复入栈或触发无效导航。

### 实际修改文件

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/BottomNavigationBar.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `tasks/todo.md`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

### 验证与边界

- `git diff --check`：通过；仅有工作区既存的 LF/CRLF 警告，无 whitespace error。
- Gradle/JVM 测试：`NOT_RUN_BY_SCOPE`。
- ADB、APK 构建/安装、真机验收：`NOT_RUN_BY_SCOPE`。
- 自动 ROI 检测、Detector、PASS/FAIL 自动计算、真实照片持久化和导出边界未改变。
- Git 未提交；等待用户人工验收。

## 29. 模板包导入失败修复（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。

### 29.1 根因与修复

模板包导入失败不是单一原因：SAF 返回的临时文件可能为空或与上一次导入发生文件名碰撞；解析器对历史 manifest 的图片引用格式过于严格，反斜杠、大小写差异或单字符串 `imageFiles` 会被当成缺图；另外，解析出无有效图片的包后仍可能先删除旧 View，导致失败表现不稳定且有数据风险。

本轮修复：

- `TemplatePackageScreen` 使用 `File.createTempFile`，检查实际复制字节数和文件长度。
- `TemplatePackageImporter` 将损坏/非 ZIP 转换为明确错误，并兼容图片引用中的 Windows 分隔符、大小写差异和单字符串格式。
- `TemplateImportService` 在数据库操作前拒绝无有效视角图片的包；Part、View、ROI 替换放入 Room 事务，失败时旧模板保留并清理新复制图片。

### 29.2 实际修改文件

- `app/src/main/java/com/wearable/inspection/mobile/template/TemplatePackageImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplateImportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TemplatePackageScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TemplateConfigScreen.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplatePackageImporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplatePackageManagementTest.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/template/TemplateViewOrderTest.kt`
- `tasks/todo.md`

### 29.3 验证与边界

- `git diff --check`：通过。
- Gradle/JVM 自动化测试、ADB、APK 构建/安装、真机交互与视觉验收：`NOT_RUN_BY_SCOPE`。
- 本轮未提交 Git；未修改旧工程。待自动化和现场复测确认。

## 30. 切换零件后模板图片与 ROI 偶发缺失修复（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。

### 30.1 根因

现场采集页的 CameraX 预览在切换零件时不会重建，但零件选择回调把页面层的 `contentRect` 清空。`CameraPreview` 的画幅回调由相机状态/显示模式驱动，切换零件本身不会再次触发，因此新的 ROI 虽然已经查询到，也没有可用画幅可绘制。

同时，零件、模板、选中模板和 ROI 使用多条独立的 `StateFlow`。切换零件时旧模板流可能晚于零件 ID 更新，导致重组短暂看到旧模板、空模板或旧 ROI；快速切换时该窗口会表现为模板图片和 ROI 偶发不加载。

### 30.2 修复结果

- `LiveInspectionScreen` 切换零件时不再清空仍对当前 CameraX 预览有效的 `contentRect`。
- `WorkbenchViewModel.templates` 在切换零件后先发出空列表，再订阅新零件模板，并过滤 `enabled` 和当前 `partId`。
- `selectedTemplate` 组合当前零件 ID，拒绝任何不属于当前零件的模板，避免旧模板驱动图片和 ROI 查询。
- `selectPart` 先清除模板选择、视角索引和完成状态，再更新零件 ID，减少跨流重组中的旧状态。

### 30.3 实际修改文件

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModelAdvanceTest.kt`
- `tasks/todo.md`

### 30.4 验证与边界

- `git diff --check`：通过；仅有 Git 的 LF/CRLF 转换提示，无 whitespace error。
- Gradle/JVM 自动化测试、ADB、APK 构建/安装、真机交互与视觉验收：`NOT_RUN_BY_SCOPE`。
- 未修改 CameraX 所有权、相机模式、ROI 坐标映射、模板导入持久化或旧工程。
- Git：未提交；保留工作区中模板导入修复等已有改动，待自动化和现场复测确认。
