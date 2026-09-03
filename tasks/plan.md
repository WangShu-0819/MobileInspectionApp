# Implementation Plan: MobileInspectionApp 当前阶段

## 当前任务：拍照后人工确认 + 持久化（IN_PROGRESS）

2026-09-04：模板 ROI 属性选择已完成并通过验收。本阶段只推进拍照后人工确认和持久化，完整照片 + Excel ZIP 作为后续独立任务。

执行边界：

- 每拍完一个 View 后进入人工确认界面，不自动把 ROI 结果或总体结果判为 OK/NG。
- 复用现有批次、照片、InspectionSession、ROI 记录、DAO 和 Repository；不创建第二套 ROI 数据模型或新的 CameraX。
- 展示当前照片全部 ROI，使用 normalizedRect 映射到实际 image contentRect，并保存 ROI/总体人工结果及确认时间。
- 软件检测结果保持 null/未执行；不实现自动检测、Homography、自动对齐、自动轮廓或 Session ROI 编辑。
- 只修改源码、自动化测试和文档；不运行 Gradle、ADB、APK 或真机测试，不提交 Git。

完成后必须更新 `tasks/todo.md` 和 `docs/reports/b2/` 报告，列出实际修改文件、测试状态、未完成项和 Git 状态，暂停等待验收。

## 已完成任务：模板视角 ROI 长按删除回归整改（2026-09-03）

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**（2026-09-03，人工交互验收通过）

用户确认：已有 ROI 和新增 ROI 均可点按/长按选中、确认删除并正确持久化。

~~结果包导出（基础照片 ZIP）~~ 阶段 1 实现中：基础照片 ZIP 导出已完成，manifest + Excel + 图片完整结果包仍未实现。
~~现场采集模板参考图拍照时上移、黑边消失和比例变化，作为阶段 2 处理。~~ 阶段 2 实现中：模板参考图拍照时上移已修复。
~~重复"新建零件"按钮作为后续独立任务处理。~~ ✅ 已修复：移除列表区全宽按钮，仅保留 TopAppBar "+" 入口。

### 审计结论与依赖

- 当前活动链路为 `Profile → TemplateConfig → PartListScreen → PartDetailScreen → TemplateDetailScreen → RoiEditorScreen`。
- ROI 移动/缩放整改已完成；上一版删除逻辑已写入，但人工验收暴露出 Canvas 选中入口不可靠，不能只以 ViewModel 测试通过作为完成依据。
- 复用现有 ROI 实体、DAO、Repository 和状态流；不新增数据库表、第二套 ROI 状态或新的手势框架。
- 前序必须保留：新 ROI 绘制/保存、取消、选中、移动、四角缩放、边界约束、模板图片 contentRect 映射和唯一 CameraX 架构。

### 实施任务（按依赖顺序）

1. [x] 修复 Canvas 命中：普通点按和长按已有 ROI 都能可靠识别当前框，长按后明确设置 `selectedRoiId`。
2. [x] 保留右上角小垃圾桶图标作为删除入口；长按只负责选中和高亮，点击图标后进入删除确认。
3. [x] 保留现有 ViewModel 删除成功/失败处理，确认失败不会错误清除本地状态。
4. [x] 补充长按选中、删除、无选中、多 ROI、多 View 隔离和删除后重载自动化测试。
5. [x] 执行三条 Gradle 命令，更新任务清单和整改报告；本轮不执行 adb。

### 验收标准

- 已有 ROI 可在当前 View 通过普通点按或长按可靠选中并高亮；右上角小垃圾桶图标可见，点击后进入清晰的删除确认。
- 删除只作用于当前 View 的当前 ROI；删除成功后持久化并从 UI 移除，重进页面后不再出现。
- 无选中 ROI 不会误删；删除失败有明确反馈并保留可恢复状态。
- 新增、取消、移动、缩放、边界约束、contentRect 映射和多 View `templateId` 隔离不回归。
- `:app:compileDebugKotlin --no-daemon`、`:app:testDebugUnitTest --no-daemon`、`:app:assembleDebug --no-daemon` 全部通过。
- 完成后 Agent 必须回填 `tasks/todo.md` 完成清单、实际修改文件、测试结果、真机范围、未完成项和 Git 状态，并同步更新 `docs/reports/b2/` 报告。

### 预计文件范围

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`（仅在现有删除接口不足时）
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt` 及必要的 UI/手势测试
- `docs/reports/b2/` 下的 ROI 整改报告

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

B1 共享 CameraX 已完成技术验收。B2 模板导入、透明叠加、模板配置层级、模板拍摄、ROI 移动/缩放和 ROI 属性选择已完成对应软件任务。当前只执行“拍照后人工确认 + 持久化”，不并行推进 Detector、完整结果包导出或 B3 Presence Detection。

## Architecture Decisions

- 唯一 CameraController 管理 CameraX 所有权，业务分析器彼此独立。
- 完整取景优先：4:3 流、竖屏 3:4、FIT_CENTER、记录 content rect。
- 当前任务和阶段门禁只在 `tasks/todo.md` 维护，历史报告不再声明进度。
- 源码按 feature 渐进整理，不进行一次性大搬迁。

## Task List

### 历史阶段：按采集批次导出照片 ZIP + UI 压缩

**阶段 1：按采集批次导出** — ✅ SOFTWARE_COMPLETE（待验收）

- [x] CaptureBatchEntity + CapturedPhotoEntity 实体定义
- [x] CaptureBatchDao + CapturedPhotoDao DAO
- [x] AppDatabase 版本 3→4，MIGRATION_3_4
- [x] InspectionRepository 新增批次/照片 CRUD
- [x] PhotoExportService 重写为 exportBatchToZip(batchId)
- [x] LiveInspectionScreen 拍照时自动创建批次、记录照片
- [x] TraceRecordsScreen 显示采集批次列表，per-batch 导出按钮
- [x] 切换零件时自动重置批次
- [x] 三条 Gradle 命令全部通过
- [x] 更新 docs/reports/b2/PHOTO_ZIP_EXPORT_REPORT.md

**阶段 2：压缩采集页控件** — ✅ SOFTWARE_COMPLETE（待验收）

- [x] 压缩 TemplateOverlayControls：minHeight 48→36dp，IconButton 48→32dp，icon 20→16dp，padding 12→8dp
- [x] 压缩 TemplateReferenceSection：padding 8→4dp，spacedBy 6→4dp，view row minHeight 40→28dp
- [x] 压缩 CaptureActionBar：button height 48→40dp，icon 24→18dp，fontSize 16→14sp
- [x] 压缩 TemplateSelector：minHeight 48→28dp，fontSize 11sp
- [x] 压缩 AllViewsCapturedCard：icon 24→18dp，padding 压缩
- [x] 压缩拍照状态提示：minHeight 36→28dp，icon 20→16dp
- [x] 不修改 CameraPreview、模板图显示、FIT_CENTER、contentRect、CameraX
- [x] 三条 Gradle 命令全部通过
- [x] 更新 tasks/todo.md

### Checkpoint：阶段 1 + 2 验收

- [x] 阶段 1 所有验收标准已由代码和测试证明
- [x] 阶段 2 所有验收标准已由代码和测试证明
- [ ] 用户验收阶段 1 + 2
- [ ] 报告记录实际修改文件、测试结果、真机范围、未完成项和 Git 状态
- [ ] 等待用户验收后再决定是否提交 Git

### 已完成任务：模板配置入口简化

- [x] 已提交 `866c23fc`
- [x] 模板配置页只保留“新建零件”入口，模板包导入独立页面保持可用

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
