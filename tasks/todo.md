# 已验收任务：DPM 扫码证据绑定采集批次并进入批次 ZIP

状态：**USER_ACCEPTED**（2026-09-16；用户确认人机验收通过）。扫码会话到后续新建批次的绑定、批次 ZIP 内 DPM 帧/ROI 文件和 CSV 记录均已完成真机验证；此前 `PHYSICAL_ACCEPTANCE_PENDING` 状态由本次用户验收取代。提交：批次绑定闭环 `2e2c5943`；DPM 空 ZIP/SAF 收口 `b7ac09c8`。证据见本节及 [`docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`](../docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md)。

## 本轮修复：DPM 扫码证据绑定采集批次闭环（2026-09-16）

**问题**：DPM 先扫码后切换零件模板再开始采集时，成功源帧因 `batchId=null` 无法进入采集批次 ZIP。根本原因：`DpmEvidenceFrameTracker` 的 `cleanupUnusedLocked()` 在后续帧到达时移除了正在被协程分析的源帧，导致 `recordDecodeSuccess(token)` 失败。

**方案**：以稳定 `scanSessionId` 为唯一绑定依据，通过「暂存 → 消费」模式实现跨步骤关联；同时添加 `inFlightTokens` 机制保护正在分析的帧不被清理。

**in-flight 修复**：
- `DpmEvidenceFrameTracker.kt` — 新增 `inFlightTokens: HashSet<Long>`、`markInFlight(token)`、`unmarkInFlight(token)`；`cleanupUnusedLocked()` 保留集合 = `{lastToken, successToken} + pendingGridTokens + inFlightTokens`；`recordDecodeSuccess()` 添加完整诊断日志
- `DpmFrameAnalyzer.kt` — `dpmAnalyzer.analyze()` 调用前后用 try/finally 包裹 `markInFlight` / `unmarkInFlight`
- `DpmScanScreen.kt` — `onResult` 仅在 `evidenceSaved=true` 时放行；`evidenceSaved=false` 时阻止回调
- `DpmScanViewModel.kt` — `startScan()` 时清除旧 `_lastResult.value = null`

**修改文件**：
- `DpmScanEvidenceDao.kt` — 新增 `bindSessionToBatch(sessionId, batchId): Int`（UPDATE WHERE batchId IS NULL AND status='SUCCESS'，幂等）
- `InspectionRepository.kt` — 暴露 `bindDpmScanSessionToBatch()` 委托方法
- `WorkbenchViewModel.kt` — 新增 `PendingDpmBatchBinding` 数据类、`setPendingDpmBatchBinding()`、`applyPendingDpmBinding()`（校验 partId 一致性）
- `DpmScanScreen.kt` — `onResult` 签名扩展为 `(String, String?)`，传递 `connectedSessionId`
- `AppNavigation.kt` — DpmScan 路由 `onResult` 回调中调用 `workbenchViewModel.setPendingDpmBatchBinding(sessionId, part.id)`
- `LiveInspectionScreen.kt` — 首批拍照创建批次后调用 `viewModel.applyPendingDpmBatchBinding(batchId, repository)`

**未修改**：DpmScanEvidenceEntity、AppDatabase、Migrations、InspectionZipExportService、DpmEvidenceExportService、DPM 解码/ECC/CameraX/NanoDet。

**自动化测试**：
- `DpmEvidenceFrameTrackerTest` — 13 项测试全部通过（in-flight 帧存活、非 in-flight 帧被清理、token 不匹配拒绝、freeze 后仍可取证据等）
- `DpmScanEvidenceContractTest` — 24 项测试全部通过（DAO 绑定方法、Repository 暴露、ViewModel 暂存/消费、DpmScanScreen 签名、LiveInspectionScreen 调用）
- `InspectionZipExportArchiveTest` — 4 项测试全部通过（使用真实 repository/DAO 行为验证绑定前后导出差异）
- `WorkbenchViewModelAdvanceTest` — 新增 5 项 DPM 绑定测试全部通过（partId 匹配绑定、不匹配丢弃、DAO 返回 0 行保留 pending、重复消费无效）

