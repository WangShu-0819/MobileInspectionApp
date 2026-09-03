# Implementation Plan: MobileInspectionApp 当前阶段

## 当前用户任务：模板配置支持先创建零件，再导入模板（2026-09-03）

用户反馈：进入“我的 → 模板配置”后仍必须先选择模板图片，才能在导入对话框中创建零件；期望流程是“先创建零件 → 进入零件详情 → 再导入或拍摄模板 View”。本轮暂停 B3 Presence Detection，只推进模板配置入口和零件上下文流程；完成后更新 `tasks/todo.md` 和 `docs/reports/b2/` 对应报告，暂停等待验收。

### 审计结论与依赖

- 当前活动链路为 `Profile → TemplateConfig → PartListScreen → PartDetailScreen`。
- `PartListScreen` 目前只有“导入模板照片”入口；“新建零件”仅存在于导入对话框中，因此必须先选择图片。
- `PartManagementScreen` 已有独立新建零件流程，但不满足“模板配置内先建零件”的目标入口。
- 复用 `PartEntity`、`PartDao`、`InspectionRepository` 和现有导航；不新增数据库表或第二套零件模型。
- 前序必须保留：已有零件导入多张图片、选择已有零件、View 顺序、拍摄/重拍/删除、逐 View ROI 和唯一 CameraX 架构。

### 实施任务（按依赖顺序）

1. [x] 在 `PartListScreen` 增加真实”新建零件”入口和创建对话框。
2. [x] 校验零件 ID、名称和重复 ID，通过现有 Repository 写入 `PartEntity`；允许创建 0 View 的空零件。
3. [x] 创建成功后导航到对应 `PartDetailScreen`，正确显示零件信息和 0 View 空状态。
4. [x] 验证从该详情页继续导入多张图片时全部写入当前 `partId`，且已有导入/拍摄流程不回归。
5. [x] 补充 JVM 自动化测试，执行三条 Gradle 命令，更新任务清单和整改报告；本轮不执行 adb。
6. [x] 移除 PartListScreen 上”导入模板”按钮，只保留”新建零件”入口，避免顶部遮挡。

### 验收标准

- 模板配置零件列表无需先选图片即可新建零件。
- 新建对话框能真实保存零件；空 ID、空名称、非法 ID、重复 ID 均有明确提示且不写入脏数据。
- 新建成功后进入正确的零件详情页；零件无 View 时可正常显示 0 个视角，不创建空模板。
- 从详情页导入多张图片后，所有 View 的 `partId` 正确，且原有 View 顺序、缩略图、拍摄和 ROI 功能不回归。
- 失败流程不产生重复零件、无图片模板或孤儿文件。
- `:app:compileDebugKotlin --no-daemon`、`:app:testDebugUnitTest --no-daemon`、`:app:assembleDebug --no-daemon` 全部通过。
- 完成后 Agent 必须回填 `tasks/todo.md` 完成清单、实际修改文件、测试结果、真机范围、未完成项和 Git 状态，并同步更新 `docs/reports/b2/` 报告。

