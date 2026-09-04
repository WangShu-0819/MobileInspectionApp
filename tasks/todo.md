# 当前任务：单零件多 View 人工确认 + ZIP 导出

状态：**IN_PROGRESS**（2026-09-04，人工验收多 View、导航和布局整改完成，待自动化和真机验收）。

目标：用户选择零件 → 按模板顺序逐 View 拍照。当前 View 有 enabled ROI 时进入确认 UI，逐个选择 ROI OK/NG 和总体 OK/NG 后保存并进入下一 View；当前 View 无 ROI 时仍先真实拍照并保存到当前 batchId，随后直接进入下一 View。全部完成后生成包含所有原始照片和真实确认结果的 ZIP，统一写入一个 Excel 兼容 CSV（检测 CSV 可无 ROI 确认行）。

## 用户说明文档补充（2026-09-04）

- [x] 基于当前 MobileInspectionApp 源码和指定图片目录整理中文使用说明
- [x] 纳入 20 张操作/结果图片，按配置 → 采集 → 确认 → 导出 → 记录管理的业务时间顺序编排
- [x] 记录当前版本边界：DPM 实时扫码、人工确认、本机离线数据、软件检测结果未执行
- [x] 生成 `docs/user-guide/视觉质检MobileInspectionApp_使用说明.docx`
- [x] 完成 DOCX 图片数量、图片可访问性、章节/分页和文本完整性检查；LibreOffice 渲染因环境未安装 `soffice` 未执行

## 本轮追加优化：采集过渡与模板包闭环

归属：当前唯一进行中的“单零件多 View 人工确认 + ZIP 导出”；本轮源码整改已完成，自动化与真机验收待执行。

- [x] 现场拍照成功后不再显示可见的“拍照”操作栏，直接进入有 ROI 的选择界面；保留照片真实落库和批次关联（补充防止相机就绪回调覆盖导航过渡状态）。
- [x] ROI 确认导航过渡期间隐藏现场页“照片已保存，进入人工确认”状态文案，避免出现拍照成功中间界面。
- [x] 保留现场页稳定骨架避免导航白屏；多视角模板名称统一由顶部切换器显示，移除图片下方重复视角名称；一级导航和 ROI 返回使用轻量淡入淡出。
- [x] 修复 `LiveInspectionScreen.kt` 导航布局调整后多余闭合括号导致的 `Expecting a top level declaration` 编译错误。
- [x] 将拍照后的 JPEG 校验、EXIF 读取和原子文件复制移至 `Dispatchers.IO`，避免大图保存阻塞主线程造成点击后卡顿。
- [x] ROI 确认保存后不再显示“确认并继续”残留栏，直接返回现场采集或进入导出页；确认页不显示根级三 Tab 导航。
- [x] 模板包导出保存 `Part`、DPM、全部 View/图片、顺序和全部 ROI 配置（含 `targetType`）。
- [x] 模板包导入能解析本应用导出的 manifest，并恢复模板图片、View 顺序和 ROI 配置；保留旧包兼容性。
- [x] 模板包页面支持按稳定 `partId` 删除整包，清理受管理模板图片和关联模板/ROI，不影响采集批次。
- [x] 追溯记录采集批次卡片将勾选框放在“X 视角”计数右侧，保持批次多选逻辑不变。
- [x] 补充自动化测试和 B2 报告；按当前范围不运行 Gradle、ADB、APK 或真机验收。

## 本轮修复：模板包导入失败（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。

- [x] SAF 复制 ZIP 使用系统唯一临时文件，并校验复制字节数和文件长度，避免空临时文件进入解析器。
- [x] 模板包解析将损坏/非 ZIP 文件转换为可读错误；兼容历史 manifest 的字符串 `imageFiles`、Windows 反斜杠和图片扩展名大小写差异。
- [x] 导入前拒绝没有有效视角图片的包，避免“导入成功但实际没有模板”。
- [x] Part、View 和 ROI 的替换写入放入 Room 事务；失败时旧模板保持不变，已复制的新图片清理。
- [x] 相册模板导入增加异常兜底和 `finally` 状态复位，失败后不会永久停在“导入中”。
- [x] 增加损坏 ZIP、历史图片引用兼容和无有效图片不替换旧模板测试；按范围未运行 Gradle、ADB、APK 或真机验收。

