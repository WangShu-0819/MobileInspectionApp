# 基础照片 ZIP 导出报告（按采集批次）

日期：2026-09-03
状态：**Phase 1 COMPLETE**（待用户验收）

## 问题描述

原方案将 `captures/` 目录下所有照片打包为一个 ZIP，无法区分零件和采集批次。

新需求：
- 按"零件 + 单次采集"导出基础照片 ZIP
- 建立可靠的 partId/batch 关联（采集时记录，非 UI 过滤）
- 老照片（无批次记录）作为"未关联"处理，不被导出
- 追溯记录界面按采集批次显示导出入口

## 实施方案

### 新增文件

| 文件 | 说明 |
|---|---|
| `data/entity/CaptureBatchEntity.kt` | 采集批次实体：batchId, partId, partName, startTime, endTime, viewCount |
| `data/entity/CapturedPhotoEntity.kt` | 已拍照片实体：photoId, batchId, filePath, viewIndex, templateId, templateName, capturedAt |
| `data/dao/CaptureBatchDao.kt` | 批次 DAO：observeAll, getById, observeByPartId, insert, update, delete |
| `data/dao/CapturedPhotoDao.kt` | 照片 DAO：observeByBatchId, getByBatchId, insert, delete, deleteByBatchId |

### 修改文件

| 文件 | 说明 |
|---|---|
| `data/db/AppDatabase.kt` | 版本 3→4，添加 CaptureBatchEntity + CapturedPhotoEntity，新增 DAO 抽象函数 |
| `data/db/Migrations.kt` | MIGRATION_3_4：CREATE TABLE capture_batches + captured_photos |
| `data/repository/InspectionRepository.kt` | 添加 captureBatchDao/capturedPhotoDao 参数，新增批次/照片 CRUD 方法 |
| `MobileInspectionApp.kt` | 更新 repository 构造参数 |
| `data/export/PhotoExportService.kt` | 重写为按批次导出：`exportBatchToZip(batchId, outputFile)` |
| `ui/screens/LiveInspectionScreen.kt` | 拍照时自动创建批次、记录照片、更新批次状态 |
| `ui/screens/TraceRecordsScreen.kt` | 显示采集批次列表，每个批次独立导出按钮 |
| `data/export/PhotoExportServiceTest.kt` | 更新测试：uniqueName + ExportResult + 批次相关测试说明 |

### 数据模型

```
capture_batches
├── batchId (PK)
├── partId (FK → parts.id, SET_NULL on delete)
├── partName
├── startTime
├── endTime
└── viewCount

captured_photos
├── photoId (PK, autoGenerate)
├── batchId (FK → capture_batches.batchId, CASCADE on delete)
├── filePath
├── viewIndex
├── templateId
├── templateName
└── capturedAt
```

### 批次生命周期

1. **创建**：首次拍照时自动创建（lazy），记录 partId、partName、startTime
2. **记录**：每次拍照后插入 CapturedPhotoEntity（filePath、viewIndex、templateId）
3. **更新**：所有视角完成后更新 endTime
4. **重置**：切换零件时 currentBatchId = null（下次拍照创建新批次）

### 导出流程

`PhotoExportService.exportBatchToZip(batchId, outputFile)`:
1. 查询 batchId 对应的所有 CapturedPhotoEntity
2. 空列表 → `ExportResult.Failure("该采集批次没有照片")`
3. 遍历照片：读取文件、去重命名、写入 ZIP（view_0.jpg, view_1.jpg...）
4. 返回 `ExportResult.Success(zipFile, photoCount, skippedCount)`

### 未关联老照片处理

旧照片（Migration 前存储在 captures/ 目录）没有批次记录，不会出现在任何批次导出中。这是设计意图：老照片无法追溯零件和批次信息。

## 测试结果

### Gradle 命令

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（12s） |
| `:app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL（39s） |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL（16s） |

### 测试覆盖

`PhotoExportServiceTest` — 9 项：
- uniqueName：原始名不重复、单个重复、多个重复、无扩展名、多点文件名、空扩展名
- ExportResult：Success 字段、Failure 消息、空批次 Failure

### Instrumented 测试建议

1. 批次隔离：batch A 的照片不出现在 batch B 的 ZIP 中
2. ZIP 条目命名：view_0.jpg, view_1.jpg 格式
3. 多视角导出：4 视角零件 → ZIP 包含 4 张照片
4. 跨零件批次：不同零件的批次独立导出
5. 失败路径：空批次返回 Failure
6. 未关联旧照片：不在任何批次中的照片不被导出

### APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-03

### 真机范围

`NOT_RUN_BY_SCOPE`（本轮禁止 adb）

## 验收标准对照

| 标准 | 状态 |
|---|---|
| 按零件+批次导出 ZIP | ✅ exportBatchToZip(batchId) |
| 采集时建立 partId/batch 关联 | ✅ CaptureBatchEntity + CapturedPhotoEntity |
| 老照片不被导出 | ✅ 无批次记录 = 不在导出范围 |
| 追溯记录按批次显示 | ✅ CaptureBatchCard 组件 |
| 每个批次独立导出按钮 | ✅ SAF per-batch |
| 切换零件自动新建批次 | ✅ LaunchedEffect(selectedPart?.id) |
| 三条 Gradle 命令全部通过 | ✅ |

## 未完成项

1. manifest + Excel + 图片完整结果包（当前产品边界暂缓）
2. 拍后比对（V1-3）、Detector（V1-4）、结果查看（V1-5）仍为 DEFERRED

## Git 状态

`NOT_COMMITTED`（阶段 1 不提交）