### 预计文件范围

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/PartListScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`（仅在需要新增导航回调时）
- `app/src/test/java/com/wearable/inspection/mobile/` 下与 Part 创建/导入归属相关的测试
- `docs/reports/b2/` 下的模板配置整改报告

### 执行完成回填要求

执行 Agent 完成实现后必须：

1. 逐项勾选已由代码和测试证明的清单；
2. 填写实际修改文件、真实测试命令及结果；
3. APK 信息无法生成时如实填写，真机未执行时填写 `NOT_RUN_BY_SCOPE`；
4. 记录未完成项、残留 TODO 和 Git 状态；
5. 同步更新整改报告并包含前序能力回归矩阵；
6. 未满足全部验收标准时保持 `IN_PROGRESS`，不得写 `SOFTWARE_COMPLETE`。

## 已完成整改计划：ROI 移动/缩放整改（2026-09-03）

用户根据多零件、多视角、逐 View ROI 只读审计结果，要求先修复已有 ROI 无法选中、移动、缩放和持久化的问题。本轮暂停 B3 Presence Detection，只推进这一项最小整改；完成后更新 `tasks/todo.md` 和 `docs/reports/b2/` 对应报告，暂停等待验收。

### 审计结论与依赖

- `PartListScreen → PartDetailScreen → TemplateDetailScreen → RoiEditorScreen` 已是当前活动导航链路。
- `RoiDefinitionEntity.templateId` 和 `RoiDao WHERE templateId = :templateId` 已提供 View 级隔离；`InspectionRepository.updateRoi` 已存在，但当前 UI 没有调用。
- `RoiEditorScreen.kt` 的已有 ROI 移动/缩放回调仍是 TODO 空回调，Canvas 没有完成已有 ROI 命中、控制柄和编辑手势。
- 复用现有实体、DAO、Repository、`normalizedRect` 和真实图片 `contentRect`；除非测试证明必要，不改 Room schema。
- 前序必须保留：新 ROI 绘制/保存、取消、删除基础路径；多 View 的 `templateId` 隔离；真实图片 contentRect 映射；唯一 CameraX 架构。

### 实施任务（按依赖顺序）

1. [ ] ROI 命中与显示：在 Canvas 中将指针位置映射到 `contentRect`，命中已有 ROI 后设置 `selectedRoiId`，为选中 ROI 绘制清晰的边界/四角控制柄。
2. [ ] 移动与缩放：实现已有 ROI 的拖拽移动和四角缩放；统一做最小尺寸、边界约束和 left/top/right/bottom 合法性校验。
3. [ ] 持久化：在 `RoiEditorViewModel` 增加移动/缩放更新方法，调用 `InspectionRepository.updateRoi`，更新本地状态并保证离开后重新加载结果一致。
4. [ ] 回归测试：覆盖新增、更新、删除、取消、0..1 边界、序列化和不同 View 的 ROI 隔离；测试应证明 updateRoi 被调用且保存值可重新读取。
5. [ ] 收口与报告：执行三条 Gradle 命令，更新任务完成清单和整改报告；不执行 adb 或真机验收。

### 验收标准

- 已有 ROI 可点击选中，移动和四角缩放均有真实 UI 响应，不存在空 TODO 回调。
- 每次移动/缩放均将矩形约束在图片实际 `contentRect` 内，并持久化为 0..1 范围的 `normalizedRect`。
- 重进同一 View 后位置和尺寸保持；切换到其他 View 时不会读取或修改前一 View 的 ROI。
- 新增、取消、删除和已有前序模板配置流程不回归；单个 ROI 更新失败时保留可解释错误，不伪造成功状态。
- `:app:compileDebugKotlin --no-daemon`、`:app:testDebugUnitTest --no-daemon`、`:app:assembleDebug --no-daemon` 全部通过。
- 完成后必须回填 `tasks/todo.md` 的完成清单、实际修改文件、测试结果、未完成项和 Git 提交状态；未获明确授权不得提交 Git。

### 预计文件范围

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`（仅在现有接口不足时）
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt`
- `docs/reports/b2/` 下的 ROI 整改报告

### 执行完成回填要求

执行 Agent 完成实现后，必须在 `tasks/todo.md` 中：

1. 逐项勾选已经由代码和测试证明的完成清单；
2. 填写实际修改文件、真实测试命令及结果；
3. 记录 APK 信息/真机范围；本任务不执行 adb 时明确写 `NOT_RUN_BY_SCOPE`；
4. 写明未完成项、残留 TODO 和 Git 是否提交；
5. 同步更新 `docs/reports/b2/` 整改报告，并在报告中包含前序能力回归矩阵；
6. 未满足全部验收标准时保持 `IN_PROGRESS` 或标记为回归整改中，不得写 `SOFTWARE_COMPLETE`。

## Overview

B1 共享 CameraX 已完成技术验收。B2 Task 1 DPM 迁移 SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING。B2 Task 2 模板导入 + 透明叠加 MVP 软件层面已完成（提交 `bdf1bd89`）。DPM 绑定/已绑定码切件的源码路由已接入，模板导入与按序拍摄真机流程已通过；已绑定码实际切换和完整累积 instrumented 回归仍待补证。模板拍摄、缩略图、重拍、排序和 ROI 移动/缩放整改已完成对应软件任务；当前先执行“模板配置支持先创建零件，再导入模板”，不并行推进 B3 Presence Detection 或其他未完成业务。

## Architecture Decisions

- 唯一 CameraController 管理 CameraX 所有权，业务分析器彼此独立。
- 完整取景优先：4:3 流、竖屏 3:4、FIT_CENTER、记录 content rect。
- 当前任务和阶段门禁只在 `tasks/todo.md` 维护，历史报告不再声明进度。
- 源码按 feature 渐进整理，不进行一次性大搬迁。

## Task List

### 当前任务：模板配置支持先创建零件，再导入模板

- [x] 模板配置零件列表可不依赖图片直接新建零件
- [x] 新建零件校验 ID/名称/重复项并真实写入数据库
- [x] 创建成功后进入正确 PartDetail，允许 0 View 空状态
- [x] 后续多图导入全部关联当前 `partId`
- [x] 原有导入、拍摄、重拍、删除、排序和 ROI 回归通过
- [x] 补充自动化测试并通过三条 Gradle 命令
- [x] Agent 回填 `tasks/todo.md` 和 `docs/reports/b2/` 报告

### Checkpoint：模板配置入口整改完成

- [x] 所有验收标准已由代码和测试证明
- [x] 报告记录实际修改文件、测试结果、真机范围、未完成项和 Git 状态
- [ ] 等待用户验收后再决定是否提交 Git

### 已完成整改任务：ROI 移动/缩放整改

- [x] 已有 ROI 可点击选中并显示编辑状态
- [x] 已有 ROI 支持拖拽移动和四角缩放
- [x] 移动/缩放受图片 `contentRect`、最小尺寸和 0..1 坐标约束
- [x] 移动/缩放调用 `InspectionRepository.updateRoi` 并在重进页面后保持
- [x] 新增、取消、删除和多 View `templateId` 隔离行为回归通过
- [x] 补齐 ROI 创建/更新/删除/边界/隔离自动化测试
- [x] `compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug` 全部通过
- [x] Agent 回填 `tasks/todo.md` 完成清单和 `docs/reports/b2/` 整改报告

### Checkpoint：ROI 整改完成

- [x] 所有验收标准已由代码和测试证明
- [x] 报告记录实际修改文件、测试结果、真机范围、未完成项和 Git 状态
- [ ] 等待用户验收后再决定是否提交 Git

### B1：共享 CameraX 收口

- [x] Task 1：审计活跃页面并归档未引用旧 Screen
- [x] Task 2：接入真实 CameraPreview，完成权限、状态、画幅与 content rect（真机提交 `28d692d`）
- [x] Task 3：完成 CameraController 模式重绑、互斥与生命周期（真机验收完成）
- [x] Task 4：真实 ImageCapture 与 MobileImageStore 收口及最终验收（已验收，提交链 `48f7587` → `566acaea` → `3a04b658`）
- [x] **Task 5：B1 完整验证** — 已验收，APK SHA-256 `235f8aa8c4d65b365a93bff021041e43dca86d5eb4b121ba9d13ebd3f436768f`；详见 `TASK5_FINAL_VALIDATION_REPORT.md`

### 已验收 Task 2：CameraPreview 状态与画幅

执行顺序：

1. **入口收敛**：让 `AppNavigation` 进入真实 CameraPreview 实现；移除 `PlaceholderScreens.kt` 中的相机占位职责，保留检测结果和模板详情占位。
2. **状态模型**：使用明确状态表达无权限、请求中、初始化、ACTIVE、临时拒绝、永久拒绝和错误；回调必须由真实 CameraX/权限事件驱动。
3. **权限恢复**：临时拒绝可再次请求，永久拒绝可进入系统应用设置，返回页面后重新检查权限；错误态提供有效重试。
4. **完整画幅**：`PreviewView.ScaleType = FIT_CENTER`；优先统一 4:3 UseCase；竖屏按实际流显示 3:4 内容，允许 letterbox，不允许裁切、拉伸或固定 60/40 强撑。
5. **坐标基础**：计算 PreviewView 内真实图像 `contentRect`；Debug 模式记录 View 尺寸、流尺寸、旋转、缩放方式和 content rect。
6. **验证收口**：编译、单元测试、生成并安装当前 APK；在至少 `360x800`、`412x915` 和一台真机验证四边标记完整、圆形不变形、权限分支可恢复。

Task 2 交付物：源码改动、更新后的 `tasks/todo.md`、`docs/reports/b1/TASK2_CAMERA_PREVIEW_REPORT.md`、测试命令和结果、APK 路径/时间/大小/SHA-256、真机截图或录屏证据。

Task 2 已完成并通过真机验收，证据位于 `docs/reports/b1/evidence/task2/`。

### 已验收 Task 3：CameraController 模式与生命周期

当前状态：已通过累积真机验收，最终修复提交 `bb22f1e`，证据位于 `docs/reports/b1/evidence/task3/`。

执行顺序：

1. **前序回归恢复**：以 Task 2 提交 `28d692d`、代码收口 `a037a08` 和证据截图为基线，把完整权限、OPEN 状态、`FIT_CENTER`、实际流变换、contentRect、诊断和校准能力适配到当前 CameraController；不得整文件回滚。
2. **画幅复验**：当前设备容器约 `1080x1039` 时，真实 3:4 图像区域应约 `779x1039` 并左右留边；四角位于图像区域、中央圆不变形、上下内容不裁切。
3. **坐标复验**：LiveInspection 的轮廓/ROI 叠加接收真实 contentRect，只在图像区域绘制；letterbox 不得出现检测图形。
4. **测试入口清理**：测试 Activity 从 `src/main` 和主 Manifest 移出，只保留在 androidTest/debug；冻结并不得使用超出当前阶段的 `tools/contour_extraction/`。
5. **保持生命周期成果**：重新运行并发、模式互斥、ImageProxy、observer 和 20 轮模式测试，确认恢复预览没有破坏 Task 3 已完成部分。
6. **最终真机循环**：在修复后的同一 APK 上执行 Tab 往返 10 次、前后台 10 次，并重新检查 8 项 logcat 禁止模式。
7. **累积验收**：报告同时给出 Task 2 回归矩阵和 Task 3 生命周期矩阵；任一项失败都不得进入 Task 4。

Task 3 已完成。其权限、画幅、contentRect、会话互斥和生命周期能力继续作为后续累积门禁。

### 已验收 Task 4：真实拍照与存储收口

当前状态：已验收，提交链 `48f7587` → `566acaea` → `3a04b658`，证据位于 `docs/reports/b1/evidence/task4/`。

Task 4 已完成全部验收项：会话安全快门、capture request token 机制、.part 文件事务、17 项拍照异步测试、8 项存储测试、真机 20 张连续拍摄。

### 已验收 Task 5：B1 完整验证

状态：✅ 已验收（APK SHA-256 `235f8aa8c4d65b365a93bff021041e43dca86d5eb4b121ba9d13ebd3f436768f`，HONOR YAL-AL10, ERLDU20429005890）。

执行结果：

1. **JVM 测试**：78/78 通过（CameraControllerTest 40 + CameraControllerTakePhotoTest 17 + MobileImageStoreTest 11 + ContentRectCalculatorTest 10）
2. **APK 构建与安装**：BUILD SUCCESSFUL，adb install Success
3. **冷启动 10 次**：0 FATAL EXCEPTION
4. **Tab 往返 10 轮**：无黑屏、重复绑定
5. **前后台切换 10 次**：无崩溃
6. **日志门禁 12 项**：0 违规（1 项系统误报）
7. **截图证据**：01_cold_start.png 用户视觉复核通过
8. **文档收口**：AGENTS.md、plan.md、todo.md、B1 报告已更新

详见 `TASK5_FINAL_VALIDATION_REPORT.md`。

### Checkpoint：B1

- [x] `tasks/todo.md` 的 B1 验收全部通过
- [x] 用户确认进入 B2

用户已确认进入 B2。B2 Task 1 软件层面已完成，物理验收不阻塞后续功能开发。B2 Task 2 模板导入 + 透明叠加 MVP 软件层面已完成。

### B2：DPM 迁移

- [x] **Task 1：旧 DPM 识别链迁移与实时扫码闭环** — **SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-02）。APK SHA-256 `6e2ca7d3f573c1da1af7f9180c23a0dbe8f2f9081eafff5ccf466dcb09c051cc`。JVM 208 项（203 passed / 0 failed / 5 skipped），Instrumented 30/30 passed，冷启动 10/10 passed。4 项物理验收标记为 `PENDING_PHYSICAL_DPM_SAMPLE`。
- [x] **Task 2：旧模板导入 + 模板透明叠加 MVP** — **SOFTWARE_COMPLETE**（2026-09-02，提交 `bdf1bd89`）。V1-1 导入 + V1-2 overlay + alpha slider 完成。JVM 242 项（237 passed / 0 failed / 5 skipped）。遗留：legacy ROI 未迁移、imageFiles[] 仅取首图。
- [ ] **Task 3：DPM 绑定、已绑定码切件和冲突处理** — 源码路由已接入；模板配置绑定保存、冲突拒绝和未知码提示已有自动化/流程证据；同一最终 APK 的已绑定码实际切换、切换后从 View 1/N 重新开始及完整累积 instrumented 回归待补。物理 DPM 样本验收仍为 `PENDING_PHYSICAL_DPM_SAMPLE`。

B2 Task 1 固定边界：使用唯一 CameraController 的 `DPM_SCAN` 模式，忠实迁移旧工程已经可用的生产识别链。顺序固定为中心 ROI/全图的 ZXing `DataMatrixReader` 主解码（含旧预处理策略与双极性尝试）→ ML Kit DATA_MATRIX 兜底 → 满足旧门控条件时执行网格重建兜底；同时保留帧节流、single-flight、响应门、连续 miss 对焦、取消和停止后不回调。”扫一扫”只进入实时扫码，不提供 DPM 相册选图、码图导入或对应权限/路由。

### B2 Task 3：模板拍摄、缩略图、重拍、排序

状态：**SOFTWARE_COMPLETE**（2026-09-03）。源码、自动化测试和工程文档已完成，真机验收待下一轮指令。

实际修改文件：MobileImageStore（模板图片存储）、TemplateDao（批量排序）、InspectionRepository（新增方法）、TemplateCaptureViewModel（新增）、TemplateCaptureScreen（新增）、Screen/AppNavigation（路由注册）、TemplateConfigScreen（缩略图+排序+拍摄入口）、TemplateDetailScreen（重拍按钮）、MobileImageStoreTest（7 项新测试）。

JVM 325 项通过（320 passed / 0 failed / 5 skipped）。APK SHA-256 `56e390a067ccd1a040ea05b86b9743bc185bf2c1215630e7fc0f4f35a9e7f495`。

网格重建尺寸模式也属于旧版基线：默认 `AUTO` 公平交错尝试 16×16、18×18、20×20，并保留 `DIM_16/DIM_18/DIM_20` 固定模式、原候选配额、解析回退和设置持久化。尺寸模式只约束网格重建，不限制扫码框 ZXing、全图 ZXing、预处理或 ML Kit。

验收使用旧 App 与新 App 对同一组现场/打印 Data Matrix 样本做 A/B 对照。新 App 至少保持旧 App 的可识别样本集合、防连扫行为和可接受响应时间；未通过对照前不得以“基础扫码已成功”宣布 DPM 迁移完成。

B2 Task 1 连续执行检查点：

1. 纯 Kotlin 尺寸模式、响应门、网格门、取消和设置快照。
2. 旧 OpenCV 预处理、质量门控、ImportedDpmScanner 与网格重建。
3. ZXing/ML Kit 真实适配器与完整旧 DpmAnalyzer 行为组合。
4. DpmFrameAnalyzer 接入唯一 CameraController 的 DPM_SCAN。
5. 扫码页面、扫码框 contentRect/rotation ROI 映射和现场采集导航。
6. 自动化、真机扫码框、CameraX 累积回归及旧/新 App 同样本 A/B 验收。

所有真机检查点先执行 `AGENTS.md` 的“真机包名门禁”。新工程验收只能显式启动 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`；桌面图标、最近任务或旧包 `com.wearable.inspection` 产生的证据无效。旧 App 仅在标注清楚的 A/B 对照轮次中单独启动，并在切换前停止另一包。`connectedDebugAndroidTest` 返回后必须假定新包已被卸载，无论测试成功或失败都先重新安装并显式启动主 APK、核对新旧 PID 与前台包，再继续或报告失败。

