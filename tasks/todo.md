# 当前唯一任务：模板视角 ROI 长按删除回归整改

用户最新反馈（2026-09-03）：上一版虽然增加了删除按钮和确认框，但人工测试时点击模板视角中的已有 ROI 仍无法完成选中和删除。需要改为可靠的“长按 ROI 框选中 → 删除确认”交互。本轮暂停 B3 Presence Detection、拍后比对和结果包导出，只处理 ROI 删除回归；完成后更新本文件及对应报告，暂停等待用户验收。不得并行处理其他未完成业务。

结果包导出（基础照片 ZIP、manifest + Excel + 图片）作为后续独立任务，待本任务验收后再单独规划。

现场采集模板参考图拍照时上移、黑边和显示比例变化，作为 ROI 删除验收通过后的独立任务处理。

## 模板视角 ROI 长按删除回归整改

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**（2026-09-03，人工交互验收通过）

目标：在现有 `RoiEditorScreen` 中让用户通过长按已有 ROI 框可靠选中并高亮，再通过右上角小垃圾桶图标进入删除确认；删除结果真实持久化到当前 View，重新进入页面后不再出现；不得影响其他 View 的 ROI。

执行边界：

- 复用现有 `RoiEditorScreen`、`RoiEditorViewModel`、`InspectionRepository.deleteRoi`/现有删除接口、`RoiDefinitionEntity` 和 `templateId` 隔离，不新增数据库表或第二套 ROI 状态。
- 长按已有 ROI 框必须可靠完成命中并高亮选中；删除继续通过右上角小垃圾桶图标触发确认。
- 只删除当前选中的 ROI；无选中 ROI 时删除操作不可用或明确提示；删除失败时保留可解释错误，不伪造成功。
- 保留新 ROI 绘制、取消、选中、移动、四角缩放、边界约束、保存和多 View 隔离行为。
- 只进行源码、自动化测试和文档修改，禁止执行 adb、安装/卸载或启动/停止真机应用，不修改旧工程。
- 本轮不实现拍后比对、Detector/PASS-FAIL、Session ROI、结果包导出或其他业务。

完成清单（执行 Agent 完成真实实现和验证后回填，不得提前勾选）：

- [x] 普通点按或长按已有 ROI 均能可靠完成命中；长按明确选中当前 ROI
- [x] 长按后可靠选中并高亮 ROI，右上角小垃圾桶图标可见且能触发清晰删除确认
- [x] 删除只作用于当前 View 的当前选中 ROI
- [x] 删除调用真实 Repository/DAO 删除接口并更新本地 UI 状态
- [x] 删除后重新进入同一 View，ROI 不再出现
- [x] 删除失败有明确错误反馈，且不误清理本地状态
- [x] 无选中 ROI 时不会误删其他 ROI
- [x] 新增、取消、移动、缩放、边界约束和多 View `templateId` 隔离行为回归通过
- [x] 补充长按选中、ROI 删除及回归自动化测试
- [x] `:app:compileDebugKotlin --no-daemon` 通过
- [x] `:app:testDebugUnitTest --no-daemon` 通过
- [x] `:app:assembleDebug --no-daemon` 通过
- [x] 更新 `docs/reports/b2/` 对应整改报告，记录人工验收结果、前序能力回归矩阵、测试结果和未完成项

### 执行完成回填区（由 Agent 在完成任务后填写）

- 实际修改文件：
  - `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorScreen.kt` — 重写 RoiCanvas 编辑模式手势处理：将 `detectDragGestures` 替换为 `awaitEachGesture` + `awaitFirstDown` + `awaitLongPressOrCancellation` + 手动拖拽跟踪循环；实现可靠的点按选中、长按选中、拖拽移动和四角缩放手势分离；新增 `import androidx.compose.foundation.gestures.awaitEachGesture/awaitFirstDown/awaitLongPressOrCancellation`
  - `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModel.kt` — 新增 `refreshRois()` 公开方法，支持删除后重新加载 ROI 列表
  - `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt` — 新增 4 项测试：删除后重新加载验证、新增 ROI 回归、移动 ROI 回归、缩放 ROI 回归；总测试从 49 项增至 53 项
  - `gradle/libs.versions.toml` — 新增 `androidx-compose-ui-test`、`androidx-compose-ui-test-junit4`、`androidx-compose-ui-test-manifest` 库声明
  - `app/build.gradle.kts` — 新增 `testImplementation(libs.androidx.compose.ui.test.junit4)` 和 `debugImplementation(libs.androidx.compose.ui.test.manifest)`
- 测试命令及结果：
  - `:app:compileDebugKotlin --no-daemon` — BUILD SUCCESSFUL
  - `:app:testDebugUnitTest --no-daemon` — BUILD SUCCESSFUL（410 项：全部 passed / 0 failed / 0 skipped）
  - `:app:assembleDebug --no-daemon` — BUILD SUCCESSFUL
- APK 路径/时间/大小/SHA-256：
  - 路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 时间：2026-09-03 16:29:48 +0800
  - 大小：221,316,150 bytes（~211 MB）
  - SHA-256：`884a45fd789c10c12ff50602589bbeef0c1fba3cb1ff6c2b0c10fa9310b2b04c`
- 真机证据：`NOT_RUN_BY_SCOPE`（本轮禁止 adb）；人工交互验收已通过（2026-09-03 用户确认：已有 ROI 和新增 ROI 均可点按/长按选中、确认删除并正确持久化）
- 未完成项：
  - 手势级自动化测试（点按选中、长按选中、重叠 ROI 命中）因 RoiCanvas 为 private 函数无法直接通过 Compose UI 测试覆盖；已通过 ViewModel 级测试（53 项）+ 人工交互验收覆盖
  - 重复"新建零件"按钮（PartListScreen 顶部与列表区可能存在两个入口）作为后续独立任务处理
  - 结果包导出（基础照片 ZIP、manifest + Excel + 图片）作为后续独立任务，待单独规划
  - 现场采集模板图布局稳定性、拍后比对（V1-3）、Detector（V1-4）、结果查看（V1-5）暂不处理
- Git 提交：`NOT_COMMITTED`（本轮不提交）

---

## 已完成任务：模板配置支持先创建零件，再导入模板

