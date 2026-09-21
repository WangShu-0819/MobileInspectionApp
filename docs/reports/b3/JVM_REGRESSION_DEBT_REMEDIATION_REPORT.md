# 既有 14 项 JVM 失败整改报告

状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-21）

本报告对应 `tasks/todo.md` 顶部的独立整改任务，不属于已验收的 NanoDet exp09 四分类协议任务。执行 Agent 未提交 Git；本轮由主协调完成工作区审计、定向复跑、全量 JVM 复跑、编译复核和文档收口。

## 1. 基线与最终结果

- 历史基线：`959 tests completed / 14 failed / 5 skipped`。
- 基线来源：`tasks/todo.md` 顶部清单及 2026-09-20/21 NanoDet 回归记录。
- 当前全量命令：
  `./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`
- 当前 Gradle 结果：`BUILD SUCCESSFUL`，退出码 `0`。
- 当前 JUnit XML 汇总：`959 tests / 0 failures / 0 errors / 5 skipped`。
- XML 目录：`app/build/test-results/testDebugUnitTest/`（77 个 `TEST-*.xml`）。
- 全量 Gradle 输出：`docs/reports/b3/JVM_REGRESSION_FULL_TEST_20260921.log`。
- 基线失败 XML：当前工作区没有保留整改前的失败 XML；全量复跑已覆盖 `app/build/test-results/testDebugUnitTest/`。整改前的 14 项方法名和症状以本报告第 2 节及任务清单为准，不能伪称为已保存的失败 XML。

## 2. 14 项逐项审计与最小修复

| 失败测试 | 根因判断 | 最小修复/审计结论 | 当前 XML |
|---|---|---|---|
| `MultiViewPhotoPersistenceTest`：schema 路径 | 测试进程工作目录为 `app`，原路径多了一层 `app/`；当前数据库版本为 11 | 测试读取 `schemas/.../AppDatabase/11.json`；未改 Room schema | `TEST-com.wearable.inspection.mobile.data.MultiViewPhotoPersistenceTest.xml` |
| `MultiViewPhotoPersistenceTest`：完成卡片固定高度 | 生产代码已有 `.height(64.dp)` 与 `.widthIn(min = 0.dp)`，原源码截取窗口不足 | 截取窗口由 500 扩到 1200，两个断言均保留 | 同上 |
| `MultiViewPhotoPersistenceTest`：导航前复位 | 有 ROI 分支原先在导航前仍设为 `SAVED`，不满足复位契约 | 生产代码先设 `captureNavigationPending = true`，再设 `captureState = IDLE`，最后导航；现场栏由 pending 状态锁定 | 同上 |
| `TemplatePackageExporterTest` | `JSONObject` 对象字段顺序不是稳定格式契约，原断言比较字符串顺序 | 测试改为解析 JSON 后逐字段比较 4 个坐标值；没有放宽数值语义 | `TEST-com.wearable.inspection.mobile.template.TemplatePackageExporterTest.xml` |
| `TemplatePackageImporterTest`：损坏 ZIP | `ZipInputStream` 对无条目输入未产生预期 IOException，旧逻辑继续报“缺少 template.json” | 导入器统计条目数；零条目给出“不是有效的 ZIP 文件（无条目）”，有条目但缺 manifest 仍报“缺少 template.json” | `TEST-com.wearable.inspection.mobile.template.TemplatePackageImporterTest.xml` |
| `ViewConfirmationNavigationTest`：导出回调 | 回调已存在，原源码截取窗口 2000 字符未覆盖完整参数块 | 窗口扩到 4500；仍检查回调、`ExportResult.createRoute` 及 batch/part/name 参数 | `TEST-com.wearable.inspection.mobile.ui.navigation.ViewConfirmationNavigationTest.xml` |
| `ViewConfirmationNavigationTest`：onBack | `onBack` 已只调用 `popBackStack()`，原窗口 4000 字符不足 | 窗口扩到 5000；仍断言无推进/导出且存在 `popBackStack()` | 同上 |
| `BatchFilterAndDeleteTest` | 固定提示槽位的 `.height(20.dp)` 位于 `exportMessage?.let` 之前，原窗口只向后截取 | 测试向前扩 300 字符，继续检查固定高度与 `maxLines = 1` | `TEST-com.wearable.inspection.mobile.ui.screens.BatchFilterAndDeleteTest.xml` |
| `CameraPreviewTest` | 生产代码已有 `if (isScreenVisible)` 守卫；源码换行差异使字符串断言失败 | 测试同时接受 CRLF/LF，不改变可见状态和 session 错误语义 | `TEST-com.wearable.inspection.mobile.ui.screens.CameraPreviewTest.xml` |
| `NoRoiViewAdvancementTest`：拍照失败 | 当前实现使用 `Result.exceptionOrNull()` 分支，不是旧测试寻找的 `onFailure` lambda | 测试按当前 Result API 定位失败分支，继续断言失败分支不调用 `completeView` | `TEST-com.wearable.inspection.mobile.ui.screens.NoRoiViewAdvancementTest.xml` |
| `NoRoiViewAdvancementTest`：照片插入失败 | 生产代码的插入/关联校验已由 catch 转为 ERROR；原测试要求过窄的 catch 文本 | 测试定位插入后的 catch，并保留 ERROR 与“不推进”断言 | 同上 |
| `NoRoiViewAdvancementTest`：无 ROI 照片导出 | 生产导出服务使用 `photos.forEach`，原测试只接受 `for (photo in photos)` | 测试接受两种等价遍历写法，仍检查按 view 写入和统一 CSV 回链 | 同上 |
| `ViewConfirmationPerformanceTest` | 当前生产变量从 `dimensions` 更名为 `geometry`，IO 调度契约未变 | 测试按当前变量名定位，并继续检查 `withContext(Dispatchers.IO)` 顺序及批量回填 | `TEST-com.wearable.inspection.mobile.ui.screens.ViewConfirmationPerformanceTest.xml` |
| `WorkbenchViewModelAdvanceTest` | `StateFlow` 使用 `WhileSubscribed`；测试未订阅 `selectedPart`，直接读 `.value` 存在未启动收集的问题 | 测试增加 `selectedPart.collect {}`，不改生产状态流或切换逻辑 | `TEST-com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModelAdvanceTest.xml` |

