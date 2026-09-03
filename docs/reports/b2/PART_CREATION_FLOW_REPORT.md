# 模板配置：先创建零件，再导入模板 — 整改报告

日期：2026-09-03
状态：**IN_PROGRESS**（待用户验收）

## 问题描述

### 第一轮（14:21）

进入"我的 → 模板配置"后，必须先选择模板图片，才能在导入对话框中创建零件。
期望流程：模板配置 → 新建零件 → 零件详情 → 导入或拍摄多个 View。

### 第二轮（14:56）

打开"我的 → 模板配置"后，页面同时显示"新建零件"和"导入模板"入口，顶部文字/按钮发生遮挡。
需求：模板配置零件列表页只保留"新建零件"入口，移除"导入模板"按钮。"导入模板包"独立页面不受影响。

## 实施方案

### 第一轮修改文件

| 文件 | 改动说明 |
|---|---|
| `PartListScreen.kt` | 新增 `onPartCreated` 回调；TopAppBar 添加"新建零件"IconButton；按钮行改为双按钮布局；新增独立"新建零件"AlertDialog；新增 `PartCreationValidator` 校验工具 |
| `AppNavigation.kt` | TemplateConfig 和 PartList 路由均新增 `onPartCreated` 回调 |
| `PartCreationValidatorTest.kt` | 新增 18 项单元测试 |

### 第二轮修改文件

| 文件 | 改动说明 |
|---|---|
| `PartListScreen.kt` | 移除"导入模板"按钮、图片选择器、导入对话框和所有导入相关状态；按钮行改为单个全宽"新建零件"按钮；移除未使用的 `database` 变量和 5 个 import；EmptyPartsState 文案更新 |

**未修改文件**：PartDetailScreen.kt、AppNavigation.kt、TemplatePackageScreen.kt、Screen.kt、InspectionRepository.kt

### 前序必须保留的能力

| 能力 | 状态 |
|---|---|
| PartCard 点击 → PartDetail 导航 | ✅ 不回归 |
| DPM 绑定入口（TopAppBar + PartCard） | ✅ 不回归 |
| Lifecycle ON_RESUME 自动刷新 | ✅ 不回归 |
| 空零件状态（EmptyPartsState） | ✅ 不回归 |
| PartDetailScreen：导入/拍摄/删除/ROI 编辑 | ✅ 不回归（未修改） |
| TemplatePackages 独立页面/路由 | ✅ 不回归（未修改） |
| TemplateImportService 事务回滚 | ✅ 不回归（未修改） |
| 唯一 CameraX 架构 | ✅ 不回归（未修改） |

### 校验逻辑

`PartCreationValidator.validate(id, name, idExists)` 校验顺序：

1. `id.isBlank()` → "请输入零件 ID"
2. `name.isBlank()` → "请输入零件名称"
3. `!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))` → "零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）"
4. `idExists` → "该零件 ID 已存在"

### 创建流程

```
用户点击"新建零件"按钮或 TopAppBar "+" → 弹出对话框 → 输入 ID + 名称
→ 客户端校验（空值/非法字符） → 异步检查重复 ID
→ repository.upsertPart(PartEntity) → reload() → onPartCreated(id)
→ 导航到 PartDetailScreen(partId)
```

## 测试结果

### Gradle 命令

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin --no-daemon` | ✅ BUILD SUCCESSFUL（52s） |
| `:app:testDebugUnitTest --no-daemon` | ✅ BUILD SUCCESSFUL（397 项：全部 passed / 0 failed / 0 skipped） |
| `:app:assembleDebug --no-daemon` | ✅ BUILD SUCCESSFUL（29s） |

### 测试覆盖

`PartCreationValidatorTest` — 18 项：

- 空 ID / 空白 ID
- 空名称 / 空白名称
- 非法字符（中文、空格、特殊字符）
- 重复 ID
- 合法输入（字母、数字、下划线、连字符、混合）
- 单字符 ID
- 最大长度 64 位 / 超长 65 位
- 名称无字符限制
- 重复检查优先级

### APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-03 14:56
- 大小：221,315,919 bytes（~211 MB）
- SHA-256：`2a32ee15784734a8c719f3c3e33598788066d5c3673f1d90980bfbaab88c458b`

### 真机范围

`NOT_RUN_BY_SCOPE`（本轮禁止 adb）

## 验收标准对照

| 标准 | 状态 |
|---|---|
| 模板配置零件列表只保留"新建零件"入口 | ✅ 移除"导入模板"按钮，仅保留一个全宽"新建零件"按钮 + TopAppBar "+" |
| 移除"导入模板"按钮避免顶部遮挡 | ✅ 按钮行从双按钮改为单按钮 |
| "导入模板包"独立页面/路由/功能不受影响 | ✅ TemplatePackageScreen 和 ProfileScreen 入口未修改 |
| 新建对话框能真实保存零件 | ✅ 通过 Repository.upsertPart |
| 空 ID、空名称、非法 ID、重复 ID 有明确提示 | ✅ PartCreationValidator 18 项测试 |
| 新建成功后进入正确的零件详情页 | ✅ onPartCreated → navigate to PartDetail |
| 零件无 View 时显示 0 个视角空状态 | ✅ PartDetailScreen EmptyViewsState |
| 从详情页导入多张图片后 partId 正确 | ✅ PartDetailScreen 未修改 |
| 原有 View 顺序、缩略图、拍摄和 ROI 不回归 | ✅ PartDetailScreen 未修改 |
| 不产生重复零件、无图片模板或孤儿文件 | ✅ 仅 upsertPart，不创建模板 |
| 三条 Gradle 命令全部通过 | ✅ |

## 未完成项

1. 拍后比对（V1-3）、Detector（V1-4）、结果查看（V1-5）仍为 DEFERRED

## Git 状态

`NOT_COMMITTED`（未获用户明确授权前不提交）

## 前序能力回归矩阵

| 能力 | 验证方式 | 状态 |
|---|---|---|
| PartCard 点击导航 | 代码审查：onPartClick 未修改 | ✅ |
| DPM 绑定 TopAppBar + PartCard | 代码审查：onBindDpm 未修改 | ✅ |
| Lifecycle ON_RESUME reload | 代码审查：DisposableEffect 未修改 | ✅ |
| PartDetailScreen 空状态 | 代码审查：EmptyViewsState 未修改 | ✅ |
| PartDetailScreen 导入/拍摄/删除 | 代码审查：PartDetailScreen.kt 未修改 | ✅ |
| TemplatePackages 独立页面 | 代码审查：TemplatePackageScreen.kt 和 ProfileScreen 入口未修改 | ✅ |
| TemplateImportService 事务 | 代码审查：未修改 | ✅ |
| 唯一 CameraX 架构 | 未涉及相机文件 | ✅ |