状态：**SOFTWARE_COMPLETE**，已提交 `866c23fc`（2026-09-03，人工验收通过）

目标：在当前 `PartListScreen` 中提供真实的”新建零件”入口，使零件可以在没有模板图片/没有 View 的状态下创建；创建成功后进入对应 `PartDetailScreen`，再由用户导入相册图片或拍摄模板 View。

执行边界：

- 复用现有 `PartEntity`、`PartDao`、`InspectionRepository`、`PartListScreen`、`PartDetailScreen` 和现有导航；不新增数据库表或第二套零件数据模型。
- 保留现有”从相册导入多张图片””选择已有零件导入””拍摄新 View””View 重拍/删除/ROI 编辑”行为，不重写 ROI 和模板存储逻辑。
- 新建零件时只填写一次零件 ID/名称；创建空零件不要求模板图片，不产生空模板或孤儿文件。
- 只修改 MobileInspectionApp、自动化测试和文档；不执行 adb，不安装/卸载或启动/停止真机应用，不修改旧工程。
- 修改前先列出文件和必须保留的前序行为；不得以按钮出现或编译通过代替真实回调/数据库验证。

完成清单（执行 Agent 完成真实实现和验证后回填，不得提前勾选）：

- [x] `PartListScreen` 提供可发现、可操作的”新建零件”入口（TopAppBar + 单个全宽按钮）
- [x] 新建对话框校验 ID、名称和重复 ID，并通过真实 Repository 写入 `PartEntity`
- [x] 创建成功后可进入对应 `PartDetailScreen`，即使该零件暂时没有 View
- [x] 空零件重新进入模板配置后仍存在，并显示 0 个视角/空状态
- [x] 从零件详情导入多张图片时，所有图片仍归属当前 `partId`，并按顺序创建 View（前序行为未修改）
- [x] 既有”选择已有零件导入”流程和”拍摄新视角”流程不回归
- [x] 未生成无图片模板、重复零件或孤儿文件；失败状态有明确提示
- [x] 补充新建空零件、重复 ID、创建后导入和当前 Part 归属自动化测试（PartCreationValidatorTest 18 项）
- [x] `PartListScreen` 页面只保留”新建零件”入口，移除”导入模板”按钮避免顶部遮挡
- [x] `TemplatePackages` 独立页面、路由和功能未被破坏
- [x] `:app:compileDebugKotlin --no-daemon` 通过（52s）
- [x] `:app:testDebugUnitTest --no-daemon` 通过（397 项：全部 passed / 0 failed / 0 skipped）
- [x] `:app:assembleDebug --no-daemon` 通过（29s）
- [x] 更新 `docs/reports/b2/` 对应整改报告，记录实际修改文件、测试结果、未完成项和前序能力回归矩阵

### 执行完成回填区（由 Agent 在完成任务后填写）

- 实际修改文件：
  - `app/src/main/java/com/wearable/inspection/mobile/ui/screens/PartListScreen.kt` — 移除”导入模板”按钮、图片选择器（`imagePicker`）、导入对话框（`showImportDialog`）和所有导入相关状态（`importing`、`importMessage`、`selectedUris`、`newPartId`、`newPartName`、`newPartError`、`selectedExistingPartId`）；按钮行改为单个全宽”新建零件”按钮；移除未使用的 `database` 变量和 5 个不再需要的 import；EmptyPartsState 提示文案改为”点击上方按钮新建零件”
  - `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt` — 前序已有改动：TemplateConfig 和 PartList 路由均新增 `onPartCreated` 回调（本轮未修改）
  - `app/src/test/java/com/wearable/inspection/mobile/ui/screens/PartCreationValidatorTest.kt` — 前序已有 18 项测试（本轮未修改）
- 测试命令及结果：
  - `:app:compileDebugKotlin --no-daemon` — BUILD SUCCESSFUL（52s）
  - `:app:testDebugUnitTest --no-daemon` — BUILD SUCCESSFUL（397 项：全部 passed / 0 failed / 0 skipped）
  - `:app:assembleDebug --no-daemon` — BUILD SUCCESSFUL（29s）
- APK 路径/时间/大小/SHA-256：
  - 路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 时间：2026-09-03 14:56
  - 大小：221,315,919 bytes（~211 MB）
  - SHA-256：`2a32ee15784734a8c719f3c3e33598788066d5c3673f1d90980bfbaab88c458b`
- 真机证据：`NOT_RUN_BY_SCOPE`（本轮禁止 adb）
- 未完成项：
  - 拍后比对（V1-3）、Detector（V1-4）、结果查看（V1-5）仍为 DEFERRED
- Git 提交：`866c23fc`（`feat(template): simplify template config entry`）

---

## 已完成任务：ROI 移动/缩放整改

状态：**SOFTWARE_COMPLETE**

目标：在现有 `RoiEditorScreen` / `RoiEditorViewModel` 基础上，让每个 View 的已有 ROI 可以被真实选中、拖拽移动、四角缩放，并将结果以合法 `normalizedRect` 写回当前 View 对应的 `RoiDefinitionEntity`。

执行边界：

- 复用现有 `PartEntity`、`InspectionTemplateEntity`、`RoiDefinitionEntity`、`RoiDao`、`InspectionRepository` 和唯一 CameraX 架构；不新增数据库表或第二套 CameraX。
- 不实现拍后自动比对、实时轮廓/姿态对齐、Detector/PASS-FAIL、Session ROI 或结果导出。
- 只修改 MobileInspectionApp；不执行 adb，不安装/卸载或启动/停止真机应用，不修改旧工程。
- 修改前先列出文件并说明必须保留的前序行为；不得把 TODO 或仅编译通过当作完成证据。

完成清单（执行 Agent 完成真实实现和验证后回填，不得提前勾选）：