**验证**：`:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 73 项 DPM 相关测试全部通过（1 项预存 selectPart 失败与本次修改无关）；`:app:assembleDebug` 成功。

**APK 信息**：
- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：232,677,354 bytes
- 构建时间：2026-09-16 13:32:01 +08:00
- SHA-256：`fe4c910a9c151244d58433bea79a5c73c9e3e97d7fdbc7434225956cfe0eb1c5`

**真机验证结果**（2026-09-16 13:38，设备 `ERLDU20429005890`，包名 `com.wearable.inspection.mobile`）：

| 项目 | 值 |
|---|---|
| scanSessionId | `ca802df7-5c40-4909-bfc7-8347700c12ff` |
| rawValue | `M968942280224B169AH005023044710` |
| sourceFrameToken | `22` |
| evidenceSaved | `true`（DIAG-1） |
| decodeSource | `GRID` |
| 数据库 rowId | `25` |
| DPM 帧文件 | `frame_25_1789537092865.jpg`（195,149 bytes） |
| DPM ROI 文件 | `roi_25_1789537092865.jpg`（84,717 bytes） |
| batchId | `batch_1789537096005_7c122ec5` |
| 绑定结果 | `updated=1 rows`（DIAG-5） |
| ZIP DPM 条目 | `dpm/sessions/ca802df7-.../frame_25_...`、`roi_25_...` |
| CSV DPM 记录 | `scanSessionId`、`dpmCode`、`dpmDecodeSource=GRID`、`dpmStatus=SUCCESS` |
| 帧 SHA-256 | `cacf32ac971c5826d00385272094ebf6566d40c4b5ea6e93ed9c6c1dd33a7ba0` |
| ROI SHA-256 | `98e5fea211097879572a57839b789bcd0d5f9b0720c4051b1893c8572be5373d` |
| 文件一致性 | ZIP 与 dpm_evidence 目录 SHA-256 完全一致 |
| in-flight 修复 | `recordDecodeSuccess: OK token=22`（修复前为 `REJECTED: token not in entries`） |

本轮只修复验收暴露的测试问题，未扩展功能、未修改 DPM 解码算法或 CameraX 所有权：

- `DpmScanEvidenceContractTest` 改为实际调用 `runDpmScanExit(null, ...)`，验证无 sessionId 时执行 `stopScan`、回收 Bitmap，且不保存/清理 analyzer/disconnect；不再依赖 `viewModel.stopScan()` 源码字面量。
- `SafZipExportTest` 改用 Robolectric Fake ContentResolver shadow，保留 null 输出流异常、完整字节复制和 Empty/Failure 清理 SAF 文档/临时 ZIP 三项语义；不修改生产代码迎合测试。
- 复核生产链仍为 `getEvidenceFrames → saveEvidence → stopScan → clearFrameAnalyzer → disconnect`；`DisposableEffect(Unit)`、最新 sessionId、`disconnectOnDispose = false`、Application scope、Bitmap 回收、失败清理、SUCCESS 过滤和严格 batchId 隔离均保留。

主协调审阅补充（2026-09-15，历史状态）：当时扫码没有显式 `batchId`，批次 ZIP 仅含现场照片和 CSV，因此批次 DPM 关联仍待现场核验。该历史待验收状态已由 2026-09-16 的修复、真机闭环证据和用户验收取代。

本轮真实证据：`docs/reports/b3/evidence_dpm_current/06_mobile_inspection_db`、`14_independent_dpm.zip`、`24_batch_batch_17_(2)_actual.zip`、`12_export_browser.xml`、`13_export_browser.png`、`20_batch_saved.png`。使用 APK `app/build/outputs/apk/debug/app-debug.apk`（2026-09-15 17:56:29 +08:00，232,676,681 bytes，SHA-256 `3AE7821D627AC21AF8C82D962D4D568CAEE23BDEB7083822539A6631D9ADEC3D`）；设备前台为 `com.wearable.inspection.mobile/.MainActivity`，新包 PID `16456`，旧包 PID 为空。独立 ZIP 设备/本地 SHA-256 均为 `0E9120A97C373FB0EE432F833030890A37F7E74B060E612E1CE68DB7C04B3948`。

本轮实际代码修改文件：

- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/SafZipExportTest.kt`

