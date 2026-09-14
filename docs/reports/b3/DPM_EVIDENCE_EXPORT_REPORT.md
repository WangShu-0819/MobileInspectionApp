# DPM 扫码证据可操作导出 — 实现报告

**任务**：DPM 扫码证据可操作导出
**状态**：实现完成，自动化验证通过
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
- 不新增人工 DPM 码值复核、ROI 检测或 MobileSAM