除“有 ROI 导航前复位”和“零条目导入错误语义”外，其余整改均为测试夹具/源码契约与当前实现同步；没有删除测试、改成 skipped、扩大 skip 条件或隐藏断言。

## 3. 实际修改文件

生产代码：

- `app/src/main/java/com/wearable/inspection/mobile/template/TemplatePackageImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`

测试代码：

- `app/src/test/java/com/wearable/inspection/mobile/data/MultiViewPhotoPersistenceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplatePackageExporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/CameraPreviewTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/NoRoiViewAdvancementTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationPerformanceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModelAdvanceTest.kt`

任务文档与证据：

- `tasks/todo.md`
- `tasks/plan.md`
- `docs/reports/b3/JVM_REGRESSION_DEBT_REMEDIATION_REPORT.md`
- `docs/reports/b3/JVM_REGRESSION_FULL_TEST_20260921.log`

未纳入本任务的工作区文件：`docs/reports/b3/PHOTO_REGISTRATION_ENGINE_OPTIONS.md`；它保持原状，不进入本任务提交。初始审计时曾看到的 `app/TestPattern.java`/`.class` 在最终状态已不再出现在工作区；本轮未对其执行删除或提交操作。

## 4. 定向测试与编译

定向命令覆盖上述失败类，并额外覆盖同一状态契约的 `LiveInspectionCaptureStateTest`。

结果：`206 tests / 0 failures / 0 errors / 0 skipped`。

逐类 XML 均在 `app/build/test-results/testDebugUnitTest/`，包括：

- `MultiViewPhotoPersistenceTest`：16/16
- `TemplatePackageExporterTest`：3/3
- `TemplatePackageImporterTest`：22/22
- `ViewConfirmationNavigationTest`：18/18
- `BatchFilterAndDeleteTest`：61/61
- `CameraPreviewTest`：19/19
- `LiveInspectionCaptureStateTest`：33/33
- `NoRoiViewAdvancementTest`：18/18
- `ViewConfirmationPerformanceTest`：1/1
- `WorkbenchViewModelAdvanceTest`：15/15

编译命令：

`./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks`

结果：`BUILD SUCCESSFUL`。存在既有 Kotlin/Room deprecated/index warnings，无编译错误。

## 5. 5 项 skipped

全部位于 `com.wearable.inspection.mobile.dpm.DpmScannerTest`，当前 XML 为 `app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.dpm.DpmScannerTest.xml`：

1. `frame960Decodes`：`D:/dpm_dump_20260820/cache/input_960x1280_1787219112056.png` 不存在。
2. `capturedF26Decodes`：`../device_acceptance/dpm_preprocess_samples_20260824/f26_analysis/...` 回归帧不存在。
3. `batchDecodeCache20260820`：`D:/dpm_dump_20260820/cache` 不存在。
4. `userSpecifiedFrameDecodes`：用户指定帧文件不存在。
5. `probeRootDumpCandidatesF23ToF37`：`../dpm_dump` 目录不存在。

这些是测试内 `assumeTrue` 对外部样本缺失的明确跳过，不属于本任务失败；本任务未扩大 skip 范围。

## 6. 前序能力回归矩阵

| 能力 | 本轮审计/证据 | 结论 |
|---|---|---|
| 多 View 照片持久化与稳定 batch/view 关联 | `MultiViewPhotoPersistenceTest` 16/16、全量 XML | 通过 |
| 拍照失败/照片插入失败不推进 | `NoRoiViewAdvancementTest` 18/18、`LiveInspectionCaptureStateTest` 33/33 | 通过 |
| 确认页返回、完成和导出回调 | `ViewConfirmationNavigationTest` 18/18 | 通过 |
| 相机可见状态、contentRect 与连接错误门禁 | `CameraPreviewTest` 19/19；未运行设备测试 | JVM 契约通过，真机未验收 |
| CameraX 所有权/资源生命周期 | 本轮未修改 CameraX 所有者；未运行设备测试 | 保持范围，真机未验收 |
| 模板导入导出兼容 | Importer 22/22、Exporter 3/3 | 通过 |
| DPM/NanoDet/OCR/批次清理 | 未修改对应生产路径 | 未重开、未回归真机 |

## 7. APK、真机与未完成项

- APK：`NOT_RUN_BY_SCOPE`；本任务不要求构建 APK。
- ADB、`connectedDebugAndroidTest`、安装、截图和真机测试：未运行，符合当前任务授权边界。
- 真机相机生命周期、画幅、contentRect 和视觉布局仍需沿用前序证据，不由本轮 JVM 结果替代。
- 失败前 XML 未保存在当前工作区；如验收必须审阅整改前 XML，需要从执行 Agent 或历史构建归档补回，当前报告不伪造该证据。

## 8. Git 状态

本轮未提交 Git。收口前需仅按当前任务路径选择性提交，不得纳入未跟踪的 `TestPattern` 文件或 `PHOTO_REGISTRATION_ENGINE_OPTIONS.md`，也不得使用 `git add .`、reset、clean、stash 或回滚用户改动。
