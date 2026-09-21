# Implementation Plan: MobileInspectionApp 当前阶段

## 历史任务：ROI 检测结果、人工改判与 ROI 证据图导出收口（2026-09-18）

状态：**已完成并提交**（提交 `57003b44`）。本文末尾的 2026-09-20 当前任务指针覆盖本历史任务的执行入口。

### 目标
ROI 检测结果语义、人工改判图片导出、CSV 回链到 ZIP 中真实文件；DB 保存失败时新证据文件清理。

### 本轮修复
- `ViewModelSaveLifecycleTest.createTestPhoto()` 从手写字节改为 `Bitmap.compress(JPEG)` 生成标准 JPEG
- 测试增加 `Dispatchers.IO` 协程完成等待（轮询 `isLoaded` 和 `errorMessage`/`saveCompleted`）
- 字段注入移到 `loadData()` 完成后，防止 `applyDefaultSelections()` 覆盖测试数据
- 仅修改测试文件，未修改生产代码

### 验证结果（2026-09-18 单 Gradle 命令 10 类 99 项）

- ViewModelSaveLifecycleTest：4/4 通过
- ROI 9 类：95/95 通过
- 总计：10 类 99/99 通过，0 失败
- 编译：`compileDebugKotlin` ✅、`compileDebugUnitTestKotlin` ✅、`assembleDebug` ✅
- APK：`app/build/outputs/apk/debug/app-debug.apk`，232,123,666 bytes，2026-09-18 17:56:08 +08:00
- SHA-256：`2736b661fde7a170b7cdadb0e84d89c5fc45f182c608726b316577d5028d44fb`

---

## 已验收计划：DPM 原始证据清理（2026-09-18）

状态：**USER_ACCEPTED**（2026-09-18 v3.1）。用户已确认 DPM 真机扫码不卡顿且不再出现紫色加载圈。独立 DPM ZIP 只负责导出归档，不需要新增 DPM 扫码证据记录，也不再推进独立 ZIP/包删除、历史 ZIP 导入或 SAF 包管理。审计已完成，v2 实现已完成（DpmOperationGuard + 逐 evidenceId 清理 + 绑定 TTL）。v3.1 修复已完成（OperationLease 令牌 + applicationScope 退出清理 + 诊断日志 + UI 精简）。

### 目标与边界

- 复用现有 `DpmScanEvidenceEntity`、DAO、Repository 和 `MobileImageStore`；不新增第二套 DPM 证据模型。
- 清理范围为数据库中的 DPM 证据行、`filesDir/dpm_evidence` 下对应原始帧和 ROI 图；已导出的独立 ZIP 保留不动。
- 保留现有 DPM 扫码、证据保存、独立 ZIP 导出、批次 ZIP 关联、DPM 解码、CameraX、ROI 和批次清理行为。
- 不按文件名、列表位置或全局目录删除；所有操作必须基于稳定 evidenceId/scanSessionId 和受管理路径。
- 不读取或视觉分析 PNG/JPG。

### 依赖顺序与任务拆分

#### Task 1：只读生命周期审计与存储统计

**验收标准：**

- [x] 列出成功、未绑定、共享会话、活动会话和导出中的证据状态。
- [x] 核对原图/ROI 图路径校验、缺失文件、孤立文件和重复路径。
- [x] 统计当前证据行数、文件数量和总字节数。
- [x] 输出”全部清理”或”按会话/时间清理”的建议，不修改源码和数据。

#### Task 2：确定清理策略

**验收标准：**

- [x] 明确用户清理范围：全部、按扫码会话或按时间范围。
- [x] 明确未绑定证据是否允许在显式确认后清理。
- [x] 明确正在扫码、保存、导出时的阻塞规则。
- [x] 明确独立 ZIP 始终保留，不与原始证据清理联动删除。

#### Task 3：实现安全清理路径

**预计范围：** 7 个源码/测试文件，不改数据库 schema。

**验收标准：**

- [x] 使用稳定 evidenceId/scanSessionId 精确定位证据。
- [x] 逐 evidenceId 处理，只删除 `dpm_evidence` 受管理目录下的对应文件。
- [x] 文件删除失败时保留数据库行并报告具体错误。
- [x] 文件缺失视为幂等成功，不报错。
- [x] 仅所有文件成功删除后才删 DB 行。
- [x] 孤立文件清理基于剩余 DB 行，不误删仍被引用的文件。
- [x] DB 删除失败、文件缺失、共享路径和越界路径均有安全处理。
- [x] 不影响已导出的 ZIP、批次 ZIP 和其他业务数据。
- [x] DpmOperationGuard 为应用级并发门禁，cleanup/scan/save/binding/export 共享。
- [x] 延迟绑定有 5 分钟 TTL，超时自动释放门禁。

#### Task 4：现有追溯界面增加最小清理入口

**验收标准：**

- [x] 保持现有追溯记录布局，不新增 DPM 证据记录列表或 ZIP 导入入口。
- [x] 显示原始证据数量/空间占用，并提供清晰的清理确认。
- [x] 清理进行中禁用重复操作，成功/部分失败/失败状态可追踪。

#### Checkpoint：清理功能验收

- [x] JVM/Instrumented 定向测试通过（JVM 53 项 + Instrumented 13 项）。
- [x] `DpmOperationGuard` 替换 `DpmScanViewModel` 静态门禁，cleanup/scan/save/binding/export 共享并发锁。
- [x] cleanup 逐 evidenceId 处理，文件缺失幂等，孤立清理基于剩余行。
- [x] APK 构建成功。
- [x] 真机验证清理前后数据库、文件数量和空间变化。
- [x] DPM 扫码、独立 ZIP 导出、批次 ZIP 和前序已验收能力回归通过（真机/用户验收）。

#### Task 7：v3 竞态修复与 UI 精简

**验收标准：**

- [x] `DpmScanViewModel.startScan` 中 `DpmOperationGuard.begin()` 同步等待；guard 拒绝时清理资源并中止，不设置 analyzer。
- [x] `WorkbenchViewModel.setPendingDpmBatchBinding` 中 `DpmOperationGuard.begin()` 同步等待；guard 拒绝时清除 pending binding。
- [x] DPM 解码成功后使用 `saveCurrentEvidence()`（仅保存，不清理相机），页面导航后 `DpmScanExitEffect` 负责清理，避免紫色加载圈。
- [x] `runDpmScanExit` 增加 `cleanupScope` 参数；evidenceFrames 为 null 时直接清理。
- [x] TraceRecordsScreen DPM 统计行精简为一行（"N 条 · M 张（原图+ROI） · X MB"）；确认对话框精简为一句话；成功/失败消息精简。
- [x] `DpmOperationGuard.resetForTesting()` 新增测试专用重置方法。
- [x] 编译通过、DPM 定向 JVM 测试通过、Instrumented 20/20 通过、APK 构建成功，用户真机验收通过。

### 已取消的方向

独立 DPM ZIP/包删除、SAF URI 包记录、独立包列表、历史 ZIP 导入不再作为当前需求。历史提交 `3624ffdb` 保留，不回滚；后续实现不得继续扩大该方向，也不得借机改动已验收导出闭环。

### 风险与待确认项

| 风险/问题 | 影响 | 处理 |
|---|---|---|
| 原始证据可能未绑定 batchId | 无法按批次安全归属 | 先按 evidenceId/sessionId 明确清理范围 |
| 同一文件可能被多行引用 | 误删共享证据 | 删除前建立路径引用计数 |
| SAF ZIP 是独立副本 | 清理后仍占外部空间 | 明确 ZIP 由系统文件管理器单独处理 |
| cacheDir 临时文件 | 可能有额外低优先级占用 | 不并入本任务，另立债务 |

## 本轮计划：DPM 成功扫码证据生命周期修复（2026-09-15）

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-15；已取得真实扫码、落库和独立 ZIP 证据，等待用户确认；批次 ZIP 的 DPM 合并仍需有显式 batchId 的设备上下文复核）。用户最新指令覆盖历史任务指针；本轮只处理 DPM 成功源帧从页面退出到数据库及独立/批次 ZIP 的闭环。

1. 只读审计并用现有 JVM/真实归档测试复现：确认 Compose 退出闭包、CameraPreview 清理、ViewModel 保存作用域和导出筛选的实际顺序。
2. 以最小改动修复退出快照与受控保存任务，保留 ECC 成功源帧、frameToken、去重、失败清理和 DPM 解码链。
3. 补充真实 Bitmap/Room/ZipInputStream/SAF 清理回归测试，验证 batchId 严格相等和独立 ZIP 无 batchId 证据。
4. 运行 `compileDebugKotlin`、DPM 定向 JVM、DPM 两类真实归档测试和 `assembleDebug`；不执行 adb/真机，不提交 Git。
5. 更新 `tasks/todo.md`、B3 DPM 扫描/导出报告，标记 **SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**，暂停等待主协调 Agent 审阅。

依赖顺序：退出快照 → 持久化 → 导出/SAF → 测试 → 构建 → 文档。风险重点是 CameraPreview 的独立销毁 effect 可能先于 DPM 页面 effect 断开相机会话，因此必须以真实 Compose/生命周期顺序测试锁定。

## 2026-09-15 软件回归整改收口

本轮初始定向产物为 222 tests、4 failures、5 skipped：1 项是退出契约仍依赖已移除的 `viewModel.stopScan()` 字面量，3 项是 Mockito 对 Android `ContentResolver` final 方法的桩未作用于实际调用。修复仅限测试：

- `DpmScanEvidenceContractTest.kt` 使用 Robolectric Bitmap 和 `runDpmScanExit` 实际行为验证无 sessionId 的 stop/recycle/no-save/no-disconnect 分支。
- `SafZipExportTest.kt` 使用 Robolectric Fake ContentResolver shadow，精确提供 null 输出流、ByteArrayOutputStream 和 delete 记录；未修改生产 SAF 逻辑。

生产复核保持：`DpmScanScreen` 使用 `DisposableEffect(Unit)` 和最新 sessionId；退出顺序为 `getEvidenceFrames → saveEvidence → stopScan → clearFrameAnalyzer → disconnect`；DPM `CameraPreview` 不在证据快照前 disconnect；保存任务在受控 Application scope；Bitmap 在文件/数据库处理后回收；失败删除半成品且不插入 SUCCESS；不保存 NO_READ、空码值、ECC 失败、lastFrameBitmap 或旧 session；批次 ZIP 只接受明确相等 batchId，独立 DPM ZIP 允许合法空 batchId 证据。DPM 解码算法和 CameraX 所有权未改。

实际修改文件：

- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/SafZipExportTest.kt`
- `tasks/todo.md`
- `tasks/plan.md`
- `docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`
- `docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`

验证命令与结果：

1. `.\gradlew.bat :app:compileDebugKotlin --no-daemon`：退出码 0，BUILD SUCCESSFUL。
2. `.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --tests "com.wearable.inspection.mobile.dpm.*" --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest" --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportServiceTest" --tests "com.wearable.inspection.mobile.ui.screens.DpmScanExit*" --tests "com.wearable.inspection.mobile.ui.screens.SafZipExportTest" --tests "com.wearable.inspection.mobile.ui.screens.CameraPreviewTest"`：退出码 0，222 项，217 passed / 0 failed / 5 skipped。
3. `.\gradlew.bat :app:assembleDebug --no-daemon`：退出码 0，BUILD SUCCESSFUL。

执行 Agent 报告的 APK：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk`；生成时间 `2026-09-15 16:18:20 +08:00`；大小 `232,041,746 bytes`；SHA-256 `0B82E8E51048EA7B87EB10493F921DB8CCA33567287D6CDC96CEBC228A1A57D5`。主协调审阅时当前工作区未找到该 APK，以上信息尚未独立核验。本轮未执行 ADB/安装卸载/启动停止/真机或 instrumented 测试；未提交 Git，工作区其他改动保留。剩余风险：尚无本轮设备物理证据，等待新 APK 和现场证据。

