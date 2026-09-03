# B2 模板 View 顺序与现场 Part 选择报告

日期：2026-09-03  
工程：`MobileInspectionApp`  
验收包名：`com.wearable.inspection.mobile`

## 本轮范围

本轮延续已有模板导入、透明叠加和真实拍照能力，补齐模板 View 顺序持久化以及现场采集的 Part/视角选择体验：

- `InspectionTemplateEntity` 增加从 0 开始的 `displayOrder`。
- Room schema 升级到 v2；旧数据按 `createdAt ASC, id ASC` 稳定回填顺序。
- ZIP/Directory：显式 manifest order 优先；缺失或非法 order 回退到 manifest index；重复 order 以原 manifest index 作稳定次排序。
- flat-directory：按稳定文件名排序后写入 index。
- `TemplateImportService` 和 `TemplateDao` 全链路保留有序 View；重复导入顺序稳定。
- 现场采集增加真实 Part 下拉选择；选中 Part 后自动加载该 Part 的全部启用 View。
- View 选择器只用于查看或切换当前 View，不把多个 View 当作多个模板集分别选择。
- 零件栏改为单行 40dp 紧凑布局，恢复实时相机和模板主体区域的空间分配。
- 模板详情补充 `View 序号 / 总数` 和“参考图片”语义。
- 现场采集顶部 DPM 入口保持不变；已绑定码命中后只切换已有 Part 及其有序模板，不进入未知码建件流程。
- 模板配置按 Part 显示 DPM 绑定状态，并提供“扫码绑定/更换绑定”下一层入口；已绑定到其他 Part 的码拒绝覆盖。
- 零件和模板视角增加左滑删除及确认弹窗；采集完成文案统一为“零件采集完成”。
- ROI 配置、现场 ROI 编辑、ROI Detector、PASS/FAIL、检测记录和结果导出本轮不实现，继续暂缓。

## 2026-09-02 DPM 业务边界补充

本次 DPM 扩展沿用现有 `DpmScanScreen`、`DpmScanViewModel` 和唯一 `CameraController`，没有新增解码算法或第二套 CameraX。

- 现场采集顶部“扫一扫”入口和实时扫码页面保持不变。
- 扫码结果按 `PartEntity.dpmCode` 精确查询；命中后通过跨页面选择事件切换已有 Part，现场采集页重新加载该 Part 的有序模板并回到 View 1/N。
- 未绑定码只提示先在模板配置绑定，不在现场扫码流程中新建或录入零件。
- 模板配置的每个零件分组显示当前 DPM 绑定状态，扫码绑定页保存前检查冲突，冲突时不覆盖已有绑定。
- DPM DAO/Repository 查询和更新已接入；完整 JVM、DPM DAO instrumented 测试、主 APK 构建以及模板导入/按序拍摄真机流程已通过。

## 实际修改文件

主要源码文件：

- `app/src/main/java/com/wearable/inspection/mobile/data/entity/InspectionTemplateEntity.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/db/AppDatabase.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/db/Migrations.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/TemplateDao.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplatePackageImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplateImportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TemplateDetailScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TemplateConfigScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ProfileScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/PartDao.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/settings/PartSelectionBus.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/Screen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/PartManagementScreen.kt`

测试与 schema：

- `app/src/test/java/com/wearable/inspection/mobile/template/TemplatePackageImporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporterTest.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/template/TemplateViewOrderTest.kt`
- `app/src/androidTest/java/com/wearable/inspection/mobile/data/db/AppDatabaseTest.kt`
- `app/schemas/com.wearable.inspection.mobile.data.db.AppDatabase/2.json`
- `app/build.gradle.kts`

## 测试结果

本轮 JVM 和构建：

```text
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
BUILD SUCCESSFUL
318 tests: 313 passed, 5 skipped, 0 failed
```

本轮 DPM 绑定 DAO instrumented 测试：

```text
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.data.dao.PartDpmDaoTest" --no-daemon
BUILD SUCCESSFUL
PartDpmDaoTest: 3/3 passed
```

`DpmOfflineDecodeTest` 已加载工程内 `DPM_data/` 的 6 张真实 JPEG；3 个测试均无异常，但 ZXing 全图和中心 50% ROI 两个基线阶段均为 `0/6`，因此本轮没有虚构 DPM 命中结果。ML Kit/GRID 兜底需要 instrumented/实时相机环境。

## 2026-09-03 真实数据真机流程

- 从模板配置打开 SAF 图片选择器，在 `Download/inspection-flow-test` 中选择 `sample_data/1/` 的 8 张 JPEG。
- 导入成功提示：`导入成功：flowtest_20260903，8 个视角`；模板配置真实统计更新为 `1 个零件 · 8 个视角 · 0 个 ROI`。
- 返回现场采集并选择 `FlowTest_20260903`，页面加载 `视角 1/8` 和对应模板参考图。
- 连续执行 8 次主拍照按钮；界面依次推进到 `视角 8/8`，最后显示 `零件采集完成 / 所有视角已拍摄完毕`。
- `run-as com.wearable.inspection.mobile` 检查到 `files/captures/` 下新增 8 张 JPEG，文件大小约 7.8–8.7 MB。
- 进入“我的 → 检测结果”后显示 `暂无检测结果`；页面没有导出按钮。原因是 InspectionSession 完整结果写入、PASS/FAIL 和结果导出仍按产品边界暂缓，不是本轮运行报错。
- 现场采集顶部“扫一扫”成功进入实时 DPM 页面，未使用相册码图导入；本轮实时相机曾识别 `M968942280224B169AH005023044710`，并在模板配置的“扫码绑定”流程中保存成功，返回后该 Part 显示“DPM 绑定”和最新码。
- 随后再次从现场采集进入顶部扫码页时，等待窗口内未重新捕获到码；由于当前视野内没有码，不把这次未命中记为软件异常，也不虚构“已绑定码切换 Part”回调已完成。`DPM_data/` 离线基线仍为 ZXing 0/6，不能替代实时相机/物理样本 A/B 验收。