## 本轮修复：切换零件后模板图片与 ROI 偶发缺失（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。

- [x] 切换零件时保留相机预览仍有效的 `contentRect`，不因未重建 CameraX 预览而永久隐藏 ROI 框。
- [x] 模板流切换时先清空旧零件模板，并只接受 `partId` 与当前零件一致的模板。
- [x] 切换零件时先清除模板选择和视角索引，避免旧模板/旧 ROI 与新零件短暂组合。
- [x] 增加零件切换后的模板、选中模板和 ROI 数据隔离测试，以及 `contentRect` 保留契约测试。
- [x] `git diff --check` 通过；按范围未运行 Gradle、ADB、APK 或真机验收。

## 完成清单

- [x] 审计现有批次/照片/View 顺序/ROI/Repository 数据路径
- [x] 有 ROI 的 View 拍照成功后进入 ViewConfirmationScreen，不在拍照成功事件中自动推进
- [x] 按 templateId 加载当前 View 的全部 ROI，裁剪并显示
- [x] 展示 ROI ID、名称、targetType 和独立 OK/NG 选择
- [x] 展示并保存独立的整张照片总体 OK/NG 选择
- [x] 使用 normalizedRect 映射到实际照片 image contentRect 坐标
- [x] 保存 ViewRoiConfirmEntity（逐 ROI 独立行，含像素坐标）
- [x] 未确认时不默认 OK/NG，软件检测结果保持 null
- [x] View 确认完成后由导航层显式推进到下一 View；最后一 View 进入 ExportResultScreen
- [x] 返回/取消确认页不推进 View；不再依赖 DisposableEffect + LifecycleEventObserver
- [x] ViewRoiConfirmDao：getConfirmedViewIndices、deleteByBatchAndViewIndex
- [x] InspectionExcelExporter：UTF-8 BOM + 15 列 CSV
- [x] InspectionZipExportService：按 View 分目录导出全部照片，并将照片索引与真实确认结果合并为一个 `inspection_result.csv`
- [x] ExportResultScreen：SAF 下载 + Intent.ACTION_SEND 分享
- [x] RoiCoordinateMapper：parseNormalizedRect、mapToImagePixels、cropRoiBitmap、getImageDimensions
- [x] ContentRectBounds 纯 Kotlin 替代 android.graphics.Rect（单元测试兼容）
- [x] 前一阶段测试基线：550 项（545 passed / 0 failed / 5 skipped）
- [x] 更新 `docs/reports/b2/` 对应报告并等待验收

## 本轮修复：无 ROI View 与多 View 推进（2026-09-04）

状态：**IN_PROGRESS**（源码审计通过、补充测试已写入；按范围未运行 Gradle/ADB，待自动化和真机验收）。

- [x] 只按当前拍摄 `templateId` 查询 enabled ROI；不使用零件、其他 View 或全局 ROI 数量
- [x] 无 ROI View 先保存原始照片、插入并回读真实 `photoId`，再按当前 `viewIndex` 直接推进
- [x] 有 ROI View 继续进入 ViewConfirmationScreen，确认完成后才显式推进
- [x] LiveInspectionScreen 与 AppNavigation 共享同一个 WorkbenchViewModel
- [x] `completeView(viewIndex)` 防止重复确认、过期回调、越界和跳过 View
- [x] 无 ROI 最后一个 View 更新批次 `endTime` 后进入 ExportResultScreen
- [x] 全无 ROI 批次允许生成 ZIP；所有 View 原始照片均从 `captured_photos` 打包
- [x] 无 ROI 不生成 ROI/人工/软件检测结果，不进入空确认页
- [x] 返回/取消确认页不推进；确认完成事件只消费一次
- [ ] 本轮自动化回归（受执行限制未运行，状态 `NOT_RUN_BY_SCOPE`）
- [ ] 真机验收：有 ROI、无 ROI、连续无 ROI、中间无 ROI、最后无 ROI 混合场景