主协调审阅补充（2026-09-15）：已完成新包真机取证。设备数据库快照有 11 条 `SUCCESS` 行，独立 DPM ZIP 有 23 entries；本次扫描没有显式 `batchId`，所以实际批次 ZIP 的 DPM entries 为 0，不能把无批次证据猜测归入 `batch_17`。在用户确认前仍不得标记 `USER_ACCEPTED`；如需验收批次 DPM 合并，下一轮必须从有活跃 batchId 的现场采集上下文启动扫码。

## 已验收任务：拍照后人工确认 + 持久化（USER_ACCEPTED）

验收记录：模板 ROI 属性、拍照后逐 ROI/总体人工确认、多 View 推进和 ZIP 导出已由用户验收；历史边界保留供后续维护参考。当前任务指针仅以 `tasks/todo.md` 为准。

执行边界：

- 有 ROI 的 View 拍完后进入人工确认界面；无 ROI 的 View 拍完并确认照片落库后直接进入下一 View，不创建人工结果。
- View 推进由同一个 WorkbenchViewModel 按拍摄时的 `viewIndex` 显式完成，最后一个 View 更新批次结束时间并进入导出页。
- 复用现有批次、照片、InspectionSession、ROI 记录、DAO 和 Repository；不创建第二套 ROI 数据模型或新的 CameraX。
- 展示当前照片全部 ROI，使用 normalizedRect 映射到实际 image contentRect，并保存 ROI/总体人工结果及确认时间。
- 软件检测结果保持 null/未执行；不实现自动检测、Homography、自动对齐、自动轮廓或 Session ROI 编辑。
- 只修改源码、自动化测试和文档；不运行 Gradle、ADB、APK 或真机测试，不提交 Git。

完成后必须更新 `tasks/todo.md` 和 `docs/reports/b2/` 报告，列出实际修改文件、测试状态、未完成项和 Git 状态，暂停等待验收。

## 后续需求：采集批次/零件 ZIP 清理

状态：**REQUIREMENT_RECORDED / NOT_IMPLEMENTED**（2026-09-04）。

在当前人工确认任务完成后，再实现采集批次清理：点击批次卡片选中，在“采集批次”栏最右侧显示垃圾桶；确认后按稳定 `batchId` 删除对应批次/ZIP，成功刷新列表，失败保留选中状态并提示错误。必须先审计现有 ZIP 文件路径/URI 和批次级删除语义，不能全局扫描或误删其他批次、模板图片和 ROI。复用现有批次、照片 DAO、Repository 和导出服务，不新增第二套数据模型。

## 已完成任务：模板视角 ROI 长按删除回归整改（2026-09-03）

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**（2026-09-03，人工交互验收通过）

用户确认：已有 ROI 和新增 ROI 均可点按/长按选中、确认删除并正确持久化。

~~结果包导出（基础照片 ZIP）~~ 阶段 1 实现中：基础照片 ZIP 导出已完成，manifest + Excel + 图片完整结果包仍未实现。
~~现场采集模板参考图拍照时上移、黑边消失和比例变化，作为阶段 2 处理。~~ 阶段 2 实现中：模板参考图拍照时上移已修复。
~~重复"新建零件"按钮作为后续独立任务处理。~~ ✅ 已修复：移除列表区全宽按钮，仅保留 TopAppBar "+" 入口。

### 审计结论与依赖

- 当前活动链路为 `Profile → TemplateConfig → PartListScreen → PartDetailScreen → TemplateDetailScreen → RoiEditorScreen`。
- ROI 移动/缩放整改已完成；上一版删除逻辑已写入，但人工验收暴露出 Canvas 选中入口不可靠，不能只以 ViewModel 测试通过作为完成依据。
- 复用现有 ROI 实体、DAO、Repository 和状态流；不新增数据库表、第二套 ROI 状态或新的手势框架。
- 前序必须保留：新 ROI 绘制/保存、取消、选中、移动、四角缩放、边界约束、模板图片 contentRect 映射和唯一 CameraX 架构。

### 实施任务（按依赖顺序）

1. [x] 修复 Canvas 命中：普通点按和长按已有 ROI 都能可靠识别当前框，长按后明确设置 `selectedRoiId`。
2. [x] 保留右上角小垃圾桶图标作为删除入口；长按只负责选中和高亮，点击图标后进入删除确认。
3. [x] 保留现有 ViewModel 删除成功/失败处理，确认失败不会错误清除本地状态。
4. [x] 补充长按选中、删除、无选中、多 ROI、多 View 隔离和删除后重载自动化测试。
5. [x] 执行三条 Gradle 命令，更新任务清单和整改报告；本轮不执行 adb。

### 验收标准

- 已有 ROI 可在当前 View 通过普通点按或长按可靠选中并高亮；右上角小垃圾桶图标可见，点击后进入清晰的删除确认。
- 删除只作用于当前 View 的当前 ROI；删除成功后持久化并从 UI 移除，重进页面后不再出现。
- 无选中 ROI 不会误删；删除失败有明确反馈并保留可恢复状态。
- 新增、取消、移动、缩放、边界约束、contentRect 映射和多 View `templateId` 隔离不回归。
- `:app:compileDebugKotlin --no-daemon`、`:app:testDebugUnitTest --no-daemon`、`:app:assembleDebug --no-daemon` 全部通过。
- 完成后 Agent 必须回填 `tasks/todo.md` 完成清单、实际修改文件、测试结果、真机范围、未完成项和 Git 状态，并同步更新 `docs/reports/b2/` 报告。

### 预计文件范围

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`（仅在现有删除接口不足时）
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt` 及必要的 UI/手势测试
- `docs/reports/b2/` 下的 ROI 整改报告

### 执行完成回填要求

执行 Agent 完成实现后必须：

1. 逐项勾选已由代码和测试证明的清单；
2. 填写实际修改文件、真实测试命令及结果；
3. APK 信息无法生成时如实填写，真机未执行时填写 `NOT_RUN_BY_SCOPE`；
4. 记录未完成项、残留 TODO 和 Git 状态；
5. 同步更新整改报告并包含前序能力回归矩阵；
6. 未满足全部验收标准时保持 `IN_PROGRESS`，不得写 `SOFTWARE_COMPLETE`。

## 已完成整改计划：ROI 移动/缩放整改（2026-09-03）

用户根据多零件、多视角、逐 View ROI 只读审计结果，要求先修复已有 ROI 无法选中、移动、缩放和持久化的问题。本轮暂停 B3 Presence Detection，只推进这一项最小整改；完成后更新 `tasks/todo.md` 和 `docs/reports/b2/` 对应报告，暂停等待验收。

### 审计结论与依赖

- `PartListScreen → PartDetailScreen → TemplateDetailScreen → RoiEditorScreen` 已是当前活动导航链路。
- `RoiDefinitionEntity.templateId` 和 `RoiDao WHERE templateId = :templateId` 已提供 View 级隔离；`InspectionRepository.updateRoi` 已存在，但当前 UI 没有调用。
- `RoiEditorScreen.kt` 的已有 ROI 移动/缩放回调仍是 TODO 空回调，Canvas 没有完成已有 ROI 命中、控制柄和编辑手势。
- 复用现有实体、DAO、Repository、`normalizedRect` 和真实图片 `contentRect`；除非测试证明必要，不改 Room schema。
- 前序必须保留：新 ROI 绘制/保存、取消、删除基础路径；多 View 的 `templateId` 隔离；真实图片 contentRect 映射；唯一 CameraX 架构。

### 实施任务（按依赖顺序）

1. [ ] ROI 命中与显示：在 Canvas 中将指针位置映射到 `contentRect`，命中已有 ROI 后设置 `selectedRoiId`，为选中 ROI 绘制清晰的边界/四角控制柄。
2. [ ] 移动与缩放：实现已有 ROI 的拖拽移动和四角缩放；统一做最小尺寸、边界约束和 left/top/right/bottom 合法性校验。
3. [ ] 持久化：在 `RoiEditorViewModel` 增加移动/缩放更新方法，调用 `InspectionRepository.updateRoi`，更新本地状态并保证离开后重新加载结果一致。
4. [ ] 回归测试：覆盖新增、更新、删除、取消、0..1 边界、序列化和不同 View 的 ROI 隔离；测试应证明 updateRoi 被调用且保存值可重新读取。
5. [ ] 收口与报告：执行三条 Gradle 命令，更新任务完成清单和整改报告；不执行 adb 或真机验收。

### 验收标准

- 已有 ROI 可点击选中，移动和四角缩放均有真实 UI 响应，不存在空 TODO 回调。
- 每次移动/缩放均将矩形约束在图片实际 `contentRect` 内，并持久化为 0..1 范围的 `normalizedRect`。
- 重进同一 View 后位置和尺寸保持；切换到其他 View 时不会读取或修改前一 View 的 ROI。
- 新增、取消、删除和已有前序模板配置流程不回归；单个 ROI 更新失败时保留可解释错误，不伪造成功状态。
- `:app:compileDebugKotlin --no-daemon`、`:app:testDebugUnitTest --no-daemon`、`:app:assembleDebug --no-daemon` 全部通过。
- 完成后必须回填 `tasks/todo.md` 的完成清单、实际修改文件、测试结果、未完成项和 Git 提交状态；未获明确授权不得提交 Git。

### 预计文件范围

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`（仅在现有接口不足时）
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt`
- `docs/reports/b2/` 下的 ROI 整改报告

### 执行完成回填要求

执行 Agent 完成实现后，必须在 `tasks/todo.md` 中：

1. 逐项勾选已经由代码和测试证明的完成清单；
2. 填写实际修改文件、真实测试命令及结果；
3. 记录 APK 信息/真机范围；本任务不执行 adb 时明确写 `NOT_RUN_BY_SCOPE`；
4. 写明未完成项、残留 TODO 和 Git 是否提交；
5. 同步更新 `docs/reports/b2/` 整改报告，并在报告中包含前序能力回归矩阵；
6. 未满足全部验收标准时保持 `IN_PROGRESS` 或标记为回归整改中，不得写 `SOFTWARE_COMPLETE`。

## Overview

B1 共享 CameraX 已完成技术验收。B2 模板导入、透明叠加、模板配置层级、模板拍摄、ROI 移动/缩放和 ROI 属性选择已完成对应软件任务。当前只执行“拍照后人工确认 + 持久化”，不并行推进 Detector、完整结果包导出或 B3 Presence Detection。

## Architecture Decisions

