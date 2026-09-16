# DPM 扫码证据可操作导出 — 实现报告

**任务**：DPM 扫码证据可操作导出
**当前状态**：DPM 扫码会话绑定采集批次并进入批次 ZIP 的闭环 **USER_ACCEPTED**（2026-09-16）。以下 2026-09-15 小节保留其当时的历史状态；本报告末尾补充为当前验收结论。
**日期**：2026-09-15

## 2026-09-15 现场采集结果 ZIP 关联补充

独立 DPM ZIP 继续按 `scanSessionId` 导出；本轮只将显式关联的 DPM 成功证据纳入 `InspectionZipExportService` 的批次 ZIP，不合并两个服务的目录或模型。批次 ZIP 仅接受 `batchId == 当前 batchId`、`SUCCESS`、非空 `decodedContent`、`ZXING/ML_KIT/GRID` 和真实非空 `originalImagePath`；原图/ROI 使用统一 `copyDpmFileToZip` 从独立证据原路径复制，缺失、NO_READ、无 batchId、跨 batch 均被排除。

真实归档样例：`C:\Users\ws\AppData\Local\Temp\inspection-export7435539282332082454`，批次 `batch.zip` 与独立 DPM `independent.zip` 均以真实 `ZipInputStream` 解包。成功 DPM frame 为 4 bytes、SHA-256 `952B50FD4FE30AEE9420F479FF3F4C6268F2865EE65A82E1F8E157ABC1455272`；成功 DPM ROI 为 4 bytes、SHA-256 `EE10DA4AEFE61A37DF1DEE937CA3221AFA3B2351F9EA34EDBBB769573C6785F7`；批次与独立 ZIP 字节完全相等。测试确认 NO_READ、无 batchId、缺失源帧未进入批次 DPM 目录。

本轮结果包实际修改文件：`InspectionZipExportService.kt`、`InspectionExcelExporter.kt`、`InspectionZipExportArchiveTest.kt`；为支持扫描启动时显式 `batchId` 关联，配套修改 `DpmScanEvidenceEntity`、DAO、Repository、`DpmScanViewModel`、DPM 导航入口和 v8→v9 migration，并加入 schema/migration 契约测试。定向结果 JVM 104/104 通过；全量 JVM 792 项完成、779 通过、13 失败、5 跳过，失败属于工作区既有并行改动/基线断言。APK 为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-15 13:12:25 +08:00，276579040 bytes，SHA-256 `D2D7B57FF523EA82D48E7F1EEDCE4ED1FCAB7D32EC5CC192CAD2DEED06B6B35B`。未运行 ADB、connectedDebugAndroidTest 或真机；已提交 Git：`f723da0e`。

---

## 1. 目标

在"追溯记录"页面增加"导出 DPM 扫码证据"操作，生成独立 ZIP：
- 按 scanSessionId 分目录存放原始帧和 ROI 裁切图
- 附 manifest.csv 记录每条证据的元数据和对应 ZIP 路径
- 缺失文件在 manifest 中明确标注
- 导出后保留应用内原始证据

---

## 2. 实现架构

### 2.1 新文件

| 文件 | 说明 |
|------|------|
| `data/export/DpmEvidenceExportService.kt` | ZIP 导出服务：查询全部证据、按会话分目录、写 manifest.csv |
| `app/src/test/.../DpmEvidenceExportServiceTest.kt` | 16 项 JVM 测试 |

### 2.2 修改文件

| 文件 | 修改点 |
|------|--------|
| `data/repository/InspectionRepository.kt` | 添加 `getAllDpmScanEvidence()` 方法 |
| `ui/screens/TraceRecordsScreen.kt` | 添加 DPM 证据导出卡片（SAF launcher + 进度 + 结果消息） |

### 2.3 ZIP 结构

```
dpm_evidence_20260915_143000.zip
├── sessions/
│   ├── {scanSessionId-1}/
│   │   ├── frame_{id}_{timestamp}.jpg    ← 原始帧
│   │   └── roi_{id}_{timestamp}.jpg      ← ROI 裁切（可选）
│   └── {scanSessionId-2}/
│       └── frame_{id}_{timestamp}.jpg
└── manifest.csv
```

### 2.4 manifest.csv 列

| 列 | 说明 |
|---|---|
| scanSessionId | 扫描会话 ID |
| frameTimeMs | 帧时间戳（毫秒） |
| frameSource | 帧来源（CAMERA） |
| status | SUCCESS / NO_READ |
| decodedContent | 读码内容（NO_READ 时为空） |
| decodeSource | ZXING / ML_KIT / GRID（NO_READ 时为空） |
| frameZipPath | 原始帧在 ZIP 中的路径 |
| frameStatus | 已导出 / 缺失：文件不存在或为空 / 写入失败 |
| roiZipPath | ROI 裁切在 ZIP 中的路径（无 ROI 时为空） |
| roiStatus | 已导出 / 无 ROI 裁切 / 缺失 |