## 人工验收问题二次整改（2026-09-04）

状态：**IN_PROGRESS**（源码和测试已修改，待自动化和真机验收）。

### 问题 1：连续拍摄两张照片后 ZIP 只保留一张

**根因**：上一轮虽然修复了拍照状态复位，但 `currentBatchId` 仍保存在现场页的 `remember` 状态中。进入 ROI 确认页后现场页可能被销毁并重建，第二个 View 重新创建了 batch，最终导出页只导出了最后一个 batch，因此 ZIP 只有一张照片。

**修复**：有 ROI 照片落库后，导航前保留固定的 `SAVED`/“进入确认…”状态，避免切换窗口重新显示可点击的“拍照”按钮；返回现场页时按 `isScreenVisible` 统一复位为 `IDLE`。同时把活动 `batchId` 提升到根级共享的 `WorkbenchViewModel`，仅在切换零件、手动重新开始或最后一个 View 完成时清除。每个 View 复用同一未结束批次，导出器按 batchId 读取全部照片。

- 修改文件：`LiveInspectionScreen.kt`、`WorkbenchViewModel.kt`、`InspectionZipExportService.kt`
- 确认页返回或取消后均可稳定继续拍摄，取消时仍保持当前 `viewIndex`
- 每次拍照仍通过唯一文件路径、真实 `photoId` 和当前 View 关联保存

### 问题 2：检测记录只有一条

**结论**：当前无 ROI 自动检测算法，不应有 RoiInspectionRecordEntity。"一条检测记录"对应的是第一条 View 的 ViewRoiConfirmEntity（人工确认记录），这是正确语义。无 ROI View 不生成确认记录，照片仍保存在 captured_photos 表中。不伪造 PASS/FAIL。

### 问题 3/4/5/6：布局稳定性

**根因**：
- 状态提示区域使用 `heightIn(min=28.dp)` ，内容出现/消失时高度变化导致底部按钮跳动
- AllViewsCapturedCard 高度和内部文字/重启按钮宽度未固定，长文案会挤压操作区
- ViewConfirmationScreen 错误消息动态出现/消失推动按钮移动

**修复**：
- `TemplateReferenceSection` 的视角标题栏固定为 `height(32.dp)`
- `CaptureActionBar` 提升为现场页 Scaffold 的固定 `bottomBar`，内部固定 `height(52.dp)`，按钮固定为 `height(40.dp)`
- `TemplateOverlayControls` 固定为 `height(48.dp)`
- `AllViewsCapturedCard` 改为紧凑的固定 `height(64.dp)`，文字区域允许收缩，重新开始按钮固定宽度和触控区域
- 拍照状态区域固定为 `height(28.dp)`，错误提示改为单行 Row，不再超出状态槽位
- `ViewConfirmationScreen` 使用 Scaffold `bottomBar` 固定承载确认栏，确认栏固定为 `height(140.dp)`
- ROI 列表和确认栏分离，选择结果、错误提示和保存状态不会推动确认按钮上下移动
- 确认按钮文案固定为“确认并继续”，未完成提示使用独立固定高度槽位，状态变化不改变按钮布局
- 有 ROI 导航期间现场页主操作保留固定禁用槽位，按钮文案显示“进入确认…”，返回后再恢复“拍照”

### 本轮二次整改实际修改文件

源码文件（7 个）：
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`

测试文件（7 个）：
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/NoRoiViewAdvancementTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/MultiViewPhotoPersistenceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`

### 本轮测试状态

- 自动化测试：**未执行**，原因是执行限制（禁止 Gradle）
- 已补充拍照状态、无 ROI 推进、确认页固定栏、批次稳定 key 和导出提示固定槽位的回归契约
- 预计总测试数以 Gradle 实际运行结果为准（本轮受范围限制未执行）
- APK：未构建（`NOT_RUN_BY_SCOPE`）
- 真机：未执行（`NOT_RUN_BY_SCOPE`）
- Git：未提交；保留工作区已有脏改动