验证：`:app:compileDebugKotlin --no-daemon` 通过；定向 `:app:testDebugUnitTest --no-daemon --rerun-tasks` 通过（223 项，218 passed / 0 failed / 5 skipped；JUnit XML 汇总）；本轮补跑同一 DPM/导出/退出/SAF/CameraPreview 定向集合也是 223 项，0 failed，5 skipped。APK：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk`，2026-09-15 17:56:29 +08:00，232,676,681 bytes，SHA-256 `3AE7821D627AC21AF8C82D962D4D568CAEE23BDEB7083822539A6631D9ADEC3D`。本轮已执行设备只读核验和真实扫码/导出取证；未运行 connectedDebugAndroidTest，未重新安装/卸载 APK，未提交 Git，工作区其他改动保留。详见 B3 两份 DPM 报告。

---

# 当前唯一任务：ROI 最终结果语义、人工改判与 ROI 证据图导出

状态：**IN_PROGRESS**（2026-09-16；仅此任务允许执行）。DPM 批次 ZIP 关联交付项已由用户验收；当前只处理 ROI 最终结果语义、人工确认页口径及改判 ROI 证据图与 CSV 的稳定关联。不得并行执行其他待办。

## 交付项 1：DPM ECC 成功照片合并到采集批次 ZIP

状态：**USER_ACCEPTED**（2026-09-16）。

批次 ZIP 仅导出 ECC 纠错通过且码值非空的 DPM 源帧照片和同一源帧扫描 ROI 照片。文件从 `filesDir/dpm_evidence` 原路径按字节复制，必须与独立 `DpmEvidenceExportService` ZIP 中的对应照片完全一致。独立 DPM ZIP 继续按 `scanSessionId` 导出；ECC 失败、未读出、超时、取消或旧 session 不产生 DPM 照片。

## 交付项 2：ROI 检测结果、人工改判及 ROI 证据图导出

状态：**IN_PROGRESS / REQUIREMENT_REVISED**（2026-09-16）。

### 开始前审计与执行边界

先审计现有 `ViewRoiConfirmEntity`、DAO、`InspectionRepository`、`ViewConfirmationViewModel`、确认页、CSV/ZIP 导出器及 ROI 图片实际存储/路径语义，再列出最小修改文件；不得预设需要新实体或 migration，也不得直接创建平行数据模型。

- 每个 ROI 只保留一个规范的最终 `result`。模型有 OK/NG 时，人工按钮默认选中模型结果；未改判时最终 `result` 等于模型结果，改判时最终 `result` 等于人工选择。
- 改判时额外记录原模型结果、人工结果、改判标记、改判时间，并持久化对应 ROI 照片；`inspection_result.csv` 必须写入能回链到 ZIP 内真实文件的正确路径。未改判不要求额外生成改判照片。
- 总体结果 OK/NG 由人工独立确认和保存，不能根据 ROI 自动汇总。
- 保持现有确认页上下结构、排版和固定确认栏；仅显示零件名称、视角进度、ROI 编号、ROI 类型、ROI 图片、人工确认 OK/NG、总体结果 OK/NG、操作状态和“确认并继续”。不得显示模型建议文字、ROI UUID、分数、阈值或模型版本。
- FEATURE、模型未执行或部件类别不支持时显示“部件类别暂不支持”或“模型未执行”；不默认选中，不得自动判为 NG。
- 覆盖无改判、OK→NG、NG→OK、总体结果独立、未执行/不支持状态、稳定关联重载、真实 ZIP 条目和 CSV 回链。若改动数据库 schema，提供真实 Room migration 并验证旧记录兼容；保留现有 CSV 字段兼容性。
- 本任务不包含 DPM 代码/算法或绑定修改、NanoDet 模型/阈值校准、CameraX、批次清理、批量多选导出等独立事项。执行完成后更新本任务及对应报告，报告列明实际文件、命令和结果、APK/真机证据（若本任务授权并执行）、未完成项和 Git 状态；等待主协调审阅与用户验收。

批次 ZIP 的 `inspection_result.csv` 已按稳定 `batchId/photoId/templateId/viewIndex/roiId` 导出 NanoDet 检测框、类别、置信度、阈值、模型版本、推理状态、确认时间和照片总体人工结果。按最新口径，每个 ROI 只保留一个最终 `result`：人工不改判时写入模型结果；人工改判时写入人工结果，并额外记录原模型结果、改判结果、改判标记和改判时间。发生改判时还必须留存该 ROI 照片并让 CSV 正确回链；未改判不要求额外生成改判照片。当前实现仍有模型/人工分字段和 `ROI图ZIP路径` 为空的历史行为，不能视为已满足最新口径。

### 当前现场人工确认页显示口径

保持现有确认页的界面排版、上下结构和固定确认栏不变，只保留以下内容：零件名称、视角进度、ROI 编号、ROI 类型（螺纹/螺母/部件）、ROI 图片、人工确认 OK/NG、总体结果 OK/NG、操作状态和“确认并继续”。模型结果直接反映为人工确认 OK/NG 按钮的默认选中状态，不单独显示“模型建议”文字；人工可以改判。模型未执行或不支持时不默认选中，并在操作状态位置显示简短提示。所有 ROI 和总体结果有值后启用“确认并继续”。

本轮结果包代码已提交：`f723da0e`。定向结果 JVM 104 项、真实 ZIP 解包和 DPM 两 ZIP 字节比较通过；全量 JVM 的既有失败单独记录于报告。其它工作区改动保留。

---

# 历史任务：修复 MobileInspectionApp 启动闪退

状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-15）。已修复 `CameraPreview` 在后台线程求值 `PreviewView.surfaceProvider` 导致的启动闪退；本轮只修复该启动路径，不进入任何后续功能。

实现与验证：在 `Dispatchers.Main.immediate` 获取 `Preview.SurfaceProvider`，再将已取得的 provider 传给后台 `CameraController.connect`；保留现有 CameraX 主线程桥接、`active=false` 立即 `disconnect(sessionId)`、`connectionGeneration`、sessionId 防竞态、`DisposableEffect` 兜底和重入行为。定向 JVM：`CameraControllerTest` 42/42、`CameraPreviewTest` 18/18；`compileDebugKotlin` 与 `assembleDebug` 通过。主 APK `app/build/outputs/apk/debug/app-debug.apk`，2026-09-15 10:42:09 +08:00，276,579,040 bytes，SHA-256 `D6A0481AA9288F0550150D5132CE981AF5B9DDF2C4C0842CF9BF3E45F09356C4`。设备 `ERLDU20429005890` ABI 为 `arm64-v8a,armeabi-v7a,armeabi`；新包安装并显式启动成功，PID 5995，旧包无 PID，前台为 `com.wearable.inspection.mobile/.MainActivity`，启动后指定 logcat 错误模式无匹配。DPM ECC、CameraX 可见性、NanoDet 确认页仍保持待用户验收，未标记 `USER_ACCEPTED`。详细记录见 [`docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md`](../docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md)。未提交 Git，工作区其他改动保留，等待验收。

---

# 历史任务：DPM 扫码证据只保存 ECC 成功源帧

状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-15，纠正版）。本项只修改 DPM 证据帧选择与保存语义；只有 ZXing、ML Kit 或 GRID 返回非空 Data Matrix 码值且 `DpmAnalyzer` 发出当前会话 `DECODED` 时，才保存准确源帧和对应 scan ROI。无 ECC 成功时不保存原图、ROI、预处理图，不创建 `NO_READ` 证据行，也不回退到 `lastFrameBitmap`。

实现：复用 ZXing `DataMatrixReader.decode()`、`Decoder().decode(matrix)` 和 ML Kit 内部 ECC；不新增 Reed-Solomon 或像素修正。`DpmEvidenceFrameTracker` 以稳定 frameToken/时间和 ROI 保存同步 ZXing/ML Kit 源帧；GRID 提交/完成生命周期携带源 token，取消、超时、stop、会话结束和迟到结果均失效并回收 Bitmap。成功帧被选中后后续帧不能覆盖；保存失败会清理原图/ROI/数据库孤立状态。独立 DPM ZIP 仅导出实际存在的 SUCCESS 照片，现场采集照片 ZIP 未修改。

验证：`:app:testDebugUnitTest --tests "com.wearable.inspection.mobile.dpm.*" --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*"`（182 项执行、0 失败、5 跳过）；`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.dpm.DpmScanEvidencePersistenceInstrumentedTest`（YAL-AL10，3/3 通过）；`:app:assembleDebug :app:assembleDebugAndroidTest` 通过。主 APK `app/build/outputs/apk/debug/app-debug.apk`（276,579,040 bytes，SHA-256 `3B68B891368304A87CCA5B9C22BF2458632D286D593B96A32E84A39EDBD9C961`），测试 APK 见 B3 报告。真机测试后按门禁停止旧/新包并重装主 APK；新包启动后因工作区已有 `CameraPreview.kt:255` 的 `PreviewView.getSurfaceProvider()` 非主线程崩溃无法保持前台，旧包 PID 已为空，未将该启动状态宣称为通过。未提交 Git，其他工作区改动保留。详细记录见 [`docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`](../docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md) 和 [`docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`](../docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md)。

