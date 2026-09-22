# V4 RegistrationResult → NanoDet 检测集成和结果包：主协调审计

日期：2026-09-21
状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-22 最新 handback 复核后）
范围：只读审计 handback 及工作区差异；未运行 ADB、instrumented 或真机；当前 V4 代码已选择性提交。

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