### 本轮二次整改源码审计结果（2026-09-04）

对 3 个受影响页面逐项审计，结论：**已完成源码修复，待自动化和真机验收**。

| 审计项 | 结果 |
|---|---|
| LiveInspection: 无 ROI 路径调用 completeView 直接推进 | ✅ |
| LiveInspection: 有 ROI 路径导航 ViewConfirmationScreen | ✅ |
| LiveInspection: 有 ROI 导航前保留 SAVED 过渡状态，返回可见时统一复位 | ✅ |
| LiveInspection: 拍照失败不调用 completeView | ✅ |
| LiveInspection: 照片插入后回读 photoId 并校验关联 | ✅ |
| LiveInspection: 只按 capturedTemplateId 查询 ROI | ✅ |
| LiveInspection: 无 DisposableEffect/LifecycleEventObserver | ✅ |
| LiveInspection: COMPLETED 路径先 finishCaptureBatch 再 navigate | ✅ |
| WorkbenchViewModel: completeView 幂等（IGNORED for stale/duplicate） | ✅ |
| WorkbenchViewModel: ADVANCED/COMPLETED/IGNORED 三态正确 | ✅ |
| AppNavigation: 根级共享 WorkbenchViewModel 传入 LiveInspection | ✅ |
| AppNavigation: onConfirmed 使用 workbenchViewModel.completeView | ✅ |
| AppNavigation: onBack 只 popBackStack 不推进 | ✅ |
| AppNavigation: onNavigateToExport 导航 ExportResult | ✅ |
| ViewConfirmationScreen: completionHandled 一次性消费 | ✅ |
| ViewConfirmationViewModel: 校验 photo 关联后才加载 ROI | ✅ |
| ViewConfirmationViewModel: rois.isEmpty() 拒绝空确认 | ✅ |
| ViewConfirmationScreen: 确认栏由 Scaffold bottomBar 固定承载 | ✅ |
| TraceRecords: 批次列表使用稳定 batchId key | ✅ |
| TraceRecords: 导出提示使用固定单行槽位 | ✅ |
| CapturedPhotoDao: insert 返回 Long，getById 存在 | ✅ |
| InspectionRepository: insertCapturedPhoto 返回 Long | ✅ |
| InspectionRepository: finishCaptureBatch 仅当 endTime==null 时更新 | ✅ |
| WorkbenchViewModel: 活动 batchId 跨确认页重建保持不变 | ✅ |
| TraceRecords: 使用完整 InspectionZipExportService，不再走照片专用导出 | ✅ |
| InspectionZipExportService: 所有 batch 照片按 View 分目录，并与真实确认结果合并写入一个 inspection_result.csv | ✅ |
| InspectionZipExportService: 照片为空才失败，确认行可空且不伪造结果 | ✅ |
| AppNavigation: 确认/导出子流程隐藏根级底部导航 | ✅ |

### 本轮新增测试覆盖（14 项要求对照）

| # | 要求 | 测试文件 | 状态 |
|---|---|---|---|
| 1 | 有 ROI 进入确认页 | ViewConfirmationNavigationTest | ✅ |
| 2 | 无 ROI 不进入确认页 | LiveInspectionCaptureStateTest + CapturedPhotoPersistenceContractTest | ✅ |
| 3 | 无 ROI 自动进入下一 View | LiveInspectionCaptureStateTest | ✅ |
| 4 | 无 ROI 拍照失败不推进 | NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 5 | 无 ROI 照片真实保存到 batchId | CapturedPhotoPersistenceContractTest + NoRoiViewAdvancementTest | ✅ |
| 6 | 连续多个无 ROI View 按顺序拍摄 | NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 7 | 无 ROI 中间 View 不被跳过 | NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 8 | 最后无 ROI 进入 ExportResultScreen | LiveInspectionCaptureStateTest + NoRoiViewAdvancementTest | ✅ |
| 9 | 有 ROI 确认完成只推进一次 | WorkbenchViewModelAdvanceTest | ✅ |
| 10 | 返回/取消确认不推进 | ViewConfirmationNavigationTest | ✅ |
| 11 | viewIndex/templateId 一致 | LiveInspectionCaptureStateTest | ✅ |
| 12 | ZIP 包含所有 View 原始照片 | InspectionZipExportServiceTest + NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 13 | 无 ROI 不生成虚假 ROI/PASS/FAIL | NoRoiViewAdvancementTest（本轮新增） + ViewConfirmationFlowTest | ✅ |
| 14 | 前序功能不回归 | CapturedPhotoPersistenceContractTest + InspectionZipExportServiceTest | ✅ |