---

# 历史任务：现场采集页离开时立即暂停 CameraX

状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-15）。修复现场采集页切换到“追溯记录/我的”时相机仍保持绑定造成的卡顿；仅修改 CameraPreview 活跃状态、现场页透传、相机生命周期 JVM 测试和本报告，不运行 adb、安装 APK 或真机验收。

实现与验证：复用 `CameraController.disconnect(sessionId)`，`active=false` 时在后台立即断开 Preview、ImageAnalysis、ImageCapture、分析器、Executor 和 observer；不调用 `release()`。CameraX 要求主线程的 API 由 `RealCameraBinder` 统一桥接执行，provider 获取和等待不占用 Compose 主线程。连接代次门禁会清理不可见期间迟到的 session，重入时允许新 session 重新连接；旧 observer 回调不能覆盖新 session；`DisposableEffect` 保留为销毁兜底，且本地 session 标识先清空以避免重复解绑。AppNavigation 原有 `currentRoute == Screen.LiveInspection.route` 作为可见状态来源，未修改一级淡入淡出动画。最终定向命令 `:app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --tests CameraControllerTest --tests CameraPreviewTest` 通过；`CameraControllerTest` 42/42、`CameraPreviewTest` 17/17。未生成 APK、未做真机测试。详细记录见 [`docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md`](../docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md)。工作区其他改动保留，未提交 Git，等待用户验收。

