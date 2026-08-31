# Task 1 审计报告：整理活跃源码边界

> 状态：Task 1 已验收。本报告只证明源码边界整理结果；当前任务请读取根目录 `AGENTS.md` 和 `tasks/todo.md`。

**执行时间**：2026-08-31
**执行人**：Agent
**验证结果**：✅ `./gradlew.bat :app:compileDebugKotlin --no-daemon` BUILD SUCCESSFUL

---

## 一、活跃 Screen 清单（8 个）

### 1. 一级导航页面（3 个）

| 文件 | 实际职责 | 调用方 | 处理结论 |
|------|---------|--------|---------|
| LiveInspectionScreen.kt | 现场采集页（相机实时预览 + 模板参考 + 拍照检测） | AppNavigation.kt:51 | ✅ 保留（当前活跃） |
| TraceRecordsScreen.kt | 追溯记录页（历史查询、复核、结果移交） | AppNavigation.kt:68 | ✅ 保留（当前活跃） |
| ProfileScreen.kt | 个人中心页（模板配置、零件管理、应用设置入口） | AppNavigation.kt:76 | ✅ 保留（当前活跃） |

### 2. 二级导航页面（5 个）

| 文件 | 实际职责 | 调用方 | 处理结论 |
|------|---------|--------|---------|
| TemplateConfigScreen.kt | 模板配置页（模板列表、创建、编辑） | AppNavigation.kt:91 | ✅ 保留（当前活跃） |
| AppSettingsScreen.kt | 应用设置页（相机、通知、振动等设置） | AppNavigation.kt:99 | ✅ 保留（当前活跃） |
| PartManagementScreen.kt | 零件管理页（零件 CRUD） | AppNavigation.kt:105 | ✅ 保留（当前活跃） |
| PlaceholderScreens.kt:InspectionResultScreen | 检测结果详情页（阶段 B 占位） | AppNavigation.kt:126 | ✅ 保留（占位页面） |
| PlaceholderScreens.kt:TemplateDetailScreen | 模板详情页（阶段 B 占位） | AppNavigation.kt:137 | ✅ 保留（占位页面） |

---

## 二、已归档的过期 Screen（4 个）

| 文件 | 实际职责 | 替代页面 | 移动目标 |
|------|---------|---------|---------|
| **WorkbenchScreen.kt** | 工作台（旧版，卡片式布局） | LiveInspectionScreen.kt | `docs/archive/code-backups/screens/` |
| **RecordListScreen.kt** | 检测记录（占位，"记录列表功能待实现"） | TraceRecordsScreen.kt | `docs/archive/code-backups/screens/` |
| **TemplateListScreen.kt** | 模板管理（占位，"模板管理功能待实现"） | TemplateConfigScreen.kt | `docs/archive/code-backups/screens/` |
| **SettingsScreen.kt** | 设置（占位，"设置功能待实现"） | AppSettingsScreen.kt | `docs/archive/code-backups/screens/` |

### 移动原因详述

#### WorkbenchScreen.kt
- **职责**：显示"工作台"标题、零件选择器、今日统计、实时检测卡片、模板配置卡片
- **参数**：`onStartInspection: (String) -> Unit, onOpenTemplates: () -> Unit, onViewRecord: (Long) -> Unit`
- **调用方**：❌ 无（仅自身定义）
- **替代关系**：LiveInspectionScreen.kt 整合了相机预览和模板参考，采用上下分屏布局（60%/40%），WorkbenchScreen 的卡片式布局已被废弃
- **处理结论**：移入归档

#### RecordListScreen.kt
- **职责**：显示"检测记录"标题 + "记录列表功能待实现"占位文本
- **参数**：`onViewRecord: (Long) -> Unit`
- **调用方**：❌ 无（仅自身定义）
- **替代关系**：TraceRecordsScreen.kt 提供了完整的追溯记录功能（搜索、筛选、记录列表、统计数据）
- **处理结论**：移入归档

#### TemplateListScreen.kt
- **职责**：显示"模板管理"标题 + "模板管理功能待实现"占位文本
- **参数**：`onEditTemplate: (Long) -> Unit`
- **调用方**：❌ 无（仅自身定义）
- **替代关系**：TemplateConfigScreen.kt 提供了完整的模板配置功能（模板列表、添加、编辑、删除）
- **处理结论**：移入归档

#### SettingsScreen.kt
- **职责**：显示"设置"标题 + "设置功能待实现"占位文本
- **参数**：无
- **调用方**：❌ 无（仅自身定义）
- **替代关系**：AppSettingsScreen.kt 提供了完整的应用设置功能（相机设置、通知、振动、关于等）
- **处理结论**：移入归档

---

## 三、PlaceholderScreens.kt 函数调用关系

| 函数名 | 实际职责 | 调用方 | 处理结论 |
|--------|---------|--------|---------|
| CameraPreviewScreen | 相机预览页（阶段 B 占位，显示"相机预览功能（阶段 B 实现）"） | AppNavigation.kt:115 | ✅ 保留（二级页面） |
| InspectionResultScreen | 检测结果详情页（阶段 B 占位，显示"检测结果详情（阶段 B 实现）"） | AppNavigation.kt:126 | ✅ 保留（二级页面） |
| TemplateDetailScreen | 模板详情页（阶段 B 占位，显示"模板详情（阶段 B 实现）"） | AppNavigation.kt:137 | ✅ 保留（二级页面） |

---

## 四、待实现 / 待接线组件（2 个）

以下文件**不是导航 Screen**，当前无调用方，等待后续阶段接线：

| 文件 | 实际职责 | 状态 | 处理结论 |
|------|---------|------|---------|
| CameraPreview.kt | 相机预览 Composable（CameraX PreviewView 封装） | B1 待接线 | ⏳ 保留（功能组件，待 LiveInspectionScreen 集成） |
| ScanImportBottomSheet.kt | 待重构扫描入口；DPM 仅允许相机实时扫码，禁止相册码图导入 | 待接线子组件 | ⏳ 保留，后续接入现场采集页顶部“扫一扫”入口 |

### 补充说明

- **CameraPreview.kt**：Task 1 验收时无调用方。`AppNavigation.kt:115` 调用的是 `PlaceholderScreens.kt` 中的 `CameraPreviewScreen`（占位页面），而非 CameraPreview.kt 中的 `CameraPreview` Composable。Task 2 负责收敛为单一真实相机页面入口，具体以当前 `tasks/plan.md` 为准。
- **ScanImportBottomSheet.kt**：当前无任何调用方，计划在 B2 DPM 迁移时集成。

---

## 五、已归档的过期 Screen（4 个）

```bash
# 已移动到 docs/archive/code-backups/screens/
WorkbenchScreen.kt
RecordListScreen.kt
TemplateListScreen.kt
SettingsScreen.kt
```

---

## 六、编译结果

```
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 24s
16 actionable tasks: 2 executed, 4 from cache, 10 up-to-date
```

**注**：包含若干 deprecation 警告（ArrowBack、Divider、LocalLifecycleOwner），均不影响编译。

---

## 七、后置条件确认

- ✅ 所有仍被引用的 Screen 保留在 `src/main`
- ✅ 未引用的旧 Screen 已移入 `docs/archive/code-backups/screens/`
- ✅ `src/main` 下无 `.backup` 文件
- ✅ `LiveInspectionScreen.kt` 已修复 `isLoading` 编译错误
- ✅ 编译通过
- ⏳ 等待 Task 1 验收

---

**下一步**：等待用户验收 Task 1 完成后，继续执行 **Task 2：CameraPreview 状态与画幅**。