### 本轮测试与状态

- 自动化测试命令：未执行（用户明确禁止 Gradle；`NOT_RUN_BY_SCOPE`）。修改前历史基线为 550 项（545 passed / 0 failed / 5 skipped）。
- 本轮已补充多 View 批次复用、ZIP 综合 CSV 和确认/导出导航门禁；总测试数以解除限制后实际运行结果为准。
- APK：未构建（`NOT_RUN_BY_SCOPE`），无新的 APK 路径、时间、大小或 SHA-256。
- 真机：未执行（`NOT_RUN_BY_SCOPE`）。
- Git：未提交；保留工作区已有脏改动。

## 实际修改文件

### 新增文件（12 个）
- `data/entity/ViewRoiConfirmEntity.kt` — 逐 ROI 人工确认实体
- `data/dao/ViewRoiConfirmDao.kt` — DAO（Flow 观察、按 View 删除、已确认 View 索引查询）
- `data/export/InspectionExcelExporter.kt` — 15 列 CSV 生成器（UTF-8 BOM）
- `data/export/InspectionZipExportService.kt` — ZIP 打包服务（照片 + CSV）
- `ui/screens/RoiCoordinateMapper.kt` — normalizedRect → contentRect → imagePixels 坐标映射
- `ui/screens/ViewConfirmationViewModel.kt` — 确认页 ViewModel（裁剪、选择、保存）
- `ui/screens/ViewConfirmationScreen.kt` — 确认页 Compose UI
- `ui/screens/ExportResultScreen.kt` — 导出结果页（统计 + 下载 + 分享）
- `ui/screens/ContentRectBounds.kt` — 纯 Kotlin 坐标数据类（替代 android.graphics.Rect）

### 测试文件（5 个）
- `RoiCoordinateMapperTest.kt` — 16 项（坐标映射、裁剪、边界）
- `ViewRoiConfirmEntityTest.kt` — 16 项（实体字段、OK/NG 保留、JSON 格式、targetType）
- `InspectionExcelExporterTest.kt` — 15 项（CSV 头、行值、NG 不丢、BOM、escapeCsv）
- `InspectionZipExportServiceTest.kt` — 6 项（ZIP 包名、结果类型、文件结构）
- `ViewConfirmationFlowTest.kt` — 11 项（多 View 行数、NG 保留、批量隔离）

### 修改文件（6 个）
- `data/db/AppDatabase.kt` — 版本 5→6，新增 ViewRoiConfirmEntity 和 DAO
- `data/db/Migrations.kt` — MIGRATION_5_6（view_roi_confirms 表）
- `data/repository/InspectionRepository.kt` — 新增 6 个方法
- `MobileInspectionApp.kt` — repository 构造参数新增 viewRoiConfirmDao
- `ui/navigation/Screen.kt` — ViewConfirmation 路由（8 参数）+ ExportResult 路由（3 参数）
- `ui/navigation/AppNavigation.kt` — 注册 ViewConfirmation 和 ExportResult composable
- `ui/screens/LiveInspectionScreen.kt` — 拍照后按 ROI 分支 + 显式 View 完成推进

## 测试命令及结果

```
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```

BUILD SUCCESSFUL — 527 项（522 passed / 0 failed / 5 skipped）