各检查点通过测试后允许自动继续并分别提交；任一失败立即暂停。DPM 识别链的软件迁移已完成，但在真实已绑定码切件和物理样本验收补证前，不得把 DPM 绑定/切件整体标记为最终验收完成。物理验收不阻塞后续非 DPM 软件开发。

### B3：钢印 OCR 迁移

- [x] Phase 1：核心算法迁移（提交 `0df8e9c5`，10 source +9 test files，66 OCR tests）
- [x] Phase 2：CameraX/UI 集成（SOFTWARE_COMPLETE，2026-09-02）— StampOcrFrameAnalyzer + StampOcrViewModel + StampOcrScreen + Navigation
- [ ] 真机 OCR 拍照 + 识别 + 人工确认流程验证（需要钢印样品）

### V1 可交付闭环（当前优先目标）

**Contour-based live alignment**: DEFERRED / POST-MVP。轮廓提取成熟度不足，继续优化会阻塞可交付版本。V1 改为模板原始图片透明叠加 CameraX 实时画面。`tools/contour_extraction/` 工具和数据保留但不进入 V1。

V1 MVP 数据流闭环：

```
模板导入/拍摄 → ROI 配置 → 模板原图透明叠加辅助现场取景 → 拍照 → 模板/实拍双图比对 → 本次 ROI 微调 → ROI 检测 → 保存结果
```