- 唯一 CameraController 管理 CameraX 所有权，业务分析器彼此独立。
- 完整取景优先：4:3 流、竖屏 3:4、FIT_CENTER、记录 content rect。
- 当前任务和阶段门禁只在 `tasks/todo.md` 维护，历史报告不再声明进度。
- 源码按 feature 渐进整理，不进行一次性大搬迁。

## Task List

### 历史阶段：按采集批次导出照片 ZIP + UI 压缩

**阶段 1：按采集批次导出** — ✅ SOFTWARE_COMPLETE（待验收）

- [x] CaptureBatchEntity + CapturedPhotoEntity 实体定义
- [x] CaptureBatchDao + CapturedPhotoDao DAO
- [x] AppDatabase 版本 3→4，MIGRATION_3_4
- [x] InspectionRepository 新增批次/照片 CRUD
- [x] PhotoExportService 重写为 exportBatchToZip(batchId)
- [x] LiveInspectionScreen 拍照时自动创建批次、记录照片
- [x] TraceRecordsScreen 显示采集批次列表，per-batch 导出按钮
- [x] 切换零件时自动重置批次
- [x] 三条 Gradle 命令全部通过
- [x] 更新 docs/reports/b2/PHOTO_ZIP_EXPORT_REPORT.md

**阶段 2：压缩采集页控件** — ✅ SOFTWARE_COMPLETE（待验收）

- [x] 压缩 TemplateOverlayControls：minHeight 48→36dp，IconButton 48→32dp，icon 20→16dp，padding 12→8dp
- [x] 压缩 TemplateReferenceSection：padding 8→4dp，spacedBy 6→4dp，view row minHeight 40→28dp
- [x] 压缩 CaptureActionBar：button height 48→40dp，icon 24→18dp，fontSize 16→14sp
- [x] 压缩 TemplateSelector：minHeight 48→28dp，fontSize 11sp
- [x] 压缩 AllViewsCapturedCard：icon 24→18dp，padding 压缩
- [x] 压缩拍照状态提示：minHeight 36→28dp，icon 20→16dp
- [x] 不修改 CameraPreview、模板图显示、FIT_CENTER、contentRect、CameraX
- [x] 三条 Gradle 命令全部通过
- [x] 更新 tasks/todo.md

### Checkpoint：阶段 1 + 2 验收

- [x] 阶段 1 所有验收标准已由代码和测试证明
- [x] 阶段 2 所有验收标准已由代码和测试证明
- [ ] 用户验收阶段 1 + 2
- [ ] 报告记录实际修改文件、测试结果、真机范围、未完成项和 Git 状态
- [ ] 等待用户验收后再决定是否提交 Git

### 已完成任务：模板配置入口简化

- [x] 已提交 `866c23fc`
- [x] 模板配置页只保留“新建零件”入口，模板包导入独立页面保持可用

### 已完成整改任务：ROI 移动/缩放整改

- [x] 已有 ROI 可点击选中并显示编辑状态
- [x] 已有 ROI 支持拖拽移动和四角缩放
- [x] 移动/缩放受图片 `contentRect`、最小尺寸和 0..1 坐标约束
- [x] 移动/缩放调用 `InspectionRepository.updateRoi` 并在重进页面后保持
- [x] 新增、取消、删除和多 View `templateId` 隔离行为回归通过
- [x] 补齐 ROI 创建/更新/删除/边界/隔离自动化测试
- [x] `compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug` 全部通过
- [x] Agent 回填 `tasks/todo.md` 完成清单和 `docs/reports/b2/` 整改报告

### Checkpoint：ROI 整改完成

- [x] 所有验收标准已由代码和测试证明
- [x] 报告记录实际修改文件、测试结果、真机范围、未完成项和 Git 状态
- [ ] 等待用户验收后再决定是否提交 Git

### B1：共享 CameraX 收口

- [x] Task 1：审计活跃页面并归档未引用旧 Screen
- [x] Task 2：接入真实 CameraPreview，完成权限、状态、画幅与 content rect（真机提交 `28d692d`）
- [x] Task 3：完成 CameraController 模式重绑、互斥与生命周期（真机验收完成）
- [x] Task 4：真实 ImageCapture 与 MobileImageStore 收口及最终验收（已验收，提交链 `48f7587` → `566acaea` → `3a04b658`）
- [x] **Task 5：B1 完整验证** — 已验收，APK SHA-256 `235f8aa8c4d65b365a93bff021041e43dca86d5eb4b121ba9d13ebd3f436768f`；详见 `TASK5_FINAL_VALIDATION_REPORT.md`

### 已验收 Task 2：CameraPreview 状态与画幅

执行顺序：

1. **入口收敛**：让 `AppNavigation` 进入真实 CameraPreview 实现；移除 `PlaceholderScreens.kt` 中的相机占位职责，保留检测结果和模板详情占位。
2. **状态模型**：使用明确状态表达无权限、请求中、初始化、ACTIVE、临时拒绝、永久拒绝和错误；回调必须由真实 CameraX/权限事件驱动。
3. **权限恢复**：临时拒绝可再次请求，永久拒绝可进入系统应用设置，返回页面后重新检查权限；错误态提供有效重试。
4. **完整画幅**：`PreviewView.ScaleType = FIT_CENTER`；优先统一 4:3 UseCase；竖屏按实际流显示 3:4 内容，允许 letterbox，不允许裁切、拉伸或固定 60/40 强撑。
5. **坐标基础**：计算 PreviewView 内真实图像 `contentRect`；Debug 模式记录 View 尺寸、流尺寸、旋转、缩放方式和 content rect。
6. **验证收口**：编译、单元测试、生成并安装当前 APK；在至少 `360x800`、`412x915` 和一台真机验证四边标记完整、圆形不变形、权限分支可恢复。

Task 2 交付物：源码改动、更新后的 `tasks/todo.md`、`docs/reports/b1/TASK2_CAMERA_PREVIEW_REPORT.md`、测试命令和结果、APK 路径/时间/大小/SHA-256、真机截图或录屏证据。

Task 2 已完成并通过真机验收，证据位于 `docs/reports/b1/evidence/task2/`。

### 已验收 Task 3：CameraController 模式与生命周期

当前状态：已通过累积真机验收，最终修复提交 `bb22f1e`，证据位于 `docs/reports/b1/evidence/task3/`。

执行顺序：

1. **前序回归恢复**：以 Task 2 提交 `28d692d`、代码收口 `a037a08` 和证据截图为基线，把完整权限、OPEN 状态、`FIT_CENTER`、实际流变换、contentRect、诊断和校准能力适配到当前 CameraController；不得整文件回滚。
2. **画幅复验**：当前设备容器约 `1080x1039` 时，真实 3:4 图像区域应约 `779x1039` 并左右留边；四角位于图像区域、中央圆不变形、上下内容不裁切。
3. **坐标复验**：LiveInspection 的轮廓/ROI 叠加接收真实 contentRect，只在图像区域绘制；letterbox 不得出现检测图形。
4. **测试入口清理**：测试 Activity 从 `src/main` 和主 Manifest 移出，只保留在 androidTest/debug；冻结并不得使用超出当前阶段的 `tools/contour_extraction/`。
5. **保持生命周期成果**：重新运行并发、模式互斥、ImageProxy、observer 和 20 轮模式测试，确认恢复预览没有破坏 Task 3 已完成部分。
6. **最终真机循环**：在修复后的同一 APK 上执行 Tab 往返 10 次、前后台 10 次，并重新检查 8 项 logcat 禁止模式。
7. **累积验收**：报告同时给出 Task 2 回归矩阵和 Task 3 生命周期矩阵；任一项失败都不得进入 Task 4。

Task 3 已完成。其权限、画幅、contentRect、会话互斥和生命周期能力继续作为后续累积门禁。

### 已验收 Task 4：真实拍照与存储收口

当前状态：已验收，提交链 `48f7587` → `566acaea` → `3a04b658`，证据位于 `docs/reports/b1/evidence/task4/`。

Task 4 已完成全部验收项：会话安全快门、capture request token 机制、.part 文件事务、17 项拍照异步测试、8 项存储测试、真机 20 张连续拍摄。

### 已验收 Task 5：B1 完整验证

状态：✅ 已验收（APK SHA-256 `235f8aa8c4d65b365a93bff021041e43dca86d5eb4b121ba9d13ebd3f436768f`，HONOR YAL-AL10, ERLDU20429005890）。

执行结果：

1. **JVM 测试**：78/78 通过（CameraControllerTest 40 + CameraControllerTakePhotoTest 17 + MobileImageStoreTest 11 + ContentRectCalculatorTest 10）
2. **APK 构建与安装**：BUILD SUCCESSFUL，adb install Success
3. **冷启动 10 次**：0 FATAL EXCEPTION
4. **Tab 往返 10 轮**：无黑屏、重复绑定
5. **前后台切换 10 次**：无崩溃
6. **日志门禁 12 项**：0 违规（1 项系统误报）
7. **截图证据**：01_cold_start.png 用户视觉复核通过
8. **文档收口**：plan.md、todo.md、B1 报告已更新

详见 `TASK5_FINAL_VALIDATION_REPORT.md`。

### Checkpoint：B1

- [x] `tasks/todo.md` 的 B1 验收全部通过
- [x] 用户确认进入 B2

用户已确认进入 B2。B2 Task 1 软件层面已完成，物理验收不阻塞后续功能开发。B2 Task 2 模板导入 + 透明叠加 MVP 软件层面已完成。

### B2：DPM 迁移

- [x] **Task 1：旧 DPM 识别链迁移与实时扫码闭环** — **SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-02）。APK SHA-256 `6e2ca7d3f573c1da1af7f9180c23a0dbe8f2f9081eafff5ccf466dcb09c051cc`。JVM 208 项（203 passed / 0 failed / 5 skipped），Instrumented 30/30 passed，冷启动 10/10 passed。4 项物理验收标记为 `PENDING_PHYSICAL_DPM_SAMPLE`。
- [x] **Task 2：旧模板导入 + 模板透明叠加 MVP** — **SOFTWARE_COMPLETE**（2026-09-02，提交 `bdf1bd89`）。V1-1 导入 + V1-2 overlay + alpha slider 完成。JVM 242 项（237 passed / 0 failed / 5 skipped）。遗留：legacy ROI 未迁移、imageFiles[] 仅取首图。
- [ ] **Task 3：DPM 绑定、已绑定码切件和冲突处理** — 源码路由已接入；模板配置绑定保存、冲突拒绝和未知码提示已有自动化/流程证据；同一最终 APK 的已绑定码实际切换、切换后从 View 1/N 重新开始及完整累积 instrumented 回归待补。物理 DPM 样本验收仍为 `PENDING_PHYSICAL_DPM_SAMPLE`。

B2 Task 1 固定边界：使用唯一 CameraController 的 `DPM_SCAN` 模式，忠实迁移旧工程已经可用的生产识别链。顺序固定为中心 ROI/全图的 ZXing `DataMatrixReader` 主解码（含旧预处理策略与双极性尝试）→ ML Kit DATA_MATRIX 兜底 → 满足旧门控条件时执行网格重建兜底；同时保留帧节流、single-flight、响应门、连续 miss 对焦、取消和停止后不回调。”扫一扫”只进入实时扫码，不提供 DPM 相册选图、码图导入或对应权限/路由。

### B2 Task 3：模板拍摄、缩略图、重拍、排序

