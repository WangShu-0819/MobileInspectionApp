# DPM 扫码会话图像证据留存 — 实现报告

**任务**：DPM 扫码会话图像证据留存
**状态**：**USER_ACCEPTED**（2026-09-16；DPM 成功帧留存及其批次 ZIP 关联闭环已由用户验收。2026-09-15 的阶段记录作为历史保留。）
**日期**：2026-09-15

---

## 1. 目标

用户退出每次 DPM 扫码会话时，保存一组图像证据：

- **解码成功**：保存产生解码结果的那帧原始图 + ROI 裁切图
- **未解码**：保存最后一个有效分析帧的原始图 + ROI 裁切图

两种情况均持久化：`scanSessionId`、帧时间戳、帧来源、解码内容（可空）、`SUCCESS`/`NO_READ` 状态、解码来源（可空）。

---

## 2. 实现架构

### 2.1 数据层

| 文件 | 类型 | 说明 |
|------|------|------|
| `data/entity/DpmScanEvidenceEntity.kt` | 新文件 | Room 实体，表 `dpm_scan_evidence` |
| `data/dao/DpmScanEvidenceDao.kt` | 新文件 | DAO，insert + 按 sessionId 查询 |
| `data/db/AppDatabase.kt` | 修改 | 版本 6→7，注册新实体和 DAO |
| `data/db/Migrations.kt` | 修改 | `MIGRATION_6_7` 创建表和索引 |
| `data/repository/InspectionRepository.kt` | 修改 | 添加 `insertDpmScanEvidence()` / `getDpmScanEvidenceBySession()` |
| `data/image/MobileImageStore.kt` | 修改 | 添加 `saveDpmEvidenceFrame()` / `saveDpmEvidenceRoi()`，目录 `dpm_evidence/` |

### 2.2 DPM 扫码链

| 文件 | 修改点 | 说明 |
|------|--------|------|
| `dpm/DpmFrameAnalyzer.kt` | 新增字段 | `lastFrameBitmap`、`successBitmap`、`successRoi`、`successResult` |
| `dpm/DpmFrameAnalyzer.kt` | `analyze()` | 分析前复制帧 bitmap，解码成功时额外保存成功帧副本 |
| `dpm/DpmFrameAnalyzer.kt` | `getAndClearEvidenceFrames()` | 返回证据帧（优先成功帧），调用方接管 bitmap 所有权 |
| `dpm/DpmScanViewModel.kt` | 新增依赖 | `MobileImageStore`、`AppDatabase`、`DpmScanEvidenceDao` |
| `dpm/DpmScanViewModel.kt` | `getEvidenceFrames()` | 从 analyzer 提取证据帧 |
| `dpm/DpmScanViewModel.kt` | `saveEvidence()` | 保存 JPEG + ROI 裁切 + Room 持久化，`evidenceSaved` 防重复 |
| `ui/screens/DpmScanScreen.kt` | `DisposableEffect` | 退出流程：证据提取 → 保存 → stopScan → clearFrameAnalyzer → disconnect |

### 2.3 Room Migration v6→v7

```sql
CREATE TABLE IF NOT EXISTS dpm_scan_evidence (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    scanSessionId TEXT NOT NULL,
    frameTimeMs INTEGER NOT NULL,
    frameSource TEXT NOT NULL,
    decodedContent TEXT,
    status TEXT NOT NULL,
    decodeSource TEXT,
    originalImagePath TEXT NOT NULL,
    roiImagePath TEXT,
    createdAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_dpm_scan_evidence_scanSessionId
    ON dpm_scan_evidence (scanSessionId);
```

---

## 3. 关键设计决策

### 3.1 Bitmap 生命周期安全

- `DpmFrameAnalyzer.analyze()` 在 `analyzerScope.launch` 内立即复制帧 bitmap（`bitmap.copy()`），原始 bitmap 在 `finally { bitmap.recycle() }` 中回收
- 解码成功时，从已复制的 `frameCopy` 再次 `copy()` 作为 `successBitmap`，独立管理生命周期
- `getAndClearEvidenceFrames()` 返回的 bitmap 由调用方接管，保存后必须 `recycle()`

### 3.2 退出流程顺序

```
DisposableEffect.onDispose {
    evidenceFrames = viewModel.getEvidenceFrames()  // 1. 提取证据（analyzer 仍存活）
    coroutineScope.launch {
        viewModel.saveEvidence(sid, evidenceFrames)  // 2. 持久化（suspend）
        viewModel.stopScan()                          // 3. 停止 analyzer
        cameraController.clearFrameAnalyzer()         // 4. 清理回调
        cameraController.disconnect(sid)              // 5. 断开相机
    }
}
```