---

## 3. 关键设计决策

### 3.1 SAF 交付
使用 `ActivityResultContracts.CreateDocument("application/zip")` 让用户选择保存位置。ZIP 先在 `cacheDir` 生成，再通过 `contentResolver.openOutputStream()` 复制到用户选择的 URI。

### 3.2 缺失文件处理
文件不存在或为空时，在 manifest.csv 的 `frameStatus`/`roiStatus` 列标注"缺失：文件不存在或为空"，不静默跳过，不中断导出。

### 3.3 导出后保留原始文件
ZIP 生成只读取原始文件，不删除、不移动。临时 ZIP 文件在成功写入 SAF URI 后删除。

### 3.4 无证据时提示
`DpmEvidenceExportResult.Empty` 返回"暂无 DPM 扫码证据"提示，不生成空 ZIP。

---

## 4. 测试覆盖

### 新增测试（16 项，全部通过）

| 测试文件 | 测试数 | 覆盖范围 |
|----------|--------|----------|
| `DpmEvidenceExportServiceTest.kt` | 16 | Result 类型、ManifestRow 字段、ZIP 文件名格式、源码契约（manifest 列头、分目录、缺失标注、不删除原始、空证据、失败清理、UI 集成） |

### 测试基线

- **总计**：745 tests（+16 新增）
- **通过**：731 passed
- **失败**：14 failed（全部预存）
- **跳过**：5 skipped

---

## 5. 未改动范围

- DPM 解码算法不变
- CameraController/CameraX 不变
- Room migration 不新增（复用现有 dpm_scan_evidence 表）
- 检测结果 ZIP 内容不扩展
- 不新增人工 DPM 码值复核或 ROI 检测

---

## 6. SAF 导出空 ZIP 修复（2026-09-15）

### 根因与修复

`writeManifestCsv()` 使用 `OutputStreamWriter(zos).use`，其 `close()` 会关闭底层 `ZipOutputStream`。返回后服务再关闭 `manifest.csv` 条目时抛出异常，删除缓存 ZIP；但 `ACTION_CREATE_DOCUMENT` 已经预创建了目标文件，因此用户会看到空 ZIP。

- manifest writer 现在只执行 `flush()`，由外层 ZIP 流负责关闭和写中央目录。
- SAF `openOutputStream()` 返回 null 时明确失败，不再将未写入的目标当成功；写入异常、空证据或 ZIP 生成失败时，尽力删除 SAF 预创建的空文档。
- 新增真实归档回归测试：运行导出服务后实际打开 ZIP，检查图像字节、会话目录和 manifest 行。
- 工作区已有的 `DpmScanScreen.kt` 改动调用了 `saveEvidenceInScope()`，但 ViewModel 中原先缺少该方法，导致 Kotlin 编译失败。已在 `DpmScanViewModel` 补齐按 ViewModel scope 保存、完成后清理的回调，并将退出流程契约测试更新为验证先保存再清理。保留了 `DpmScanScreen.kt` 上既有未提交修改。

### 本轮验证与 Git

- `:app:testDebugUnitTest --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" --no-daemon`：通过，DPM 导出服务测试 17 项全部通过。
- `:app:testDebugUnitTest --no-daemon`：746 项完成，14 项失败、5 项跳过。失败数与此前报告的预存 14 项一致；新增 ZIP 回归测试和更新后的 DPM 退出流程契约测试通过。
- `git diff --check`：通过；仅有既有 CRLF 转换提示。
- 未运行 ADB、安装 APK 或真机复测；等待用户重新导出 ZIP 验收。
- 本轮提交仅包含本任务文件；工作区其余既存改动保留。

---

## 7. 纠正版 DPM 证据输入语义（2026-09-15）

DPM 扫码证据现在只由通过 ZXing `DataMatrixReader`/`Decoder` 或 ML Kit 内部 Data Matrix ECC 解码并产生非空码值的当前会话源帧创建。没有 ECC 成功时不落盘原图或 ROI，不创建 `NO_READ` 证据行；因此导出服务增加 SUCCESS、非空码值、有效来源和原图非空文件过滤，历史 NO_READ 或孤立路径不会进入独立 ZIP。DPM ZIP 仍位于独立证据目录，未合并到现场采集照片 ZIP，`InspectionZipExportService` 未修改。