---

# 历史任务：展示 NanoDet ROI 模型结果并保存人工终审

状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-14）。前一项 Android NCNN runtime smoke 和 NanoDet 静态照片 ROI 推理均已由用户验收。本项只在现有 ViewConfirmationScreen/ViewConfirmationViewModel 展示真实 inferenceResults，并保存人工终审。

复用现有 ViewRoiConfirmEntity 和 photoId、batchId、templateId、viewIndex、roiId 稳定关联；先审计 Room schema、DAO、repository、ViewModel 和确认页，禁止建立平行结果模型。逐 ROI 展示真实推理状态、类别、模型建议、最高匹配分数和检测框。FEATURE、属性未配置、无框、照片/模型不可用和推理错误应显示明确状态。人工逐 ROI 独立选 OK/NG，允许双向改判；softwareResult 与 humanResult 分字段保存，记录所有检测框/分数/阈值/模型版本及摘要/推理状态、人工最终值、是否改判和确认时间。旧记录的模型字段保持未执行/null。整张照片总体 OK/NG 继续只由人工独立选择。

如需修改 Room，提供真实 migration；增加分离存储、双向改判、总体结果独立、未执行/无框/错误态、稳定关联重载及 migration 测试。本项不做 ZIP/DPM 导出、Detector 算法、阈值校准、CameraX 预览推理或新相机架构。完成后更新本项和 B2/B3 报告，不提交 Git，等待验收。

实现与验证：复用现有确认实体，Room v7→v8；定向确认/实体/兼容性 JVM 测试 44/44 通过，YAL-AL10 Room instrumented 5/5 通过。最终主 APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-14 18:52:50 +08:00，276,579,040 bytes，SHA-256 `4F721E4244BD6D1FE133BBB2CBCB3453C8177234C12D56E84C32A58E4A243FDA`。最终设备核验：新包 `com.wearable.inspection.mobile` PID 4337、旧包无 PID、前台为 `MainActivity`。未提交 Git；工作区其他已有改动保留。实现文件、APK/测试 APK、测试命令和限制见 [`docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`](../docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md) 与 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。等待用户验收。

---

# 已验收任务：NanoDet 模板 ROI 静态照片推理接入

状态：**USER_ACCEPTED**（2026-09-14）。前一项 Android NCNN runtime 冒烟测试已由用户验收；本项仅为已保存照片的当前模板 ROI 提供真实 NCNN 推理结果，不接入预览流、确认页 UI、人工改判、数据库迁移、结果 ZIP、自动对齐或新相机架构。

复用已验证的 NCNN optlevel=2 模型、Android FP32 CPU runtime 和 `arm64-v8a` 库，不重做转换或冒烟测试。复用 `RoiDefinitionEntity.targetType` 与 `RoiCoordinateMapper`；校验照片 EXIF 方向、实际图像区域、像素边界及 ROI/整图框坐标。NUT 映射类别 0，THREAD 映射类别 1，FEATURE 明确标记不支持；保持 BGR、416×416 左上补边、既定 mean/std、`in0`/`out0`，候选过滤保留低分框。默认业务阈值 0.37 仅为未校准起始值。