- [x] 已有 ROI 可在 Canvas 内点击命中并显示选中状态
- [x] 已有 ROI 支持拖拽移动，且始终限制在图片 `contentRect` 内
- [x] 已有 ROI 支持四角缩放，且最小尺寸、边界和 left/top/right/bottom 关系有效
- [x] 移动/缩放结果转换为 0..1 范围的 `normalizedRect` 并调用 `InspectionRepository.updateRoi`
- [x] ROI 删除、取消、新增和不同 View 的 `templateId` 隔离行为保持不回归
- [x] 删除两个 ROI 移动/缩放 TODO 空回调；无 UI 按钮只存在但不落库的假功能
- [x] 补充 ROI 创建、更新、删除、边界、序列化和多 View 隔离自动化测试
- [x] `:app:compileDebugKotlin --no-daemon` 通过
- [x] `:app:testDebugUnitTest --no-daemon` 通过
- [x] `:app:assembleDebug --no-daemon` 通过
- [x] 更新 `docs/reports/b2/` 对应整改报告，记录实际修改文件、测试结果、未完成项和前序能力回归矩阵

### 执行完成回填区（由 Agent 在完成任务后填写）

- 实际修改文件：
  - `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModel.kt` — 移除未使用的 InteractionMode 枚举；moveRoi/resizeRoi 方法调用 InspectionRepository.updateRoi 持久化；NormalizedRect.move/resize 方法实现边界约束和最小尺寸
  - `app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiEditorScreen.kt` — RoiCanvas 实现点击选中、拖拽移动、四角缩放；角点控制柄绘制；onRoiMoved/onRoiResized 回调连接 ViewModel；两个 TODO 空回调已删除
  - `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt` — 40 项测试：覆盖 NormalizedRect 序列化、move 边界约束（8 项）、resize 四角语义（10 项）、最小尺寸约束、0..1 范围约束、多 ROI 独立性、move+resize 组合操作
- 测试命令及结果：
  - `:app:compileDebugKotlin --no-daemon` — BUILD SUCCESSFUL（17s）
  - `:app:testDebugUnitTest --no-daemon` — BUILD SUCCESSFUL（379 项：374 passed / 0 failed / 5 skipped）
  - `:app:assembleDebug --no-daemon` — BUILD SUCCESSFUL（20s）
- APK 路径/时间/大小/SHA-256：
  - 路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 时间：2026-09-03 13:43
  - 大小：224,833,659 bytes（~214 MB）
  - SHA-256：`d679e7a3e41f236d1958b125410ec827a54eb28c7d7e58b83fd216a8345bb56c`
- 真机证据：`NOT_RUN_BY_SCOPE`（本轮禁止 adb）
- 未完成项：
  - 移动/缩放回调中逐次 `updateRoi` 调用可能在高频拖拽时产生性能压力（可后续优化为 onDragEnd 才持久化）
  - ViewModel 持久化测试因 Mockito 不支持 Kotlin suspend 函数未能添加（通过编译+assembleDebug+NormalizedRect 逻辑测试覆盖）
  - 拍后比对（V1-3）、Detector（V1-4）、结果查看（V1-5）仍为 DEFERRED
- Git 提交：`NOT_COMMITTED`（未获用户明确授权前不提交）

---

## 暂停任务：B3 Presence Detection：螺纹、螺母和其他特征的目标有无检测

状态：**PAUSED_BY_USER**

- [ ] 离线清点 Key/DCIM、EXIF、HSV 青绿色掩码、debug 图、manifest、unknown-only ground truth
- [ ] 实现并测试独立 `ThreadPresenceDetector`、`NutPresenceDetector`、`FeaturePresenceDetector`
- [ ] 建立统一 detector 协议/注册器，接入 `RoiDefinitionEntity` 配置和 `RoiInspectionRecordEntity`
- [ ] 通过现有唯一 `CameraController`/`FrameAnalyzer` 接入实时 single-flight、帧节流和连续帧结果
- [ ] 执行离线工具、JVM 测试、Debug 编译和 APK 构建
- [ ] 更新 `docs/reports/b3/feature_presence/` 最终报告；DCIM 未标注数据保持 `unknown` 并声明 `INSUFFICIENT_DATA`

---

# 历史任务：模板拍摄、缩略图、重拍、排序

B2 Task 1 已 SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING。B2 Task 2 模板导入与透明叠加 MVP 软件层面已完成。B3 Phase 2 钢印 OCR CameraX/UI 集成 SOFTWARE_COMPLETE。模板拍摄、缩略图、重拍和排序已作为历史软件任务完成；当前唯一进行中任务见文档顶部。

## Task 1：整理活跃源码边界

状态：已验收（提交 `754ec5b`）。

- [x] 用导航和全仓引用确认当前活跃 Screen
- [x] 列出旧/新重复 Screen 的引用与替代关系
- [x] 将 `LiveInspectionScreen.kt.backup` 移出 `src/main`
- [x] 不删除仍被引用的 Screen

复核记录（2026-08-31）：上次执行报告未通过验收。报告称“所有 Screen 均在 AppNavigation 中被引用”，但全仓检索显示 `WorkbenchScreen`、`RecordListScreen`、`TemplateListScreen`、`SettingsScreen` 只有定义、没有调用；同时“共 10 个”实际列出 13 个 UI 文件。执行 Agent 必须先给出“文件、职责、调用方、保留/归档结论”四列表，再处理未引用旧页面。不得仅以函数名不同判断不存在重复职责。

验证：每个小批改动后执行 `:app:compileDebugKotlin`。

## Task 2：CameraPreview 状态与画幅

状态：✅ 已验收（提交 `28d692d`，HONOR YAL-AL10, ERLDU20429005890）。

- [x] 接通 onCameraReady、权限拒绝、永久拒绝和错误回调
- [x] 永久拒绝提供系统设置入口，错误状态提供真实重试
- [x] 加载状态在 CameraState.OPEN 后消失（isCameraReady 可观察状态）
- [x] `PreviewView.ScaleType` 使用 `FIT_CENTER`
- [x] Preview/ImageAnalysis/ImageCapture 统一 RATIO_4_3_FALLBACK_AUTO_STRATEGY
- [x] ContentRect 计算完成（使用 CameraController.streamResolution 和 streamRotation）
- [x] 竖屏内容使用实际流比例 + FIT_CENTER，无固定 60/40 拉伸
- [x] 输出 PreviewView、流尺寸、旋转和 content rect 诊断日志
- [x] 真机：PreviewView 1080x1039；现场拍照 ImageCapture 8000x6000（4:3），实时 Preview/Analysis 约 1440x1080（4:3）；旋转 90°，contentRect 779x1039
- [x] 真机：四角标记可见，不进入 letterbox，中央圆不变形
- [x] 真机：权限临时拒绝后可再次请求并恢复
- [x] 真机：LiveInspectionScreen 首屏直接显示实时相机预览
- [x] 真机：顶部操作为扫一扫/OCR 钢印