状态：**SOFTWARE_COMPLETE**（2026-09-03）。源码、自动化测试和工程文档已完成，真机验收待下一轮指令。

实际修改文件：MobileImageStore（模板图片存储）、TemplateDao（批量排序）、InspectionRepository（新增方法）、TemplateCaptureViewModel（新增）、TemplateCaptureScreen（新增）、Screen/AppNavigation（路由注册）、TemplateConfigScreen（缩略图+排序+拍摄入口）、TemplateDetailScreen（重拍按钮）、MobileImageStoreTest（7 项新测试）。

JVM 325 项通过（320 passed / 0 failed / 5 skipped）。APK SHA-256 `56e390a067ccd1a040ea05b86b9743bc185bf2c1215630e7fc0f4f35a9e7f495`。

网格重建尺寸模式也属于旧版基线：默认 `AUTO` 公平交错尝试 16×16、18×18、20×20，并保留 `DIM_16/DIM_18/DIM_20` 固定模式、原候选配额、解析回退和设置持久化。尺寸模式只约束网格重建，不限制扫码框 ZXing、全图 ZXing、预处理或 ML Kit。

验收使用旧 App 与新 App 对同一组现场/打印 Data Matrix 样本做 A/B 对照。新 App 至少保持旧 App 的可识别样本集合、防连扫行为和可接受响应时间；未通过对照前不得以“基础扫码已成功”宣布 DPM 迁移完成。

B2 Task 1 连续执行检查点：

1. 纯 Kotlin 尺寸模式、响应门、网格门、取消和设置快照。
2. 旧 OpenCV 预处理、质量门控、ImportedDpmScanner 与网格重建。
3. ZXing/ML Kit 真实适配器与完整旧 DpmAnalyzer 行为组合。
4. DpmFrameAnalyzer 接入唯一 CameraController 的 DPM_SCAN。
5. 扫码页面、扫码框 contentRect/rotation ROI 映射和现场采集导航。
6. 自动化、真机扫码框、CameraX 累积回归及旧/新 App 同样本 A/B 验收。