1. [x] **导入旧模板包**：DirectoryTemplateImporter 解析目录 + TemplateImportService 事务编排 → PartEntity / InspectionTemplateEntity / 模板图片文件（提交 `bdf1bd89`）。遗留：legacy ROI 未迁移、imageFiles[] 仅取首图；模板 EXIF 方向归一化仍需补证/补实现。
2. [x] **模板透明叠加**：CameraX live preview + template image overlay，contentRect 内绘制，保持正确宽高比，opacity slider (0f~0.8f, 默认 0.45f)（提交 `bdf1bd89`）。已支持按视角连续拍摄，但拍摄后的照片仍未进入比对/检测闭环。
3. [ ] **拍后比对**：CaptureComparisonScreen — 模板 vs 实拍，切换/叠加/opacity、blink、缩放和平移；区分 templateRoi vs sessionRoi。当前尚未开始，是下一软件任务。
4. [ ] **ROI 人工微调**：拖动矩形 + resize handles，当前 session ROI 不覆盖模板 RoiDefinitionEntity
5. [ ] **ROI → Detector → Result**：crop ROI bitmap → AlgorithmRegistry → detector → per-ROI result → persist
6. [ ] **结果查看**：Overall PASS/FAIL，per-ROI name/algorithm/PASS/FAIL/score/error

DEFERRED / POST-MVP（不阻塞 V1 交付）：
- 实时主体轮廓提取与投影
- 依赖主体轮廓的自动姿态匹配（SIFT/单应性）
- ALIGNED/LOST 自动对齐结果作为拍照门禁
- ROI 自动跟踪

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| 多页面重复绑定相机 | 高 | 唯一 CameraController 与模式状态机 |
| FIT_CENTER 后坐标映射错误 | 高 | 保存 content rect 并用边缘标记测试 |
| 报告与真实状态冲突 | 中 | 进度只由 todo 验收项决定 |
| 一次性目录重构破坏构建 | 中 | 按 feature 小批移动并逐次编译 |
| 新旧 App 共存时误开旧包 | 高 | 完整组件名启动、前台包校验；旧包证据不得计入新工程验收 |