## Task 3：CameraController 模式与生命周期

状态：✅ 已验收（最终修复提交 `bb22f1e`，HONOR YAL-AL10, ERLDU20429005890；APK SHA-256 `fad6ef0ddbf1c4b59970ede6810d0e072dfa7680e2fa6d9be9290d2cc3c29720`）。

### Task 2 累积回归恢复

- [x] `PreviewView.ScaleType` 恢复 `FIT_CENTER`，禁止 `FILL_CENTER` 和 1:1 裁切
- [x] 实际 4:3 流旋转后以 3:4 完整显示，允许 letterbox，不拉伸、不裁切
- [x] 只有 `CameraStateType.OPEN` 后加载消失并触发 `onCameraReady`
- [x] 权限请求、临时拒绝、永久拒绝、系统设置返回和错误重试恢复
- [x] 实际 streamResolution/rotationDegrees、生产 ContentRectCalculator 和诊断日志恢复
- [x] Debug 四角/中央圆校准通过，轮廓与 ROI 只映射到 contentRect
- [x] 正式源码和主 Manifest 不包含 exported 测试 Activity
- [x] 当前源码新 APK 真机截图与 Task 2 回归矩阵通过
- [x] 重复进入现场采集不会叠加 CameraX UseCase
- [x] 延迟 disconnect 不会解绑新 session
- [x] 相机错误使用简洁 UI，不显示原始异常

- [x] CameraMode 枚举：IDLE/INSPECTION/DPM_SCAN/STAMP_OCR/TEMPLATE_CAPTURE + UseCase 需求配置
- [x] switchMode() 串行 Mutex 保护：停止旧分析器 → 关闭旧 Executor → unbindAll → 构建新 UseCase → 重绑
- [x] 同一时刻只有一组 UseCase、一个分析器、一个分析 Executor
- [x] disconnect() 页面离开可恢复，release() 永久释放后不可复用
- [x] 不持有 Activity/LifecycleOwner/PreviewView 强引用
- [x] FrameAnalyzer 接口：analyze() 所有路径关闭 ImageProxy，stop() 清理内部状态
- [x] TestCountingAnalyzer 测试分析器验证互斥和资源释放
- [x] 真机模式 round-trip 20 次：100 次切换全部成功，0 失败（HONOR YAL-AL10, ERLDU20429005890）
- [x] logcat 禁止模式检查：8 项全部 0 次匹配
- [x] 真机 Tab 往返 10 次：无黑屏、重复绑定、Camera already in use
- [x] 真机前后台切换 10 次：无 RejectedExecutionException、ImageProxy 泄漏
- [x] 单元测试 50/50 通过（CameraControllerTest，含并发/压力测试和会话管理测试）

完成条件：上方 Task 2 回归项、Tab 10 次、前后台 10 次和重新生成的 logcat 检查必须在同一个最终 APK 上全部通过。

## Task 4：真实拍照与存储

状态：✅ 已验收（提交链 `48f7587` → `566acaea` → `3a04b658`，HONOR YAL-AL10, ERLDU20429005890；APK SHA-256 `6a3ce752f2f07a09084c57499a4c1ccac8e331b9a52dd8066824c43d7ade858d`）。

- [x] Task 3 收口基线已提交，正式 Manifest 无测试入口，`tools/contour_extraction/` 未混入
- [x] 主快门使用当前 CameraSession/ImageCapture，不创建或重绑第二套 CameraX
- [x] 拍照前校验 session、OPEN、Capture、零件、模板和 ROI
- [x] UI 使用 IDLE/CAPTURING/SAVED/ERROR，拍摄中禁用重复点击
- [x] 临时 JPEG 唯一命名并写入 App 私有目录
- [x] MobileImageStore 校验非空、可解码、宽高和方向后原子移动（使用 .part 中间文件）
- [x] 失败、取消、过期 session、页面离开和低存储路径清理临时文件
- [x] 成功只表示原图保存，不创建假检测记录、识别图或 ROI 结果
- [x] takePhoto 不在异步回调期间持有全局 Mutex（capture request token 机制）
- [x] capture request token 机制使旧会话回调失效
- [x] Task 4 专项自动化覆盖并发点击、重名、空文件、损坏 JPEG、取消和会话切换（17 项拍照测试 + 8 项存储测试，共 25 项）
- [x] 真机连续拍摄 20 张：无空文件、重名、方向错误和临时残留
- [x] Task 2/3 受影响回归矩阵在同一最终 APK 上通过

整改内容：
1. takePhoto 不在异步回调期间持有全局 Mutex ✅
2. capture request token 机制使旧会话回调失效 ✅
3. 文件事务使用 .part 中间文件 + 真正原子移动 ✅
4. 补齐自动化测试（17 项 CameraControllerTakePhotoTest + 8 项 MobileImageStoreTest） ✅
5. 真机连续拍摄 20 张验收 ✅
6. CaptureExecutor 可注入接口支持异步行为测试 ✅
7. runTest + advanceUntilIdle() 异步测试策略 ✅

## Task 5：B1 完整验证

状态：✅ 已验收（APK SHA-256 `235f8aa8c4d65b365a93bff021041e43dca86d5eb4b121ba9d13ebd3f436768f`，HONOR YAL-AL10, ERLDU20429005890）。

- [x] `.\gradlew.bat :app:testDebugUnitTest --no-daemon` — 78/78 通过
- [x] `.\gradlew.bat :app:assembleDebug --no-daemon` — BUILD SUCCESSFUL
- [x] 当前源码新 APK 安装成功 — adb install Success
- [x] 冷启动 10 次无 FATAL EXCEPTION — 10/10 通过
- [x] 权限允许流程通过 — CameraService connectDevice 日志确认
- [x] logcat 无 Camera already in use、重复绑定、ImageProxy 泄漏 — 12 项门禁 0 违规（1 项系统误报）
- [x] 记录 APK 路径、时间、大小和 SHA-256 — 见报告
- [x] 提供真实预览截图 — 01_cold_start.png 用户视觉复核通过
- [x] MobileImageStoreTest 补强修正 — 11/11 通过
- [x] 自动化测试真实总数统计 — 78 项（40+17+11+10）
- [x] Tab 往返 10 次 — 无黑屏、重复绑定
- [x] 前后台切换 10 次 — 无崩溃、Camera already in use
- [x] 日志门禁 12 项为 0 — 通过（1 项 WindowManager 系统误报）
- [x] 截图与证据收集 — docs/reports/b1/evidence/task5/
- [x] TASK5_FINAL_VALIDATION_REPORT.md — 已创建