所有真机检查点先执行 `tasks/todo.md` 顶部“未来真机验收门禁”。新工程验收只能显式启动 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`；桌面图标、最近任务或旧包 `com.wearable.inspection` 产生的证据无效。旧 App 仅在标注清楚的 A/B 对照轮次中单独启动，并在切换前停止另一包。`connectedDebugAndroidTest` 返回后必须假定新包已被卸载，无论测试成功或失败都先重新安装并显式启动主 APK、核对新旧 PID 与前台包，再继续或报告失败。

各检查点通过测试后允许自动继续并分别提交；任一失败立即暂停。DPM 识别链的软件迁移已完成，但在真实已绑定码切件和物理样本验收补证前，不得把 DPM 绑定/切件整体标记为最终验收完成。物理验收不阻塞后续非 DPM 软件开发。

### B3：钢印 OCR 迁移

- [x] Phase 1：核心算法迁移（提交 `0df8e9c5`，10 source +9 test files，66 OCR tests）
- [x] Phase 2：CameraX/UI 集成（SOFTWARE_COMPLETE，2026-09-02）— StampOcrFrameAnalyzer + StampOcrViewModel + StampOcrScreen + Navigation
- [ ] 真机 OCR 拍照 + 识别 + 人工确认流程验证（需要钢印样品）

### V1 可交付闭环（当前优先目标）

**Contour-based live alignment**: DEFERRED / POST-MVP。轮廓提取成熟度不足，继续优化会阻塞可交付版本。V1 改为模板原始图片透明叠加 CameraX 实时画面。`tools/contour_extraction/` 工具和数据保留但不进入 V1。

V1 MVP 数据流闭环：

```
模板导入/拍摄 → ROI 配置 → 模板原图透明叠加辅助现场取景 → 拍照 → 模板/实拍双图比对 → 本次 ROI 微调 → ROI 检测 → 保存结果
```

1. [x] **导入旧模板包**：DirectoryTemplateImporter 解析目录 + TemplateImportService 事务编排 → PartEntity / InspectionTemplateEntity / 模板图片文件（提交 `bdf1bd89`）。遗留：legacy ROI 未迁移、imageFiles[] 仅取首图；模板 EXIF 方向归一化仍需补证/补实现。
2. [x] **模板透明叠加**：CameraX live preview + template image overlay，contentRect 内绘制，保持正确宽高比，opacity slider (0f~0.8f, 默认 0.45f)（提交 `bdf1bd89`）。已支持按视角连续拍摄，但拍摄后的照片仍未进入比对/检测闭环。
3. [ ] **拍后比对**：CaptureComparisonScreen — 模板 vs 实拍，切换/叠加/opacity、blink、缩放和平移；区分 templateRoi vs sessionRoi。当前尚未开始，是下一软件任务。
4. [ ] **ROI 人工微调**：拖动矩形 + resize handles，当前 session ROI 不覆盖模板 RoiDefinitionEntity
5. [ ] **ROI → Detector → Result**：crop ROI bitmap → AlgorithmRegistry → detector → per-ROI result → persist
6. [ ] **结果查看**：Overall PASS/FAIL，per-ROI name/algorithm/PASS/FAIL/score/error

DEFERRED / POST-MVP（不阻塞 V1 交付）：
- 实时主体轮廓提取与投影
- 依赖主体轮廓的自动姿态匹配（SIFT/单应性）
- ALIGNED/LOST 自动对齐结果作为拍照门禁
- ROI 自动跟踪

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| 多页面重复绑定相机 | 高 | 唯一 CameraController 与模式状态机 |
| FIT_CENTER 后坐标映射错误 | 高 | 保存 content rect 并用边缘标记测试 |
| 报告与真实状态冲突 | 中 | 进度只由 todo 验收项决定 |
| 一次性目录重构破坏构建 | 中 | 按 feature 小批移动并逐次编译 |
| 新旧 App 共存时误开旧包 | 高 | 完整组件名启动、前台包校验；旧包证据不得计入新工程验收 |

## 2026-09-04 追加优化计划：采集过渡与模板包闭环

### 目标

在当前“单零件多 View 人工确认 + ZIP 导出”任务内，收口现场采集与 ROI 确认的页面过渡，并补齐模板包的导出、删除和导出包再导入能力。

### Task 1：采集页面过渡收口

- 现场拍照成功后，隐藏 LiveInspectionScreen 的拍照操作栏，直接进入 ViewConfirmationScreen；不改变照片落库、batchId、photoId 和 ROI 分支。
- ROI 确认保存事件触发后，隐藏确认页操作栏并只执行一次导航；根级三 Tab 导航仅在三个一级页面显示。
- 补充源码契约测试，覆盖过渡期间没有重复“拍照”/“确认并继续”操作层，及确认后返回现场采集页。

### Task 2：模板包格式与数据闭环

- 复用现有 `TemplatePackageImporter`、`DirectoryTemplateImporter` 和 `TemplateImportService`，扩展 manifest 保存每个 View 的图片、顺序、模板 ID 和全部 ROI 字段（含 `targetType`）。
- 新增纯 JVM `TemplatePackageExporter`，使用标准 `java.util.zip`/`org.json`，缺失图片时明确失败，不生成不完整包。
- 导入导出包时恢复零件、DPM、模板、图片及 ROI；保留旧包无 `rois` 字段的兼容行为。

### Task 3：模板包管理 UI 与删除

- 在现有模板包页面列出按零件分组的模板包，提供导出到 SAF、删除确认和成功/失败状态。
- Repository 按稳定 `partId` 删除该零件模板、ROI 及受管理模板图片，不误删采集照片或历史批次。
- 补充 exporter/importer 和删除语义的 JVM 契约测试；本轮按项目限制不运行 Gradle、ADB 或真机。

### 验收边界

- 不新增 CameraX、批次模型、ROI 模型或自动检测；不伪造 PASS/FAIL。
- 不修改旧工程，不清理工作区历史脏改动，不提交 Git。

## 后续计划：检测与 DPM 证据进入追溯结果包

状态更新（2026-09-16）：本节原始计划已部分实现。DPM 成功证据合入批次 ZIP 已由用户 `USER_ACCEPTED`；ROI 最终结果语义与改判照片/CSV 回链为当前唯一 `IN_PROGRESS` 任务。更早的 `NOT_IMPLEMENTED` 与 Android 尚未接入文字均为历史计划状态，以本文末尾 2026-09-16 更新及 `tasks/todo.md` 为准。DPM 人工码值复核仍因缺少可信真值不排期。

### 目标与现状

当前结果包扩展目标是保留各 View 原图、现有 Excel 兼容 CSV、已有 ROI/总体人工确认，并将已持久化、可独立导出的 DPM 扫码图像证据和原始读码状态关联进该采集结果包。

**执行顺序**：以 `tasks/todo.md` 顶部当前任务为唯一入口。透明度、DPM 图像证据留存/独立 ZIP 和 DPM 成功证据批次关联均已验收；当前执行 ROI 最终结果/改判证据图片任务。NanoDet runtime 与静态 ROI 推理已完成并验收；DPM 人工码值复核因缺少可靠真值不排期。

本轮文档规划核对到的现状：

- `ViewRoiConfirmEntity` 已关联 `batchId`、`photoId`、`viewIndex`、`templateId` 和 ROI，并保存 ROI 像素框、每 ROI 人工结果、总体人工结果及确认时间；`softwareResult` 当前允许为空，现有 UI 保存时为 `null`。
- 当前 ZIP 包含采集批次的原始照片和 `inspection_result.csv`。CSV 可由 Excel 打开，但目前不是包含模型框、置信度、误检/漏检结论和 DPM 证据的完整追溯包。
- `DpmFrameAnalyzer` 已捕获会话成功读码对应帧或退出前最后有效帧；`dpm_scan_evidence` 按稳定 `scanSessionId` 持久化原图、ROI 裁剪图、码值/状态和来源。追溯记录页已有独立 DPM 证据 ZIP 导出；本节仍待办的是将这些证据并入采集批次结果 ZIP。
- **目前不支持 DPM 人工复核，也不安排该流程**：除界面未提供历史证据与人工码值确认外，目前没有准确、独立的 DPM 码真值供人员据以判断读码是否正确。已绑定的 `Part.dpmCode` 只是业务绑定值，不能冒充实物码真值。当前需求重点是保存每次扫码的有效图像帧，供日后优化识别算法。

### 产品与数据规则

- 一次 DPM 扫码会话最多留一组主证据：会话内成功读码时绑定产生该结果的准确帧；没有成功读码时，在用户退出前选取最近一张有效分析帧。两种情况都在停止 analyzer/CameraController 前完成帧快照并持久化，另存对应扫描 ROI 裁剪图；不得保存连续预览帧序列。
- DPM 证据记录至少保留稳定 `scanSessionId`、源帧时间、成功帧/退出末帧类型、原图和裁剪图路径、原始解码内容（未读出为空）、成功/未读出状态、解码来源及可选零件/采集批次关联。当前不保存人工确认码、人工异常类型或人工复核状态；不依据没有真值支持的比较标注 `CONTENT_MISMATCH`。
- DPM 源图来自现有 CameraX 分析帧，不另建 CameraX 或通过退出后才拍一张不对应解码结果的照片。异步解码、退出和 `ImageProxy` 生命周期必须明确排序；帧数据要在 Bitmap 回收前复制/编码并落盘。
- DPM 解码顺序、预处理轮转、门控、帧节流、对焦和旧版网格兜底完全沿用现有生产实现；证据采集不得改变识别策略。模板样本相册导入和 DPM 相册码图导入仍是不同功能，后者不增加。
- DPM 扫码结果与采集批次使用显式稳定 ID 关联，不按时间窗口、列表位置或零件名猜关联。未关联到批次的扫码证据仍可在 DPM 记录/诊断导出中查看；删除采集批次不得连带删除独立 DPM 算法样本。
- NanoDet ROI/App 实现目前尚未开始；用户已授权完成现有 ONNX 的 NCNN 转换与桌面三方对照，该阶段已完成。Android ABI/NDK/runtime 核验及后续 ROI 集成按本文件的 NanoDet 计划执行；
- 未来逐 ROI 人工核对必须放在现有 ROI 人工确认页：展示每个 ROI 的真实模型 OK/NG，人工只核对并可双向改判（模型 OK 可改 NG，模型 NG 也可改 OK）。不增加误检、漏检或“其它”分类选择步骤。
- 对每个 ROI 分别保存模型原始 OK/NG 与人工最终 OK/NG，不允许人工值覆盖模型值。若改判，记录对应现场照片和具体 ROI（以及现有 batch/view/template 稳定关联）、改判时间及检测结果图；未改判的模型结果也应可区分为已核对且一致与待核对。
- 整张照片总体 OK/NG 始终由人工独立选择，不能由 ROI 结果计算、覆盖或推导。
- 近期扩展 ZIP 时只覆盖现有照片、已有 ROI/总体人工确认、DPM 原图/扫描 ROI 裁剪图及原始读码状态/内容；不包含 DPM 人工复核记录或尚未实现的模型 ROI 结果。保留现有 CSV 字段兼容。
- ROI 检测获授权并接入真实输出后，后续结果包任务必须纳入逐 ROI 模型原始 OK/NG、人工最终 OK/NG、是否改判、改判时间，以及对应照片、ROI 和检测结果图的稳定关联；模型值与人工结论分字段导出，不能被改判覆盖。所有文件使用稳定 ID/manifest 关联，不因一行对应多个对象而丢数据。

### 按顺序执行的任务

#### 已完成：DPM 成功帧与未读出退出帧留存

**状态**：**DONE**；成功时保存准确读码帧，未读出时保存退出前最后有效帧及扫描 ROI 裁剪图。该项不改扫码算法；实现和测试记录见 `docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`。

**验收标准**：

- 成功读码记录与产生该读码的准确帧、码值、解码来源和时间关联；离开时无成功结果则保存最后有效分析帧并明确标为未读出。
- 两种路径均保存可打开的原始帧和扫描框裁剪图；每会话最多一组，不保存视频/连续帧。
- 用户退出时先完成选帧和受管理文件写入，再停止分析器/清理 CameraController；迟到的帧或结果不得复活已结束会话。
- Room Migration、文件清理/写入失败处理和可选 `partId`/`batchId` 关联明确；删除批次不删除独立 DPM 证据。
- 自动化覆盖成功帧精确关联、退出时无读码取末帧、退出竞态、裁剪坐标、文件/数据库关联、批次隔离与解码回归契约。保持唯一 CameraController，不运行真机验证，除非后续验收任务另行安排。

**优先审计文件**：`dpm/DpmFrameAnalyzer.kt`、`dpm/DpmScanViewModel.kt`、`ui/screens/DpmScanScreen.kt`、`camera/CameraController.kt`、现有 `data/entity`/DAO/Repository/Room migration、DPM analyzer 测试及 DPM B2 报告。

### 已完成：模板叠加默认透明度为 0%

状态：**USER_ACCEPTED**。首次进入时 alpha 为 0，真实相机画面正常显示；现有滑杆仍可调节透明度。验收和测试记录见 `docs/reports/b2/TEMPLATE_OVERLAY_ALPHA_ZERO_REPORT.md`。本节原 Agent 指令已过期，不再执行。


#### DPM 人工码值复核：暂不排期

**原因**：目前没有准确、独立的实物 DPM 码数据作为读码真值，人工无法可靠确认模型读码内容正确与否。当前不开发人工码值录入、读码对比、异常定性或复核状态流程；`Part.dpmCode` 不能当作真值。

当前可执行范围仅为 DPM 扫码会话图像留存：成功时留存精确成功帧，未读出时在退出前留存最后一张有效帧，并保存原始读码内容/状态。若以后出现可信真值数据且用户重新提出需求，再另行评估人工复核。

#### NanoDet 模板 ROI 检测与人工改判（历史计划状态）

模型转换、Android runtime、静态 ROI 推理、确认/持久化及基础 CSV 元数据已在后续阶段完成；此处更早的 `ANDROID_APP_INTEGRATION_NOT_STARTED` 状态已过期。当前仅剩 `tasks/todo.md` 顶部的最终结果语义和改判照片/CSV 回链任务，阈值校准另行排期。

后续 UI 语义已确认：只对 `NUT/THREAD` ROI 显示真实模型 OK/NG 建议，用户可双向改判；整张照片总体 OK/NG 仍由人工独立选择。模型原始值与人工最终值分字段保存，改判记录稳定关联照片和 ROI。`FEATURE` 不由本模型检测。详细调用边界与验证见下方“新增计划：NanoDet 接入模板 ROI 与逐 ROI 人工改判”。


#### 第三任务：结果 ZIP 与 Excel 兼容表扩展

- ZIP 继续包含全部 View 原图和现有结果表，并在近期范围内添加已持久化的 ROI/总体人工确认数据，以及 DPM 原图/扫描 ROI 裁剪图和原始读码状态/内容；不添加 DPM 人工复核记录，也不要求尚未授权/实现的 ROI 模型检测输出。
- 每个文件在 manifest/表格中关联稳定 `batchId`、`photoId`、`scanSessionId`、`partId`、`templateId`、View、ROI 和现有复核 ID；未关联的 DPM 记录不得被静默归入某批次。
- CSV/Excel 兼容数据保留现有 15 列语义和照片行；需要时增加专用表或文件，避免一对多关联丢失。缺图、无 ROI、未读出、现有 ROI 人工未确认、导出失败均有明确状态。
- 测试 ZIP 实际目录与文件、CSV/manifest 外键可回链、原图不重复、现有人工确认数据完整、DPM 成功帧/未读出退出帧及原始读码状态完整、批次筛选与删除隔离。未来 detector 导出须在 ROI 检测另获授权后单独验收。

### 检查点与风险

- DPM 留帧先于算法调参：先取得有代表性的成功/失败真实图像，再另立离线算法优化任务；不在本轮改变 ZXing/ML Kit/网格重建链。
- 检测置信度只使用 detector 原始输出；若当前算法没有可解释的 score，存空并注明算法不提供，不把类别或阈值伪装成置信度。
- ROI 检测算法尚未接入 Android；本轮只完成模型转换和桌面对照，
- DPM 帧图可能增加本机空间和 ZIP 体积；先一会话一组证据，不保存预览视频，并报告文件大小。后续删除/保留策略不得未经用户授权清除优化样本。
- 各任务仍遵守单任务验收：逐项更新 `tasks/todo.md` 与 `docs/reports/b2/`，当前任务以外的工作不并行实现，不提交 Git，完成后暂停验收。

### DPM 扫码证据留存：已完成

本节原 DPM 执行指令已过期，不再作为 Agent 入口。扫码会话证据采集和独立 ZIP 导出均已完成；结果与验证见 `tasks/todo.md`、`docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md` 和 `docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`。后续若要并入采集批次 ZIP，按上方“后续计划”另设唯一任务，不重复实现扫码证据留存。

## 新增计划：NanoDet 接入模板 ROI 与逐 ROI 人工改判（历史实施计划）

状态更新（2026-09-16）：本节原计划中的 ONNX 转换、Android runtime、静态照片 ROI 推理、确认持久化及基础结果 ZIP 元数据已在后续阶段完成；原 `ANDROID_APP_INTEGRATION_NOT_STARTED` 仅表示 2026-09-14 当时状态。当前唯一待执行工作为最新口径的单一最终 `result` 与改判 ROI 图片/CSV 回链，详细执行顺序见本文末尾“2026-09-16 当前任务指针与 ROI 执行计划”。

### 目标与现有依据

- 使用已导出的 NanoDet-Plus 模型，将模板 ROI 的 `targetType` 连接到检测类别：`NUT → class 0 / nut`、`THREAD → class 1 / thread`；本模型不支持 `FEATURE`，遇到此类 ROI 显示“当前模型不支持”，不得套用螺母/螺纹结果。
- 用户拍照后沿用现有 `ViewConfirmationScreen`：每个 ROI 显示裁剪图、模型建议 OK/NG、目标类别、置信度和检测框；用户仍须人工选择最终 OK/NG，允许双向改判。整张照片总体 OK/NG 仍由人工独立选择，不由 ROI 汇总。
- ONNX 已导出，不重复从 `.pth` 导出：输入 `data` 为 float32 `[1,3,416,416]`，输出 `output` 为 `[1,3598,34]`；当前 ONNX/PyTorch 两张回归图原始张量最大绝对差约 `1.24e-5`。这些事实和图像阈值观察详见 `docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`。
- 工程已有 `RoiDefinitionEntity.targetType`、`RoiCoordinateMapper`、`ViewConfirmationViewModel`/`ViewConfirmationScreen`、`ViewRoiConfirmEntity.softwareResult` 和 ZIP/CSV 导出。确认页当前按模板 ROI 裁现场照片，保存人工 ROI 结果和独立总体结果；`softwareResult` 当前为 null。`RoiInspectionRecordEntity` 关联 `InspectionSessionEntity`，并不直接具有当前采集照片的 `photoId/batchId` 链路，因此接入前必须审计其与当前采集链路的关系，避免创建平行结果体系。

### 产品判定定义与阈值

用户已明确产品规则：每个 ROI 根据模板属性检查相应目标是否存在。ROI 属性为 `NUT` 时检查 nut 类，属性为 `THREAD` 时检查 thread 类；存在对应目标且置信度达到阈值，模型建议 OK；没有检测到对应目标，或最高匹配分低于阈值，模型建议 NG。此处 OK/NG 表示“对应螺母/螺纹目标是否检出”，不扩展为螺纹损坏、变形等质量等级判断。

拟按以下规则计算模型建议：选取与 ROI 属性对应类别的最高分有效框；`score >= threshold` 时模型建议 OK，低于阈值时模型建议 NG；没有目标框时建议 NG，置信度记为空并标明 `NO_DETECTION`，不能伪造 `0`。检测器候选过滤阈值必须低于可调业务阈值，防止低分候选在计算建议状态前已被丢弃。初始默认阈值可设为 `0.37`、提供用户可调项，并在每条结果记录实际阈值/模型版本；`0.37` 是起始值，仍需用现场代表性样本评估漏检与误检。

阈值不得仅凭两张图验收：已知 `frame_00106_f1060.jpg` 有约 `0.053` 的低分 thread，阈值 `0.37` 会将其判到阈值下；`frame_00045_f450.jpg` 有约 `0.357` 的 thread，`0.37` 会抑制该框，而 `0.05` 会出现更多候选并包含已知误报。需要用代表性验证集同时统计漏检、误检和 OK/NG 混淆，确认阈值后再用于现场；两张图继续作为定向回归样例。

### 按依赖顺序实施

#### 阶段 0：清任务门禁与冻结接口

- 实施 Agent 先读取 `tasks/todo.md` 和 `tasks/plan.md`，核实唯一当前任务。2026-09-14 的转换/桌面对照已结束；开始 Android/App 源码工作前，仍须由项目维护者将后续单一任务写入 `tasks/todo.md`，不得自行并行切换或同时设多个任务为 `IN_PROGRESS`。
- 核对训练配置、类别标签与 ROI 属性映射，并固定 ONNX 路径、SHA-256、输入输出名称/shape、预处理和解码版本。产品判定语义已由用户确认：对应目标达到阈值视为模型建议 OK，未检出/低于阈值视为模型建议 NG。
- 确认模型产物的存储/加载方式、目标设备 ABI、现有 Android NDK 与 CMake/Gradle 配置；不改相机所有权、导航、模块结构或现有人工确认页路由。

#### 阶段 1：打通 ONNX → NCNN 并验证模型

- 已使用 NCNN 官方 PNNX 20260526 将现有 ONNX（不重新导出）转换为 FP32 `.param/.bin`，`optlevel=2` 产物已由 NCNN runtime 成功加载。`optlevel=0` 产物含 37 个 runtime 不支持的 `prim::ListConstruct` 节点，已标记为 `ncnn_20260526_opt0_rejected`，不得用于接入。
- 已从官方 NCNN release 准备 Android shared 库包（arm64-v8a、armeabi-v7a、x86、x86_64、riscv64）。App 当前没有 `abiFilters`、`ndkVersion` 或 CMake 设置，本机 Android SDK 未发现 NDK/CMake；尚未确认项目目标 ABI，也未进行 Android 链接/运行验证。下一阶段须先核对实际部署设备 ABI 和可用 NDK，再选择最小变更方式；不得由此重建或改造现有 App 基础框架。
- 已检查实际转换产物：239 个 NCNN 层、283 个 blob，无 `prim::` 未注册层；输入 blob `in0`，输出 blob `out0`。桌面 NCNN 实测输出 `[3598,34]`（`w=34,h=3598`），与 ONNX `[1,3598,34]` 对齐；每位置前 2 项是 sigmoid nut/thread 分数，后 32 项是 4 边×8-bin DFL logits。输入 416×416、类别 `0=nut/1=thread`、strides `[8,16,32,64]`、`reg_max=7`、`decouple_reg=True` 与配置一致。详见 B3 报告中的模型、param/bin SHA-256 和 blob/解码说明。
- 已用同一份当前 Android demo 预处理张量，对 `frame_00106_f1060.jpg`、`frame_00045_f450.jpg` 完成 PyTorch、ONNX、NCNN 原始输出及共用解码/NMS 对照；两图 NCNN/ONNX 最大绝对误差均小于 `4.0e-6`，框匹配 IoU 约 `0.9999997`，类别/数量一致。此结果是桌面 NCNN 对照，不是 Android runtime 验证；有人工框标注的代表性验证集和 Android 输出比较仍未完成，阈值 `0.37` 仍需校准。转换或 Android 输出差异未解释前，不进入 App 接入。

#### 阶段 2：把模板 ROI 裁剪接到检测器

- 只对拍照保存后的静态照片推理，不在 CameraX 预览流中增加检测器。复用当前 View 的 `RoiDefinitionEntity` 和 `targetType`，按 `normalizedRect` 映射到现场照片实际图像区域后裁剪每个 ROI 子图。
- 复用/补齐现有 `RoiCoordinateMapper`，检查照片方向、EXIF、像素边界和 contentRect 映射；不做 Homography、模板自动对齐或 ROI 自动重定位。一个 ROI 的裁剪失败时明确显示推理不可用/错误，不创建伪造 OK/NG。
- 对每个 ROI crop 使用与训练/验证一致的 BGR、mean/std、416×416 输入和等比例缩放补边。核实裁剪图坐标到现场照片坐标的逆变换，保留 ROI 内检测框与整图绝对框，便于 UI 叠加和追溯。
- 新增最小检测调用边界：输入照片 ROI crop + `targetType`，返回候选类别、全部保留的框/分数、映射坐标、耗时、阈值与模型版本。模型只用于 `NUT/THREAD`；`FEATURE` 明确不运行。推理在后台 dispatcher 执行，避免阻塞确认页。

#### 阶段 3：沿用现有确认页并持久化人工终审

- 在现有 ROI 卡片增加真实模型状态、目标类别、最高匹配置信度、阈值和框选结果图；用户可选择 OK 或 NG，模型 OK/NG 均可人工反向改判。不得把模型值预填成已确认的人为结论；最终人工值由操作人员独立确认后保存。
- 保留 `ViewRoiConfirmEntity` 中模型结果与人工结果分字段的语义：`softwareResult` 保存模型建议，`humanResult` 保存人工最终值。对框/分数/模型版本/阈值/推理状态/证据图路径的保存，先审计是否可扩展这条现有 photo/batch/ROI 记录；如需字段或 nullability 变更，使用真实 Room migration，旧行的模型字段保持未执行/null。不得在 `RoiInspectionRecordEntity` 和 `ViewRoiConfirmEntity` 各写一份无法互相追溯的副本。
- 保存模型原始检测类别、框、置信度、最终阈值、模型版本/文件摘要、推理状态、ROI 裁剪图和标框结果图路径；人工改判时保存是否与模型不同及人工确认时间。原始照片、`photoId`、`roiId`、`templateId`、`viewIndex`、`batchId` 关联不变。
- 整张照片总体 OK/NG 仍须由人工单独选择和保存；无 ROI View 不产生软件检测记录；检测异常、模型缺失、旧 ROI 未选择 targetType 均不得假装成功。

#### 阶段 4：结果包纳入 ROI 检测与改判证据

- 在现有 ZIP 中保留全部 View 原图与现有 Excel 兼容 CSV；为每张受检照片/ROI 附带裁剪图、检测框结果图和含所有检测类别/框/置信度的机器可读记录（CSV/JSON），以稳定 `batchId/photoId/templateId/viewIndex/roiId` 关联，不按文件名或顺序猜测。
- 导出模型 OK/NG、人工最终 OK/NG、阈值、模型版本、是否改判和确认时间；模型原始值不能被人工改判覆盖。改判图和原图均可从 manifest 找到。缺图、推理失败、无框、未选 targetType 与未确认状态都写清状态，不静默丢行。
- 保留现有结果包字段兼容；大于一条的检测框不得压缩成单个置信度或丢弃。修改导出服务前先审计其事务和稳定路径语义。

#### 阶段 5：回归、构建与验收

- 单测覆盖：NUT/THREAD 类别映射、FEATURE 跳过、目标框阈值边界（相等属于 OK）、无框 NG 且 score 为空、ROI crop 坐标和图像方向、框坐标逆映射、推理错误态、模型结果与人工结果独立存储、OK↔NG 双向改判、总体结果独立、Room migration、ZIP 文件/manifest 回链。
- 在同一批验证图上报告 PyTorch/ONNX/NCNN 差异及不同阈值误检/漏检；对 `frame_00106_f1060.jpg` 和 `frame_00045_f450.jpg` 单列结果。
- 软件门禁通过后按获准范围构建 APK，报告 APK 路径、大小、SHA-256、NCNN 库和模型文件体积；不执行 ADB/真机操作，除非后续任务明确授权。真机验证另作物理验收。
- 完成后更新 `tasks/todo.md` 与 B3/B2 报告，列实际文件、迁移、测试、模型和 APK 校验、前序功能回归矩阵及 Git 状态；等待用户验收，不擅自提交 Git。

### 工具链怎么解阻

工具链准备/转换记录：用户已授权使用官方 PNNX 路径；转换器和预编译 Python 运行依赖隔离在 `D:\study\Textile_defects\_ncnn_toolchain_20260526`，模型对照/预处理脚本位于 NanoDet 项目 `tools/ncnn_android/`，对照 JSON 位于 `reports/ncnn_android/`；NCNN Android shared 运行库位于 `D:\ProgramData\Android\SDK\ncnn-20260526-android-shared`。没有安装进 `yolov12` Python 环境，没有下载权重。实际转换与校验文件、版本、校验和见 `docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`。下一阶段重点不是再次下载转换器，而是确认设备 ABI、可用 Android NDK/CMake 与 shared package 的兼容性；`onnx2ncnn` 不需要并行准备。

### 当前限制和风险

- 历史风险（已关闭）：Android native runtime 与静态 ROI 推理后续已通过；详细设备、APK 与回归证据见 `docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`。
- 当前仍未完成代表性标注集阈值校准及更广设备/性能覆盖；这些不属于当前 ROI 最终结果语义/证据图任务。
- 本计划中的 OK/NG 仅表达 ROI 对应 nut/thread 目标是否检出，不评价目标损伤或加工质量；如未来需要缺陷判断，应另行训练/验证专用质量类别模型。
- 当前活动任务指针在文档间需要对齐；ROI 实施前必须确保 `tasks/todo.md` 只有一个清晰、获授权的 `IN_PROGRESS` 任务。

### 本轮计划验收

- 已完成官方 PNNX ONNX→NCNN 转换、`.param/.bin` 层/Blob/shape 审计，并用 PyTorch/ONNX/NCNN 在两张指定回归图上做同输入桌面对照；具体数值见 `docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`。
- Android app 源码、相机、导航、页面/模块框架未改；未运行 Gradle、APK、ADB 或真机；未安装 Python 依赖到现有环境。
```