真机流程证据：

- `docs/reports/b2/evidence/ui-polish-20260902/flow-live-ready.png`
- `docs/reports/b2/evidence/ui-polish-20260902/flow-capture-complete.png`
- `docs/reports/b2/evidence/ui-polish-20260902/flow-result-page.png`
- `docs/reports/b2/evidence/ui-polish-20260902/flow-dpm-route.png`
- `docs/reports/b2/evidence/ui-polish-20260902/flow-dpm-bind.png`
- `docs/reports/b2/evidence/ui-polish-20260902/flow-live-final.png`

模板顺序相关真机 instrumented 测试此前已单独通过：

```text
TemplateViewOrderTest: 3/3 passed
AppDatabaseTest: 3/3 passed
```

覆盖显式 order、缺失 order fallback、flat-directory 稳定文件名排序、重复 order、DAO 二级排序、v1→v2 回填以及重复导入顺序稳定。

完整 `connectedDebugAndroidTest` 本轮未计为通过：设备 runner 在历史 `CameraControllerLifecycleInstrumentedTest.modeRoundTrip20Times` 处长时间无结果后中止。随后单独运行的 `PartDpmDaoTest` 3/3 通过。该中止不是失败通过，后续仍需单独处理测试 runner/设备阻塞后再做完整累积回归。

## 真机包名门禁

设备：HONOR YAL-AL10，serial `ERLDU20429005890`。

安装和启动使用完整组件：

```text
adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection
adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection.mobile
adb -s ERLDU20429005890 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s ERLDU20429005890 shell am start -W -n com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity
```

结果：

- 新包已安装：`com.wearable.inspection.mobile`
- 新包 PID：非空，本轮复核为 `30530`
- 旧包 PID：空
- 前台 Activity：`com.wearable.inspection.mobile/.MainActivity`
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK 大小：`221,443,990` bytes
- APK SHA-256：`BB263970BF0E133A54FC96BDBA890A5637C66E4C34340E335A26D3810511965F`

本次主 APK 恢复安装及真机流程结果：

- `install -r` 返回 `Success`。
- `mResumedActivity` 为新包 `com.wearable.inspection.mobile/.MainActivity`；模板导入、8 次拍摄、结果页和 DPM 实时页面均在该新包中完成。
- `pidof com.wearable.inspection.mobile` 非空，旧包 `pidof com.wearable.inspection` 为空。

此前已取得的结构化 UI 复核结果：

- 现场采集显示单行 `零件 · hh`，控件 bounds 高度约 128px。
- 下拉菜单可见 `hh`、`dh`、`示例零件A`、`示例零件B`。
- 切换到 `dh` 后显示 `视角 1 / 2`，模板参考图和拍照入口仍在页面中。
- 实时预览 bounds 约 `[0,446][1080,1043]`，没有因零件栏使用两行大字号而被进一步压缩。

结构化证据只证明包名、控件层级、bounds 和运行状态；颜色、裁切、整体美观度仍需用户在真机上进行视觉复核。

## 前序能力回归矩阵

| 能力 | 本轮结果 |
|---|---|
| 新包导航和现场采集入口 | 已通过：前台为新包 MainActivity，现场采集页可见 |
| 相机权限/相机状态 | 已保留既有实现；本轮 JVM/build 通过，完整 instrumented 累积回归因历史 camera round-trip 阻塞未完成 |
| 4:3 画幅、PreviewView、contentRect | 未改动既有核心算法；`CameraPreview` 继续按设置支持原比例/填充预览 |
| 模板 overlay | 未改动既有模板透明叠加路径；当前页面仍有真实参考图 |
| DPM 已绑定码切换 | 源码已接入 Part 查询、选择事件和有序模板重载；本轮验证实时扫码页入口，重新捕获码因当前视野无码未完成切换回调 |
| 模板配置 DPM 绑定 | 已在实时相机中识别 `M968942280224B169AH005023044710`，绑定保存成功并返回后显示最新码；冲突保护由 DAO/自动化测试覆盖 |
| 视角顺序 | 已通过 importer、migration、DAO 和重复导入测试 |
| 资源释放/错误态 | 未改动既有 CameraController 生命周期与拍照错误态；待完整设备回归重新执行 |
| 旧包门禁 | 已通过：旧包 PID 为空，截图/UI 证据均以新包为对象 |

## 未完成项

- `ResultManagementScreen` 仍是空状态页面，没有用户可点击的结果导出按钮；`ResultPackager` 单测不能替代端到端导出。
- 本轮已完成“导入模板 → 按序拍摄 → 检测结果页”真机链路；结果导出入口尚未实现，不能记为导出通过。
- 拍摄完成后的模板/实拍比较、Template ROI 映射、Detector、PASS/FAIL、InspectionSession 结果写入和结果包导出待后续阶段。
- 现场不提供 ROI 框选、拖动、缩放或 Session ROI 编辑器。
- DPM 物理样本 A/B 对照仍按 B2 Task 1 的 `PENDING_PHYSICAL_DPM_SAMPLE` 处理；本轮只记录实际实时相机识别和绑定保存，不替代完整 A/B 样本验收。
- 当前 DPM 代码修改后的完整 JVM、APK 构建和新包真机门禁已通过；结果导出仍待后续边界解除，完整 DPM A/B 样本验收仍待现场样本。
- 完整 connected instrumented 回归需要先解决设备 runner 在 camera round-trip 测试中的长时间阻塞。