本轮归档回归：`.\gradlew.bat :app:testDebugUnitTest --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" --no-daemon` 通过；其中真实 ZIP 测试写入成功证据和 NO_READ 文件/记录，解包后确认仅 SUCCESS 原图、ROI 和 manifest 行被导出。DPM 源帧 token/时间关联、异步 GRID 生命周期和无成功不保存语义详见 [`DPM_SCAN_EVIDENCE_REPORT.md`](DPM_SCAN_EVIDENCE_REPORT.md)。

## 2026-09-15 软件回归整改收口

**状态**：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**。本节记录本轮验收失败修复和最终软件门禁，不标记 `USER_ACCEPTED`；真实 DPM 扫码证据仍待确认。

### 实际修改文件与失败原因

- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`：将原先依赖源码字面量 `viewModel.stopScan()` 的断言改为实际调用 `runDpmScanExit(sessionId = null)`，验证无 sessionId 时执行 `stopScan`、回收 Bitmap，且不保存、不清理分析器、不 disconnect。
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/SafZipExportTest.kt`：原 Mockito 桩调用 Android `ContentResolver` final 方法，导致测试桩未拦截并触发 NPE；改用 Robolectric + 测试用 `ContentResolver` shadow，保留 SAF 空输出流异常、完整字节复制和失败清理语义。
- `tasks/todo.md`、`tasks/plan.md`、本报告及 `docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`：补充本轮回归结果、APK 信息、未执行真机声明和剩余风险。
- 生产导出与 DPM 生命周期逻辑本轮未因错误测试而放宽或改写；manifest 写入流关闭、SAF 空流/失败清理、批次 batchId 精确匹配及独立 DPM 空 batchId 合法导出语义保持不变。

### 最终验证与 APK

1. `.\gradlew.bat :app:compileDebugKotlin --no-daemon`：退出码 0，`BUILD SUCCESSFUL`。
2. `.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --tests "com.wearable.inspection.mobile.dpm.*" --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest" --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportServiceTest" --tests "com.wearable.inspection.mobile.ui.screens.DpmScanExit*" --tests "com.wearable.inspection.mobile.ui.screens.SafZipExportTest" --tests "com.wearable.inspection.mobile.ui.screens.CameraPreviewTest"`：退出码 0，222 tests，217 passed，0 failures，5 skipped。
3. `.\gradlew.bat :app:assembleDebug --no-daemon`：退出码 0，`BUILD SUCCESSFUL`。

APK：

- 执行 Agent 报告的绝对路径（主协调审阅时未能独立核验）：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk`
- 生成时间：2026-09-15 16:18:20 +08:00
- 文件大小：232,041,746 bytes
- SHA-256：`0B82E8E51048EA7B87EB10493F921DB8CCA33567287D6CDC96CEBC228A1A57D5`

本轮未执行 ADB、安装/卸载、启动/停止、connected instrumented test 或其他真机操作；未提交 Git，工作区其他改动均保留。

### 剩余风险

本轮仅完成软件回归整改，尚未进行真机视觉与实际 SAF 提供方验收；APK 安装、DPM 现场采集、系统文件选择器行为和导出 ZIP 的用户验收仍待执行。检测算法、CameraX 所有权及后续自动检测能力不在本轮范围内。

### 主协调审阅补充（2026-09-15）

当前工作区未找到上述 `app-debug.apk` 文件，报告中的 APK 时间、大小和 SHA-256 尚未独立核验。现阶段没有真实数据库行、DPM 证据文件或 ZIP 内容证据；下一步必须使用 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` 完成新 APK 的现场扫码和导出取证。现场导出前还必须确认异步保存已经输出 `persisted rowId`，避免把“保存尚未完成”误判为 ZIP 导出失败。以上是 2026-09-15 的历史审阅结论，已由下方 2026-09-16 闭环验证和用户验收取代。

## 2026-09-15 真实设备扫码与 ZIP 复核

本节更新为本轮实际设备证据；状态仍为 **PHYSICAL_ACCEPTANCE_PENDING**，不替代用户验收。