新增 64 项测试全部通过：
- RoiCoordinateMapperTest: 16 passed
- ViewRoiConfirmEntityTest: 16 passed
- InspectionExcelExporterTest: 15 passed
- InspectionZipExportServiceTest: 6 passed
- ViewConfirmationFlowTest: 11 passed

## Bug Fix 历史记录（2026-09-04 现场验收）

### 问题 A：拍照成功提示被遮挡且文案错误
- **原因**：SAVED 状态显示"已保存，切换下一视角"，但实际流程是进入人工确认页
- **修复**：改为"照片已保存，进入人工确认"，增加 `maxLines=1` + `TextOverflow.Ellipsis`

### 问题 B：确认完成后没有推进到下一 View（已由本轮显式推进替代）
- **原因**：`DisposableEffect(lifecycleOwner)` 创建 observer 时闭包捕获 `pendingAdvance` 初始值 `false`，后续 `pendingAdvance` 变为 `true` 时 observer 读不到
- **修复**：增加 `rememberUpdatedState(pendingAdvance)` 和 `rememberUpdatedState(currentBatchId)`，observer 内部读取 `pendingAdvanceRef` / `currentBatchIdRef`

### Bug Fix 修改文件
- `ui/screens/LiveInspectionScreen.kt` — 2 处修改：
  1. SAVED 提示文案改为"照片已保存，进入人工确认"
  2. DisposableEffect 使用 `rememberUpdatedState` 避免闭包捕获旧状态

### Bug Fix 新增测试文件（3 个，23 项）
- `ui/screens/LiveInspectionCaptureStateTest.kt` — 11 项（文案、overflow 保护、rememberUpdatedState、DisposableEffect 结构）
- `ui/screens/workbench/WorkbenchViewModelAdvanceTest.kt` — 5 项（advanceToNextView 顺序推进、末尾不越界、resetViewIndex）
- `ui/navigation/ViewConfirmationNavigationTest.kt` — 7 项（onConfirmed 非最后/最后 View 导航、onBack 不推进、路由参数）

### Bug Fix 历史测试结果
```
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```
BUILD SUCCESSFUL — 550 项（545 passed / 0 failed / 5 skipped）

新增 23 项测试全部通过，无回归。

---

## 采集批次/零件 ZIP 清理

状态：**SOFTWARE_COMPLETE**（2026-09-04，待用户验收）。

- [x] 点击多个批次卡片或复选框可多选，并显示清晰选中状态（Primary 边框 + BackgroundVariant1 背景）
- [x] 选中一个或多个批次后启用右侧垃圾桶 IconButton；未选中时灰色禁用
- [x] 删除前弹出确认框，显示选中批次数量、零件名/批次 ID 摘要和删除内容
- [x] 按稳定 `batchId` 集合逐个精确删除，不按列表位置、名称或全局目录删除
- [x] 删除成功刷新列表并清除选中集合，显示批量删除数量 Snackbar 提示
- [x] 删除失败保留未删除批次的选中状态并显示明确错误
- [x] 正确处理照片文件删除；数据库 CASCADE 删除 captured_photos 和 view_roi_confirms
- [x] 其他零件、批次、模板图片和 ROI 不受影响
- [x] 选中集合包含导出中的批次时整体禁止删除，给出明确提示
- [x] 补充 batchId 匹配、多选切换、部分失败保留、照片隔离、导出冲突和实体字段测试

---

## 采集批次筛选、删除交互与布局稳定性优化

状态：**IN_PROGRESS**（2026-09-04，源码和测试已写入，因执行限制未运行 Gradle/ADB）。

### 一、时间筛选

