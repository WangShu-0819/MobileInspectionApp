# DPM 扫码证据可操作导出 — 实现报告

**任务**：DPM 扫码证据可操作导出
**状态**：**USER_ACCEPTED**（用户确认空 ZIP 修复后导出正常）
**日期**：2026-09-15

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