- 包名门禁通过：前台 `com.wearable.inspection.mobile/.MainActivity`，新包 PID `16456`，旧包 `com.wearable.inspection` PID 为空。使用 APK `app/build/outputs/apk/debug/app-debug.apk`，时间 `2026-09-15 17:56:29 +08:00`，大小 `232,676,681` bytes，SHA-256 `3AE7821D627AC21AF8C82D962D4D568CAEE23BDEB7083822539A6631D9ADEC3D`；设备安装 APK SHA-256 一致。
- 真实扫码落库：设备数据库快照 `evidence_dpm_current/06_mobile_inspection_db` 查询到 11 条 `dpm_scan_evidence` 行；11 条均为 `SUCCESS`、非空码值 `M968942280224B169AH005023044710`、`GRID`，并有非空原图和 ROI 路径。`files/dpm_evidence` 中对应 22 个文件均为非空 JPEG。
- 独立 ZIP：设备路径 `/storage/emulated/0/Download/inspection-flow-test/dpm_evidence_20260915_182204.zip`，3,433,021 bytes；本地证据 `evidence_dpm_current/14_independent_dpm.zip`，23 entries（22 张图 + `manifest.csv`），设备/本地 SHA-256 均为 `0E9120A97C373FB0EE432F833030890A37F7E74B060E612E1CE68DB7C04B3948`。按 DB `id`/`scanSessionId`/`frameTimeMs` 对应检查，22/22 个归档文件与 `files/dpm_evidence` 源文件字节一致。
- 批次 ZIP：本次同名避让文件为 `/storage/emulated/0/Download/inspection-flow-test/batch_batch_17 (2).zip`，本地证据 `evidence_dpm_current/24_batch_batch_17_(2)_actual.zip`，53,441,393 bytes，7 entries（6 张现场照片 + `inspection_result.csv`）。本次扫描的 11 条 DPM 记录 `batchId` 全部为 `NULL`，因此批次包 `dpm/` entries 为 0；生产代码的严格 `batchId == 当前 batchId` 过滤正确阻止跨上下文猜测关联。上一轮读取的 `batch_batch_17.zip` 是 2026-09-04 旧文件；`(2)` 才是 2026-09-15 本次导出的实际文件。
- 结论：本轮已经排除“成功扫码没有证据落库”和“独立 DPM ZIP 为空”；独立证据闭环为“成功码值 → 源帧/ROI 文件 → Room 行 → 可读 ZIP”。批次 ZIP 的 DPM 合并路径已由 JVM 真实归档测试覆盖，但本轮现场扫码未在有活跃 `batchId` 的采集上下文中进行，故不把批次 DPM 合并报告为设备实测通过。

证据文件：`docs/reports/b3/evidence_dpm_current/06_mobile_inspection_db`、`14_independent_dpm.zip`、`24_batch_batch_17_(2)_actual.zip`、`12_export_browser.xml`、`13_export_browser.png`、`20_batch_saved.png`。

## 2026-09-16 扫码会话绑定新建批次并导出验收

**状态：`USER_ACCEPTED`**。用户确认本闭环人机验收通过；本节结论取代前述“尚未进行有活跃 `batchId` 的批次验证”状态。扫码会话通过稳定 `scanSessionId` 暂存，匹配同一 `partId` 后在首张现场照片创建批次时绑定真实 `batchId`；不猜测、不补写旧 batchId。

- APK：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.wearable.inspection.mobile`；SHA-256 `fe4c910a9c151244d58433bea79a5c73c9e3e97d7fdbc7434225956cfe0eb1c5`。
- 设备：`ERLDU20429005890`。真实记录：`scanSessionId=ca802df7-5c40-4909-bfc7-8347700c12ff`、rowId `25`、`batchId=batch_1789537096005_7c122ec5`；DAO 绑定更新 1 行。
- 数据库为 SUCCESS、非空 DPM 码值及对应源帧/ROI 路径；批次 ZIP 含 `dpm/sessions/<scanSessionId>/` 下的帧图和 ROI 图，`inspection_result.csv` 含对应会话记录。
- 源帧与 ROI 文件在独立 DPM 证据目录和 ZIP 内的 SHA-256 分别一致（源帧 `cacf32ac971c5826d00385272094ebf6566d40c4b5ea6e93ed9c6c1dd33a7ba0`；ROI `98e5fea211097879572a57839b789bcd0d5f9b0720c4051b1893c8572be5373d`）。这里只核验数据库、文件和 ZIP 结构/字节，不对 JPG 画面内容作视觉结论。
- 测试结果：DPM tracker 13/13、DPM contract 24/24、ZIP archive 4/4、Workbench DPM binding 5/5；`:app:compileDebugKotlin` 与 `:app:assembleDebug` 通过。APK 大小 `232,677,354` bytes，构建时间 `2026-09-16 13:32:01 +08:00`。完整验证记录见 `tasks/todo.md`。

本次验收只关闭 DPM 扫码证据批次关联交付，不代表 ROI 最终结果/改判证据图交付已完成；后者是当前唯一进行中的任务。

### Git 收口

- DPM 扫码证据批次绑定闭环（含任务指针和验收文档）：`2e2c5943`。
- DPM 空 ZIP / SAF 写入失败清理及应用级保存作用域：`b7ac09c8`。
- DPM 扫码退出顺序、SAF 完整写入和 ZIP 条目回归补充：`62976e60`。
- 其他未能明确归入上述验收交付的工作区修改均未纳入提交，原样保留。