结果需提供全部保留框及类别、分数、ROI/整图坐标、阈值、模型版本、耗时和明确状态；对应类别达到阈值建议 OK，无框建议 NG 且分数为空。推理错误、ROI 属性未配置和 FEATURE 均为非检测成功状态。补充类别/状态、阈值边界、无框、预处理、映射、EXIF/边界和错误测试，并用两张既定回归图与桌面 NCNN 结果对照。

验证结果：定向 JVM 33/33、YAL-AL10 Android runtime 4/4 通过；两张回归图的检测类别一致，最大置信度差 1.35e-7、最大框坐标差 2.85e-5 px。全量差异、APK、文件及限制见 B3 报告。已由用户验收。

前一项 NCNN 冒烟记录、设备/ABI、模型、库、回归图与 runtime/桌面对照证据保留在 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。
---

# 已验收任务：Android NCNN 运行时冒烟测试

状态：**USER_ACCEPTED**（2026-09-14）。本任务验证 Android NCNN runtime 加载、推理及两张回归图片与桌面对照；未接入 ROI 页面、数据库或结果包。

使用现有 NDK `D:\ProgramData\Android\SDK\android-ndk-r30`（`30.0.16248370`）的 `ndk-build.cmd`，在 `YAL-AL10` 真机（ABI `arm64-v8a`）运行仅限 `androidTest` 的 NCNN FP32 加载/推理通路。两图按 BGR、左上放置 234×416 等比例缩放补边至 416×416、指定 mean/std 归一化；输出 blob `out0` shape `[3598,34]`。两图类别、数量、候选点和四档阈值检测数均与桌面 NCNN 相同；最大置信度差 `2.69e-7`，最大框坐标差 `3.53e-5 px`。`parity_results.json` 不含完整原始张量，未声称完成逐元素张量对照。

通过命令：`.\gradlew.bat :app:assembleDebug`、`.\gradlew.bat :app:assembleDebugAndroidTest`、`.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.ncnn.NcnnRuntimeSmokeInstrumentedTest`（1/1 passed）。设备测试前后按包名门禁停止新旧包、显式安装/启动新包并核验 PID 与前台 Activity。详细输入、模型/库、差异、APK 信息和文件清单见 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。该任务已由用户验收；本轮未提交 Git。

---
# 历史任务记录：DPM 扫码证据 ZIP 空文件修复

状态：**USER_ACCEPTED**（2026-09-16；本任务的 SAF/ZIP 软件整改与后续 DPM 导出闭环均已由用户验收）。早期记录中的 `SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING` 和“禁止标记 USER_ACCEPTED”属于验收前状态，现由文档顶部的最新验收记录取代。修复 manifest writer 关闭底层 ZipOutputStream、SAF 输出流为空或 ZIP 生成失败时误报成功，以及 SAF 预创建空文件清理；增加真实 ZIP 解包回归测试、无 sessionId 退出和 SAF resolver 行为回归。报告：`docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`。

历史实现范围：DPM ZIP 导出服务、SAF 写入逻辑、导出/生命周期测试及配套任务文档；当时的未运行真机说明保留为历史事实，不用于覆盖当前已验收的 DPM 批次闭环证据。

---
# 已完成任务：NanoDet 转换与三方桌面对照

状态：**SOFTWARE_COMPLETE / DESKTOP_PARITY_VERIFIED / ANDROID_APP_INTEGRATION_NOT_STARTED**（2026-09-14；本次授权范围完成，待用户验收）。

使用已存在的 ONNX，通过 NCNN 官方 PNNX 20260526、FP32、`optlevel=2` 转成可由 NCNN runtime 加载的 `.param/.bin`；另用官方 NCNN Android shared 包准备了多 ABI 运行库。对 `frame_00106_f1060.jpg` 和 `frame_00045_f450.jpg` 使用同一输入张量比较 PyTorch、ONNX、NCNN 原始输出、类别、置信度及解码框，结果通过。最初 `optlevel=0` 产物含 runtime 不支持的 `prim::ListConstruct`，已单独标为 `opt0_rejected`，不得用于接入。