### 3.3 竞态处理

- **重复退出**：`evidenceSaved` volatile 标记拦截
- **迟到回调**：`DpmFrameAnalyzer.stop()` 设置 `isStopped = true` 后，迟到帧被丢弃
- **onCleared 兜底**：ViewModel 销毁时尽力保存证据（异步）
- **写入失败**：catch + bitmap 回收，不丢图

### 3.4 ROI 裁切

- 使用与 `DpmAnalyzer.cropRoi()` 相同的 `clampRect` 逻辑
- 帧 ROI 坐标（分析时追踪的 `scanRoi`）直接用于裁切已保存的帧 bitmap
- 无 ROI 时不保存裁切图（`roiImagePath = null`）

---

## 4. 测试覆盖

### 新增测试（35 项，全部通过）

| 测试文件 | 测试数 | 覆盖范围 |
|----------|--------|----------|
| `DpmScanEvidenceEntityTest.kt` | 10 | 实体字段、SUCCESS/NO_READ、可空字段、createdAt |
| `DpmScanEvidenceContractTest.kt` | 15 | 源码契约：退出顺序、bitmap 复制时序、Room migration、DAO/存储方法 |
| `DpmFrameAnalyzerEvidenceTest.kt` | 10 | 证据帧提取、EvidenceFrames 数据类、幂等性、stop 安全性 |

### 测试基线

- **总计**：729 tests（+35 新增）
- **通过**：710 passed
- **失败**：14 failed（全部预存，与本任务无关）
- **跳过**：5 skipped

---

## 5. 未改动范围

- 扫码算法（ZXing/ML Kit/网格重建）不变
- DPM 人工码值复核未实现
- ZIP 导出未扩展 DPM 证据
- ROI 检测算法不变
- 14 项预存测试失败未修复（不属于本任务边界）

---

## 6. 文件清单

### 新文件
- `app/src/main/java/com/wearable/inspection/mobile/data/entity/DpmScanEvidenceEntity.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/entity/DpmScanEvidenceEntityTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzerEvidenceTest.kt`