## 2026-09-15 结果 ZIP 扩展收口记录

本轮唯一执行任务为：把 NanoDet ROI 检测快照、ViewConfirmation 人工终审和扫描启动时显式关联的 DPM SUCCESS 源帧/ROI 纳入 `InspectionZipExportService`，同时保留独立 `DpmEvidenceExportService` ZIP。

依赖顺序已按数据库 v8→v9 显式关联字段、Repository/导出筛选、统一 CSV、真实 ZIP 归档回归、编译/APK 构建执行。验收重点：SUCCESS-only、非空码值、合法解码源、明确相等 batchId；不使用名称/文件名/时间猜关联，不使用 lastFrameBitmap，不生成 NO_READ；模型字段与人工字段分离，多个检测框逐行保留，总体人工结果独立。

结果：定向结果 JVM 104 项通过，真实 `ZipInputStream` 批次/独立 DPM 归档及原始字节比较通过，Kotlin 编译和 Debug APK 构建通过。未运行真机或 connected tests；全量既有 13 项失败和 5 项跳过沿用执行 Agent 报告，未将其混入本轮通过数。

## 2026-09-15 结果 ZIP 任务拆分说明

为避免把 DPM 照片交付与 ROI 检测结果交付混为一个验收项，后续文档和 Agent 任务按以下两个交付项记录。本节是当前边界，覆盖前文相同主题的历史计划描述。两项必须串行推进，每次只保留一个 `IN_PROGRESS`。

### 交付项 1：DPM ECC 成功照片合并到采集批次 ZIP