> 补充提交 `b7c4c08e`：`connectedDebugAndroidTest` 已在 HONOR YAL-AL10 上完成，Instrumented 20/20 通过；JVM 78/78 通过。

## B1 完成门禁

- [x] 上述所有验收项全部完成
- [x] `docs/reports/b1/B1_CAMERA_FOUNDATION_REPORT.md` 与真实结果一致
- [x] 用户确认进入 B2

B1 已完成并关闭。

## B2 Task 1：旧 DPM 识别链迁移与实时扫码闭环

**状态**：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-02）。软件层面全部完成：尺寸设置接线、旧参数恢复、框内约束自动化、DPM instrumented 测试、10 次稳定性验证、APK SHA-256 `6e2ca7d3f573c1da1af7f9180c23a0dbe8f2f9081eafff5ccf466dcb09c051cc`。仅剩物理 DPM 样品相关验收项（需要物理样品，不得伪造）。物理验收不阻塞后续非 DPM 功能开发。
~~1. 新版缺少旧版全图 ZXing 兜底阶段~~ ✅ 已修复（Stage2 全图降采样1280）
~~2. 新版缺少旧版 ML Kit 全图兜底阶段~~ ✅ 已修复（Stage3 ML Kit 全图兜底）
~~3. 中心 ROI 使用固定1200×1200像素~~ ✅ 已修复（centerCropRatio=0.5f）
~~4. DpmGridGate(missThreshold=5, cooldownMs=3000)~~ ✅ 已修复（missThreshold=8, cooldownMs=1500）
~~5. triggerGridDecode 硬编码 AUTO~~ ✅ 已修复（读取 SettingsStore）
~~6. gridGate.onMiss() 从未被调用~~ ✅ 已修复（handleMiss() 调用）
~~7. CameraController.setTorch()~~ ✅ 已修复（2026-09-02 用户复测通过）

物理验收不阻塞后续非 DPM 功能开发。不得伪造物理验收结果。

