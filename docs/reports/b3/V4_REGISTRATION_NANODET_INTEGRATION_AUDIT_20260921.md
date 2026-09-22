# V4 RegistrationResult → NanoDet 检测集成和结果包：主协调审计

日期：2026-09-22
状态：**SOFTWARE_AUDIT_PASSED / AWAITING_USER_ACCEPTANCE**（最终证据修正已复核）
范围：只读审计 handback、源码差异、JVM XML/HTML 和 APK 现场；未运行 ADB、instrumented 或真机。V4 基线提交为 `a690fa15`，当前模板加载和自动导航修正仍未提交。

> 说明：本报告前面的 handback 章节按时间顺序保留，仅用于追溯；当前有效结论以文末“最终证据修正审计”为准。

## 第一版 handback 复核（16:51 APK）

- 实际工作区：7 个已修改文件、3 个未跟踪文件；未修改 `registration/`。
- `./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`：`BUILD SUCCESSFUL`。
- XML：`1048 tests / 0 failures / 0 errors / 5 skipped`。
- 5 个 skipped 均为 `DpmScannerTest` 外部样本缺失时的 `assumeTrue`：`dpm_dump`、f26 regression frame、用户指定帧和批次缓存目录不在当前工作区。
- `./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks`：`BUILD SUCCESSFUL`。
- `./gradlew.bat :app:assembleDebug --no-daemon`：`BUILD SUCCESSFUL`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；2026-09-21 16:51:08 +08:00；232882135 bytes；SHA-256 `275708EAEC16D9130CEDE97F6349647113594F05C95F336E9FA814263A04B1E4`。

## 阻塞问题

### P0：失败/整图 fallback 不可达

`CaptureComparisonViewModel.canProceedToConfirmation()` 仍只接受 `RegistrationStatus.SUCCESS` 且 Session ROI 非空。失败和 `FALLBACK_FULL_IMAGE` 会让比对页按钮禁用；`AppNavigation.kt` 的 `onProceed` 又固定传 `isFullImageFallback = false`。因此 handback 所述失败 → `inferFullImage()` 主链没有实际入口。

相关文件：

- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt`

### P0：失败态存在模板 ROI 检测回退

`ViewConfirmationViewModel.loadData()` 仅在 registry 成功且有 ROI，或显式 `isFullImageFallback` 时进入新分支；否则进入 `loadWithTemplateRois()`。当 registry 状态是 FAILED 但路由参数仍为 false，或 registry 丢失时，原模板 ROI 仍可能被调用，违反失败态不得使用模板 ROI。

### P1：整图聚合和 UI 语义不安全

`inferFullImage()` 对所有类别取最高分，按阈值生成 OK/NG；无检测时也生成 NG。`loadFullImageFallback()` 再把同一聚合建议复制到每个模板 ROI 的 `NanoDetRoiInferenceResult`。这既没有目标类别约束，也会把检测不足/歧义表现为确定模型结果。当前 `ViewConfirmationScreen` 没有整图分支，仍按模板 ROI 列表渲染。

### P1：整图确认记录未进入 ZIP/CSV ROI 行

确认记录使用 `__FULL_IMAGE__`，照片行可以读取其总体结果，但 `InspectionZipExportService` 只从模板 ROI 定义建立 ROI 行；找不到同 ID 定义时跳过该确认行。整图检测证据、整图坐标和模型元数据无法通过现有 CSV ROI 行回链。

## 下一轮验收门槛

- 成功配准：只用拍照后模板对齐产生的 projected ROI 快照；手动框选/拖动/缩放只属于模板 ROI 编辑阶段，拍照后比对页不再手动调整 ROI，不得把手动编辑动作带入 NanoDet 或结果包。
- 失败/`FALLBACK_FULL_IMAGE`：可从比对页进入确认；不使用模板 ROI，不调用未经验证裁剪，只运行整图检测。
- registry 缺失、状态不一致、重复消费：必须 fail-closed，不能偷偷落入模板 ROI。
- 整图无检测、低置信度或类别/数量歧义：模型建议保持空值，明确进入人工复核/重拍语义；不得伪造确定 OK/NG。
- 既有人工确认、照片路径、`ViewRoiConfirmEntity`、ZIP/CSV 字段和证据路径保持稳定；`__FULL_IMAGE__` 需要在现有导出模型中可追溯。
- 增加对应 JVM 回归测试：成功投影、失败/fallback 可达、缓存缺失、重复输入稳定、整图不使用模板 ROI、整图导出回链、资源释放。
- 仅执行 JVM、Kotlin 编译和 Debug APK 构建；不执行 ADB、instrumented 或真机测试；本任务修复完成前不提交 Git。

## 第二次 handback 复核（2026-09-21 17:35）

### 已确认的进展

- `FAILED`/`FALLBACK_FULL_IMAGE` 已能通过 `canProceedToConfirmation()` 进入导航；路由传递 `isFullImageFallback`。
- `FullImageInferResult.aggregatedSuggestion` 和 `inferFullImage()` 保持空模型建议，整图确认页已增加摘要卡片。
- `InspectionZipExportService` 已为未匹配确认构造内存虚拟 `RoiDefinitionEntity`，具备导出 `__FULL_IMAGE__` 的代码路径。

### 主协调实际验证

- `testDebugUnitTest`：`1078 tests / 0 failures / 0 errors / 5 skipped`。
- `V4NanoDetIntegrationTest`：28 / 0 / 0 / 0。
- `SessionRoiRegistryTest`：9 / 0 / 0 / 0。
- `CaptureComparisonGeometryTest`：21 / 0 / 0 / 0。
- `compileDebugKotlin`、`assembleDebug`：`BUILD SUCCESSFUL`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；2026-09-21 17:35:22 +08:00；232885605 bytes；SHA-256 `64EF3D175A52512ED7AD9DD7B6443C3778ACDDF616E592F7A5A2875AB14FEF5E`。
- 实际 5 个 skipped 全部来自 `DpmScannerTest`：`frame960Decodes`、`capturedF26Decodes`、`batchDecodeCache20260820`、`userSpecifiedFrameDecodes`、`probeRootDumpCandidatesF23ToF37`；原因是外部样本文件或目录不存在。handback 列出的 5 个其他测试类与本次 XML 不符。

### 上一轮仍未收口项（本轮已复核关闭）

1. `ViewConfirmationViewModel.loadData()` 在 registry 缺失/重复消费/状态不一致且 `isFullImageFallback=false` 时仍调用 `loadWithTemplateRois()`。
2. `loadWithProjectedRois()` 对缺少 Session ROI 的模板 ROI 保留原 `normalizedRect`，可能混合调用 projected ROI 和模板 ROI。
3. `V4NanoDetIntegrationTest` 的“导出回链”和“无需模板 ROI”主要是对象/源码级断言，没有真实 ViewModel + exporter 组合验证。
4. `InspectionZipExportService` 对所有未匹配 `roiId` 都生成虚拟定义，范围比 `__FULL_IMAGE__` 更宽；应至少有未知 ROI 的稳定性测试，避免把失效确认行静默导出。

因此第二轮修复后，前述 P0/P1 主链问题已按源码复核收口；本轮不提交 Git。保留用户验收项：真实设备上的检测效果、整图检测框可视化和外部样本 skipped 的环境补证。不得改变 V4 registration、NanoDet 模型/decoder/阈值/类别协议、CameraX、DPM、OCR，也未运行 ADB、instrumented 或真机测试。

## 第二轮修复 handback 复核（2026-09-21 18:08）

### 源码审计结论

- `CaptureComparisonViewModel` 继续只在 registration 已产生时允许进入确认；手动框选/拖动/缩放属于模板 ROI 编辑阶段，不属于拍照后检测流程。当前 `AppNavigation` 把 `sessionRois` 写入 registry，需在修正中明确交接 registration 自动产生的 projected ROI 快照；失败和 `FALLBACK_FULL_IMAGE` 写入空 ROI 及真实状态。
- `ViewConfirmationViewModel.resolveLoadingPath()` 只允许 `PROJECTED` 或 `FULL_IMAGE`；registry 缺失、消费后重复进入、状态不一致、空投影和显式 fallback 均 fail-closed 到整图，模板 ROI 加载函数没有可达调用点。
- 当前成功路径会用 registry 中的 Session ROI 改写本次推理坐标；需修正为只使用 registration 自动产生的 projected ROI 快照，拍照后不再存在手调 ROI 路径。缺少任一 projected ROI 时整体调用整图推理，避免 projected/template 混合检测。
- `inferFullImage()` 复用既有 NanoDet runtime、预处理和 decoder，返回全图 detections；整图 `aggregatedSuggestion` 固定为空，人工确认仍通过现有总体 OK/NG 选择完成。
- 整图确认继续写入 `ViewRoiConfirmEntity`，使用 `__FULL_IMAGE__` 合成 ROI、现场照片关联、全图像素矩形和检测元数据。导出器仅允许 `KNOWN_SYNTHETIC_ROI_IDS` 中的 `__FULL_IMAGE__` 生成虚拟定义，未知未匹配 `roiId` 跳过并记录 warning。
- `inferFullImage()` 和已有 ROI 推理路径均在 finally 中释放 OpenCV Mat；ViewModel `onCleared()` 释放位图并关闭 inference service。

### 主协调实际验证

- `./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`：`BUILD SUCCESSFUL`。
- 实际 XML：`1100 tests / 0 failures / 0 errors / 5 skipped`。handback 的 `1105` 是将 5 个 skipped 再次计入总数后的口径，不作为实际 XML 统计。
- 5 个 skipped 均来自 `com.wearable.inspection.mobile.dpm.DpmScannerTest`：`frame960Decodes`、`capturedF26Decodes`、`batchDecodeCache20260820`、`userSpecifiedFrameDecodes`、`probeRootDumpCandidatesF23ToF37`；均因外部样本文件或目录不存在。
- 目标回归套件：`V4NanoDetIntegrationTest=45`、`SessionRoiRegistryTest=9`、`InspectionZipExportServiceTest=17`、`ViewModelSaveLifecycleTest=4`、`CaptureComparisonGeometryTest=21`、`NoRoiViewAdvancementTest=18`、`ViewConfirmationNavigationTest=18`，均 `0 failures / 0 errors / 0 skipped`。
- `./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks`：`BUILD SUCCESSFUL`。
- `./gradlew.bat :app:assembleDebug --no-daemon`：`BUILD SUCCESSFUL`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；2026-09-21 18:08:08 +08:00；232888143 bytes；SHA-256 `0668688DEDA908BB86EF83B3B6228BDA055A67D319DBFC9C90D6CC5C358F2838`。
- 未运行 ADB、instrumented 或真机测试；当前 V4 代码已选择性提交为 `a690fa15`。

### 收口与限制

- 软件集成状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**。
- 本次通过的是源码/JVM/编译/APK 级审计，不等同于真实 NanoDet 检出效果或真机验收。
- 已知后续项：整图模式目前显示摘要而非检测框叠加；DPM skipped 仍需在具备外部样本的环境补证；下一历史任务为 DPM 绑定码切件的完整真机/累积 instrumented 验收，须用户单独授权设备测试。

## 2026-09-22 主协调复核补记

- 重新核对工作区后，实际 Git 状态为 16 个已修改文件、5 个未跟踪文件；第 5 个为本审计报告，先前任务文件中的“4 个未跟踪文件”计数已更正。
- `testDebugUnitTest` 既有 XML 汇总仍为 `1100 tests / 0 failures / 0 errors / 5 skipped`；未重新执行 Gradle、ADB、instrumented 或真机测试。
- APK 文件现场核对仍为 `app/build/outputs/apk/debug/app-debug.apk`，232888143 bytes，2026-09-21 18:08:08 +08:00，SHA-256 `0668688DEDA908BB86EF83B3B6228BDA055A67D319DBFC9C90D6CC5C358F2838`。
- 发现两项 P1 坐标/语义问题：①模板 ROI 的手动框选/拖动/缩放与拍照后自动 projected ROI 的交接未收口，当前导航把 `sessionRois` 写入 registry，需改为 registration 自动产生的 projected ROI 快照；拍照后不应再有手调 ROI 路径。②`loadWithProjectedRois()` 在第 186–239 行使用 registry ROI 裁剪并调用 NanoDet，但 `saveRoiConfirms()` 在第 606–613 行仍从模板 `roi.normalizedRect` 重新生成 `roiPixelRect`，因此 DB/CSV 坐标可能与实际拍照后 projected 推理 `roiBounds`/检测框不一致；现有回归未断言这两点。
- 本轮不修改代码、不联系执行 Agent、不运行新构建或设备测试；状态改为 `SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION`，待修正并补充成功路径保存字段回归后再恢复用户验收口径。

## 2026-09-22 ROI 数据边界修正 handback 复核

### 已核对的修正

- `AppNavigation` 已改为把 `CaptureComparisonViewModel.projectedRoisSnapshot` 写入 `SessionRoiRegistry`，不再直接把可变的 `sessionRois` 作为 NanoDet 检测交接数据。
- `ViewConfirmationViewModel.loadWithProjectedRois()` 已用 registry 中的 projected ROI 生成像素矩形、裁剪输入和推理 ROI，并缓存 `projectedPixelRects` 供保存阶段复用。
- `V4NanoDetIntegrationTest` 源码已扩展到 54 项；`SessionRoiRegistryTest` 为 9 项；handback 列出的目标套件均报告 0 failures / 0 errors。
- APK 现场核对一致：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 09:53:31 +08:00，232888610 bytes，SHA-256 `7C7BF221769F0361520E69895F449D59081CFBCFD119DAAAA972DBE19D18DDA4`。

### 主协调实际证据

- 当前本地 `app/build/test-results/testDebugUnitTest/*.xml` 和 `app/build/reports/tests/testDebugUnitTest/index.html` 汇总为 `1109 tests / 0 failures / 0 errors / 5 skipped`，不是 handback 声称的 `1114 tests`；5 个 skipped 仍全部来自 `DpmScannerTest` 外部样本或目录缺失。
- 目标 XML 实际包含：`V4NanoDetIntegrationTest=54`、`SessionRoiRegistryTest=9`、`InspectionZipExportServiceTest=17`、`ViewModelSaveLifecycleTest=4`、`CaptureComparisonGeometryTest=21`，均为 0 failures / 0 errors。
- 本轮主协调未重新执行 Gradle；未运行 ADB、instrumented 或真机测试；当前 V4 代码已选择性提交为 `a690fa15`。`compileDebugKotlin` 和 `assembleDebug` 仅记录为 handback 报告结果，APK 由主协调现场复核。

### 当前仍未收口的阻塞

1. `CaptureComparisonScreen` 和 `CaptureComparisonViewModel` 仍保留拍照后 `sessionRois` 的拖动/缩放入口。即使这些操作不再影响 registry/NanoDet，它们仍违反当前产品边界：手动框选、拖动、缩放只属于模板 ROI 编辑阶段，拍照后比对页只应显示自动 projected ROI。
2. `saveRoiConfirms()` 第 617–628 行在 `projectedPixelRects[roi.id]` 缺失时仍按模板 `normalizedRect` 重新映射。该回退必须删除；成功 ROI 保存若缺少 projected 像素坐标，应 fail-closed，不能写入模板坐标的 DB/CSV 结果包。
3. `1114` 与本地 XML/HTML 的 `1109` 统计口径不一致；下一次 handback 必须以实际 XML/HTML 汇总为准并解释差异。

因此上一轮 handback 结论为部分修正通过；其后拍照后手调入口、模板坐标回退和测试夹具问题均已修正，以下最新复核结果覆盖此前阻塞状态。

## 2026-09-22 最终修正 handback 复核

### 源码收口

- `CaptureComparisonViewModel` 已删除 `moveRoi()` 和 `resizeRoiByDelta()`；`canProceed`、`isFullImageFallback` 均基于 `projectedRoisSnapshot`。
- `CaptureComparisonScreen` 的 `SessionRoiOverlay` 已变为只读显示，移除 `pointerInput`、四角 handle 和 `onMove`/`onResize`；模板 ROI 编辑阶段仍保留手动框选、拖动和缩放。
- `ViewConfirmationViewModel.saveRoiConfirms()` 已移除模板坐标映射回退；`projectedPixelRects` 缺失时只允许 fail-closed 到整图 bounds 或抛出异常。
- 新增 `ProjectedRoiBoundaryTest=6`，覆盖拍照后无手调入口、projected 坐标唯一来源和 CSV/确认实体坐标一致性。

### 实际验证

- 本地 XML：`1115 tests / 0 failures / 0 errors / 5 skipped`。
- 本地 HTML 汇总同样为 `1115 tests`、`0 failures`、`5 skipped`。
- `ViewModelSaveLifecycleTest=4/4`，`ProjectedRoiBoundaryTest=6/6`；此前两个保存生命周期失败已通过测试夹具补充 `photoGeometry` 和 `projectedPixelRects` 注入后消除。该注入是针对 Robolectric 无法从真实文件路径解码 Bitmap 的测试环境适配，不改变生产逻辑。
- 5 个 skipped 均为 `DpmScannerTest` 外部 DPM 样本或目录缺失：`frame960Decodes`、`capturedF26Decodes`、`batchDecodeCache20260820`、`userSpecifiedFrameDecodes`、`probeRootDumpCandidatesF23ToF37`。
- 最新 handback 报告 `compileDebugKotlin` 成功；当前 APK 现场为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 10:25:42 +08:00，232208437 bytes，SHA-256 `C99F87FD8F26139DB2F97EFB6443D9D7461A82374C1D0381F7B408F0B24EF5A9`。最新改动仅涉及测试夹具，未改变生产 APK 内容。
- 未运行 ADB、instrumented 或真机测试；当前 V4 代码已选择性提交为 `a690fa15`。

### 最终软件结论

- 模板对齐成功：NanoDet、裁剪图、检测框、证据图和结果包坐标均使用 registration 自动 projected ROI。
- 模板对齐失败、状态不一致、registry 缺失或 projected ROI 不完整：进入整图 NanoDet；确认页显示整图检测摘要，不显示逐 ROI 卡片；`aggregatedSuggestion` 保持 null，由人工确认总体 OK/NG；结果包使用唯一 `__FULL_IMAGE__` synthetic ROI。
- 手动 ROI 操作只存在于模板 ROI 编辑阶段，不参与拍照后比对、NanoDet 或结果包。

当前任务状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**。V4 代码已选择性提交为 `a690fa15`；无新的 Agent 代码指令，下一步只等待最终用户验收。

## 2026-09-22 用户新增要求：暂时隐藏拍照后 CaptureComparison 界面

### 主协调源码复核

- 当前源码已经移除拍照后 Session ROI 的拖动、缩放和四角调整控件；`SessionRoiOverlay` 为只读显示，工具栏提示为“双指缩放/平移”，注册摘要为“已自动投影 Session ROI”。模板 ROI 编辑阶段的手动框选仍保留。
- `AppNavigation.kt` 的拍照成功回调仍导航到 `Screen.CaptureComparison`，因此用户仍会看到该拍照后比对页面。该页面同时承担 registration 结果交接、projected ROI snapshot 写入 `SessionRoiRegistry` 和进入 `ViewConfirmation` 的中转职责。
- 当前源码不再包含“可调整 Session ROI”文案；若设备仍显示该旧文案，应先核对安装 APK 是否来自旧提交 `c81391b1` 或旧构建产物，不能据此认定当前源码仍有拍照后 ROI 调整能力。

### 本次修正边界

- 暂时隐藏拍照后的 `CaptureComparison` 可见 UI，但保留 `CaptureComparisonScreen`、`CaptureComparisonViewModel`、`Screen.CaptureComparison`、registration、projected ROI 和 registry 代码，便于后续恢复入口。
- 隐藏路径必须继续等待并读取 registration 结果；成功时写入 `projectedRoisSnapshot`，失败、状态不一致、registry 缺失或投影不完整时继续写入真实 fallback 状态并进入整图 NanoDet 路径，最后进入 `ViewConfirmation`。
- 不得通过直接跳转 `ViewConfirmation` 绕过 registration 或 registry 交接；不得修改 NanoDet 模型、decoder、阈值、类别协议、结果包语义、模板 ROI 编辑、CameraX、DPM 或 OCR。
- 需要新增/更新回归覆盖：隐藏入口不会渲染拍照后比对 UI；成功投影仍使用 projected snapshot；失败路径仍进入整图兜底。未获用户对本次新修正明确授权前，主协调不提交 Git。

### 当前状态

当前状态：**SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION**。主协调未修改生产代码、未联系执行 Agent、未运行 ADB、instrumented 或真机测试；等待执行 Agent 按上述边界完成修正并提交 handback 后复核。

## 2026-09-22 用户新增问题：现场采集页模板图片加载失败

### 主协调源码定位

- `LiveInspectionScreen.kt` 将当前模板的 `template.mainImagePath` 传给 `CameraPreview`，现场采集页的红色提示由 `CameraPreview.kt` 生成。
- `CameraPreview.kt` 在 `LaunchedEffect(templateImagePath)` 中使用 `java.io.File`、`BitmapFactory.decodeFile` 和降采样解码；文件不存在时显示“模板图片不存在”，解码返回空时显示“模板图片解码失败”，捕获到 `Exception` 时才显示“模板图片加载失败”。
- 当前 catch 只记录 DEBUG 日志并把所有异常压缩成同一条文案，主协调仅能确认发生了文件访问或解码异常，不能凭现有界面确定现场设备上的具体异常类型。
- 该 catch 还存在明确的协程语义风险：`CancellationException` 属于 `Exception`，模板切换、页面离开或 `LaunchedEffect` key 变化时可能被误报为“模板图片加载失败”；修正必须先单独重新抛出取消异常。
- 模板导入链当前通常把图片复制到 App 私有 `template_images/` 目录并将绝对路径写入数据库；历史数据仍可能包含 `content://` 或 `file://` 形式，因此单纯继续使用 `File(path)` 不是完整的路径兼容策略。

### 执行 Agent 修正边界

- 统一模板图片读取/解码入口，兼容 App 私有绝对路径、`file://` 和 `content://`；先读取 bounds，再以受控 `inSampleSize` 解码，所有流正确关闭。
- `catch (CancellationException) { throw ... }` 必须位于通用异常处理之前；不得把协程取消、页面离开或模板切换显示成红色加载错误。
- 区分路径为空、文件不存在、不可读、图片格式不可解码和运行时异常；DEBUG 日志必须包含模板 ID/路径类型/阶段和原始异常，UI 继续使用安全、明确的用户文案，不显示堆栈。
- `CameraPreview` 的实时模板叠加和现场页下方模板参考图应使用同一套读取语义，避免一个区域能显示而另一个区域报错。
- 保持现有模板图片存储路径和数据库字段兼容；不得删除或重写已有模板数据，不改变 CameraX 拍照、V4 registration、projected ROI、NanoDet、DPM 或 OCR。
- 增加 JVM 回归覆盖有效绝对路径、缺失路径、无效图片、`file://`/`content://` 读取和异常分类；仅运行 JVM 单元测试、`compileDebugKotlin`、`assembleDebug`，不运行 ADB、instrumented 或真机测试。

当前该问题与“隐藏拍照后 CaptureComparison UI”共同处于 **SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION**；主协调未修改生产代码，等待执行 Agent handback 后统一复核。

## 2026-09-22 模板图片加载 handback 审计

### 实际证据

- 工作区新增 `TemplateImageLoader.kt` 和 `TemplateImageLoaderTest.kt`，修改 `CameraPreview.kt`、`LiveInspectionScreen.kt`；未提交 Git。
- 本地 XML/HTML 实际汇总为 `1136 tests / 1 failure / 0 errors / 5 skipped`，不是可接受的全量回归结果。唯一失败为 `NoRoiViewAdvancementTest.live inspection source prevents view completion on capture failure`，断言“应有后续 try 块”。
- 该失败不是预存问题：此前 V4 基线本地汇总为 0 failure；本次 `TemplateContent` 移除内层 `try/catch` 后，旧测试通过全文寻找后续 `try {` 的脆弱实现失效。执行 Agent 必须更新该测试为与图片加载实现无关的结构/行为断言，或保留等价的生产结构后重新验证，但不得把它标为 pre-existing。
- APK 现场为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 11:43:05 +08:00，232224821 bytes，SHA-256 `3F2E069C2D518D84251EECB8A687D7C81D73F374A62A5D4D75C73470AD6AB69B`。该 APK 不能作为本轮最终交付证据，因为全量测试仍失败。

### 代码审计问题

- `TemplateImageLoader.decodeFromStream()` 使用 `inputStream.readBytes()`，然后对完整 ByteArray 做 bounds 和实际解码；这会把整张模板图一次性放入堆内存，不能兑现“受控降采样避免 OOM”的目标。应对同一来源重新打开流，分别用 `BitmapFactory.decodeStream()` 读取 bounds 和按 `inSampleSize` 实际解码。
- handback 所列“有效 file URI”测试实际调用的是绝对路径；content URI 只覆盖缺少 ContentResolver 的失败分支，没有真实可读 Provider 的成功路径。需要补齐有效 `file://` 和可控 `content://` Resolver/Provider 测试。

### 当前结论

模板图片加载修正暂不通过，状态保持 **SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION**。主协调不提交当前代码；等待执行 Agent 修正测试回归、内存解码和 URI 成功证据后重新 handback。未运行 ADB、instrumented 或真机测试。

## 2026-09-22 模板图片加载 handback v2 审计

### 已核对的实际证据

- `TemplateImageLoader.kt` 已改为两次重新打开输入流，分别使用 `BitmapFactory.decodeStream()` 读取 bounds 和按 `inSampleSize` 解码；未发现 `readBytes()`。
- `TemplateImageLoaderTest` XML：`23 tests / 0 failures / 0 errors / 0 skipped`。
- `NoRoiViewAdvancementTest` XML：`18 tests / 0 failures / 0 errors / 0 skipped`。
- 全量 `testDebugUnitTest` XML 汇总：`1138 tests / 0 failures / 0 errors / 5 skipped`；5 个 skipped 仍来自 `DpmScannerTest` 外部样本或目录缺失。
- APK 现场：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 12:05:52 +08:00，232963737 bytes，SHA-256 `9DEFC9F28569A7A46CF5029E12B5019411976DF1465E4F2E922D7A54B011A0D4`。
- `compileDebugKotlin`、`assembleDebug` 的 handback 结果为成功；本轮未运行 ADB、instrumented 或真机测试。

### 仍未通过的审计项

1. `NoRoiViewAdvancementTest` 用 `source.indexOf("}", elseBranch + 4)` 得到的是 `storeResult == null` 分支内 `withContext` lambda 的首个闭合括号，不是整个 `else` 块的匹配括号；它仍可能漏过 else 块后半段的违规调用。
2. 该测试只断言 `completeView` 不在截取片段中，未稳定验证失败分支必须删除临时文件、设置 `captureError = "图片保存失败"` 和 `captureState = CaptureUiState.ERROR`。
3. `content://` 成功测试仍使用 Mockito 返回的单个 `ByteArrayInputStream`；由于该类关闭操作是 no-op，不能证明 ContentResolver 在 bounds 与实际解码阶段均能提供新的可读流，也不是实际 Provider 成功路径。
4. `AppNavigation.kt` 拍照成功回调仍导航到 `Screen.CaptureComparison`；“暂时隐藏拍照后的 CaptureComparison UI、保留 registration/registry/projected snapshot 并进入 ViewConfirmation”的独立要求仍未收口。

### 当前结论

模板图片加载 v2 的构建和现有 XML 统计通过，但源码审计仍不通过；整体任务继续保持 **SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION**。当前代码不提交 Git。下一轮必须先修正失败分支测试的真实边界和可控 content URI 两次开流证据，并同时处理拍照后 CaptureComparison 隐藏路径；不得修改 V4 registration、NanoDet、CameraX、DPM 或 OCR。

## 2026-09-22 模板图片加载 + CaptureComparison 自动导航 handback v3 审计

### 已核对的实际证据

- `TemplateImageLoader.kt` 使用两次独立 `openStream().use {}`，分别读取 bounds 和执行 `BitmapFactory.decodeStream()`；未发现 `readBytes()`。
- `TemplateImageLoaderTest` 实际为 `23 tests / 0 failures / 0 errors / 0 skipped`，`content://` 成功测试使用新流和 `AtomicInteger` 验证两次打开。
- `NoRoiViewAdvancementTest` 实际为 `18 tests / 0 failures / 0 errors / 0 skipped`，已改用完整花括号块提取。
- `CaptureComparisonAutoNavigationTest` 实际为 `16 tests / 0 failures / 0 errors / 0 skipped`，不是 handback 声称的 15 项。
- 全量 `testDebugUnitTest` 实际汇总：`1154 tests / 0 failures / 0 errors / 5 skipped`；5 个 skipped 仍来自 `DpmScannerTest` 外部样本或目录缺失。
- `AppNavigation.kt` 当前在 `comparisonViewModel.isLoaded` 后写入 `SessionRoiRegistry`，传递 `isFullImageFallback`，并导航到 `ViewConfirmation`；拍照后不再渲染 `CaptureComparisonScreen`，仅显示加载指示器。
- APK 现场：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 12:24:53 +08:00，232963737 bytes，SHA-256 `C7FE3B140B381F2CF740A4B63DB0C08BBE49B46967CFB4C4D00C0F22E29693CC`。
- handback 声称 `compileDebugKotlin`、`assembleDebug` 成功；本轮未运行 ADB、instrumented 或真机测试。

### 仍需修正的审计项

1. `NoRoiViewAdvancementTest` 的完整失败分支块当前只断言包含“图片保存失败”和 `CaptureUiState.ERROR`，以及不包含 `completeView`；没有断言该分支执行 `imageStore.delete(file.absolutePath)`，未完整覆盖本轮明确要求。
2. handback 对 `CaptureComparisonAutoNavigationTest` 的数量报告为 15，但本地 XML 实际为 16；下一次 handback 必须按 XML/HTML 实际统计报告并解释差异。
3. `CaptureComparisonAutoNavigationTest` 主要是源码结构断言，未通过真实 NavController/Compose 行为验证 `LaunchedEffect` 完成后自动导航和 `popUpTo` 栈清理；这属于证据强度限制，至少需要补充明确的路由顺序/栈清理断言或在 handback 中说明可接受边界。

### 当前结论

CaptureComparison 隐藏自动导航和模板图片加载实现已基本符合产品边界，现有测试与构建均通过；但由于失败分支删除临时文件断言缺失、目标测试统计不一致，源码审计暂不完全通过。整体任务继续保持 **SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION**，当前不提交 Git。

## 2026-09-22 最终证据修正审计

### 实际核验结果

- 全量 `testDebugUnitTest` XML/HTML：`1158 tests / 0 failures / 0 errors / 5 skipped`。
- `TemplateImageLoaderTest`：`23 / 0 / 0 / 0`。
- `NoRoiViewAdvancementTest`：`18 / 0 / 0 / 0`，失败分支已断言临时文件删除、错误消息、`CaptureUiState.ERROR` 和不调用 `completeView`。
- `CaptureComparisonAutoNavigationTest`：`20 / 0 / 0 / 0`；handback 已解释此前 15/16 项统计遗漏，本地 XML 以 20 项为准。
- `AppNavigation.kt` 已通过 `LaunchedEffect(comparisonViewModel.isLoaded)` 等待 registration ViewModel 完成；写入 `SessionRoiRegistry` 后才导航 `ViewConfirmation`，并使用 `popUpTo(Screen.CaptureComparison.route) { inclusive = true }` 清理隐藏路由。
- 拍照后不再渲染 `CaptureComparisonScreen`，但 `CaptureComparisonScreen`、`CaptureComparisonViewModel`、`Screen.CaptureComparison`、registry 和 projected ROI 逻辑均保留。
- APK 现场：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 12:24:53 +08:00，232963737 bytes，SHA-256 `C7FE3B140B381F2CF740A4B63DB0C08BBE49B46967CFB4C4D00C0F22E29693CC`。
- `compileDebugKotlin`、`assembleDebug` handback 成功；未运行 ADB、instrumented 或真机测试。
- 当前 Git 状态为未提交工作区；未发现 V4 registration、NanoDet、CameraX、DPM 或 OCR 改动。

### 验证边界

`CaptureComparisonAutoNavigationTest` 为源码结构测试，能验证关键调用、参数和顺序，但不能替代真实 Compose `LaunchedEffect` 时序、NavController 执行、栈清理或 `SessionRoiRegistry` 运行时写入。由于本任务明确禁止 instrumented/真机测试，该边界已记录，不阻塞软件审计通过。

### 最终结论

模板图片加载修正和拍照后 CaptureComparison 自动导航已通过本地源码、JVM、Kotlin 编译和 APK 审计，状态更新为 **SOFTWARE_AUDIT_PASSED / AWAITING_USER_ACCEPTANCE**。当前不提交 Git；待用户明确授权后再按文件路径选择性提交。后续无新的 Agent 指令。

## 2026-09-22 用户确认的后续任务范围修正

- **legacy ROI 迁移关闭。** 用户明确不需要兼容历史旧格式的单个 `roi` 字段；当前 App 只维护新格式 `rois[]` 的导入导出闭环。新格式中的 ROI 名称、`normalizedRect`、属性、`enabled` 状态和顺序属于当前支持范围。
- **`imageFiles[]` 多图处理移出待办。** 当前产品模型确定为一个视角对应一张主模板图。`imageFiles[]` 仅作为模板包数组字段保留格式兼容；当前每个视角只写入一个图片元素并落到 `mainImagePath`，不开发多图切换、配准、ROI 或结果导出。
- **模板 EXIF 改为条件式方案。** 第一阶段只取证实际模板和采集图片的原始像素宽高、EXIF `Orientation`，并核对模板显示、ROI 和检测位置。若原始像素为竖向、`Orientation = 1` 且三者一致，则关闭 EXIF 风险，不创建代码任务；若原始像素为横向、`Orientation = 6/8`，或方向/坐标不一致，则必须新建并实施独立的全链路 EXIF upright 代码修正任务，而不只是记录风险或补测试。修正必须统一 EXIF-aware 解码、模板编辑、预览叠加、配准、ROI 映射和检测入口的方向语义，明确 normalized ROI 使用 upright 坐标，并补充方向与坐标回归测试。不得只修改单一加载器导致重复旋转或坐标语义分裂。
- 上述范围修正只更新当前有效任务边界；报告此前按时间顺序保留的 handback 章节仍为历史追溯，不代表当前待办。当前没有新的 Agent 指令，本轮未修改生产代码、未运行构建或设备测试。

## 2026-09-22 用户验收完成与 Git 收口授权

- 用户已确认完成真实设备验收，确认范围包括：Compose `LaunchedEffect` 实际自动导航、`NavController.popUpTo` 栈清理、`SessionRoiRegistry` 运行时写入、失败/fallback 整图检测现场效果，以及模板图片真实设备加载表现。
- 当前任务状态更新为 **SOFTWARE_AUDIT_PASSED / USER_ACCEPTED**。这条用户验收记录不改变此前“未运行 ADB/instrumented/真机”的主协调审计事实；它记录的是用户对现场验收结果的明确确认。
- 用户已明确授权当前 7 个已修改文件和 3 个未跟踪文件按路径选择性提交 Git，已完成提交 `aec66356`；5 个 DPM skipped 仍因外部样本/目录缺失保留，不作为本次用户验收阻塞项。
- 模板加载日志缺少 `templateId`、整图模式缺少检测框叠加仍属于非阻塞后续增强；本次不启动新的代码任务。