Android App 尚未接入模型，也未构建 APK：工程没有 `abiFilters`/`ndkVersion`/CMake 配置，本机 Android SDK 没有 NDK；需在后续任务核定目标 ABI 和 NDK 后再最小化接入，不改现有相机、导航、页面或基础框架。本轮未运行 Gradle、ADB、APK 或真机。阈值 `0.37` 仅是起始值：当前 demo 预处理下 frame45 误报分数 `0.37719` 仍会通过该阈值，frame106 的低分 thread（约 `0.0525/0.0504`）会被滤掉；需在有人工标注的代表性验证集上校准。详细产物、哈希、差异和下一步见 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。

---

# 已完成任务：DPM 扫码证据可操作导出

状态：**DONE**（2026-09-15，实现完成，自动化验证通过，待用户验收）。

目标：在现有导出入口增加"导出 DPM 扫码证据"操作，生成独立 ZIP（按 scanSessionId 分目录、原始帧 + ROI 裁切 + manifest.csv），支持 SAF 保存和 FileProvider 分享。导出后保留应用内原始证据。

执行边界：复用现有 dpm_scan_evidence 表/DAO/Repository/MobileImageStore 和 ZIP 导出/分享交互。不改扫码算法、不新增 Room migration、不扩展本项之外的检测结果 ZIP。

自动化验证结果：745 tests completed, 14 failed (全部预存), 5 skipped。新增 16 项测试全部通过。

实现摘要：
- 新文件：DpmEvidenceExportService.kt, DpmEvidenceExportServiceTest.kt
- 修改文件：InspectionRepository.kt（添加 getAllDpmScanEvidence）, TraceRecordsScreen.kt（添加 DPM 证据导出按钮 + SAF launcher）
- 报告：docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md

---

# 已完成任务：DPM 扫码会话图像证据留存

状态：**DONE / 语义由上方纠正版覆盖**（2026-09-15）。

历史目标曾包含无读出时保存最后有效帧/`NO_READ`；该语义已被上方纠正版“无 ECC 成功时不保存照片证据或记录”覆盖。当前仍保留成功读码源帧、scan ROI、`scanSessionId`、帧时间和独立 DPM ZIP 的稳定关联。

执行边界：
- 复用现有 DpmFrameAnalyzer/DpmScanViewModel/DpmScanScreen/CameraController 生命周期接点和 MobileImageStore。
- 帧图像必须在 Bitmap 回收前复制或编码；退出流程必须先完成证据快照和持久化，再停止 analyzer 和断开相机。
- 最小 Room 扩展（新 Entity/DAO/Migration）+ 真实 Migration。
- 不改扫码算法、不实现 DPM 人工码值复核、不扩展 ZIP、不做 ROI 检测。
- 14 项预存测试失败与本任务无关，不顺手修复。
- 补充测试；不运行 ADB/真机。

自动化验证结果：729 tests completed, 14 failed (全部预存), 5 skipped。新增 35 项测试全部通过。

实现摘要：
- 新文件：DpmScanEvidenceEntity.kt, DpmScanEvidenceDao.kt
- 修改文件：DpmFrameAnalyzer.kt（帧追踪+证据提取）, DpmScanViewModel.kt（证据保存流程）, DpmScanScreen.kt（退出时证据优先）, MobileImageStore.kt（DPM 证据存储方法）, AppDatabase.kt（v7）, Migrations.kt（v6→v7）, InspectionRepository.kt, MobileInspectionApp.kt
- 新测试：DpmScanEvidenceEntityTest.kt, DpmScanEvidenceContractTest.kt, DpmFrameAnalyzerEvidenceTest.kt
- 报告：docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md

---

# 已完成任务：模板叠加默认透明度为 0%

状态：**USER_ACCEPTED**（2026-09-15，用户确认验收通过）。详见 `docs/reports/b2/TEMPLATE_OVERLAY_ALPHA_ZERO_REPORT.md`。

---

# 已完成任务：单零件多 View 人工确认 + ZIP 导出

---

# 已完成任务：单零件多 View 人工确认 + ZIP 导出

状态：**USER_ACCEPTED**（2026-09-15，用户确认测试通过）。

目标：用户选择零件 → 按模板顺序逐 View 拍照。当前 View 有 enabled ROI 时进入确认 UI，逐个选择 ROI OK/NG 和总体 OK/NG 后保存并进入下一 View；当前 View 无 ROI 时仍先真实拍照并保存到当前 batchId，随后直接进入下一 View。全部完成后生成包含所有原始照片和真实确认结果的 ZIP，统一写入一个 Excel 兼容 CSV（检测 CSV 可无 ROI 确认行）。