### 修改文件
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/db/AppDatabase.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/db/Migrations.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt`
- `app/src/main/java/com/wearable/inspection/mobile/MobileInspectionApp.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/data/dao/PartDpmDaoTest.kt`（修复预存缺失 DAO 参数）

---

## 7. 纠正版：仅保存 ECC 成功源帧（2026-09-15）

本节覆盖上文“未解码保存最后有效帧/NO_READ”语义。当前实现以本轮纠正版为准：没有 ECC 成功时不保存任何照片，也不创建带图片路径的证据记录。

### 7.1 ECC 所在解码层

- ZXing 主链调用 `ZxingDataMatrixDecoder.decodeWithBinarizer()` → `DataMatrixReader.decode(...)`；Data Matrix ECC 校验/纠错在 ZXing 内部完成。
- GRID 兜底调用 `ImportedDpmScanner.decodePureBits()` → ZXing `Decoder().decode(matrix)`；同样由 ZXing Data Matrix 解码器完成 ECC。
- ML Kit 兜底把非空码值视为 ML Kit 已接受的成功结果；其 ECC/纠错由 ML Kit 内部完成，应用不读取或虚构纠错次数。
- ECC 不会生成像素被修正后的照片；保存的是产生成功码值的原始 upright Bitmap。

### 7.2 成功源帧关联与会话隔离

`DpmEvidenceFrameTracker` 为每个 upright 源帧复制一份 Bitmap，使用递增 `frameToken`、源帧时间和当时的 scan ROI 建立关联。同步 ZXing/ML Kit 结果直接携带当前 token；`DpmAnalyzer.triggerGridDecode()` 在提交任务时发出 `SUBMITTED(token)`，异步成功结果携带同一 token，`FINISHED`/取消/超时回收对应临时帧。tracker 只接受当前会话的 `DECODED`、非空码值；成功 token 一经选定，后续普通帧不能覆盖。

退出时先调用 `getAndClearEvidenceFrames()` 取消并使 GRID 任务代次失效，再冻结成功快照；随后保存原图和 scan ROI，保存完成后才 stop analyzer 并断开 CameraController。stop、会话结束及冻结后的迟到 GRID 结果均被丢弃；未转移给保存层的 Bitmap 在成功、失败、取消、超时、异常和退出路径回收。

### 7.3 无 ECC 成功与保存失败语义

- 若会话没有 ECC 成功，`getAndClearEvidenceFrames()` 返回 `null`，仅保留内存扫描状态；不写原始帧、ROI、二值化/预处理/标注图，不创建 `NO_READ` 行，不引用 `lastFrameBitmap`。
- 成功证据必须同时包含 `DECODED`、非空 `decodedContent`、`decodeSource`（`ZXING`/`ML_KIT`/`GRID`）和当前会话源帧 token。数据库状态当前仅写 `SUCCESS`，`frameTimeMs` 使用源帧时间。
- 原图压缩失败、ROI 写入失败或数据库插入失败时删除已写文件并回收 Bitmap，不留下孤立文件或伪造记录。
- `DpmEvidenceExportService` 只导出状态为 `SUCCESS`、码值/来源有效且原图为非空实际文件的记录；DPM ZIP 仍独立保存，不修改现场采集照片 ZIP 或 `InspectionZipExportService`。

### 7.4 本轮修改文件与测试

源码/测试修改：

- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmAnalyzer.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmGridGate.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmEvidenceFrameTracker.kt`（新增）
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/entity/DpmScanEvidenceEntity.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt`
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmEvidenceFrameTrackerTest.kt`（新增）
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmAnalyzerTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzerEvidenceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportArchiveTest.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/dpm/DpmScanEvidencePersistenceInstrumentedTest.kt`（新增）

真实命令与结果：

1. `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin --no-daemon`：通过。
2. `.\gradlew.bat :app:testDebugUnitTest --tests "com.wearable.inspection.mobile.dpm.*" --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" --no-daemon`：182 项执行、0 失败、5 跳过。
3. `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.dpm.DpmScanEvidencePersistenceInstrumentedTest" --no-daemon`：YAL-AL10（Android 10），3/3 通过；真实检查无 ECC 时无文件/无数据库行、成功源帧与 ROI 文件内容/尺寸、写入失败无孤立文件。
4. `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon`：通过。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-15 10:19:02 +08:00，276,579,040 bytes，SHA-256 `3B68B891368304A87CCA5B9C22BF2458632D286D593B96A32E84A39EDBD9C961`。测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，2026-09-15 10:30:04 +08:00，12,547,784 bytes，SHA-256 `FEB712045D68FB52361D0C89BB22471DE8AAE0A047251117ABA44A9788B3BCAB`。