- [x] `BatchTimeFilter` 枚举：今日 / 近 3 天 / 近 7 天 / 所有（默认"近 7 天"）
- [x] `CaptureBatchDao.observeByStartTimeSince(sinceMillis)` — 基于 `startTime` 的 Room Flow 查询
- [x] `InspectionRepository.observeCaptureBatchesSince(sinceMillis)` — 透传 DAO
- [x] 标题栏紧凑 DropdownMenu 筛选器，当前选项始终可见
- [x] 中文选项名：今日 / 近 3 天 / 近 7 天 / 所有
- [x] "今日"按本地日期 00:00 开始；"近 3 天"含今天及前 2 个自然日；"近 7 天"含今天及前 6 个自然日
- [x] 基于数据库 `startTime` 字段筛选，不按文件名或列表位置
- [x] 空时间历史记录只在"所有"中显示，不自动猜测日期
- [x] 筛选只影响列表展示，不删除/修改/重新生成批次数据

### 二、标题栏布局稳定性

- [x] 固定结构：[采集批次] [时间筛选固定槽位] [垃圾桶固定槽位]
- [x] 筛选器位置和宽度稳定（DropdownMenu + 固定 padding）
- [x] 垃圾桶始终占位，未选中时灰色禁用（不隐藏，不导致布局跳动）
- [x] 不插入"已选择 1 个"新行，避免列表下移
- [x] 卡片选中样式通过 border + background 色实现，不改变卡片尺寸

### 三、批次多选与删除

- [x] 点击卡片或复选框切换选中状态，绑定稳定 `selectedBatchIds: Set<String>`
- [x] 切换时间筛选时 `LaunchedEffect(activeFilter)` 清除选中集合
- [x] 删除确认框显示选中批次数量、最多 3 个批次摘要和删除内容说明
- [x] 删除只作用于当前 `selectedBatchIds` 对应的稳定 `batchId` 集合
- [x] 选中集合包含正在导出的批次时整体阻止删除，避免部分删除
- [x] 批量删除成功后刷新列表、清除选中集合、Snackbar 浮层提示删除数量
- [x] 批量删除部分失败时仅移除已成功删除的 ID，保留其余选中项便于重试
- [x] 删除期间禁用垃圾桶按钮，标题栏尺寸不变

### 四、Snackbar 浮层避免列表跳动

- [x] 使用 Scaffold `snackbarHost` 替代列表内 `item { Text }` 提示
- [x] Snackbar 悬浮显示，不改变 LazyColumn 布局高度
- [x] 成功/失败提示均通过 SnackbarHostState 管理
- [x] 不新增永久性成功状态卡片

### 五、空状态

- [x] 今日暂无采集批次 / 近 3 天暂无采集批次 / 近 7 天暂无采集批次 / 暂无采集批次
- [x] 非"所有"筛选为空时提供"查看所有"轻量操作

### 六、测试

- [x] 新增 `BatchFilterAndDeleteTest.kt`（47 项 JVM 测试，含多选与批量删除契约）
- [ ] 自动化回归（受执行限制未运行，`NOT_RUN_BY_SCOPE`）
- [ ] 真机验收（受执行限制未执行，`NOT_RUN_BY_SCOPE`）

### 本轮实际修改文件

源码文件（3 个）：
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt` — 重写：时间筛选、稳定布局、Snackbar、空状态
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/CaptureBatchDao.kt` — 新增 `observeByStartTimeSince`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt` — 新增 `observeCaptureBatchesSince`

测试文件（1 个新增）：
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt` — 35 项 JVM 测试

### UI 布局测试覆盖说明

Compose 布局 bounds 断言（标题栏高度不变、筛选器与垃圾桶不遮挡）需要 instrumented 测试或 Screenshot 测试框架。本轮仅在 JVM 层覆盖状态逻辑，布局稳定性通过代码结构保证（固定 Row 权重、固定 padding、始终占位的 IconButton）。待解除 Gradle 限制后补充 instrumented 验证。

---

## 已完成任务：模板 ROI 属性选择

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**（2026-09-04，用户确认验收完成）

---

## 已完成任务：模板视角 ROI 长按删除回归整改

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**（2026-09-03，人工交互验收通过）

---

# 历史任务

## 按采集批次导出照片 ZIP + UI 压缩

状态：**SOFTWARE_COMPLETE**（2026-09-03，待用户验收）

## B2 Task 1：旧 DPM 识别链迁移与实时扫码闭环

**状态**：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-02）