## 用户说明文档补充（2026-09-04）

- [x] 基于当前 MobileInspectionApp 源码和指定图片目录整理中文使用说明
- [x] 纳入 20 张操作/结果图片，按配置 → 采集 → 确认 → 导出 → 记录管理的业务时间顺序编排
- [x] 记录当前版本边界：DPM 实时扫码、人工确认、本机离线数据、软件检测结果未执行
- [x] 生成 `docs/user-guide/视觉质检MobileInspectionApp_使用说明.docx`
- [x] 完成 DOCX 图片数量、图片可访问性、章节/分页和文本完整性检查；LibreOffice 渲染因环境未安装 `soffice` 未执行

## 本轮追加优化：采集过渡与模板包闭环

归属：既有“单零件多 View 人工确认 + ZIP 导出”源码整改；自动化与真机验收仍待处理。本轮执行入口由本文顶部的 Android NCNN runtime 任务占用，本记录未被标记为完成。

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

状态：**USER_ACCEPTED**（2026-09-15，用户确认测试通过）。

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

状态：**USER_ACCEPTED**（2026-09-15，用户确认测试通过）。

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

状态：**USER_ACCEPTED**（2026-09-15，用户确认测试通过）。

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

状态：**USER_ACCEPTED**（2026-09-15，用户确认测试通过）。

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

---

## 检测结果追溯与 DPM 扫码证据进度摘要

状态：DPM 成功证据进入批次 ZIP **USER_ACCEPTED**；ROI 最终结果/改判证据图片交付 **IN_PROGRESS**。当前唯一执行入口为本文顶部；本历史摘要不另建并行任务。完整边界见 [`tasks/plan.md`](plan.md#2026-09-16-当前任务指针与-roi-执行计划) 与 [`docs/reports/b2/RESULT_TRACEABILITY_PLAN.md`](../docs/reports/b2/RESULT_TRACEABILITY_PLAN.md)。

计划顺序：

1. **模板叠加默认透明度改为 0%** — **USER_ACCEPTED**；首次进入 `alpha=0f`，透明度可用现有滑杆提高到 0%～80%。验收记录见 `docs/reports/b2/TEMPLATE_OVERLAY_ALPHA_ZERO_REPORT.md`。
2. **DPM 扫码会话图像证据留存** — **DONE**；成功时绑定精确读码帧。无 ECC 成功时不保存最后有效帧、ROI 或 `NO_READ` 证据。不改扫码算法。见 `docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`。
3. **DPM 成功帧/ROI 合并到采集批次 ZIP** — **USER_ACCEPTED**（2026-09-16）；保留 View 原图、现有确认记录和 CSV 兼容字段，并通过稳定 ID 关联成功 DPM 源帧/扫描 ROI；独立 DPM ZIP 保持独立，不包含 DPM 人工复核。真机闭环证据见本文顶部和 B3 导出报告。
4. **DPM 人工码值复核：暂不排期** — 当前没有准确、独立的 DPM 码真值可供人工判断读码是否正确；`Part.dpmCode` 不能当作真值。现阶段只留存扫码图像证据和原始解码状态。待有可信真值数据并重新确认需求后再评估人工复核流程。
5. **NanoDet ROI 推理、确认与基础元数据导出** — Android NCNN runtime、静态照片 ROI 推理和先前确认/持久化及 CSV 元数据实现已完成；更早的“尚未接入”状态已过期。当前进行中的后续口径为本文顶部任务：单一最终 `result`、模型结果只作为人工按钮默认选择、人工独立总体结果、改判才额外保留原模型值/人工值/标记/时间及 ROI 照片，并让 CSV 回链真实 ZIP 文件。`NUT→class 0`、`THREAD→class 1`，`FEATURE` 不受模型支持；0.37 仍是未校准起始值，不属于当前任务的阈值校准范围。完整历史模型验证见 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。

边界：人工确认 OK/NG 不等于模型质量判定；模型和推理链已集成，但 0.37 未经代表性验证集校准。DPM 证据只按稳定 `scanSessionId` 和显式关联的真实 `batchId` 归属；清理批次不级联删除独立 DPM 证据。当前 ROI 最终语义/证据图任务完成前，不得声称 ROI ZIP 证据交付完成。实现与测试门禁以本文顶部和 `tasks/plan.md` 最新状态更新为准。
