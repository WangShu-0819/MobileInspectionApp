# 模板叠加默认透明度为 0%

**日期**：2026-09-15
**状态**：USER_ACCEPTED（2026-09-15；按 `tasks/todo.md` 记录，用户已验收）

## 1. 需求

首次进入现场采集页时，模板叠加透明度默认为 `0f`（0%，完全透明），相机画面正常显示。用户可通过现有滑杆（范围 0f～0.8f）手动提高透明度后再显示模板图。

## 2. 实际修改文件

### 源码文件（1 个）

| 文件 | 变更 |
|------|------|
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt` | `overlayAlpha` 初始值从 `0.45f` 改为 `0f`；注释更新为"默认 0%：模板叠加完全透明" |

### 测试文件（1 个修改）

| 文件 | 变更 |
|------|------|
| `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt` | 新增 2 项测试：默认值为 0f、滑杆和显示/隐藏切换保留 |

## 3. 变更详情

### LiveInspectionScreen.kt

```kotlin
// 修改前（第 159 行）：
var overlayAlpha by remember { mutableStateOf(0.45f) }

// 修改后：
var overlayAlpha by remember { mutableStateOf(0f) }
```

### TemplateOverlayControls 文档注释

```kotlin
// 修改前：
// 范围 0.0f ~ 0.8f，默认 0.45f。

// 修改后：
// 范围 0.0f ~ 0.8f，默认 0%（完全透明）。
```

## 4. 保留的现有行为

- 滑杆范围 `0f..0.8f` 不变
- `TemplateOverlayControls` 显示/隐藏切换不变
- `CameraPreview`、`contentRect`、CameraX 生命周期不变
- 模板参考图、View/零件选择、拍照流程不变
- `overlayAlpha` 传入 `CameraPreviewSection` 的逻辑不变：`overlayAlpha = if (templateVisible) overlayAlpha else 0f`
- 不新增持久化设置

## 5. 新增测试覆盖

| # | 测试 | 覆盖 |
|---|------|------|
| 1 | `overlay alpha defaults to zero percent on first entry` | 源码中 `overlayAlpha` 初始值为 `0f` |
| 2 | `slider range and visibility toggle are preserved after default change` | 滑杆 `0f..0.8f`、显示/隐藏切换、contentRect 传值保留 |

## 6. 测试状态

```
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```

- **总测试数**：694 项（675 passed / 14 failed / 5 skipped）
- **本次新增 2 项**：`overlay alpha defaults to zero percent on first entry` ✅、`slider range and visibility toggle are preserved after default change` ✅
- **14 项失败全部为预存问题**（在本次修改前已存在于工作区，与透明度默认值变更无关）：涉及 `ViewConfirmationNavigationTest`、`BatchFilterAndDeleteTest`、`NoRoiViewAdvancementTest`、`WorkbenchViewModelAdvanceTest`、`MultiViewPhotoPersistenceTest`、`TemplatePackageExporterTest`、`TemplatePackageImporterTest` 等文件中的源码契约断言，根因是此前多轮迭代中源码结构调整后未同步更新测试中的字符串匹配。
- **本次修改不引入任何新失败**
- 单独运行新增测试验证：
  ```
  .\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --tests "...LiveInspectionCaptureStateTest.overlay alpha defaults..." --tests "...LiveInspectionCaptureStateTest.slider range..."
  ```
  BUILD SUCCESSFUL ✅
- ADB、APK 构建/安装、真机验收：`NOT_RUN_BY_SCOPE`（不运行 ADB 或真机）
- Git：源码实现已在历史中；专属回归测试和本报告与透明度任务单独提交，见 §8。

## 7. 遗留项（不阻塞本项用户验收）

- 全量测试中的 14 项预存源码契约失败与本项无关，按独立问题跟踪。
- Agent 本轮未另行执行 ADB/APK/真机验证（`NOT_RUN_BY_SCOPE`）；本项最终状态以 `tasks/todo.md` 所记用户验收为准。

## 8. Git 状态

实现代码已存在于当前 Git 历史；本次补齐独立回归测试与验收报告，按该任务单独提交。完整源码实现与提交历史可通过 `git log -- app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt` 追溯。