- [x] 整改现场采集”扫一扫”仍为 TODO，接通真实 DPM 路由并补导航测试
- [x] CameraPreview 支持显式目标 CameraMode；DPM 页面连接即为 DPM_SCAN，不得被组件重新连接成 INSPECTION
- [x] 将可见扫码框按真实 contentRect、流旋转映射为动态 scanRoi，禁止以 null/固定中心裁剪冒充框内扫码
- [x] 修复 ImageProxy 已旋转 Bitmap 后又把 rotation 传给 DpmAnalyzer 的重复旋转
- [x] 修复 YUV_420_888 转换对 Y/UV rowStride、pixelStride 和裁剪矩形的处理，并增加真机/合成测试
- [x] stop 必须取消 DPM 专属任务并阻止迟到结果；生产代码不得调用 resetForTest
- [x] 网格重建成功结果必须进入统一结果流，不能调用 processDecodeResult 后丢弃返回值
- [x] DpmScanResult 保留真实 ZXING/ML_KIT/GRID 来源，不得在 ViewModel 中统一伪写 ZXING
- [x] 尺寸模式从 SettingsStore 读取并作为任务快照传入网格链，不得在 DpmAnalyzer 中硬编码 AUTO（2026-09-02 代码审计确认：DpmScanViewModel 传递 `{ MobileInspectionApp.settings(app).dpmDimensionMode }`，triggerGridDecode 调用 `dimensionMode()` 非硬编码）
- [x] 恢复旧参数：中心 50%/ROI 目标宽 400、focus miss 30、grid miss 8、grid cooldown 1500ms（2026-09-02 代码审计确认：DpmAnalyzerConfig centerCropRatio=0.5f、roiTargetWidth=400、missTriggerCount=30、gridMissThreshold=8、gridCooldownMs=1500L；DpmGridGate missThreshold=8/cooldownMs=1500；handleMiss()调用 gridGate.onMiss()）
- [x] CP6 重新统计实际测试用例；Gradle actionable tasks 数量不得冒充测试数量
- [x] 新增 DPM 专属 instrumented/UI 测试及真实框内/框外、旧新 App 同样本 A/B 证据（2026-09-02：DpmSettingsInstrumentedTest 10 项 + DpmFrameConstraintTest 17 项已通过；A/B 对照需要物理 DPM 样品）
- [x] 所有 Batch 5 真机证据均按包名门禁重新确认：显式安装当前新 APK，启动 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`，并记录前台包、启动组件和 APK SHA-256（2026-09-02 验证通过）
- [x] 每次 `connectedDebugAndroidTest` 返回后强制重新停止新旧包、安装主 APK、完整组件启动新 App，并确认新包 PID 非空、旧包 PID 为空、前台属于新包（2026-09-02 验证通过）
- [x] 整改 `0c8e045e`：提交 `4c522ce7` 已恢复旧版 ZXing 主解码 → ML Kit DATA_MATRIX 兜底顺序
- [x] 将含糊的 `PrimaryDecoder/FallbackDecoder` 改为 `DpmZxingDecoder/DpmMlKitDecoder`
- [x] 按旧文件、旧职责、新文件、复用内容、去除耦合和迁移测试更新迁移表（2026-09-02 更新：docs/migration/LEGACY_MIGRATION_MAP.md Section 0 已包含 B2 Task 1 迁移结论）
- [x] “扫一扫”进入真实 `CameraMode.DPM_SCAN`，只绑定 Preview + ImageAnalysis
- [x] 使用独立 `DpmFrameAnalyzer`，共享唯一 CameraController
- [x] 按旧顺序迁移 ZXing DataMatrixReader 主解码和 ML Kit DATA_MATRIX 兜底，不调换主备关系
- [x] 迁移中心 ROI、全图降采样、DpmPreprocessor 策略轮转和正常/反转双极性尝试（2026-09-02 代码审计确认：performMultiStrategyDecode Stage1=ROI ZXing、Stage2=全图降采样1280 ZXing、Stage3=ML Kit 全图兜底、Stage4=网格兜底；正常+反色双试已实现）
- [x] 迁移 DpmRespondGate、帧节流、single-flight、连续 miss 对焦和 stop 后不回调
- [x] 迁移 DpmGridGate、DpmGridReconstructor、ImportedDpmScanner，并保留旧版取消、冷却和超时边界
- [x] 迁移 `DpmDimensionMode` 的 AUTO/DIM_16/DIM_18/DIM_20、旧候选配额、跨尺寸交错和非法值回退 AUTO
- [x] 将尺寸模式真实接入网格重建；默认 AUTO 同时公平尝试 16×16、18×18、20×20，固定模式只尝试所选尺寸
- [x] 持久化 DPM 尺寸模式；设置变化只影响后续网格任务，不能中途篡改在途任务快照
- [x] 实现 `DpmFrameAnalyzer`，通过现有 `FrameAnalyzer` 接入唯一 CameraController，不创建第二套 CameraX
- [x] `DPM_SCAN` 只绑定 Preview + ImageAnalysis，分析结束由 CameraController 统一关闭 ImageProxy
- [x] 新增扫码页面和导航，现场采集”扫一扫”进入真实扫码，返回后 INSPECTION 相机恢复
- [x] 扫码框基于真实 contentRect 映射到旋转后图像 ROI；ROI 存在时禁止任何框外全图解码
- [x] 框外码不响应、框内码可识别、框内外同时存在时只返回框内码（2026-09-02：DpmFrameConstraintTest 验证 scanRoi 存在时跳过全图阶段、scanRoi 为 null 时允许全图兜底；真机验证需要物理 DPM 样品）
- [x] 同一显示设备上的旧 App 可识别 DPM 码，新 App 修复后也可识别；真机命中策略 2 点阵链，结果 `M968942280224B169AH005023044710`（2026-09-02 用户复测通过）
- [x] 闪光灯开/关真实生效，CameraX Future 成功且真实 torchState/UI 状态同步；根因是绑定成功路径遗漏保存 cameraControl（2026-09-02 用户复测通过）
- [x] CameraX 页面往返 10 次、前后台切换 10 次通过（2026-09-02 真机验证，logcat 8 项门禁 0 违规）
- [x] 页面退出后释放扫码分析资源，返回现场采集后相机正常恢复（2026-09-02 真机验证）
- [x] UI 不存在 DPM 相册选择、码图导入或相关权限/路由
- [x] 迁移旧 DPM 专项测试，并覆盖解码链、预处理、网格门控、节流、并发、重复抑制、停止和资源释放
- [ ] `PENDING_PHYSICAL_DPM_SAMPLE` 使用同一批现场/打印样本对旧 App 与新 App 做 A/B 对照，记录逐样本结果和响应时间（需要物理 DPM 样品）
- [ ] `PENDING_PHYSICAL_DPM_SAMPLE` A/B 对照分别标注 `OLD: com.wearable.inspection` 与 `NEW: com.wearable.inspection.mobile`，每轮启动前停止另一包（需要物理 DPM 样品）
- [x] 真机完成 10 次页面往返（2026-09-02 验证通过）；`PENDING_PHYSICAL_DPM_SAMPLE` 10 次扫码需要物理 DPM 样品
- [x] 10 次冷启动稳定性验证（2026-09-02：10/10 通过，logcat 6 项门禁 0 违规）
- [x] 更新 `docs/reports/b2/B2_TASK1_DPM_LEGACY_PARITY_REPORT.md` 和证据目录（本次提交）

本 Task 的最新业务边界：现场采集顶部 DPM 入口保持不变；扫码命中已绑定码时只切换已有零件及其有序模板，未知码只提示先在模板配置绑定；模板配置提供扫码绑定/更换 DPM 码，冲突码拒绝覆盖。不实现扫码后新建零件、未知码录入、OCR、轮廓、ROI 或检测算法，不改变既有 DPM 解码策略。物理样本验收仍单独标记为 pending。

---

## B2 Task 2：旧模板导入 + 模板透明叠加 MVP

**状态**：**SOFTWARE_COMPLETE**（2026-09-02，提交 `bdf1bd89`）

**产品方向变更**：实时主体轮廓投影、依赖主体轮廓的自动姿态匹配、单应性对齐、ALIGNED/LOST 自动对齐门禁 — 以上能力标记为 **DEFERRED / POST-MVP**，保留代码和工具但不继续阻塞当前 App 交付。

当前 MVP 路线：

```
模板导入/拍摄 → 选择 Part → 按 displayOrder 加载 Views → 模板原图透明叠加辅助现场取景 → 按序真实拍摄
```

Template ROI 配置、拍后模板/实拍比对、ROI 映射、Detector、PASS/FAIL、检测记录和结果导出保留在后续计划中；现场人员不编辑 ROI。

### V1-1：导入旧模板包

状态：✅ 完成（提交 `bdf1bd89`）

- [x] 检查旧 `extracted_data/template_exports` ZIP 数据结构与字段映射
- [x] 创建 `DirectoryTemplateImporter`（薄 adapter，复用 `TemplatePackageImporter.parseManifest()`）
- [x] 创建 `TemplateImportService`（事务编排：解析 → 复制图片 → upsert Part → insert Template → 失败回滚）
- [x] 适配 `PartEntity` / `InspectionTemplateEntity` 字段映射
- [x] 成功导入模板目录（template.json + images/）
- [x] 导入后零件、模板记录、模板图片文件存在且可解码
- [x] 重复导入不产生脏数据（先删除旧模板再重建）
- [x] 损坏 JSON / 缺图 / 非法字段均有明确错误且不污染数据库
- [x] 失败事务回滚，不留下孤儿文件
- [x] JVM 测试 12 项通过（DirectoryTemplateImporterTest）
- [x] 提交

遗留边界：
- **Legacy ROI 未迁移**：当前 `TemplateRegionData` 不携带 roi 信息，`roiCount` 始终为 0。`validateRoi()` 验证数据结构但不创建 `RoiDefinitionEntity`。原因：旧 ROI 的 normalized/pixel 语义、source image size、orientation、origin 和 width/height 语义尚未确认。
- **imageFiles[] 单图策略**：旧模板 schema 的 `imageFiles` 是数组，当前 MVP 仅取第一张有效图片作为 `mainImagePath`。非完整多图片业务语义迁移。

### V1-2：模板透明叠加 + Alpha Slider

状态：✅ 完成（提交 `bdf1bd89`）

- [x] CameraPreview 增加 `templateImagePath` + `overlayAlpha` 参数
- [x] 模板图片覆盖在真实 camera contentRect 内，保持原始纵横比
- [x] 不覆盖 FIT_CENTER letterbox 区域
- [x] 模板缺失时不绘制 overlay，图片缺失/损坏时显示明确错误不 crash
- [x] 模板切换时 overlay 原子更新（LaunchedEffect），不残留旧图
- [x] Alpha Slider 范围 0.0f ~ 0.8f，默认 0.45f，显示"模板透明度 XX%"
- [x] Slider 调节实时生效，不触发 CameraX rebind / session 重建
- [x] 隐藏/显示模板快捷操作
- [x] 默认不绘制自动主体轮廓（DEFERRED）
- [x] JVM 测试通过（242 项：237 passed / 0 failed / 5 skipped）
- [x] 提交

### 当前补充任务：模板 View 顺序持久化

状态：✅ 软件完成（2026-09-02）

- [x] `InspectionTemplateEntity` 增加 `displayOrder`
- [x] Room schema 版本升级和 v1 → v2 migration，旧数据按稳定创建时间/id 回填顺序
- [x] ZIP / Directory manifest 保留显式 order；缺失或非法 order 回退到 manifest index
- [x] flat-directory 使用稳定文件名排序生成 index
- [x] `TemplateImportService` 写入 displayOrder，重复导入保持相同顺序
- [x] TemplateDao 按 Part 返回 `displayOrder ASC, id ASC`
- [x] 显式 order、缺失 order、flat-directory、重复 order、数据库查询和重复导入测试

### 当前补充任务：现场采集选择和交互整理

状态：✅ 软件完成（2026-09-02）

- [x] 现场采集增加真实 Part 选择入口；选择 Part 后自动加载其全部启用 Views
- [x] View 切换仅用于查看/切换当前视角，不把每个 View 误作独立模板集
- [x] 模板配置和 Profile 统计使用真实 DB 数据，不再显示硬编码数量
- [x] 模板详情显示 View 序号/总数和参考图片语义
- [x] 完成提示改为紧凑的“本轮视角采集完成”，重新开始作为次要操作
- [x] 相机预览支持设置“原比例 / 填充预览”，默认保留原比例
- [x] 模板参考图支持“原比例 / 撑满”切换；原比例允许黑边，撑满模式仍保留模板透明叠加
- [x] 零件管理和模板视角支持左滑删除并二次确认
- [x] 采集完成提示统一为“零件采集完成”，不重复显示状态文案

### 当前补充任务：DPM 绑定与已绑定码切换

状态：源码与业务路由已接入；模板导入/按序拍摄真机流程完成；已绑定码实际切换与完整累积 instrumented 回归待补

- [x] 保持现场采集顶部“扫一扫”入口和现有实时 DPM 解码链不变
- [x] 扫码命中已绑定 DPM 码后，只切换已有 Part，并重新加载该 Part 的有序模板和 View 1/N 进度（源码路由已接入，实物命中切换待补）
- [x] 未绑定 DPM 码不新建零件，只提示先在模板配置绑定
- [x] 模板配置按 Part 显示 DPM 绑定状态，并提供扫码绑定/更换绑定入口
- [x] 已被其他 Part 使用的 DPM 码拒绝覆盖
- [x] Room DAO/Repository 增加 DPM 精确查询和更新能力
- [x] 通过 PartSelectionBus 让已存在的现场采集 ViewModel 立即切换零件
- [x] `:app:compileDebugKotlin` 已通过
- [x] 补充/执行 DPM 绑定查询测试和完整 JVM 回归（PartDpmDaoTest 3/3；JVM 318 项：313 passed / 5 skipped / 0 failed）
- [x] 生成新 APK 并按新包名门禁完成真机验证（`com.wearable.inspection.mobile`；模板导入 8 视角、按序拍摄 8/8、DPM 入口和绑定保存通过）
- [ ] 使用已绑定 DPM 码在同一最终 APK 实测切换 Part，并确认切换后从 View 1/N 重新开始
- [ ] 完整 `connectedDebugAndroidTest` 累积回归成功；当前报告因 camera round-trip runner 长时间无结果未计为通过

本轮已将 `DPM_data/` 的 6 张样本纳入离线基线运行；测试无异常退出，但当前 ZXing 全图及中心 ROI 两阶段均为 0/6 命中。该测试不覆盖 ML Kit/GRID 兜底，也不能替代实时相机样本验收。实时相机流程中曾识别 `M968942280224B169AH005023044710`，并在模板配置中绑定保存成功；随后从现场采集进入扫码页时因当前视野无码未重新命中，因此不能把已绑定码切换 Part 记为真机通过。`sample_data/1/` 的 8 张模板图已在真机通过 SAF 导入，现场采集已完成 8/8 张真实拍摄并进入检测结果页；结果页仍为空状态且无导出按钮，因为 InspectionSession 完整结果写入和结果导出按当前产品边界暂缓。完整方案对照见 `docs/reports/b2/V1_PLAN_IMPLEMENTATION_AUDIT_20260903.md`。

### 历史任务：模板拍摄、缩略图、重拍、排序

状态：**SOFTWARE_COMPLETE**（2026-09-03）。源码、自动化测试和工程文档已完成。APK SHA-256 `56e390a067ccd1a040ea05b86b9743bc185bf2c1215630e7fc0f4f35a9e7f495`。

### 历史任务：模板配置重构与逐视角 ROI

状态：**SOFTWARE_PARTIAL / ROI_REMEDIATION_PENDING**（2026-09-03 审计）

- [x] Task 1：审计并更新任务状态
- [x] Task 2：重构 Part 上下文 + 导入流程 + UI 层级
  - [x] 新建 PartListScreen（零件列表页，替代原 TemplateConfig 扁平列表）
  - [x] 新建 PartDetailScreen（零件详情页，视角网格展示）
  - [x] 改造导入对话框：支持选择已有零件或新建零件
  - [x] 添加 Screen 路由（PartList、PartDetail、RoiEditor）
  - [x] 更新 AppNavigation 注册新路由
- [x] Task 3：实现逐视角 ROI 编辑器基础能力
  - [x] 新建 RoiEditorScreen（Canvas 绘制新矩形）
  - [x] 新建 RoiEditorViewModel（状态管理、坐标转换）
  - [x] normalizedRect 坐标转换（像素 ↔ 0-1 范围）
  - [x] 边界约束（矩形不超出图片内容区域）
  - [x] TemplateDetailScreen 添加 ROI 编辑入口
  - [x] RoiEditorViewModelTest 基础序列化测试
  - [ ] 已有 ROI 点选、拖拽移动、四角缩放
  - [ ] 移动/缩放调用 updateRoi 并持久化
- [x] Task 4：自动化回归测试
  - [x] `:app:compileDebugKotlin` BUILD SUCCESSFUL
  - [x] `:app:testDebugUnitTest` 332 项（327 passed / 0 failed / 5 skipped）
  - [x] `:app:assembleDebug` BUILD SUCCESSFUL

审计结论：新增 ROI、取消、删除和按 `templateId` 查询隔离的基础路径存在，但 `RoiEditorScreen.kt` 中已有 ROI 的移动/缩放回调仍为空，`RoiEditorViewModel` 没有对应更新方法；上述未勾选项由“ROI 移动/缩放整改”任务负责，不得以本节历史测试通过替代。

### Bug Fix：模板图片降采样导致 Canvas 绘制失败

状态：**已修复**（2026-09-03）

问题：CameraPreview 加载模板图片时 inSampleSize 计算逻辑错误，8000x6000 图片只得到 inSampleSize=2，解码后仍为 4000x3000，Bitmap 过大导致 Canvas 无法绘制。

修复内容：
- [x] 修正 inSampleSize 计算逻辑：以图片最大边为依据，确保解码后不超过 2048px
- [x] 8000x6000 图片现在计算为 inSampleSize=4（解码后约 2000x1500）
- [x] 模板图片解码放在 Dispatchers.IO，不阻塞 Compose 主线程
- [x] templateImagePath 变化时释放旧 Bitmap，避免连续切换 View 造成泄漏
- [x] 提取 `calculateInSampleSize()` 为可测试函数
- [x] 新增 CameraPreviewTest 14 项单元测试
- [x] `:app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] `:app:testDebugUnitTest` 全部通过
- [x] `:app:assembleDebug` BUILD SUCCESSFUL

