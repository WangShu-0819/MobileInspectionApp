# B2 采集批次删除功能报告

**状态**：**USER_ACCEPTED**（2026-09-18；用户完成人工验收并确认通过）

## 目标

在"追溯记录 → 采集批次"列表中，允许用户多选并批量删除不需要的零件采集批次及其关联数据和照片文件。

## 实际修改文件

| 文件 | 改动说明 |
|---|---|
| `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt` | 批次精确删除、路径安全和 `BatchDeletionResult`（已在前序工作区提交） |
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt` | 多选状态、确认删除、结果成功/失败处理和导出冲突保护；本次收口保留未提交改动 |
| `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt` | 筛选、多选与批量删除契约；本次收口保留未提交改动 |
| `app/src/androidTest/java/com/wearable/inspection/mobile/data/repository/BatchDeleteInstrumentedTest.kt` | 真实 Room CASCADE、文件隔离、路径安全和 DPM/模板保留；本次收口新增未跟踪文件 |

## 删除范围

### 数据库删除

- **主表**：`capture_batches` 按 `batchId` 精确删除
- **级联删除**（Room FOREIGN KEY CASCADE）：
  - `captured_photos` WHERE `batchId = :batchId`
  - `view_roi_confirms` WHERE `batchId = :batchId`

### 文件删除

- 查询 `captured_photos` 表获取照片 `filePath` 列表（在删除 DB 记录之前）
- 删除批次记录后，逐个删除照片实际文件
- 文件删除失败不阻塞整体操作（SecurityException 被捕获）

### 不删除的内容

- 导出的 ZIP 文件（SAF 用户选择路径，导出后临时文件已清理）
- 其他批次的照片和记录
- 模板图片、模板 ROI、零件记录
- InspectionSession 和 RoiInspectionRecord（不与 batchId 关联）

## 交互流程

1. 用户在"采集批次"列表中点击多个批次卡片或复选框 → 加入 `selectedBatchIds`，显示 Primary 边框 + BackgroundVariant1 背景高亮
2. 再次点击已选卡片或复选框 → 仅移除该批次，不影响其他选中项
3. 选中一个或多个批次后，"采集批次"标题右侧垃圾桶 IconButton 从灰色变为 FailColor 可点击
4. 点击垃圾桶 → 弹出 `DeleteBatchDialog`，显示选中数量、最多 3 个批次摘要和警告
5. 点击"取消" → 不产生任何变化
6. 点击"确认删除" → 按快照的稳定 `batchId` 逐个调用 `repository.deleteCaptureBatchCompletely(batchId)`
7. 选中批次中只要有一个正在导出 → 整体禁止删除，显示提示
8. 全部成功 → 刷新列表（Flow 自动）、清除选中集合、通过 Snackbar 显示实际删除数量
9. 中途失败 → 仅移除已成功删除的 ID，保留其余选中项并显示错误消息

## 测试覆盖

`CaptureBatchDeleteTest.kt` — 27/27 项数据删除隔离 JVM 测试；`BatchFilterAndDeleteTest.kt` — 61 项筛选与批量删除 JVM 测试，其中 60 项通过，1 项为既有“导出结果提示应使用固定高度”契约失败，未通过修改本任务源码规避：

| 类别 | 测试项 |
|---|---|
| batchId 精确匹配 | 4 项：完全匹配、不匹配不同字符串、不匹配前缀、不匹配空字符串 |
| 选中状态切换 | 3 项：同 batchId 取消、不同 batchId 切换、null 选中 |
| 照片路径收集 | 3 项：路径正确、批次隔离、空列表 |
| 空列表/已删除批次 | 3 项：空列表无目标、删除后不存在、选中已删除批次安全 |
| 导出冲突检测 | 3 项：同批次冲突、不同批次允许、未导出允许 |
| 批次实体字段 | 3 项：字段正确、null partId、startTime 正值 |
| 照片批次关联 | 3 项：batchId 匹配、viewIndex 保留、多照片同批次 |

`BatchFilterAndDeleteTest.kt` 额外覆盖：多选集合增删、按稳定 batchId 过滤、批量删除快照、选中导出冲突、全成功清空选中、部分失败保留未删除项、复选框和删除数量提示源码契约。

### 需 Instrumented 测试覆盖

- `BatchDeleteInstrumentedTest`：13/13 通过，覆盖 Room CASCADE 删除、真实 DB/文件删除、路径越界保护、其他批次隔离、模板/ROI 保留和 DPM 证据保留。
- TraceRecordsScreen 的多选、确认、取消、错误提示和批量删除交互已由用户在最终 APK 上人工验收通过。

## 前序能力回归矩阵

| 能力 | 状态 | 说明 |
|---|---|---|
| 追溯记录页今日统计 | ✅ 不变 | 未修改 TodayStatsCard |
| 采集批次列表显示 | ✅ 不变 | Flow 自动刷新，仅增加选中交互 |
| 批次 ZIP 导出 | ✅ 不变 | 导出逻辑未修改，增加批量删除冲突保护 |
| 现场采集拍照 | ✅ 不变 | 未修改 LiveInspectionScreen |
| 模板配置/ROI | ✅ 不变 | 未修改相关文件 |

## 未完成项

- 本任务无未完成项。
- Compose 专项布局测试未新增；本次依赖现有契约测试、instrumented 数据/文件测试和用户人工验收。
- Excel、Detector、ROI 检测、新 CameraX 和独立 DPM 包删除均不在本任务范围。

## 最终 APK 与人工验收

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 生成时间：2026-09-17 17:41:43（工作区文件时间）
- 大小：232,058,130 bytes
- SHA-256：`08a510d9907f00b908871b44424621b1a6cd76d5bb123b2c9932e3958a0e7444`
- 用户已确认追溯记录中的批次筛选、稳定 batchId 多选、确认删除、删除结果提示、导出冲突保护和数据隔离行为通过。
- 人工验收对象为新包 `com.wearable.inspection.mobile`；本报告不读取或视觉分析 PNG/JPG。

## Git 状态

本次收口提交仅包含以下明确路径：

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/data/repository/BatchDeleteInstrumentedTest.kt`
- `tasks/todo.md`
- `docs/reports/b2/BATCH_DELETE_REPORT.md`

其他工作区改动不纳入本次提交。
