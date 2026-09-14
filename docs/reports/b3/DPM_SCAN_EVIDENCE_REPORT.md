# DPM 扫码会话图像证据留存 — 实现报告

**任务**：DPM 扫码会话图像证据留存
**状态**：实现完成，自动化验证通过
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
- MobileSAM 实验冻结
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