- 保留现有独立 DPM 证据 ZIP，继续按 `scanSessionId` 导出。
- 采集批次 ZIP 的 DPM 部分只包含 ECC 纠错通过且码值非空的 DPM 源帧照片和同一源帧扫描 ROI 照片。
- 批次 ZIP 与独立 DPM ZIP 使用同一份受管理文件并按字节复制，不重新拍照、裁切或压缩。
- ECC 失败、未读出、超时、取消、`stop` 或旧 session 不保存、不导出 DPM 照片，也不创建 `NO_READ` 证据行。
- 本交付项的软件实现、真机闭环和用户验收均已完成（`USER_ACCEPTED`，2026-09-16）；扫码后创建批次并将成功会话证据绑定到该批次的验证细节见本计划末尾的状态更新及 `tasks/todo.md`。

### 交付项 2：ROI 检测结果、人工改判及 ROI 证据图导出

- 批次 ZIP 的 CSV/数据库元数据必须保留 `batchId/photoId/templateId/viewIndex/roiId`，模型建议、人工最终结果、`humanChangedModel`、确认时间、检测框、类别、置信度、阈值、模型版本、推理状态和照片总体人工结果。
- 最新产品口径改为：每个 ROI 只有一个最终 `result` 字段；人工不改判时写入模型结果，人工改判时写入人工结果，并额外记录原模型结果、改判结果、改判标记和改判时间。照片总体 OK/NG 仍由人工独立选择。
- 只有发生人工改判时才必须留存对应 ROI 照片，并让 CSV 正确回链；未改判不要求额外生成改判照片。当前批次 ZIP 只包含原始采集照片，`ROI图ZIP路径` 仍为空并标记“缺失：未持久化”，因此本交付项尚未按最新口径完成。
- 当前代码中的模型/人工分字段属于先前实现记录，与最新单一最终 `result` 口径不一致；后续实现和验收必须以本节最新口径为准。
- 现场人工确认页保持现有排版和固定确认栏不变，只显示零件名称、视角进度、ROI 编号、ROI 类型、ROI 图片、人工确认 OK/NG、总体结果 OK/NG、操作状态和“确认并继续”。模型结果直接体现为 OK/NG 按钮的默认选中状态，不单独显示“模型建议”文字；模型未执行/不支持时不默认选中，并显示简短状态提示。
- 后续如用户要求视觉证据，必须明确选择“ROI 原始裁剪图”或“带检测框/模型结果/人工结果的标记图”，生成真实文件并让 CSV 正确回链；不得用空路径或状态文字冒充图片。
- 本交付项当前状态由 2026-09-16 状态更新设为唯一 `IN_PROGRESS` 任务；不得与已验收的 DPM 照片交付混为一个完成结论。

### 共用限制

- 不新增第二套批次、照片、ROI、检测结果或导出模型；不改变 DPM 解码算法、NanoDet 模型、CameraX、导航或阈值校准。
- 失败、未执行、无框、`FEATURE` 不支持、未配置 `targetType`、缺图和未确认状态必须明确记录，不能伪造 OK/NG。
- 独立 DPM ZIP 和采集批次 ZIP 的 DPM 成功照片字节必须保持一致；DPM 人工码值复核不排期。

## 2026-09-16 当前任务指针与 ROI 执行计划

`tasks/todo.md` 是唯一任务状态来源。DPM 扫码证据绑定采集批次闭环已由用户确认 `USER_ACCEPTED`；当前唯一 `IN_PROGRESS` 为“ROI 最终结果语义、人工改判与 ROI 证据图导出”。本节覆盖本计划前文中 ROI 仍未接入或双字段直接作为最终结果的过期执行状态；历史测试和实现记录不因此改写。

### 执行顺序

1. **审计现状**：逐项核对 `ViewRoiConfirmEntity`、DAO、Repository、ViewModel、确认页、`InspectionZipExportService`/CSV exporter、ROI 图片存储和 URI/路径；给出当前字段映射、实际 ZIP 目录和兼容风险，再提出最小修改文件清单。
2. **对齐最终语义**：每个 ROI 一个最终 `result`。模型有 OK/NG 时人工按钮默认选模型值；未改判时 `result=modelResult`，改判时 `result=humanResult`。改判额外保留原模型结果、人工结果、改判标记和时间。照片总体结果继续由人工独立确认，不由 ROI 汇总。
3. **生成并关联改判证据**：仅改判时保存对应 ROI 照片；未改判不要求新增图片。CSV 路径必须指向 ZIP 中真实存在的 ROI 文件，并以 `batchId/photoId/templateId/viewIndex/roiId` 稳定回链。
4. **遵守确认页口径**：保留现有上下结构、排版和固定确认栏；只显示零件名称、视角进度、ROI 编号、ROI 类型、ROI 图片、人工 OK/NG、总体 OK/NG、操作状态及“确认并继续”。不显示模型建议文案、ROI UUID、分数、阈值、模型版本。FEATURE/未执行显示“部件类别暂不支持”或“模型未执行”，不默认选择且不得自动判 NG。
5. **验证与收口**：测试无改判、双向改判、总体结果独立、未执行/不支持、持久化/重载、ZIP 内图片与 CSV 回链及字段兼容；若触及 schema，覆盖真实 Room migration。按授权完成构建/真机核验后更新报告和清单，等待主协调审阅与用户验收。

### 不在本任务范围

不修改 DPM 解码/绑定、NanoDet 模型或阈值校准、CameraX、批次清理，以及批量多选导出等独立事项；不创建平行确认/结果/ROI 数据模型。一次只保留本任务为 `IN_PROGRESS`。

### Git 收口规则

用户已明确：阶段任务完成后必须提交 Git，避免工作区长期堆积。执行 Agent `mimo` 不提交；主协调在阶段验收完成后按路径审计 `status`/`diff`，只选择当前任务相关文件提交，保留其他工作区改动，不使用 `git add .`、reset、clean、stash 或回滚。

## 2026-09-18 ROI 任务主协调审计更新

~~当前状态：**IN_PROGRESS / AUDIT_REOPENED**~~（历史记录，已被2026-09-18 的 99/99 证据 supersede）。文档中 2026-09-17 的 `USER_ACCEPTED` 记录与最新可复现证据冲突，已降级为历史记录；本节为历史状态依据。当前状态以 `tasks/todo.md` 顶部为准：**软件验证完成，等待用户验收**。

只读源码审计确认核心结果语义和导出回链已经存在：`humanResult` 实际作为最终人工结果写入，导出器同时保留兼容字段并把同一最终值写入 `result`；改判证据图写入 ZIP 后，CSV 的 `ROI图ZIP路径` 使用真实 ZIP entry。当前实体没有独立持久化字段名为 `result` 的列；若产品要求数据库层也必须有规范 `result` 字段，仍需另行设计兼容 migration。若“result”仅指结果包最终列，则现有映射符合当前行为，但应在报告中明确，避免把 `humanResult` 和 `result` 误写成两个不同结论。

**[历史记录，已被2026-09-18 的 99/99 证据覆盖]** 2026-09-18 定向复跑 99 项，98 项通过、1 项失败：`ViewModelSaveLifecycleTest.dbSaveFailureCleansUpNewEvidenceFiles`。失败原因是测试 JPEG 夹具无法被 Robolectric 解码（`Quantization table 0x00 was not defined`），测试在 ViewModel 加载阶段失败，没有实际验证 DB 写入失败后的新证据清理。~~因此生命周期失败路径仍未闭环，不能提交或标记验收完成。~~

~~下一步仅处理：修复该测试夹具、重新跑生命周期测试和 ROI 定向集合，必要时补编译/APK/instrumented 证据；然后更新对应报告并等待用户验收。~~ 钢印 OCR 真机/真实样本验证按用户指示暂不纳入本任务清单。当前状态以 `tasks/todo.md` 顶部为准：**软件验证完成，等待用户验收**。
## 2026-09-20 当前任务指针：exp09 四分类 Android 检测协议、BOLT/NUTSERT 路由与阈值校准

状态：**TASK_2B_PROTOCOL_PASS / ANDROID_PARITY_PASS / TASK_3_SOFTWARE_COMPLETE / TASK_4_ANALYSIS_COMPLETE / TASK_5_REGRESSION_PASS / USER_ACCEPTED**（2026-09-21）。本节是当前执行入口，优先于本文更早的 NanoDet 二分类和 ROI 检测历史计划；历史内容保留用于追溯，不覆盖当前产品决定。

- Task 2A（桌面 parity）：✅ 已完成（yolov12 环境，用户授权环境例外）
- Task 2B（Android 34→36 协议升级）：✅ 已完成（2026-09-20）
- Task 3（BOLT/NUTSERT 路由）：✅ 软件完成（2026-09-20）
- Android parity：✅ 通过（2026-09-20；NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4；设备 YAL-AL10）

### 事实证据与产品决定

- 事实报告：`D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp09_retrain_nutsert_4class_baseline\experiment_report_4class.md`。
- exp09 四分类稳定顺序：`0=nut`、`1=thread`、`2=bolt`、`3=nutsert`。
- 当前 Android 已切换到 exp09 四分类协议：ONNX `[1,3598,36]`、NCNN `[3598,36]`；Android parity 已通过，证据为 `NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4`（YAL-AL10）。
- exp09 报告在阈值 `0.20` 的冻结外部集上给出阶段性结果；外部集只有 6 张图，bolt 没有真实标注，且固定验证集存在 source group 泄漏风险。因此 `0.20` 只能作为候选基线，不能作为最终现场阈值或完整泛化结论。
- 产品决定：`BOLT`、`NUTSERT` 扩展到 ROI 属性、持久化和检测路由；`FEATURE` 仍保留为属性，但本任务不执行检测。

### 有序执行计划

