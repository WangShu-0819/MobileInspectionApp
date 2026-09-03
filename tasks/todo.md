# 当前任务：DPM 绑定/已绑定码切换收口 + 当前进度文档同步

B2 Task 1 已 SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING（物理验收不阻塞后续功能开发）。B2 Task 2 模板导入与透明叠加软件能力已完成。B3 Phase 2 钢印 OCR CameraX/UI 集成 SOFTWARE_COMPLETE（2026-09-02）。当前已完成 View 顺序持久化、真实 Part 选择和现场采集交互整理；DPM 绑定/已绑定码切换已接入，待完整构建与真机验收后收口，之后才进入 V1-3 拍后比对。

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
- [x] 真机：PreviewView 1080x1039, 流 8000x6000 (4:3), 旋转 90°, contentRect 779x1039
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

状态：自动化与模板导入/按序拍摄真机回归完成；结果导出按产品边界暂缓

- [x] 保持现场采集顶部“扫一扫”入口和现有实时 DPM 解码链不变
- [x] 扫码命中已绑定 DPM 码后，只切换已有 Part，并重新加载该 Part 的有序模板和 View 1/N 进度
- [x] 未绑定 DPM 码不新建零件，只提示先在模板配置绑定
- [x] 模板配置按 Part 显示 DPM 绑定状态，并提供扫码绑定/更换绑定入口
- [x] 已被其他 Part 使用的 DPM 码拒绝覆盖
- [x] Room DAO/Repository 增加 DPM 精确查询和更新能力
- [x] 通过 PartSelectionBus 让已存在的现场采集 ViewModel 立即切换零件
- [x] `:app:compileDebugKotlin` 已通过
- [x] 补充/执行 DPM 绑定查询测试和完整 JVM 回归（PartDpmDaoTest 3/3；JVM 318 项：313 passed / 5 skipped / 0 failed）
- [x] 生成新 APK 并按新包名门禁完成真机验证（`com.wearable.inspection.mobile`；模板导入 8 视角、按序拍摄 8/8、结果页复核通过）

本轮已将 `DPM_data/` 的 6 张样本纳入离线基线运行；测试无异常退出，但当前 ZXing 全图及中心 ROI 两阶段均为 0/6 命中。该测试不覆盖 ML Kit/GRID 兜底，也不能替代实时相机样本验收。`sample_data/1/` 的 8 张模板图已在真机通过 SAF 导入，现场采集已完成 8/8 张真实拍摄并进入检测结果页；结果页仍为空状态且无导出按钮，因为 InspectionSession 完整结果写入和结果导出按当前产品边界暂缓。

### V1-3：拍后比对（暂缓）

状态：暂缓，待按序拍摄链路验收后开始

- [ ] CaptureComparisonScreen：templateImage + capturedImage
- [ ] 双图切换 / alpha overlay / blink comparison
- [ ] 按模板 ROI 计算后续实拍图中的对应检测区域
- [ ] 提交

### V1-4：Template ROI → Detector → Result（DEFERRED）

状态：暂缓

现场不提供 ROI 框选、拖动、缩放或 Session ROI 编辑器。模板 ROI 只在模板配置阶段配置一次，后续由算法映射到实拍图。

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
