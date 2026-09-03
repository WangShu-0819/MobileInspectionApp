# 模板 ROI 属性选择实现报告

## 任务概述

**状态**：SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS（2026-09-04，用户确认验收完成）

为模板配置中的每个 ROI 增加目标属性选择，用于后续选择一致的 ROI 检测算法：
- `THREAD` — 螺纹
- `NUT` — 螺母
- `FEATURE` — 部件

## 实现内容

### 1. 数据层

#### RoiTargetType 枚举
- 新增 `data/entity/RoiTargetType.kt`
- 三种属性类型：THREAD、NUT、FEATURE
- 中文显示名称：螺纹、螺母、部件
- `fromName()` 解析方法，无效值返回 null

#### RoiDefinitionEntity 更新
- 新增 `targetType: String? = null` 字段
- 旧 ROI 无属性时为 null，显示"未选择"
- 不得自动猜测旧 ROI 的属性类型

#### 数据库迁移
- 版本 4 → 5
- `MIGRATION_4_5`：`ALTER TABLE roi_definitions ADD COLUMN targetType TEXT`
- 旧数据保留，targetType 为 null

### 2. ViewModel 层

#### RoiEditorViewModel 更新
- 新增 `drawingTargetType` 状态：绘制时选择的目标属性
- `updateDrawingTargetType(type)`：更新绘制时的目标属性
- `saveDrawingRect()` 返回 Boolean：未选择 targetType 时返回 false
- `updateRoiTargetType(roiId, type)`：更新已有 ROI 的目标属性
- `toggleDrawingMode()` 和 `cancelDrawing()` 清除 drawingTargetType

### 3. UI 层

#### RoiEditorScreen 更新
- 绘制模式：显示目标属性选择器（"请选择" → 下拉选择）
- 选中 ROI：显示当前属性（"目标属性：螺纹"）和"修改"按钮
- 保存按钮：未选择属性时禁用
- 使用 DropdownMenu 实现属性选择

#### TemplateDetailScreen 更新
- ROI 列表显示 targetType（"螺纹"/"螺母"/"部件"/"未选择"）

### 4. 测试覆盖

#### RoiTargetTypeTest（10 项）
- 枚举值数量、displayName
- fromName 解析有效值、null、无效值
- displayName 不为空
- name 与 displayName 一一对应
- fromName 往返一致

#### RoiEditorViewModelTest 新增（12 项）
- RoiTargetType 枚举值正确
- fromName 解析有效值、null、无效值
- 旧 ROI 无 targetType 时为 null
- 新增 ROI 必须选择 targetType
- 新增 ROI 选择 targetType 后保存成功
- 更新已有 ROI 的 targetType
- 更新不存在的 ROI 的 targetType 不执行操作
- 取消绘制清除 targetType
- 切换绘制模式清除 targetType
- 不同 templateId 的 targetType 隔离

## 测试结果

```
:app:compileDebugKotlin --no-daemon    → BUILD SUCCESSFUL（36s）
:app:testDebugUnitTest --no-daemon     → BUILD SUCCESSFUL（441 项：全部 passed / 0 failed / 5 skipped）
:app:assembleDebug --no-daemon         → BUILD SUCCESSFUL（24s）
```

## 实际修改文件

| 文件 | 修改内容 |
|------|----------|
| `data/entity/RoiTargetType.kt` | 新增：ROI 目标属性类型枚举 |
| `data/entity/RoiDefinitionEntity.kt` | 增加 `targetType: String? = null` 字段 |
| `data/db/AppDatabase.kt` | 版本 4→5 |
| `data/db/Migrations.kt` | MIGRATION_4_5 |
| `ui/screens/RoiEditorViewModel.kt` | 增加 targetType 支持 |
| `ui/screens/RoiEditorScreen.kt` | 增加属性选择 UI |
| `ui/screens/TemplateDetailScreen.kt` | 显示 targetType |
| `test/.../RoiTargetTypeTest.kt` | 新增：10 项测试 |
| `test/.../RoiEditorViewModelTest.kt` | 新增 12 项测试 |

## 前序能力回归矩阵

| 能力 | 状态 | 说明 |
|------|------|------|
| ROI 新增 | ✅ | 保留，需选择 targetType |
| ROI 选中 | ✅ | 保留 |
| ROI 移动 | ✅ | 保留 |
| ROI 缩放 | ✅ | 保留 |
| ROI 边界约束 | ✅ | 保留 |
| ROI 删除 | ✅ | 保留 |
| normalizedRect | ✅ | 保留 |
| templateId 隔离 | ✅ | 保留 |
| 数据库迁移 | ✅ | v4→v5，旧数据保留 |

## 后续检测路由映射

- `THREAD` → Thread 检测
- `NUT` → Nut 检测
- `FEATURE` → Feature 检测

## 未完成项

- Detector/PASS-FAIL 集成（后续任务）
- 自动轮廓提取、自动对齐（DEFERRED）
- Session ROI、结果导出（DEFERRED）
- 模板导入时 ROI 的 targetType 设置（当前导入不创建 ROI）

## Git 状态

本轮提交 ROI 属性选择及任务交接文档；不提交拍照后人工确认任务的进行中改动。
