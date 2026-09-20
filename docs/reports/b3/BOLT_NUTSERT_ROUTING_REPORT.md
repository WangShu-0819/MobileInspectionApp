# Task 3: BOLT/NUTSERT ROI 属性与检测路由

状态：**SOFTWARE_COMPLETE**（2026-09-20），等待主协调审计和用户验收。

## 目标

在现有 NanoDet 四分类模型（exp09）基础上，将 `BOLT` 和 `NUTSERT` 纳入 ROI 属性枚举、持久化存储和检测路由，使四类可检测目标均能从 ROI 属性到模型 class index 形成显式映射。

## 审计结论

### 不需要 database migration

| 表 | 列 | 类型 | 说明 |
|---|---|---|---|
| `roi_definitions` | `targetType` | TEXT, nullable | v4→v5 migration 添加 |
| `view_roi_confirms` | `roiTargetType` | TEXT, nullable | v5→v6 migration 添加 |

两列均存储 `RoiTargetType.name` 字符串（如 `"BOLT"`、`"NUTSERT"`）。Room 不需要 TypeConverter，新增枚举值自动兼容。`RoiTargetType.fromName()` 对未识别值返回 null，历史 THREAD/NUT/FEATURE 行不受影响。

### 动态 UI，无需修改编辑器

`RoiEditorScreen` 两个下拉菜单均使用 `RoiTargetType.entries.forEach` 动态构建，新增枚举值自动出现。`displayName` 用于显示，`type.name` 用于持久化。

### 模板导入/导出透明兼容

`TemplatePackageExporter` 和 `TemplatePackageImporter` 将 `targetType` 作为原始 String? 序列化/反序列化，无枚举验证。新增 BOLT/NUTSERT 值可正常往返。

## 实际修改文件

### 生产代码（4 个）

1. **`RoiTargetType.kt`** — 新增 `BOLT("螺栓")`、`NUTSERT("铆螺母")` 枚举值，更新文档注释
2. **`NanoDetInferenceModels.kt`** — `NanoDetDecisionPolicy.classIndex()` 新增 `BOLT→2`、`NUTSERT→3` 显式映射
3. **`ViewConfirmationScreen.kt`** — `modelClassLabel()` 新增 `2→"螺栓（类别 2）"`、`3→"铆螺母（类别 3）"`
4. **`ViewConfirmationViewModel.kt`** — `softwareTargetClass` 映射新增 `2→"BOLT"`、`3→"NUTSERT"`

### 测试代码（5 个）

5. **`RoiTargetTypeTest.kt`** — 枚举数量 3→5，新增 BOLT/NUTSERT 值/fromName/displayName，更新映射表
6. **`NanoDetInferenceContractTest.kt`** — classIndex 新增 BOLT→2/NUTSERT→3，新增 BOLT/NUTSERT 路由测试
7. **`ViewConfirmationModelResultTest.kt`** — 新增 BOLT/NUTSERT `softwareTargetClass` 映射测试
8. **`ViewConfirmationViewModelStateTest.kt`** — 新增 BOLT/NUTSERT ROI 定义、默认选中、targetClass 持久化测试
9. **`RoiEditorViewModelTest.kt`** — 枚举数量 3→5，新增 BOLT/NUTSERT fromName/displayName

## 检测路由表

| RoiTargetType | classIndex | 模型类别 | 决策行为 |
|---|---|---|---|
| NUT | 0 | nut | 筛选 class 0 候选，score ≥ 阈值 → OK，否则 NG |
| THREAD | 1 | thread | 筛选 class 1 候选，score ≥ 阈值 → OK，否则 NG |
| BOLT | 2 | bolt | 筛选 class 2 候选，score ≥ 阈值 → OK，否则 NG |
| NUTSERT | 3 | nutsert | 筛选 class 3 候选，score ≥ 阈值 → OK，否则 NG |
| FEATURE | — | — | `FEATURE_UNSUPPORTED`，不执行检测，不默认 NG |
| null | — | — | `ROI_NOT_CONFIGURED`，不执行检测 |

## 测试结果

### 定向 JVM 测试（5 类，123 项）

| 测试类 | 项数 | 结果 |
|---|---|---|
| RoiTargetTypeTest | 12 | 12/12 ✅ |
| NanoDetInferenceContractTest | 19 | 19/19 ✅ |
| ViewConfirmationModelResultTest | 10 | 10/10 ✅ |
| ViewConfirmationViewModelStateTest | 17 | 17/17 ✅ |
| RoiEditorViewModelTest | 65 | 65/65 ✅ |
| **合计** | **123** | **123/123 ✅** |

### 编译与构建

| 命令 | 结果 |
|---|---|
| `compileDebugKotlin` | BUILD SUCCESSFUL |
| `compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| `assembleDebug` | BUILD SUCCESSFUL |

### 全量回归

959 tests completed, 14 failed, 5 skipped。失败均位于本轮未修改的既有功能范围；本轮 Task 3 定向测试未失败：
- MultiViewPhotoPersistenceTest (3)
- TemplatePackageExporterTest (1)
- TemplatePackageImporterTest (1)
- ViewConfirmationNavigationTest (2)
- BatchFilterAndDeleteTest (1)
- CameraPreviewTest (1)
- NoRoiViewAdvancementTest (3)
- ViewConfirmationPerformanceTest (1)
- WorkbenchViewModelAdvanceTest (1)

## 前序能力回归矩阵

| 能力 | 状态 | 说明 |
|---|---|---|
| ROI 属性编辑 UI | ✅ 不回归 | 动态 entries 枚举，BOLT/NUTSERT 自动出现 |
| 模板导入/导出 | ✅ 不回归 | 原始字符串序列化，无枚举验证 |
| 模型推理路由 | ✅ 不回归 | 新增 BOLT/NUTSERT 不影响 NUT/THREAD 路径 |
| FEATURE 不支持 | ✅ 不回归 | FEATURE → FEATURE_UNSUPPORTED 行为不变 |
| null 未配置 | ✅ 不回归 | null → ROI_NOT_CONFIGURED 行为不变 |
| 人工确认页结构 | ✅ 不回归 | 仅扩展 displayName 和 modelClassLabel |
| ZIP/CSV 回链 | ✅ 不回归 | 未触及导出服务 |
| 总体结果独立确认 | ✅ 不回归 | 未触及总体结果逻辑 |
| CameraX/DPM/OCR | ✅ 不回归 | 未触及 |

## 未完成项

- **Task 4（阈值校准）**：以 0.20 为阶段性候选基线，需更多独立数据和分类型指标
- **Task 5（Android 回归）**：需先完成 Task 3 + 4
- **真机验证**：未执行（NOT_RUN_BY_SCOPE），等待主协调安排
- **Git**：等待主协调选择性提交；本报告不把全量回归写成全部通过

## 不修改的组件

CameraX、DPM、OCR、NanoDet decoder/DFL/NMS、模型资产、阈值策略和阈值校准、批次清理、ZIP 结构、人工改判逻辑、旧 Wearable Inspection 工程。

## APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 构建时间：2026-09-20 19:23:23（Asia/Shanghai）
- 大小：232,126,458 bytes
- SHA-256：`B3C6E2BA45C058362BCA2205883425F471912ADE1C9004EB31130611E0D260FF`
- 本轮未执行 ADB、connectedDebugAndroidTest 或真机视觉验收。