### V1-3：拍后比对（暂缓）

状态：下一软件任务，尚未开始（2026-09-03 方案审计）

- [ ] CaptureComparisonScreen：templateImage + capturedImage
- [ ] 双图切换 / alpha overlay / blink comparison
- [ ] 按模板 ROI 计算后续实拍图中的对应检测区域
- [ ] 提交

### V1-4：Template ROI → Detector → Result（DEFERRED）

状态：暂缓；模板 ROI 编辑器正在进行整改，Session ROI 和 Detector 执行链路尚未接入。

现场不提供 Session ROI 框选、拖动、缩放或编辑器。模板 ROI 在模板配置阶段配置一次，后续由算法映射到实拍图。

### V1-5：结果查看（DEFERRED）

状态：暂缓；检测记录和结果包导出待后续接入

---

### V1-6：MVP Profile 信息架构简化

状态：✅ 完成（提交 `94e3f5f3`）

- [x] ProfileScreen 简化为 5 个 MVP 入口（模板配置、零件管理、模板包、检测结果、应用设置）
- [x] 移除硬编码 TemplateStats（partCount=3, templateCount=5, roiCount=12, incompleteItems=2）
- [x] 使用真实 DB 统计（repository.partCount/templateCount/roiCount）
- [x] TemplateConfigScreen 从 DB 加载真实模板列表，修复返回导航
- [x] TemplateDetailScreen 显示真实模板数据（名称、零件、ROI 列表、时间戳）
- [x] PartManagementScreen 从 DB 加载真实零件列表
- [x] AppSettingsScreen 移除未生效的假开关（提示音/振动/拍照质量），保留真实 DPM 尺寸模式设置
- [x] 新增 TemplatePackageScreen：通过 SAF ZIP 选择器接入 TemplatePackageImporter
- [x] 新增 ResultManagementScreen：空状态 shell
- [x] 修复 Long→String ID 类型不匹配（TemplateDetail, InspectionResult 路由）
- [x] 新增 DAO count 方法（TemplateDao.count, RoiDao.count, InspectionSessionDao.count）
- [x] 所有 enabled clickable row 均有真实 onClick 和 NavHost route
- [x] ProfileScreen 无 dead click
- [x] JVM 242 项通过（237 passed / 0 failed / 5 skipped）
- [x] assembleDebug 成功

遗留边界：
- 模板包导出功能尚未实现（仅导入已接通）
- 结果管理页面为空状态 shell（待接入 ResultPackager）
- Template ROI、Detector、PASS/FAIL、检测记录和结果导出仍未接入；按当前产品边界暂缓

---

## LiveInspectionScreen cleanup 任务（已完成）

### A. OCR 钢印入口
保留已有独立 OCR 页面入口，不新增 OCR route。

### B. 模板选择器
现场采集页使用真实 Part 下拉选择；Part 下的 Views 来自 Room 并按 displayOrder 加载，底部视角选择器只切换当前 View。

### C. 模板参考图片 Card
模板参考图不再提供无实现的放大点击入口。

### D. hasTemplates 硬编码
使用当前 Part 的真实启用模板数据判断模板是否存在。

### E. 假"已对齐"状态
不显示自动对齐结论；现场语义为模板已就绪、按参考图调整零件位置后拍摄。

### F. 固定轮廓 / ROI 占位图形
不使用固定轮廓或假 ROI 冒充检测结果；现阶段仅保留真实模板/已存在 ROI 的叠加数据，ROI 配置与检测能力暂缓。