## B2 Task 2：旧模板导入 + 模板透明叠加 MVP

**状态**：**SOFTWARE_COMPLETE**（2026-09-02，提交 `bdf1bd89`）

## 模板配置重构与逐视角 ROI

状态：**SOFTWARE_PARTIAL / ROI_REMEDIATION_PENDING**（2026-09-03 审计）

## B1 完成门禁

B1 已完成并关闭（提交 `b7c4c08e`）。

---

## 附加离线回归：NutPresenceDetector Key 与负样本

状态：**NUT_KEY_REFINEMENT_COMPLETE / HEX_ANGLE_REFINEMENT_APPLIED / FULL_SUITE_PASS**（2026-09-04）

- [x] 自动发现 Key 中全部 `nut_*.png|jpg|jpeg`，当前 5 张样本均按用户确认的 `expectedCount=2` 检出 2 个最终主体框
- [x] 保留 `bodyHexAngleCandidates` 配置接口，但默认使用稳定 `0°` 主体几何先验；避免 Canny 在垫圈/背景边缘上选择 `-20°/20°`，并将主体 box 限制在证据组件内
- [x] 生成 8 张 Nut 负样本，全部 `candidateCount=0`、`boxes=[]`
- [x] 基于 5 张 Nut Key 原图派生 5 张无螺母零件负样本，移除区域外原始像素保持不变；5/5 `candidateCount=0`、`boxes=[]`
- [x] 更新 Nut Key debug 图、contact sheet、机器可读结果和 B3 报告
- [x] 原图派生负样本专项回归：4/4 通过；此前完整 unittest 门禁 `17/17 PASS`，本轮未修改 Thread

---

## 28. 拍照后确认页卡顿、现场页残影与未完成批次导出门禁（2026-09-04）

状态：**源码整改完成 / AUTOMATION_AND_PHYSICAL_ACCEPTANCE_PENDING**。

### 根因与修复

- `ViewConfirmationViewModel` 原先在主线程协程中读取现场照片尺寸并逐个解码/裁剪 ROI。现在尺寸读取、ROI Bitmap 裁剪和现场模板参考图解码均在 `Dispatchers.IO` 执行，Bitmap 结果回到主线程后一次性写入状态。
- CameraX 现在先写入受管理的 `files/captures` 临时路径，`MobileImageStore.atomicMoveToFinal()` 同目录优先重命名，跨目录场景才回退 `.part` 复制；拍照存储复用移动前校验结果，不重复解码整张照片。
- `TemplateContent` 的参考图卡片填满父布局可用高度，移除 `maxHeight` 造成的透明度栏上方大块空白。
- 确认/导出子流程的 NavHost 过渡改为无动画切换，避免现场 CameraX 预览、模板图和透明度栏在路由切换时残留；确认页保留自己的顶部返回入口和底部确认栏，根级三 Tab 导航继续按子流程门禁隐藏。
- `InspectionZipExportService` 和追溯记录卡片均要求 `CaptureBatchEntity.endTime != null`，并在导出前校验 `viewCount` 个视角索引均有照片；采集中的批次只显示“完成后导出 ZIP”，不能创建或导出 ZIP。
- `TemplateCaptureViewModel` 在启动异步拍摄前立即锁定 `Capturing` 状态，避免连续点击并发新增两个相同编号的模板视角。

### 实际修改文件

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

### 验证状态

- `git diff --check`：通过；仅保留工作区既有的 LF/CRLF 警告，无 whitespace error。
- Gradle/JVM 自动化测试：`NOT_RUN_BY_SCOPE`；当前唯一任务禁止执行 Gradle。
- ADB、APK 构建/安装、启动/停止和真机视觉验收：`NOT_RUN_BY_SCOPE`；无新的 APK 路径、时间、大小或 SHA-256。
- Git：未提交；保留工作区中其他已有改动。
- 待验收：拍照后直接进入确认页、确认页布局与返回入口、模板图下方空白消除、未完成批次不可导出，以及全部 View 完成后 ZIP 才生成。