1. ✅ **协议审计**（2026-09-20）：逐项审计 `NanoDetModelContract`、输出读取、decoder/DFL/NMS、JNI/NCNN、类别索引、模型资产和二分类测试，冻结四分类 shape、列布局和类别顺序。产出：`docs/reports/b3/NANODET_EXP09_4CLASS_ANDROID_PLAN.md`。
2. ✅ **转换与 parity**（2026-09-20 Task 2A）：生成 exp09 ONNX/NCNN，记录 shape、blob、版本、文件大小和 SHA-256；用相同输入对 PyTorch、ONNX、桌面 NCNN 的原始输出与解码结果做逐级比较。**parity 通过**：PT vs ONNX max=1.22e-05, NCNN vs ONNX max=5.80e-06, IoU≥0.999。Android parity 已解除 JNI 门禁，2026-09-20 通过（NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4，YAL-AL10）。产出：`android_export/exp09_parity_results.json`、`exp09_model_metadata.json`。
3. ✅ **属性与路由**（2026-09-20）：在现有 `RoiTargetType` 和读写链路上增加 `BOLT/NUTSERT` 稳定值和中文名称，显式映射四个可检测类别；`FEATURE` 返回不支持/未执行，不默认判 NG。TEXT 列兼容新枚举，无需 migration。
4. ✅ **阈值校准**（2026-09-20）：以 `0.20` 为阶段性基线，对 0.10/0.15/0.20/0.25/0.30 按四类别统计 TP/FP/FN、precision/recall、漏检和误检；0.20 是有 GT 三类实现零 FP+零 FN 的最低阈值。bolt 外部集无 GT，仍不可评估；详见 `docs/reports/b3/NANODET_EXP09_THRESHOLD_CALIBRATION_REPORT.md`。
5. ✅ **Android 34→36 协议升级**（2026-09-20 Task 2B）：JNI `kOutputWidth`→36、decoder 4类 argmax + DFL 偏移、contract 常量更新、模型资产替换（SHA-256 已验证）、新增4类 decoder 测试。JVM 定向测试通过、compileDebugKotlin ✅、compileDebugAndroidTestKotlin ✅、assembleDebug ✅。Android parity 2026-09-20 通过（NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4，设备 YAL-AL10）。
6. ✅ **BOLT/NUTSERT 属性与检测路由**（2026-09-20 Task 3）：RoiTargetType 新增 BOLT("螺栓")/NUTSERT("铆螺母")、NanoDetDecisionPolicy.classIndex BOLT→2/NUTSERT→3、ViewConfirmationScreen/ViewModel 类别展示映射更新。TEXT 列兼容，无需 migration。定向 JVM 5 类 123/123 通过，compileDebugKotlin ✅、compileDebugAndroidTestKotlin ✅、assembleDebug ✅；全量 JVM 959 项中 14 项失败、5 项跳过，失败不在本轮修改文件。
7. **阈值校准**（2026-09-20 ✅ 离线分析完成）：以 `0.20` 为阶段性基线，在冻结外部集（6 图/8 GT）上按 5 阈值（0.10/0.15/0.20/0.25/0.30）统计四类 TP/FP/FN/P/R。**0.20 为零 FP+零 FN 最低阈值**。bolt 零外部 GT 不可评估。详见 `docs/reports/b3/NANODET_EXP09_THRESHOLD_CALIBRATION_REPORT.md`。更大规模数据和现场验证仍待补充。
8. ✅ **Android 回归**（2026-09-20）：完成 parity、四分类路由、ROI 静态推理、兼容性测试和结构化设备证据；`NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4` 通过。Task 3 软件验证、Task 4 离线分析和 Task 5 回归均已完成，并于 2026-09-21 通过用户人工验收。

### 必须保留的行为

- 模型文件、输出契约、decoder、类别路由和测试必须同步；不能以替换 `.param/.bin` 代替协议升级。
- `FEATURE` 不调用四分类 detector，不复用其他类别结果，不伪造 OK/NG。
- ROI 属性按 `templateId`、View、图片和 ROI 隔离；历史未选择属性继续保持未选择语义，不能自动猜测。
- 前序 CameraX、DPM、模板、ROI 人工确认、总体结果独立确认、ZIP/CSV 路径回链能力不得回归。

### 证据与收口要求

- 执行 Agent 只修改 MobileInspectionApp 范围内的源码、测试和文档，不修改旧工程、不提交 Git、不运行视觉图片分析。
- 报告必须列出实际修改文件、真实测试命令和结果、APK 路径/时间/大小/SHA-256、模型/NCNN parity、XML/日志/数据库/ZIP 证据和未完成项。
- `0.20` 在更多数据和独立评估完成前保持“阶段性候选”，不能写成最终现场阈值；当前软件回归已通过，仍需主协调审计、用户验收和 Git 收口。
- 用户人工视觉验收与主协调 Git 收口在结构化证据完成后进行；主协调只按路径审计并选择性提交当前任务文件。

### 明确不做

本任务不实现 `FEATURE` 检测、自动轮廓、姿态匹配、单应性对齐、ROI 自动跟踪、新 CameraX、DPM/OCR 变更、多选批量导出或批次删除；不处理 `BatchFilterAndDeleteTest`，也不重新打开已验收的 ROI 最终结果与证据图任务。

## 2026-09-21 已完成任务：既有 14 项 JVM 失败整改

状态：**USER_ACCEPTED / COMMITTED**。这是用户在 NanoDet exp09 任务 `USER_ACCEPTED` 后明确授权的新独立任务，已由主协调提交 `86d1ebd2`。`tasks/todo.md` 顶部当前指向下一阶段任务选择；本节只保留本次整改的历史目标和验收证据。

### 目标

修复上一项 Android 回归中记录的 14 个既有 JVM 测试失败，恢复全量 JVM 绿灯，同时保持已验收功能和产品边界不变。当前基线为 `959 tests completed / 14 failed / 5 skipped`，实际方法名和失败栈以本轮重跑 XML 为准。

### 依赖与分组

1. **基线与证据**：先运行全量 `:app:testDebugUnitTest --no-daemon`，保存 Gradle 输出、`app/build/test-results/testDebugUnitTest/` XML，并核实失败是否仍为 14 项。
2. **数据/模板契约**：`MultiViewPhotoPersistenceTest` 的 schema 路径与布局/状态契约；`TemplatePackageExporterTest` 的稳定 JSON 序列化契约；`TemplatePackageImporterTest` 的损坏 ZIP 与缺 `template.json` 错误语义。不得只放宽断言、删除测试或改为跳过。
3. **导航/流程状态**：`ViewConfirmationNavigationTest`、`NoRoiViewAdvancementTest`、`WorkbenchViewModelAdvanceTest`，确保回退不推进、导出回调传递、拍照/插入失败不完成视角、无 ROI 视角仍导出照片、切换零件后模板/ROI 重新加载。
4. **布局/相机/性能**：`BatchFilterAndDeleteTest`、`CameraPreviewTest`、`ViewConfirmationPerformanceTest`，确保固定提示槽位、不可见相机断开不报错、图片读取在 IO 调度器，并保留 CameraX 单一所有者与既有生命周期。
5. **收口回归**：定向测试、全量 JVM、`compileDebugKotlin`；5 个 skipped 必须列出名称和原因。除非用户另行授权，不运行 ADB、instrumented 或真机验证。

### 实施规则

- 执行 Agent 先审计再修改，提交 handback 前不得提交 Git；主协调只按路径审计和选择性提交。
- 只改 MobileInspectionApp 当前任务涉及的生产代码、测试夹具/测试和文档；不修改旧工程。
- 先判断失败是产品回归、测试夹具失效、格式契约漂移还是脆弱源码字符串断言，再选择修生产代码、修测试契约或补夹具；每项必须记录理由。
- 不通过隐藏测试、删除断言、扩大 `@Ignore`/skip、更新快照掩盖行为变化来“清零”。
- 任一前序已验收能力回归时立即暂停，状态改为“回归整改中”，不得继续扩大范围。

### 交付物

- `docs/reports/b3/JVM_REGRESSION_DEBT_REMEDIATION_REPORT.md`：实际修改文件、每项根因与修复、定向/全量测试 XML、跳过项、编译结果、未完成项和 Git 状态。
- 真实 Gradle 命令及输出路径；若构建 APK，记录 APK 路径/时间/大小/SHA-256；本任务不以真机验收为门槛。

### Git 收口

用户验收后主协调已检查 `git status`、`git diff --stat`、`git diff --check`，仅按当前任务路径选择性提交 `86d1ebd2`；未跟踪的 `docs/reports/b3/PHOTO_REGISTRATION_ENGINE_OPTIONS.md` 未纳入。未使用 `git add .`、reset、clean、stash 或回滚用户改动。

### 主协调复核结果（2026-09-21）

- 全量 `:app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`：`959 tests / 0 failures / 0 errors / 5 skipped`，Gradle 退出码 0。
- 失败相关定向测试：`206 tests / 0 failures / 0 errors / 0 skipped`。
- `:app:compileDebugKotlin --no-daemon --rerun-tasks`：`BUILD SUCCESSFUL`。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`；用户报告 APK 路径 `app/build/outputs/apk/debug/app-debug.apk`，大小约 `222 MB`；未提供新的 SHA-256。
- 5 个 skipped 均为 `DpmScannerTest` 外部样本/目录缺失，名称和原因已写入 `docs/reports/b3/JVM_REGRESSION_DEBT_REMEDIATION_REPORT.md`。
- 用户已确认本任务完成；本任务没有新增 ADB、instrumented 或真机验收。

## 2026-09-21 当前任务指针：V4/AKAZE 单张照片配准引擎

状态：**IN_PROGRESS / IMPLEMENTATION_PENDING**。用户已确认实现 V4/AKAZE，作为 V1-3 拍后模板与实拍比对 MVP 的底层配准引擎；当前等待执行 Agent handback。`tasks/todo.md` 顶部为唯一执行入口。

任务提报：[`tasks/V4_AKAZE_REGISTRATION_TASK_PROPOSAL.md`](V4_AKAZE_REGISTRATION_TASK_PROPOSAL.md)。

### 任务目标

- 为 V1-3 提供一次性的静态照片配准能力：模板图 + 现场照片 → 几何匹配 → Homography → 模板 ROI 四角投影。
- 选定方案为 V4/AKAZE 几何核心；不实现 ALIKED + LightGlue，不实现双方案 fallback。
- 配准质量不足时必须返回可解释失败状态，不能继续使用错误投影 ROI；整图检测兜底由后续检测任务负责。

### 依赖与只读审计

执行 Agent 必须先审计：

1. 模板图、现场照片、`MobileImageStore`、`CapturedPhotoEntity` 及稳定 `templateId/photoId/viewIndex/roiId` 关系。
2. 照片旋转、EXIF、`contentRect`、`RoiCoordinateMapper` 和 normalized ROI 到像素坐标的现有行为。
3. 当前 Android OpenCV 版本和旧工程 `OpenCvV4MatchEngine.kt` 的可复用几何行为；旧工程只读，不能修改或直接建立运行时依赖。
4. 现有 NanoDet、确认页、ZIP/CSV 导出链路；本任务只输出配准结果，不接管检测和人工判定。

### 实现和验收门槛

1. AKAZE 特征、BFMatcher/Lowe ratio；GMS 和 USAC_MAGSAC 先确认 Android OpenCV 能力，不能静默声称已实现。
2. 质量门禁至少覆盖内点数、内点比例、重投影误差、空间覆盖、投影四边形凸性/面积/边界。
3. 输出稳定的 `RegistrationResult`：状态、Homography/投影四角、质量指标、matcher 名称/版本和失败原因。
4. 通过同图、平移、缩放、旋转、轻微透视、弱纹理、无匹配、非法四边形和 fallback JVM 测试。
5. 通过定向 JVM、全量 JVM、`compileDebugKotlin`、`assembleDebug`；不运行 ADB/真机，除非任务边界另行授权。
6. 完成后更新 `tasks/todo.md`、本节和 [`PHOTO_REGISTRATION_ENGINE_OPTIONS.md`](../docs/reports/b3/PHOTO_REGISTRATION_ENGINE_OPTIONS.md)，执行 Agent 不提交 Git。

### 明确不做

- ALIKED + LightGlue、双方案 fallback、新模型运行时。
- CaptureComparisonScreen、Session ROI 编辑 UI、NanoDet 调用、阈值调整、ZIP/CSV 字段扩展。
- 实时轮廓、实时姿态匹配、自动 `ALIGNED/LOST`、ROI 自动跟踪和新 CameraX。

### 后续顺序

V4/AKAZE 引擎完成后，再单独启动 V1-3 比对页面与 Session ROI 微调；最后再单独做配准结果到 NanoDet/结果包的业务集成。