每次 connected test 结束后均执行旧/新包 force-stop、显式安装主 APK、显式启动 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` 并核对包名/PID。旧包 PID 为空；本工作区已有 `CameraPreview.kt:255` 在启动时调用 `PreviewView.getSurfaceProvider()` 的非主线程崩溃，导致恢复后的新包进程退出、前台回到 launcher，故本轮没有把启动前台状态报告为通过，也未修改该相机架构问题。构建时的 `-lncnn` 非系统库 warning 为既有 native 配置提示，与本项 DPM 逻辑无关。

本项未新增 Room schema/migration；沿用现有 `dpm_scan_evidence` 表。未实现自定义 ECC、纠错像素图、DPM 人工码值复核或现场采集 ZIP 合并。工作区其他改动保留，未提交 Git，等待用户验收。

## 2026-09-15 真实设备取证补充

使用新包 `com.wearable.inspection.mobile` 和 APK `app/build/outputs/apk/debug/app-debug.apk`（2026-09-15 17:56:29 +08:00，232,676,681 bytes，SHA-256 `3AE7821D627AC21AF8C82D962D4D568CAEE23BDEB7083822539A6631D9ADEC3D`）完成现场扫码。前台为 `com.wearable.inspection.mobile/.MainActivity`，新包 PID `16456`，旧包 PID 为空。

设备数据库快照 `evidence_dpm_current/06_mobile_inspection_db` 有 11 条 DPM 成功行；每条均为非空码值、`SUCCESS`、`GRID`，并对应非空原图/ROI 文件。独立导出 `/storage/emulated/0/Download/inspection-flow-test/dpm_evidence_20260915_182204.zip` 解包得到 23 entries（22 张图 + `manifest.csv`），本地副本 `14_independent_dpm.zip` 与设备文件 SHA-256 均为 `0E9120A97C373FB0EE432F833030890A37F7E74B060E612E1CE68DB7C04B3948`。

本次扫描入口没有显式 `batchId`，11 条记录的 `batchId` 均为 `NULL`；因此实际批次包 `24_batch_batch_17_(2)_actual.zip` 不含 `dpm/` entries。这符合当前稳定关联边界：批次导出只接受启动时显式传入且严格相等的 `batchId`，不按零件、时间或列表位置推断。批次合并仍需在有活跃 batchId 的现场采集上下文实测，当前不标记为设备实测通过。

## 2026-09-15 结果包关联补充

现场采集结果 ZIP 已接入现有 DPM 成功证据，但独立 `DpmEvidenceExportService` ZIP 保持不变。批次导出只消费 `batchId` 严格相等、状态 `SUCCESS`、非空码值、合法 `ZXING/ML_KIT/GRID` 来源且源帧真实非空的证据；NO_READ、无 batchId、跨批次和缺失源帧不进入批次 DPM 图片目录。DPM 原图和 ROI 仍从 `filesDir/dpm_evidence` 原路径按字节复制，未改变扫码算法、ECC 语义或会话保存流程。

本补充未修改 DPM 解码/留存源码；结果包收口修改为 `InspectionZipExportService.kt`、`InspectionExcelExporter.kt`，真实归档回归为 `InspectionZipExportArchiveTest.kt`。定向结果 JVM 104/104 通过；全量 JVM 792 项完成、13 项失败、5 项跳过，失败属于工作区既有并行改动/基线断言。未运行 ADB、connectedDebugAndroidTest 或真机；APK 信息和真实 ZIP 字节哈希见 [`DPM_EVIDENCE_EXPORT_REPORT.md`](DPM_EVIDENCE_EXPORT_REPORT.md) 和 [`RESULT_TRACEABILITY_PLAN.md`](../b2/RESULT_TRACEABILITY_PLAN.md)。

## 2026-09-15 批次 ZIP 显式关联扩展

新增 DPM 关联字段的真实 Room migration：v8→v9 增加可空 `batchId/partId/templateId/viewIndex/photoId/roiId`，旧行全部保持 null；schema 文件为 `app/schemas/com.wearable.inspection.mobile.data.db.AppDatabase/9.json`。DPM 扫描入口只把启动时调用方显式快照传给 ViewModel，DPM 扫描 ROI 不伪造模板 `roiId`。

`InspectionZipExportService` 仅查询明确相等 batchId 的证据，并再次过滤 SUCCESS、非空码值、合法 ZXING/ML_KIT/GRID 来源和真实源帧路径；原图/ROI 由独立证据目录原样复制，不重新压缩。独立 `DpmEvidenceExportService` 仍按 scanSessionId 生成独立 ZIP。真实归档样例：`C:\Users\ws\AppData\Local\Temp\inspection-export9834766818192113490`；隔离样例：`C:\Users\ws\AppData\Local\Temp\inspection-export-isolation11035632669832920657`。`ZipInputStream` 确认两个 ZIP 的 DPM 成功帧和 ROI 字节完全相同，NO_READ/无 batchId 不进入批次 ZIP，manifest/CSV 均真实存在且可解压。

本轮验证：定向 JVM 81 项通过；`:app:compileDebugKotlin`、`:app:assembleDebug` 通过。APK：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk`，2026-09-15 12:07:35 +08:00，276579040 bytes，SHA-256 `6E8653FEDC6AEC5B7757C9FB1A842724DC5D1EEB2F89161393BA226A89EE2345`。未运行 connectedDebugAndroidTest、ADB 或真机，因此无本轮设备门禁证据；全量既有 14 项失败、5 项跳过沿用前序报告。结果包关联实现已提交 Git：`f723da0e`。

## 2026-09-15 软件回归整改收口

**状态**：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**。本节覆盖本轮对上一次验收失败的测试整改；不表示真实设备或用户已验收。

### 实际修改文件

- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/SafZipExportTest.kt`
- `tasks/todo.md`
- `tasks/plan.md`
- `docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`
- `docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`

未修改 DPM 生产代码；`DpmScanScreen.kt`、`DpmScanViewModel.kt`、`DpmEvidenceExportService.kt`、`InspectionZipExportService.kt` 和 `CameraPreview.kt` 仅作必要复核。

### 失败原因与修复

- `DpmScanEvidenceContractTest.exit flow cleans up even without sessionId` 不再检查 `viewModel.stopScan()` 源码字面量，改为调用 `runDpmScanExit`，用真实 Robolectric Bitmap 验证无 sessionId 时执行一次 stop、回收 Bitmap，并且不触发 save、clear analyzer 或 disconnect。
- `SafZipExportTest` 的 3 项 NPE 来自 Mockito 对 Android `ContentResolver` final 方法的测试桩未覆盖实际平台调用，不是生产逻辑问题。测试改用 Robolectric Fake ContentResolver shadow，明确提供 null 输出流、正常 ByteArrayOutputStream 和 delete 记录，保留 null 异常、完整字节复制、临时 ZIP/SAF 文档清理语义。

### 生产代码回归检查

- `DpmScanScreen` 保持 `DisposableEffect(Unit)`，退出读取最新 sessionId。
- 顺序保持 `getEvidenceFrames → saveEvidence → stopScan → clearFrameAnalyzer → disconnect`；`CameraPreview` 的 `disconnectOnDispose = false` 保证 DPM 证据快照前不抢先断开。
- 保存继续使用受控 Application scope；Bitmap 在文件和数据库处理结束后回收。
- 保存失败删除半成品文件，不插入可导出的 SUCCESS；不保存 NO_READ、空码值、ECC 失败、`lastFrameBitmap` 或旧 session。
- 批次 ZIP 仍只接受明确相等的 batchId；独立 DPM ZIP 仍允许合法但 batchId 为空的 SUCCESS 证据。未修改 DPM 解码算法和 CameraX 所有权。

### 最终验证

1. `.\gradlew.bat :app:compileDebugKotlin --no-daemon`：退出码 0，BUILD SUCCESSFUL。
2. `.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --tests "com.wearable.inspection.mobile.dpm.*" --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*" --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest" --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportServiceTest" --tests "com.wearable.inspection.mobile.ui.screens.DpmScanExit*" --tests "com.wearable.inspection.mobile.ui.screens.SafZipExportTest" --tests "com.wearable.inspection.mobile.ui.screens.CameraPreviewTest"`：退出码 0，222 项，217 passed / 0 failed / 5 skipped。
3. `.\gradlew.bat :app:assembleDebug --no-daemon`：退出码 0，BUILD SUCCESSFUL。

执行 Agent 报告的 APK 信息（主协调审阅时未能独立核验）：路径 `D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk`；生成时间：`2026-09-15 16:18:20 +08:00`；大小：`232,041,746 bytes`；SHA-256：`0B82E8E51048EA7B87EB10493F921DB8CCA33567287D6CDC96CEBC228A1A57D5`。当前工作区未找到该 APK 文件。

本轮未执行 ADB、安装/卸载 APK、启动/停止应用、connectedDebugAndroidTest 或其他真机操作；未提交 Git，工作区其他改动保留。剩余风险：缺少本轮真机/物理证据，需主协调 Agent 审阅并由用户验收；非本轮相关的全量测试历史失败不在本轮整改范围内。

### 主协调审阅补充（2026-09-15）

以上为 2026-09-15 的历史审阅结论；2026-09-16 已由用户验收的现场闭环取代，设备、数据库、源文件与 ZIP 证据见本报告的 DPM 证据导出报告及 `tasks/todo.md`。

## 2026-09-16 DPM 退出与 ZIP 写入回归收口

本节记录 DPM 批次闭环验收前后完成的可靠性收口，不改变 DPM 解码算法或批次绑定规则：`CameraPreview` 可由 DPM 页面接管 disconnect 时机，确保先快照并保存成功帧再释放相机；退出流程覆盖有/无 sessionId 及读取最新 sessionId；ZIP 导出预检过滤缺失/空文件，若文件在检查后、复制时失效则明确失败，避免 CSV 指向实际未写入的 ZIP 条目；SAF 保存校验实际写入字节数，空流、短写或失败时清理预创建文档和临时 ZIP；DPM 日志仅记解码值长度，不输出码值。

对应源码与 JVM 测试：`CameraPreview.kt`、`DpmAnalyzer.kt`、`InspectionZipExportService.kt`、`TraceRecordsScreen.kt`、`CameraPreviewTest.kt`、`DpmScanExitFlowTest.kt`、`DpmScanExitLifecycleComposeTest.kt`。验证记录沿用上文定向命令：`compileDebugKotlin`、DPM/归档/SAF/CameraPreview 定向 JVM（222 项，217 passed、0 failed、5 skipped）及 `assembleDebug` 均通过。该轮未执行 connected instrumented test；独立设备闭环证据及 APK/数据库/ZIP 哈希见 [`DPM_EVIDENCE_EXPORT_REPORT.md`](DPM_EVIDENCE_EXPORT_REPORT.md)。

主协调提交不包含尚未执行的 Instrumented 测试文件改动，也不纳入截图、原始媒体或其他未明确归属的工作区产物。
