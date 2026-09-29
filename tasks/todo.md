# 当前软件交付状态（2026-09-28；本节为唯一有效的任务状态）

软件目标是完成并交付可供现场工人使用的数据采集与人工确认功能。以下按日期记录的 handback 是历史证据；与本节冲突时，以本节和用户最新指示为准。

## T1–T6 状态

- **T1 零件颜色、ID、模板关联和模型路由：已完成。**先前 handback 报告实现及 JVM/设备 UI 验证通过；除非当前证据显示问题，不重复实现。
- **T2 exp22/exp23 模型资产、来源与 Android parity：已完成（2026-09-29）。**来源/导出审计 `PASS_WITH_RECORDED_LIMITATION`。官方 PNNX 20260526 Windows ZIP SHA-256 为 `4e188e7606c887ac550820322f34b144140df877b73292b25e48a2ba38f297df`；ZIP 内 exe 与现有 PyPI wheel exe 字节一致，SHA-256 均为 `16165dc5fcc53d31f0339b996c15fd2dc26b8d7db43753f72eeab0a0e576116e`。CI 日志没有逐文件记录二进制 SHA-256，按 `PASS_WITH_RECORDED_LIMITATION` 处理。exp22/exp23 `assetsVerified` 已设为 `true`；生产 `NanoDetRoiInferenceService` 正向路由已在 SEA-AL10 验证：Black_ 四类（THREAD→0, NUTSERT→1, NUT→2, BOLT→3）、White_ 两类（NUT→0, THREAD→1）、White_ BOLT/NUTSERT 返回 `MODEL_TARGET_UNSUPPORTED` 且不回退 exp09、无前缀 legacy 继续走 exp09。设备：SEA-AL10 / Android 10 / API 29 / arm64-v8a。Gradle `connectedDebugAndroidTest` XML：6 tests / 0 failures / 0 errors / 0 skipped。证据路径：`app/build/t2_asset_enable_route_20260929T010000/TEST-NanoDetRoiRuntimeInstrumentedTest.xml`、`app/build/t2_asset_enable_route_20260929T010000/gradle-connected-output.txt`。运行历史：早先合并筛选有 5 个 ClassNotFoundException；六项随后分别运行通过；后续 Gradle connectedAndroidTest 生成了上述 XML。
- **T3 离线 ROI 配准与相似度实验：离线实验已完成。**历史 48 个清洁几何案例有 46 个达到当时实验候选值，276 个合成遮挡评分案例中 0 个达到当时候选值。这些结果不构成 Android 生产验证，也不能校准真实现场风险。
- **T4 Android ROI 相似度兜底：软件实现与回归测试已完成（按 2026-09-28 handback）。**四类小件统一使用灰度 SSIM 阈值 `0.75`；Lowe 配准比率 `0.75` 是独立参数。handback 报告 103 个 XML、1,505 项 JVM 测试、0 失败、0 错误、5 跳过，构建退出码 0。当前 APK SHA-256：`AC85E49F793FBF8B294E5946D76711AE2FA0F6089584069D60E4ADFD0CD8A66D`。本轮未重跑测试或构建。
- **T5 真实现场数据与人工标签：软件交付后的现场工作。**交付功能后由现场工人采集模板/ROI、真实样本和人工标签；这不是当前 Codex/Mimo 的软件实现任务。
- **T6 校准集与独立留出集：软件交付后的现场工作。**现场数据形成后，由现场/质量工作流按实物、批次或会话拆分并评估；这不是当前软件收尾的阻塞项。

## 当前待办与协作边界

1. **T2 启用与验证（已完成）：**`assetsVerified` 已设为 `true`；Black_ 四类、White_ 两类、White_ BOLT/NUTSERT `MODEL_TARGET_UNSUPPORTED`、legacy exp09 路径均在 SEA-AL10 验证通过。Gradle connectedDebugAndroidTest XML 6/0/0/0。详见上方 T2 状态及证据路径。
2. **Git 路径审阅与选择性提交评估：**最近复核快照为 `main...origin/main [ahead 2]`、31 个已修改跟踪文件、100 个未跟踪文件、0 staged；新轮必须重新核对。工作区混有多项任务和受保护文件，不得整体暂存或提交。
3. 软件交付后再启动 T5/T6 现场采集与校准工作。

- 项目代码修改、测试、构建、APK 和设备操作由外部执行者 Mimo 完成；用户手动转交指令并回传 handback。协调 Codex 负责拆解任务、只读审查当前工作区和 handback，并在用户明确要求时更新指定文档。**不联系 Mimo，不调用子 agent、delegation 或 collaboration 工具，不自行改生产代码或运行项目测试、Gradle、ADB、设备操作或 OCR。**
- 不把旧 XML/APK 当成本轮结果；每轮区分 handback 声明与本轮实际复核，并在结束前重新检查完整 Git 状态。`tasks/plan.md`、`tasks/todo.md`、`docs/reports/`、`commonMain/`、`tools/roi_similarity/` 和既有 `.npz` 默认受保护；只有用户明确指定并要求更新文档时才能编辑。
- 用户问“可以提交了吗”时只审计并给结论，不视为提交授权。只有用户明确要求提交且文件范围明确时才选择性暂存和提交；绝不 push。禁止 `git add .`、`git reset`、`git clean`、`git stash`。

---

## 历史记录：黑白件模型路由与相似度工作（以下状态按当时记录理解）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_PRODUCTION_JNI_PARITY_PASS / T2_SERVICE_ROUTE_FAIL_CLOSED_AND_EXP09_PATH_PASS / T2_ASSET_PROVENANCE_INCOMPLETE / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。最新 SEA-AL10 service-route XML 为 5 / 0 / 0 / 0：White_/Black_ 实际 service 入口均 fail-closed 且不附 exp09 元数据；无前缀 exp09 经 service、生产 JNI 对两张真图与桌面参考一致。exp22/exp23 service 本轮没有进入 JNI 推理；它们此前的生产 JNI 固定图 parity 结果未重跑。来源 checkpoint/config 到精确 NCNN 导出的可复核绑定仍不完整，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 历史快照：T2 exporter 尚未安全运行（已被后续成功重跑 handback 取代）

- **Exporter 未运行：**exp22/exp23 脚本把 `OUTPUT_DIR` 固定到已有 `android_export` 目录，没有 CLI 或环境变量覆盖选项；目录内已有 ONNX 与 NCNN 输出。按避免覆盖既有产物的要求停止，因此没有新输出或 exporter 运行日志。PNNX 转换未运行、版本未知；清单虽记录 PNNX 可执行文件哈希，当前粘贴内容未提供该哈希值。
- **已有输入哈希：**exp22 config `55f81b9f91e62f50b18eca52ed0dbfac474d65196745ca698ab0805c03f275fb`、checkpoint `22aea13b043d5f28ecbe9c82a1e0d1ef52266391918eef07059f6fb132faeb79`、脚本 `2a8911b27e92550178e6669845ddc845097898720f2ee7845996c8e6a667f1be`；exp23 config `9aa9d1517836cb2d5c98d58b23d7a9cff3087d96ed6744f37d8e589c373fba2f`、checkpoint `fc18f39cc8736f0bca223dcc3c03e90440adbca7049129e3231a6601d6f9274e`、脚本 `796acb9f0a4270c4ee34cd560fbcde9a1c1678756f87a7f228a27908c822f6c3`。Python 3.11.13、torch 2.2.2+cu118、onnx 1.17.0、onnxsim 0.7.3；预期 PNNX 参数为 `inputshape=[1,3,416,416]f32 fp16=0 optlevel=2`，该命令本轮未执行。
- **已有产物比较：**Mimo handback 报告已有 ONNX/NCNN 文件与 app assets、debug APK 对应 param/bin 哈希一致。本地独立重算了 exp22/exp23 checkpoint、config、exporter、ONNX、NCNN param/bin 及 app assets；本地可访问的输入/输出哈希相符。exp22 ONNX `eb3ed8b83b82ab04af65998aca03604fe1ea51c9c51ca864a256d633822629ed`、param `b81b824fea9ff949f72f3715a8f20c6ebe96c2792370dd80b7a7a69a65335960`、bin `d3a16edb715050b376f40b5f396fc748b984d781590aa1bf07b3f42a3d92a3d7`；exp23 ONNX `0322e2d1dd54202accafbf29f9a6a0227a5a126e1cd40b60b0ce8c8b553f235a`、param `ad45e2f3fcb6777a5e924aa9a3095c23b2c5f900632720c389d7816a1a390bdd`、bin `1b662094ce94f4a3a8c899f40e1cb449e01aa10ae7a1addabcb92bfef18da35d`。本地 `app-debug.apk` 不存在，故 APK archive 条目部分仅按 handback 记录，未能本地复算。没有本轮重新导出，来源到产物的实际执行绑定仍 `INCOMPLETE`，两模型保持 `assetsVerified=false`。
- **本轮范围与本地差异：**按 handback，测试、构建和设备项目均未运行；Mimo 报告本轮没有 repo 文件变更、暂存、提交或 push。Mimo 称 `git_status.txt` 为 631 行；当前本地 `git status --short --branch --untracked-files=all` 为 629 行（含分支行），21 个已跟踪修改、607 个未跟踪。`evidence.json`、`execution_log.txt`、`git_status.txt`、`git_diff_check.txt` 的可访问路径未出现在粘贴内容中，无法比较两份原始清单。

## 前一 handback（2026-09-28；T2 service route 复核）

- **设备 XML：**`t2-service-route-sea-al10.xml` 为 5 tests / 0 failures / 0 errors / 0 skipped，时间 `2026-09-28T06:09:24Z`，耗时 1.882s；HUAWEI SEA-AL10 / arm64-v8a / SDK 29。用例包含前缀模型 fail-closed、EXIF 处理、runtime/inference 错误状态及两张真图的 legacy service parity。
- **exp22/exp23 service 行为：**`White_BOLT_001` 路由 `EXP23_WHITE`，`Black_NUT_002` 路由 `EXP22_BLACK`；full-image 与 ROI 入口均返回 `MODEL_UNAVAILABLE`，不附带 exp09 模型版本、哈希、类别索引或张量 shape。因 `assetsVerified=false`，这两条 service 路径按门禁没有调用对应模型 JNI。
- **legacy 生产 service 链路：**无前缀 ID `legacy_T2_service_route` 从 `NanoDetRoiInferenceService` 经 `NanoDetNcnnNative` 调用生产 `libnanodet_ncnn_runtime.so`。两张图均与桌面参考一致：`frame_00106_f1060.jpg` 2 个检测，最大分数差 `2.6077032e-8`、最大框差 `2.0160e-5 px`；`frame_00045_f450.jpg` 6 个检测，最大分数差 `1.7881393e-7`、最大框差 `3.5829e-5 px`。
- **本轮范围：**exp22/exp23 固定真图 production JNI parity 的既有 XML 为 2 / 0 / 0 / 0（`2026-09-28T05:17:02Z`），本轮未重跑；PartColor UI 与 zero/synthetic XML 也未重跑。JVM 102 suites / 1491 tests / 0 failures / 0 errors / 5 skipped 是既有结果，本轮 JVM 未运行。
- **资产来源：**checkpoint/config、导出脚本及导出文件哈希已重新核对；四个 NCNN 导出文件与 app assets、debug APK 对应条目的哈希相同。但 exporter 本轮未运行，未找到将 exporter 实际读取的 checkpoint/config 哈希绑定到这些输出哈希的执行日志或不可变 manifest；来源链仍为 `INCOMPLETE`，不能仅凭文件哈希相同将 `assetsVerified` 设为 true。
- **构建与 APK：**Mimo 报告 `:app:connectedDebugAndroidTest` 成功（BUILD SUCCESSFUL in 24s）；本轮 JVM、exp22/exp23 固定真图测试、PartColorUITest、exporter 均未运行。debug APK：`2026-09-28T05:52:32.7553695Z`，241653133 bytes，SHA-256 `37AC1396CC5F7C9D98DF7D18A8CCC98B8F625273FD79DB412B3367A0F6BEA579`；androidTest APK：`2026-09-28T06:09:05.8257113Z`，12026702 bytes，SHA-256 `BC04C0CC36596CAE791B42E06F891057617638F3BCEA27EEE01D690370A8F43B`。
- **Git：**`main...origin/main [ahead 2]`，0 staged、21 个已跟踪修改、607 个未跟踪文件；本地状态与 handback 保存的 final snapshot 完全一致。service 测试源文件在 Mimo 本轮开始前已是修改状态，handback 报告本轮没有新增或消失的状态项；当前工作区仍包含未审阅的混合改动及受保护文件。

## 更早 handback（2026-09-28；T2 生产 JNI 固定真图 parity 复核）

- **设备 XML：**最新生产 JNI 真图 XML 为 2 / 0 / 0 / 0，SEA-AL10 / Android 10，时间 2026-09-28T05:17:02Z，耗时 2.047s。PartColor UI 12 / 0 / 0 / 0（03:53:34Z）和模型加载/合成候选 4 / 0 / 0 / 0（03:58:37Z）是已有结果，本轮未重跑；三份选定 XML 合计 18 项，不是全量 instrumentation 汇总。
- **生产 JNI 真图 parity：**固定图 `frame_00000_f0.jpg` 为 720×1280、SHA-256 `f9fcc75a2f4047db35fcd2b884e1bdf5bab3ce7addce8610c40a99469942ded6`、EXIF orientation=1。SEA-AL10 / arm64-v8a / SDK 29 上调用生产 `libnanodet_ncnn_runtime.so`，并使用生产 Kotlin 预处理和 decoder。生产 JNI 输出 exp22/exp23 分别为 129528 / 122332 元素；test-only Mat 探针从实际 NCNN Mat 读取 `[2,36,3598,4]` / `[2,34,3598,4]`，探针张量与生产 JNI 张量逐元素差为 0。
- **桌面比较：**输入张量 519168 个元素，最大差 0；生产 JNI 输出对桌面参考最大差 exp22 `3.3080578e-6`、exp23 `3.2186508e-6`，容差均 `1e-5`。每模型各 3 个 NMS survivor 的类别/point 集合一致；分数最大差 `6.5565e-7` / `5.9605e-8`，框坐标最大差 `1.5759e-5` / `1.4481e-5 px`，低于 `0.01 px` 容差。
- **覆盖边界：**生产 JNI 在一张固定图上通过；生产 JNI 只返回输出张量并在 native 内校验 Mat shape，Mat 元数据由 test-only 探针读取。此测试未经过 `NanoDetRoiInferenceService` 路由/服务入口，不代表多图泛化或业务准确率。
- **其他修正：**桌面 JSON 已将输入差值字段明确命名为 `inputTensorMaxAbsoluteDifferenceTolerance`，容差 `0.0175080028` 与实测最大差 `0` 分开记录；exp23 合同注释已注明 Mat.w=34 的 Android 实测。两模型 `assetsVerified=false` 未改变。
- **JVM 与 APK：**本轮 JVM 未运行；最近 XML 仍为 102 suites / 1491 tests / 0 failures / 0 errors / 5 skipped（最新文件 2026-09-28 10:40:31），NmsBehaviorTest 为 7 / 0 / 0 / 0。Mimo 报告 `:app:connectedDebugAndroidTest` 构建/运行成功。debug APK：2026-09-28 12:14:58 +08，241653133 bytes，SHA-256 `37AC1396CC5F7C9D98DF7D18A8CCC98B8F625273FD79DB412B3367A0F6BEA579`；androidTest APK：12:58:00 +08，11974449 bytes，SHA-256 `4714FFBD4534B1D6A808799DE9ECFA1D7BEBD68CB91D43E9BBCE69ED18F5C80C`。
- **来源与 Git：**handback 报告 checkpoint/config 哈希可对应源文件和各自 exporter 输出目录；但缺少把源 checkpoint/config 哈希绑定到确切 NCNN 导出哈希的不可变清单，因此来源身份仍待复核。handback 起始/结束 Git 状态一致；当前 `main...origin/main [ahead 2]`，0 staged、21 个已跟踪修改、607 个未跟踪文件。本轮无提交或 push。

## 前一 handback 快照（T1/T2 早期证据审阅）

- **Android instrumentation：**设备 XML 的属性显示 SEA-AL10 / Android 10。真图 XML 为 2 tests / 0 failures / 0 errors / 0 skipped，时间 2026-09-28T04:34:07Z；exp22/exp23 模型加载及合成候选 XML 为 4 / 0 / 0 / 0，时间 03:58:37Z；PartColorUITest XML 为 12 / 0 / 0 / 0，时间 03:53:34Z。三份选定 XML 共 18 项通过；这不是全量 instrumentation 测试套件汇总。PartColor UI 本轮已在设备复验通过。
- **固定真图 parity：**固定图为 app/src/androidTest/assets/ncnn_parity/frame_00000_f0.jpg，720×1280，SHA-256 f9fcc75a2f4047db35fcd2b884e1bdf5bab3ce7addce8610c40a99469942ded6，EXIF orientation=1。Windows 桌面 NCNN 1.0.20260526 与 Android SEA-AL10 对照同一图像；生产 NanoDetImagePreprocessor 和 NanoDetOutputDecoder 被调用，Android 推理由 androidTest 专用 libncnn_smoke.so 执行。
- **Android 与桌面比较：**预处理张量 519168 个元素，实际最大差为 0，SHA-256 同为 1780011d72d18eb5c6f68cb6695b009dbfee350fe92f81a2cd4b4eaad7ee1f6b。两个 Android NCNN Mat 的实测信息均由输出 Mat 读取：exp22 [dims=2,w=36,h=3598,elemsize=4]，exp23 [2,34,3598,4]。原始输出张量逐元素比较：exp22 129528 项、最大差 3.3080578e-6；exp23 122332 项、最大差 3.2186508e-6；容差均为 1e-5。两边各有 3 个 NMS survivor，(classIndex, point) 集合和类别名精确匹配；分数最大差分别 6.5565e-7 / 5.9605e-8，框坐标最大差 1.5759e-5 / 1.4481e-5 px，分别小于 1e-5 / 0.01 px 门限。
- **Parity 边界：**测试使用生产 Kotlin 预处理器和 decoder，但 JNI 调用 androidTest 的 libncnn_smoke.so，不是生产 nanodet_ncnn_runtime.so，也没有通过 NanoDetRoiInferenceService 的实际路由/服务入口。因此固定图组件级 parity 通过；生产 JNI/service 集成 parity 仍待补。该证据只覆盖一张固定图，不是多图泛化或业务正确性验证。
- **参考数据审阅：**桌面参考 JSON 与图像、输入/输出 .f32 已存在，类别顺序与合同一致。JSON 字段 inputTensorMaxAbsoluteDifference 当前保存的是允许差值上限 0.017508...，而不是本次实测差值；Android 报告中的实际差值为 0。字段名称及测试读取键应由后续改为明确的 tolerance 名称，避免把门限误读为实测值。NanoDetInferenceModels.kt 中 exp23 宽度注释仍称待 Android 实际输出核验，与本轮 Mat.w=34 实测相矛盾，需更新注释；不可据此把 assetsVerified 改为 true。
- **NMS 与 JVM：**本轮 handback 未运行 JVM。当前可见最近 JVM XML 为 102 suites / 1491 tests / 0 failures / 0 errors / 5 skipped，时间 2026-09-28 10:40:31；NmsBehaviorTest 为 7 / 0 / 0 / 0，包含异类高 IoU 重叠候选。此结果是既有 XML，不计成本轮新运行。
- **构建与 APK：**Mimo 报告 connectedDebugAndroidTest 编译/运行成功，arm64 JNI 编译有 -lncnn linker 配置警告但未阻断。assembleDebug 为 UP-TO-DATE；testDebugUnitTest 本轮未运行。当前 app-debug.apk 本地重算为 2026-09-28 12:14:58 +08、241653133 bytes、SHA-256 37AC1396CC5F7C9D98DF7D18A8CCC98B8F625273FD79DB412B3367A0F6BEA579；androidTest APK 为 12:33:48 +08、11974449 bytes、SHA-256 0142B17E93C401BE56039951128C57F5B775C32C6B9FC5F81D92B76C94AA114E。
- **资产验证状态：**四个 NCNN 资产哈希在桌面参考及既有源文件/主 APK 核对中一致；固定图测试中的 assetsVerified 明确为 false。文件哈希对应当前资产，不等于独立证明训练 checkpoint/config 来源身份；不得启用资产。
- **当前 Git：**main...origin/main [ahead 2]，0 staged；21 个已跟踪修改文件，607 个未跟踪文件（含 app 测试/模型 fixture、commonMain 2 个、docs/reports 574 个及工具文件）。工作区包含此前混合的 T1/T2 实现和离线实验；本轮只读审计并更新任务文档，没有提交、push、测试、Gradle 或设备操作。

## 此前进展（截至 2026-09-27 21:30；由上文最新 handback 更新）

- **T1：**功能实现和 JVM 回归完成。当前本地 XML 汇总为 **101 suites / 1484 tests / 0 failures / 0 errors / 5 skipped**。`compileDebugKotlin`、`compileDebugUnitTestKotlin`、`assembleDebug` 均由 Mimo handback 报告通过。APK：`2026-09-27 21:01:20`，`233029151` bytes，SHA-256 `F63128CF441A74E94820F150737028FBB920BC2A19F583DB19A5C263912B5E1E`。
- **T1+T2 最新软件验证：**Mimo 报告构建成功（约 23 秒）、1484 项 unit tests 通过；本地 XML 汇总 **101 suites / 1484 tests / 0 failures / 0 errors / 5 skipped**，关键 T1/T2 测试 XML 均为 0 failure/error。APK 已本地核实为 `2026-09-27 21:30:21`、`242416706` bytes，SHA-256 `76F80286D97025825234083FF58E94AF0E7EF75435E6F67EE90D2B4EA0762997`；其时间晚于 T2 源码和资产，可作为集成构建证据。
- **T2 Android instrumentation handback：**Mimo 报告 `compileDebugAndroidTestKotlin` 因 `PartColorUITest.kt` 已知问题失败，称本轮 parity 相关代码没有新增编译错误；真机推理未执行。当前无 exp22/exp23 Python/ONNX/桌面 NCNN 参考 JSON、checkpoint/config 或桌面推理脚本，故张量及 decoder 比较未完成。
- **Parity 测试源码审计：**`ExpModelParityInstrumentedTest` 目前把整个参考读取/解析/比较放在 `catch (Exception)` 中，任何解析/比较异常也会被记成参考缺失；即使参考存在，比较结果 `passed=false` 只写入 JSON，测试没有断言失败。参考缺失时也会正常返回。因此当前 instrumentation 测试不能作为 parity 通过门禁；需缩窄缺失处理、校验参考张量长度并对 mismatch 断言失败，缺参考时明确标为 skipped/incomplete。
- **T1 Android UI 测试限制：**`PartColorUITest.kt` 编译失败，未运行；handback 指出 androidTest 缺少 Compose UI 测试依赖。它不作为 T1 通过证据，也不纳入 T1 选择性提交候选。`PartColorComposeTest` 的生产表单字段覆盖不等于生产入口 Dialog 外壳验证；Robolectric 像素采样也没有形成稳定证据。
- **T2 离线 handback：**Mimo 报告 exp22 输出 `[3598,36]`、exp23 输出 `[3598,34]`；ONNX/NCNN max diff 分别为 `3.81e-06` 和 `4.47e-06`，并报告类别顺序、预处理和 decoder 参数已核验。其 PT/ONNX 约 `7.13` 差异按 handback 解释为 ONNX 导出时 class score 增加 sigmoid，而 PyTorch `forward()` 返回 logits。
- **T2 集成状态：**JNI `create()` 接收 `outputWidth` 并从模型合同传入 36/34；exp22/exp23 资产、SHA-256 常量和输出 shape 已接入。本地四个资产哈希与 handback 和合同常量一致；APK 内四个 `assets/nanodet/exp22|exp23/` 条目也已逐项检查并重算哈希，全部匹配。两模型 `assetsVerified=false`，生产路由仍 fail-closed；当前尚无真机 parity 证据。
- **T2 报告来源：**完整离线报告文件 `T2_MODEL_ASSET_VERIFICATION_REPORT.md` 本次本地工作区检查未找到；离线导出、ONNX/桌面 NCNN parity、类别顺序、预处理/decoder 和 PT/ONNX 差异解释按用户提供的 handback 记录。Android 集成的 XML/APK 结果已在本地核对；真机 Python/桌面 NCNN 对照仍待 Mimo 提交证据。
- **当前 Git 快照：**`main...origin/main [ahead 2]`，0 staged、20 个已跟踪修改（18 个 App 文件及本文件/`tasks/plan.md`）、594 个未跟踪文件（16 个 App 文件、574 个 `docs/reports/` 文件、2 个 `commonMain/` 文件、2 个工具文件）；无 commit/push。App 未跟踪项含 T2 的 4 个模型资产、新增 parity instrumentation 测试和编译失败的 `PartColorUITest.kt`。T1/T2 共用源码已有混合改动，不能把旧 T1 清单整体提交。
- 本轮只更新协调文档；未提交或 push。完整任务依赖、NCNN 资产哈希及 Android 后续门禁见 `tasks/plan.md`。

## 确认的业务规则

- 新建零件必选白件/黑件；PartEntity.id 保存 White_<基础ID>/Black_<基础ID>，模板 partId 使用最终 ID；旧无前缀零件继续 exp09。
- Black_ 路由 exp22 B 四类；White_ 路由 exp23 B，两类仅 White Nut/White Thread。
- V4 配准门禁失败：不算相似度，继续全图检测。
- **当前实现状态：**生产确认链路尚未实现 ROI 相似度兜底。确认保存时每个 ROI 都有表格记录；仅当人工最终结果不同于已有模型建议时保存对应 ROI 图片，并在导出表格记录图片 ZIP 路径。该路径不会因为 NanoDet NG/无检测而自动计算或记录相似度候选。
- **待实现的兜底与证据：**V4 配准门禁通过后，所有小件 ROI 目标类别都必须有相似度兜底：NanoDet 判 NG/无检测时进入兜底；所选模型不支持的目标也要有入口。候选通过记 OK，未通过记 NG；两种候选都允许人工改判，并分别记录 NanoDet 状态/结果、相似度分数/阈值、相似度候选、人工最终结果及是否改判。每次实际运行相似度都保存对应 ROI 图片，无论候选通过或不通过，并在表格记录图片 ZIP 路径。各类别阈值分别评估；目前只有 Black Thread 有灰度 SSIM 0.95 人工监督候选，其他类别阈值待验证。
- 白件 BOLT/NUTSERT 暂不由 exp23 检测，但仍属于相似度兜底覆盖目标；MODEL_TARGET_UNSUPPORTED 时应进入相似度辅助路径。Black Thread 的 0.95 不可迁移到其他件色或目标。
- NanoDet 候选阈值 0.05；业务建议阈值 0.50，score ≥0.50 建议 OK；当前 NMS IoU 0.60。
- 黑件数据的离线实验得到灰度 SSIM `0.95` 起始候选：48 个清洁几何案例中 46 个通过；评分通过的 ≥75% 合成遮挡案例为 0/276 通过。该值仅用于 Black Thread 人工监督试点，不代表业务阈值已校准。
- `Key_role` 有 42 张孔位照片、无标签或可核验的同工位在位配对映射；孔位预期状态不确定，不作为真实缺件负样本或阈值真值。

## 任务顺序

- [x] T1：件色选择、前缀 ID、主键校验、模板 partId 关联、模型路由、fail-closed 和 JVM 回归完成；SEA-AL10 上 PartColorUITest 12 / 0 / 0 / 0，通过本轮设备复验。
- [x] T2：exp22/exp23 来源/导出审计 `PASS_WITH_RECORDED_LIMITATION`；`assetsVerified` 已启用；Black_ 四类、White_ 两类、White_ BOLT/NUTSERT `MODEL_TARGET_UNSUPPORTED`、legacy exp09 路径均在 SEA-AL10 验证通过；Gradle connectedDebugAndroidTest XML 6/0/0/0。详见 `app/build/t2_asset_enable_route_20260929T010000/HAND_BACK.md`。
- [x] T3：用 Black Thread 项目原图进行同源几何和标记为 synthetic 的 ROI 遮挡实验，记录分数响应与配准门禁；产出人工监督试点候选 `SSIM ≥0.95`，不宣称业务校准完成。
- [ ] T4：核验所有小件类别的 Android V4→NanoDet/相似度兜底链路及 unsupported 目标入口；验证相似度通过记 OK、未通过记 NG，且两种候选均支持人工改判并留痕。确认每次实际运行相似度都保存 ROI 图片，并在表格记录 NanoDet 状态/结果、分数/阈值、相似度候选、人工最终结果、是否改判和图片 ZIP 路径。Black Thread 以 0.95 作为人工监督候选；其他类别需先按类验证阈值，再分别开展经授权的人工监督试点。
- [ ] T5：按件色和小件目标类别采集带人工真值的真实模板/现场 ROI；缺件使用同工位实拍空位并确认原本应装件，质量缺陷使用真实缺陷件及质检标签。
- [ ] T6：按物理零件/批次/会话拆分校准集与独立留出集，并按件色/目标类别报告混淆矩阵和错误 OK 风险；分别决定各类别 ROI 相似度阈值是否可推广。

相似度门禁、试点流程、校准步骤及 exp22/exp23 权重配置身份见 tasks/plan.md 顶部。离线实验完整记录位于 `docs/reports/b3/roi_similarity/synthetic_occlusion_20260927/REPORT.md`；旧的离线报告和其他历史记录保留在下方。

---

## 已完成探索：ROI 配准后相似度离线可行性评估

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 本地结果核对

- 数据：33 张 JPG、33 个 YOLO 标注文件、45 个标注框；类别目录计数 nut 11、Nutsert 6、thread 16；EXIF orientation 均缺省。
- `pair_results.csv` 有 1,089 条数据行：561 个同源合成变换、190 个同类别异源对照、338 个跨类别异源对照。561 个变换的 mask 均存在；成功和失败接触图均存在。
- 合成组 561/561 估出 Homography；538 通过 Python 质量门禁，23 回退（21 越过 50 px 边界余量、2 投影 ROI 面积低于 0.005）。这不是 Android App 的通过率保证。
- 全体合成组 ROI 灰度 SSIM 均值 `0.4035 → 0.9722 → 0.9745`（未对齐、估计 H、已知矩阵参照）；只看门禁通过的 538 例，估计 H 后均值为 `0.9724`。23 个门禁拒绝例的估计 H 后均值为 `0.9671`，仅为诊断结果，不代表 App 会采纳。
- 梯度 SSIM 均值 `0.2125 → 0.9375 → 0.9447`；估计角点误差中位数 `0.292 px`，有效全图重叠中位数 `0.9674`。
- 异源配对仅描述配准行为：同类别 190 对中 53 对通过，跨类别 338 对中 0 对通过。它们不是缺陷/缺件负样本，不可推算业务误报率。
- 报告、CSV、JSON、清单、561 个 mask、两张接触图均在 `docs/reports/b3/roi_similarity/`；脚本在 `tools/roi_similarity/evaluate_roi_similarity.py`。

## 审计判断与限制

- 可用于支持“同一张图像在这些合成几何扰动下，经特征配准后 ROI 结构相似度恢复”的离线可行性结论；不可作为把 NanoDet `NG`/无检测自动翻为 `OK` 的依据，也没有证据支持任何 SSIM 阈值。
- 数据只有 33 张原图的合成变换，不包含独立的现场重复拍摄、真实缺陷/缺件标签、光照/反光/模糊变化或缺陷最小尺寸评估。数据集 YOLO 框是空间标注，需另行确认其与 App 用户配置 ROI 的业务语义一致。
- Python 实验未复现 App 自定义 GMS，且 OpenCV 4.13.0 与 Android 目标 4.10.0 不同；估计 H 和质量门禁通过率不能视为 App V4 真实表现。估计 H 对 23 个门禁拒绝样本仍计算了诊断相似度。
- 保持业务阈值 `0.50`、候选阈值 `0.05`、人工确认和安全回退语义不变；没有 App 代码、测试或数据库修改。
- mimo 把完整运行写回既有 `roi_similarity/` 路径，而不是此前要求的独立输出目录；原有部分未跟踪产物被同路径运行覆盖。当前完整结果已保留，未尝试回滚。

下一步如要评估产品化，先取得同一真实零件的重复现场照片、已标记 OK/真实缺陷与缺件的样本，以及对应的 App ROI；在独立数据上评估错误翻转风险，再决定是否设计仅供人工参考的功能。当前不需要新的 mimo 实现指令。

---

## 最近完成修复：ROI 编辑器拖拽响应与取消按钮

- Git 已核实 `main` 的 HEAD 为 `e8f87372`，相对 `origin/main` ahead 2；未 push。
- 本地 XML：**1323 tests / 0 failures / 0 errors / 5 skipped**；`RoiEditorViewModelTest=80/0/0/0`。HTML 首页：**1323 tests / 0 failures / 5 ignored / 100% successful**，最后写入 `2026-09-24 15:03:03 +08:00`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；`2026-09-24 15:19:06 +08:00`；`232241205` bytes；SHA-256 `AAC9096F8B48B6E057D9628838246F43E8E9917F37750D3E27DDC79ACE75575F`。
- 提交已包含内存拖拽预览、手势结束一次持久化、取消时回滚快照、模板位图移至 IO 解码、取消按钮可辨认度改进及对应 JVM 测试。本轮仅复核现有 XML/HTML/APK，没有重跑测试或构建。

## 前一项已完成任务：现场采集双视图 ROI 引导

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## Task 1–4 本地审计与基线收口

- 生产接入已核对：`inspectionState.rois` 传入 `TemplateReferenceSection`，继续传入 `TemplateContent`，下方模板图 Canvas 绘制当前视角启用的 RECT ROI。
- 坐标映射已核对：`computeTemplateImageRect()` 支持 FIT/Crop 的实际图片内容矩形；`mapRoiToTemplateOverlay()` 使用归一化 ROI 映射；无效图片尺寸、容器尺寸、JSON、越界或零面积数据不绘制虚假框。
- 本地 XML：**1314 tests / 0 failures / 0 errors / 5 skipped**。`TemplateRoiOverlayTest=20/0/0/0`、`TemplateCaptureViewModelTest=18/0/0/0`、`RoiEditorViewModelTest=71/0/0/0`、`RoiSafetyMarginTest=32/0/0/0`、`TemplateCaptureConcurrencyTest=1/0/0/0`。
- 本地 HTML：**1314 tests / 0 failures / 5 ignored**，成功率 100%。handback 中“跳过 0”与本地 XML/HTML 不一致，以本地证据为准。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；`2026-09-24 13:04:04 +08:00`；`232978112` bytes；SHA-256 `D993B29424FDB3AD24792F0BCEF268A8E0F79E88952C0D11AC570064E0F26749`。
- 本轮开始时 `main` 指向 `1642e018`，相对 `origin/main` ahead 1；代码工作区干净，无暂存、未提交或未跟踪文件。此提交已包含 Task 1–4 的源码、测试及协调文档。

## 审计结论

- Task 1/2/3/4：软件审计通过。
- Task 1–4 已完成并提交；本地审计通过。该记录结束时没有待处理的 mimo 指令。

- 本轮只更新协调状态记录；未执行 Git commit 或 push。

---

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 本轮 handback v2 本地审计结论

- 本地 XML：`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总 **1238 tests / 0 failures / 0 errors / 5 skipped**；新增 `FullImageConfirmContentComposeTest=13/0/0/0`，`ViewConfirmationModelResultComposeTest=8/0/0/0`，`InspectionExcelExporterTest=20/0/0/0`，`ViewConfirmationModelResultTest=28/0/0/0`，`InspectionZipExportArchiveTest=3/0/0/0`。
- 本地 HTML：`app/build/reports/tests/testDebugUnitTest/index.html` 首页为 **1238 tests / 0 failures**；最后写入 `2026-09-22 17:39:48`。
- 本地 APK：`app/build/outputs/apk/debug/app-debug.apk`；最后写入 `2026-09-22 17:40:21`；`232975917` bytes；SHA-256 `3CF8B2B1E5A612098DF1B111D4A4DDCB9F0A67079661608601EE366A8A563138`。
- 实际工作区为 19 个文件：3 个生产文件、13 个测试文件（含 2 个 `app/src/androidTest` 文件）和 3 个协调文档；其中新增 `FullImageConfirmContentComposeTest.kt` 仍未跟踪；无其他未跟踪文件；分支 `main...origin/main [ahead 24]`。
- `ViewConfirmationScreen` 仅抽取 `FullImageConfirmContent` 和 `BottomConfirmBar` 为 `internal` 以便测试，整图过滤、upright/imageBox、原始 detections/JSON、人工确认和导航语义未改变。
- 新测试直接渲染生产 `FullImageConfirmContent` 与 `BottomConfirmBar`，覆盖摘要、0.50 过滤、错误态、旧长文案消失、OK/NG 入口、点击回调和确认按钮；上一轮 UI 证据阻塞已解除。
- Git 收口：已选择性提交本轮 19 个文件，当前提交见 Git `HEAD`；未使用 `git add .`，无未跟踪文件。

结论：**SOFTWARE_AUDIT_PASSED / BASELINE_COMMITTED**。当前不需要新的 mimo 指令。

## 本轮 handback v1 本地审计

- 本地 XML：`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总 **1225 tests / 0 failures / 0 errors / 5 skipped**；重点为 `InspectionExcelExporterTest=20/0/0/0`、`ViewConfirmationModelResultTest=28/0/0/0`、`InspectionZipExportArchiveTest=3/0/0/0`、`ViewConfirmationModelResultComposeTest=8/0/0/0`。
- 本地 HTML：`app/build/reports/tests/testDebugUnitTest/index.html` 首页计数为 **1225 tests**；目录最后写入 `2026-09-22 17:19:58`。
- 本地 APK：`app/build/outputs/apk/debug/app-debug.apk`；最后写入 `2026-09-22 17:21:29`；`232224821` bytes；SHA-256 `E0979C345A73FBEE16592DEB4ABFDE0D718147D52C8B495F66B050ABEB66EF8C`。
- 实际工作区为 18 个已修改文件：3 个生产文件、12 个测试文件（含 2 个 `app/src/androidTest` 文件）和 3 个协调文档；无未跟踪文件；分支 `main...origin/main [ahead 24]`；`git diff --check` 仅有 LF→CRLF 警告。
- 生产差异静态复核：业务阈值为 `0.50f`，候选阈值仍为 `0.05f`；模板对齐页保留比对控件；整图页保留 EXIF/upright 照片、`imageBox` overlay、人工 `BottomConfirmBar` 和原始 detections/JSON。
- 阻塞原因：`ViewConfirmationModelResultTest` 的整图摘要测试只是复制生产字符串拼接逻辑；`ViewConfirmationModelResultComposeTest` 新增测试只渲染普通 `RoiConfirmCard`，没有实际渲染 `ViewConfirmationScreen` 的整图分支，也没有断言“整图检出：…·阈值 50%”。handback 还未逐项准确列出实际 18 个文件。

在 mimo 补齐真实 UI 证据并重新回传前，不允许提交本轮代码和文档差异。

## 下一条 mimo 最小指令

```text
请只做当前 handback 的最小验收补强，不要重新实现阈值或 UI。

1. 为 ViewConfirmationScreen 的 isFullImageMode 分支增加真实 Compose/JVM UI 断言：实际渲染整图确认内容，断言出现“整图检出：… · 阈值 50%”、保留人工 OK/NG 确认入口，并断言旧长文案（整图检测模式、原始/显示/隐藏、最高置信度、推理耗时、检测详情）不出现。不要用复制生产字符串拼接逻辑的测试代替 UI 断言。
2. 如果现有 ViewModel/Repository 初始化成本过高，可抽取最小无副作用的整图摘要/确认内容 Composable 供测试，但不得改变 full-image 的过滤、人工确认、原始 detections/JSON、Session ROI 或导航语义；不要删除生产断言或改成 skip。
3. 保留当前业务阈值 0.50、CANDIDATE_THRESHOLD 0.05；不要修改 decoder、模型、registration、CameraX、DPM、OCR、数据库迁移、ZIP/CSV 语义。不要运行 ADB、instrumented、真机测试或 OCR。
4. handback 必须准确列出全部实际修改文件；特别说明 AppDatabaseTest 的当前 fixture 改动和 NanoDetRoiRuntimeInstrumentedTest 的未运行状态。不要修改 tasks/todo.md、tasks/plan.md、docs/reports，不要 Git commit。

只允许运行：
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks
./gradlew.bat :app:assembleDebug --no-daemon

handback 必须按本地 XML/HTML 实际统计，并提供全部修改文件、git diff --stat、git diff --check、Git 状态、重点 XML、APK 时间/大小/SHA-256。
```

## 新任务产品契约

- 模板对齐页和整图确认页减少解释性长文案，视觉密度接近现有人工 ROI 确认卡片；保留必要的图片、检测框、状态、导航和人工 OK/NG 操作。
- 新业务阈值统一为 `0.50`：普通 ROI `score >= 0.50` 时模型建议 OK，`score < 0.50` 时模型建议 NG。
- `NanoDetModelContract.CANDIDATE_THRESHOLD = 0.05` 保持不变；不修改 decoder、模型、registration 或其他无关阈值。
- 整图 fallback 仍不自动决定最终 OK/NG，`aggregatedSuggestion` 保持 `null`，最终结果继续由人工确认。
- 新产生的确认结果记录业务阈值 `0.50`；已有历史记录中的 `softwareThreshold` 不回写、不迁移，导出继续尊重已保存阈值和 `threshold=null` 兼容语义。
- 不新增数据库实体/迁移，不修改照片路径、ZIP entry、原始 detections、`softwareDetectionsJson`、Session ROI 或 projected ROI 语义。

## 实施任务

### Task 1：业务阈值切换到 0.50

- [x] 将 `NanoDetModelContract.STARTING_BUSINESS_THRESHOLD` 从 `0.37f` 改为 `0.50f`；普通 ROI 的 OK/NG 边界严格采用 `>=`。
- [x] 更新当前业务测试和新结果 fixture 到 0.50，补充 `<0.50`、`==0.50`、`>0.50` 边界测试；保留历史迁移/旧数据 fixture 的原值语义。
- [x] 明确候选阈值仍为 `0.05f`，不得修改 NanoDet decoder、模型和推理流程。

依赖：无。规模：S。

### Task 2：模板对齐页文案精简

- [x] 保留现场/模板/叠加、闪烁、透明度、缩放/平移/重置、ROI 绘制、返回和继续操作；只精简冗余文字，不删除比对能力。
- [x] 顶部标题、配准状态和 fallback 提示改为短文案；移除重复的“拍后比对”、详细内部实现说明、长句错误解释和非必要手势说明。
- [x] `readOnly`、SessionRoiRegistry 写入顺序、导航和 projected ROI 语义保持不变。

依赖：无。规模：S。

### Task 3：整图确认页文案精简

- [x] 页面视觉结构和人工 ROI 确认卡片一致：保留现场照片/检测框、简短检测摘要和共享的人工 OK/NG 确认栏。
- [x] 删除长段 NanoDet/配准解释、逐条重复检测详情、原始/显示/隐藏四段式解释和非必要推理技术信息；保留一行简短检出摘要及必要错误状态。
- [x] 仍只显示 `score >= 0.50` 的框/标签/可见结果；原始 `FullImageInferResult.detections` 和 `softwareDetectionsJson` 完整保留。

依赖：Task 1。规模：M。

### Checkpoint：JVM、编译和 APK

- [x] 全量 JVM：`:app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`。
- [x] Kotlin 编译：`:app:compileDebugKotlin --no-daemon --rerun-tasks`。
- [x] Debug APK：`:app:assembleDebug --no-daemon`。
- [x] 核对 XML/HTML、APK 时间/大小/SHA-256、Git diff/status；不运行 ADB、instrumented、真机测试或 OCR，不提交 Git。

## 当前 mimo 指令

完整指令如下；主协调只提供指令，不主动联系 mimo。

```text
请在已提交基线 480ab793 之上，单独处理“模板对齐页/整图确认页文案精简 + 业务阈值切换到 0.50”。不要重做上一任务的 CSV/ZIP 过滤、EXIF/upright 检测框或 CaptureComparison 导航修正。

产品契约：
1. 新业务阈值为 0.50。将 NanoDetModelContract.STARTING_BUSINESS_THRESHOLD 从 0.37f 改为 0.50f；普通 ROI score >= 0.50 为模型建议 OK，score < 0.50 为模型建议 NG，边界严格使用 >=。
2. NanoDetModelContract.CANDIDATE_THRESHOLD 保持 0.05f；不得修改 decoder、模型、registration 质量阈值或其他无关阈值。
3. 整图 fallback 不自动决定最终 OK/NG，aggregatedSuggestion 继续为 null；最终整体结果仍由人工确认。
4. 新结果记录软件业务阈值 0.50；不得回写或迁移旧记录中的 softwareThreshold。CSV/ZIP 继续尊重已保存阈值和 threshold=null 兼容语义。

模板对齐页（CaptureComparisonScreen.kt）：
5. 保留现场/模板/叠加切换、闪烁、透明度、缩放/平移/重置、Session ROI 纯绘制、返回和继续按钮。
6. 只精简文字：顶部保留零件名和视角；去掉“拍后比对”等重复标题，配准状态改为短标签（如“配准成功”“配准失败”“图片不可用”），fallback 只保留“整图检测”短提示；删除长段内部实现说明和非必要“手势说明”。
7. 不改变 readOnly 语义、SessionRoiRegistry 写入顺序、导航栈和 projected ROI 坐标语义。

整图确认页（ViewConfirmationScreen.kt）：
8. 保留现场照片、upright imageBox 检测框/标签和共享 BottomConfirmBar 的人工 OK/NG 操作，整体密度接近 RoiConfirmCard。
9. 删除长段“配准不可靠/NanoDet/人工判定”解释、逐条重复检测详情、原始/显示/隐藏四段式技术摘要和非必要推理耗时信息；改为一行简短摘要（例如“整图检出：N 个 · 阈值 50%”或等价短文案），错误时保留一条短错误提示。
10. 可见框/标签/摘要严格使用 score >= 0.50；FullImageInferResult.detections 和 softwareDetectionsJson 必须完整保留。

测试要求：
11. 更新当前业务测试/fixture 到 0.50，覆盖普通 ROI score<0.50、==0.50、>0.50；覆盖整图显示边界和原始 detections 保留；保留历史迁移/旧记录兼容测试的历史值。
12. 增加或更新 Compose/JVM UI 测试：断言模板对齐页和整图页不再显示旧长文案，保留必要控制、检测框入口和人工 OK/NG；不要只用复制生产逻辑的测试替代 UI 断言。
13. 用 rg 检查 0.37 引用，逐项判断是当前业务阈值、历史数据库 fixture 还是无关坐标/OCR/registration 数值；不得机械替换无关阈值。

允许修改：
- app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt（仅业务阈值常量）
- app/src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt
- app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt
- 必要时对应 ViewModel 及当前业务测试/Compose 测试/导出测试

禁止修改：
- CANDIDATE_THRESHOLD、NanoDet decoder、模型文件、registration 质量阈值、CameraX、DPM、OCR
- 数据库实体/迁移、照片路径、mainImagePath、SessionRoiRegistry、projected ROI 语义
- ZIP entry 命名、CSV 字段语义、原始 detections/softwareDetectionsJson、人工 OK/NG 保存语义
- tasks/todo.md、tasks/plan.md、docs/reports；禁止 Git commit

只允许运行：
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks
./gradlew.bat :app:assembleDebug --no-daemon

禁止 ADB、instrumented、真机测试和 OCR。handback 必须按本地 XML/HTML 实际统计，提供修改文件、git diff --stat、git diff --check、git status --short --branch、重点阈值/UI XML、APK 时间/大小/SHA-256，并明确未运行禁止命令、未提交 Git。
```

---

## 已完成任务：整图检测置信度阈值与采集 ZIP/CSV 记录增强

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 最新 handback 审计结论（2026-09-22）

- 本地 XML 实际汇总：`1221 tests / 0 failures / 0 errors / 5 skipped`；HTML `app/build/reports/tests/testDebugUnitTest/index.html` 同为 `1221 / 0 / 0 / 5`。
- 重点 XML：`InspectionExcelExporterTest=20/0/0/0`、`ViewConfirmationModelResultTest=26/0/0/0`、`InspectionZipExportArchiveTest=3/0/0/0`。
- APK 本地实际：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 16:43:22，232976893 bytes，SHA-256 `7F955CA6934F74EA19E715F5F714C73D959BD05784E5C86BA40DCD20D8547D4D`。
- 实际工作区为 4 个实现/测试文件和 3 个协调文档已修改；无未跟踪文件；分支 `main...origin/main [ahead 23]`。`git diff --check` 无实质错误，仅有 LF→CRLF 警告。未运行 ADB、instrumented、真机测试或 OCR。
- 已确认通过：有限/0..1 threshold 原样使用，NaN/无穷/越界回退 0.37；边界采用 `score >= threshold`；摘要显示原始数、显示数、阈值和隐藏数；原始 detections 保留；CSV/ZIP 既有闭环未被破坏。

当前软件审计通过。允许在用户明确授权后按文件路径选择性提交；当前不需要新的 mimo 指令。

- 上一阶段模板加载诊断、EXIF/upright 整图框和拍后比对页修正已完成并提交；本轮只处理 0.37 阈值显示和 ZIP/CSV 记录。

## 上一阶段完成证据

- `TemplateImageLoader.kt` 已在空路径、文件预检、bounds、decode 等失败阶段记录 `templateId/stage/scheme/source/exception`，并通过 `TemplateLogEntry` 和 `TemplateImageLoaderTest` 验证；`CancellationException` 仍传播。
- `ViewConfirmationScreen.kt` 已在 fallback 整图模式使用 EXIF-aware upright 照片和 `imageBox` 绘制整图检测框；`FullImageDetectionOverlayTest`、`ExifOrientationRegressionTest` 和坐标映射测试已有通过证据。
- 当前本地基线实际为 `1201 tests / 0 failures / 0 errors / 5 skipped`，APK SHA-256 为 `5AD754579320AEF172B9AFB3B6F02E37A0F26BF498B5DC6E4458DE051239128D`；上一阶段人工验收已确认模板对齐页面可见。
- 因此不应再次派发“从零实现”任务。若需要交给 mimo，只能执行当前工作区核验；只有发现实际缺口时才做最小补丁。

## 本任务产品契约

- 阈值来源复用 `FullImageInferResult.threshold`；正常业务阈值为 `0.37`，显示条件严格为 `score >= 0.37`。
- `FullImageInferResult.detections` 和 `softwareDetectionsJson` 保留全部原始结果；只过滤整图确认页可见框/详情和 CSV detection 行。
- `inspection_result.csv` 只保留 `__FULL_IMAGE__` detection 行中 `score >= 0.37` 的记录；ZIP 继续包含每个视角的原始照片和现有 CSV。
- 不生成真正 `.xlsx` 或新增带框照片；不新增数据库实体/迁移，不改变照片路径、人工 OK/NG、NanoDet 推理和现有 ZIP entry 语义。

## 实施任务

### Task 1：阈值过滤契约与纯逻辑

- [x] 使用现有 `FullImageInferResult.threshold`，正常值为 `0.37`，边界采用 `>=`。
- [x] 覆盖低于、等于、高于阈值、空列表和无效/越界阈值；无效阈值安全回退到 `0.37`。
- [x] 不修改 NanoDet candidate threshold、decoder、模型、推理服务或原始 detection 集合。

依赖：无。规模：S。

### Task 2：确认页可见结果过滤

- [x] 框、标签、详情列表和摘要共用同一 `visibleDetections`，只显示 `score >= 0.37`。
- [x] 摘要同时显示原始检出数、显示数、阈值和隐藏数；空列表不绘制、不伪造结果。
- [x] 保持 `imageBox`、EXIF/upright、`ContentScale.Fit`、整图 fallback 和人工总体 OK/NG 语义不变。

依赖：Task 1。规模：M。

### Task 3：ZIP/CSV 记录闭环

- [x] ZIP 继续写入每个视角的原始采集照片和 `inspection_result.csv`，照片行及既有非 detection 行语义不变。
- [x] `__FULL_IMAGE__` detection 行只保留 `score >= 0.37`，并保留 score、threshold、class、upright `imageBox`。
- [x] 无检测、推理失败、照片缺失和临时 ZIP 失败场景均可解释，不丢失原始 JSON，不伪造检测行。

依赖：Task 1；可与 Task 2 并行。规模：M。

### Checkpoint：最终软件回归

- [x] 全量 JVM：`:app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`。
- [x] Kotlin 编译：`:app:compileDebugKotlin --no-daemon --rerun-tasks`。
- [x] Debug APK：`:app:assembleDebug --no-daemon`。
- [x] 本地核对 XML/HTML、APK 时间/大小/SHA-256、Git diff/status；不运行 ADB、instrumented、真机测试或 OCR，不提交 Git。

## 当前 mimo 指令

本任务已通过软件审计，当前不需要新的 mimo 指令；上一轮修正指令和 handback 证据保留在 [`tasks/plan.md`](plan.md) 及本报告中。若用户后续要求新功能，再单独建立新任务。

## 上一阶段 Agent 核验指令（已完成）

```text
当前工作区已经包含模板加载 templateId 诊断日志和 fallback 整图检测框实现。请先只读审计，不要重复实现，不要重写已有逻辑。

核对：
1. TemplateImageLoader 所有失败阶段日志是否包含 templateId、stage、scheme、source 和原始 exception；CancellationException 是否继续传播；TemplateImageLoaderTest 是否有稳定证据。
2. fallback 整图模式是否使用 EXIF-aware upright 照片；detections[*].imageBox 是否与显示照片处于同一 upright 坐标系；ContentScale.Fit 留白、左上/右下/贴边框和 Orientation=6/8 是否有确定性测试。
3. 拍照后 CaptureComparisonScreen 是否仍可见；ROI 人工编辑入口保持隐藏/未开放但没有删除；onProceed 是否先写 SessionRoiRegistry 再进入 ViewConfirmation。

如果上述实现和测试均已满足，只回传核验结论，不修改生产代码；不要为了增加测试数量而重复添加测试。
如果发现真实缺口，只做最小修正，允许范围仅限 TemplateImageLoader.kt、ViewConfirmationScreen.kt、ViewConfirmationViewModel.kt、RoiCoordinateMapper.kt 及对应 JVM/Compose 测试；禁止修改 registration、detection/NanoDet、CameraX、DPM、OCR、数据库实体/迁移、mainImagePath、SessionRoiRegistry、projected ROI 语义和 ZIP/CSV 结构。

只允许运行：
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks
./gradlew.bat :app:assembleDebug --no-daemon

禁止 ADB、instrumented、真机测试、OCR 和 Git commit。不要修改 tasks/todo.md、tasks/plan.md、docs/reports。handback 必须按本地 XML/HTML 实际统计，提供修改文件、diff --stat、diff --check、Git 状态、重点 XML、APK 时间/大小/SHA-256，并说明未执行禁止命令。
```

## 历史方案记录：整图检测置信度阈值与采集 ZIP/Excel 记录（已提升为当前任务）

该方案已提升为当前唯一任务；当前基线已由 `a66d8f5d` 收口，阈值 `0.37`，`inspection_result.csv` 只保留达标检测记录。执行指令以本文件顶部和 `tasks/plan.md` 为准。

---

## 上一阶段记录：整图检测置信度阈值与采集 ZIP/Excel 记录增强

## 方案摘要

- 整图检测显示层默认复用现有 `FullImageInferResult.threshold`，当前业务阈值为 `0.37`；采用 `score >= threshold` 显示检测框和检测详情，不修改 NanoDet candidate threshold、decoder、模型或推理服务。
- `FullImageInferResult.detections` 保留全部原始检测结果，不在推理层丢弃低置信度框；确认页只派生 `visibleDetections` 用于框、标签、详情列表和“显示数量”。同时显示原始检出数、阈值和被隐藏数量，避免误解为模型没有检出。
- 采集 ZIP 继续使用现有结构：每个视角的原始照片写入 `views/.../photo...`，统一 Excel 兼容 CSV 写入 `inspection_result.csv`。整图 `__FULL_IMAGE__` 的 detection 行只保留 `score >= 0.37` 的检测，并继续记录现有 `业务阈值`、`detectionScore`、`detectionImageBox` 字段；低于阈值的原始结果不写入 CSV，但不改变推理层结果和数据库原始 JSON。无需新增数据库实体或迁移。
- 当前只规划原始照片 + Excel 兼容 CSV；不额外生成带框照片，除非用户另行要求。

## 实施任务

### Task 1：确认阈值契约与纯函数

**描述：** 固化整图显示阈值来源和边界，不改变推理结果集合。

**验收标准：**

- [ ] 默认阈值来自 `FullImageInferResult.threshold`，建议值为 `0.37`；边界采用 `score >= threshold`。
- [ ] 无效/越界阈值安全处理；空检测列表不绘制、不伪造结果。
- [ ] 不修改 `NanoDetModelContract`、candidate threshold、decoder、推理服务和 `aggregatedSuggestion = null` 语义。

**验证：** 增加纯 JVM 阈值过滤测试，覆盖低于阈值、等于阈值、高于阈值、空列表。

**依赖：** 无。 **预计规模：** S。

### Task 2：确认页整图显示过滤

**描述：** 只过滤整图确认页的可视化框和详情，不删除或改写原始推理结果。

**验收标准：**

- [ ] `FullImageDetectionOverlay` 只绘制达到阈值的 `imageBox`，坐标映射和 EXIF/upright 语义不变。
- [ ] 检测详情列表与框使用同一过滤结果；摘要同时显示原始检出数、显示数、阈值和隐藏数。
- [ ] 人工总体 OK/NG、整图模式 fallback、`__FULL_IMAGE__` 结果包语义不变。

**验证：** Compose/JVM 测试断言 0.36/0.37/0.90 等边界的绘制输入和摘要数量。

**依赖：** Task 1。 **预计规模：** M。

### Task 3：采集 ZIP 与 Excel 记录闭环

**描述：** 让导出的 ZIP 明确包含照片和整图检测记录，并能追溯阈值过滤行为。

**验收标准：**

- [ ] ZIP 继续包含每个视角的原始照片及 `inspection_result.csv`，照片行的 `照片ZIP路径/照片状态` 与实际 ZIP entry 一致。
- [ ] `__FULL_IMAGE__` 的 CSV 检测行只保留 `score >= 0.37` 的检测，并记录分数、阈值、类别和 upright `imageBox`；低于阈值的检测不生成 CSV detection 行。
- [ ] 无检测、推理失败、照片缺失时仍生成可解释的照片/结果行，不伪造检测框；导出失败回收临时 ZIP。
- [ ] 不修改数据库实体、迁移、照片路径、ZIP 既有 entry 命名和人工确认结果。

**验证：** 扩展 `InspectionExcelExporterTest`、`InspectionZipExportServiceTest`，用临时 ZIP 解压断言照片 entry、`inspection_result.csv`、整图检测行、阈值和过滤状态。

**依赖：** Task 1；Task 2 可并行但最终一起验收。 **预计规模：** M。

### Checkpoint：软件回归

- [ ] 重点 JVM 测试通过。
- [ ] 全量 `:app:testDebugUnitTest` 通过，5 个既有 DPM skipped 原样记录。
- [ ] `:app:compileDebugKotlin`、`:app:assembleDebug` 成功。
- [ ] 未运行 ADB、instrumented、真机测试或 OCR；未提交 Git。

## 方案中的待确认项

1. 用户已确认阈值为现有业务阈值 **0.37**，不新增模型阈值。
2. 用户已确认 CSV 只保留达到阈值的检测记录；低分原始结果仍可保留在现有持久化 JSON 中，但不写入 CSV detection 行。
3. 用户已确认继续使用 Excel 兼容 CSV `inspection_result.csv`，不生成真正 `.xlsx`。

## 本轮边界

- 允许优先修改：`ViewConfirmationScreen.kt`、`InspectionExcelExporter.kt`、必要时 `ViewConfirmationViewModel.kt`、对应 UI/JVM/导出测试。
- 仅在确认现有导出字段不足时修改 `InspectionZipExportService.kt`；不修改 `detection/`、NanoDet 模型/decoder/阈值协议、CameraX、DPM、OCR、数据库实体和迁移、`mainImagePath`、`SessionRoiRegistry`、projected ROI 语义。
- 上一阶段未提交工作区必须先冻结/选择性提交，再开始本任务 Agent 修改，避免两轮差异混在一起。

## 当前状态

方案已确认；上一阶段未提交 diff 需要先按文件路径选择性提交或冻结，完成基线收口后再生成 mimo 执行指令。

---

## 上一阶段记录：V4 后续增强——模板加载 templateId 诊断日志与 fallback 整图检测框叠加

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 本轮 handback 只读门控修正审计结论（2026-09-22）

- 本地 XML 实际汇总为 `1201 tests / 0 failures / 0 errors / 5 skipped`；HTML `app/build/reports/tests/testDebugUnitTest/index.html` 实际计数同为 `1201 / 0 / 0 / 5`。重点测试为 `TemplateImageLoaderTest=39`、`ExifOrientationRegressionTest=7`、`FullImageDetectionOverlayTest=19`、`CaptureComparisonAutoNavigationTest=21`、`CaptureComparisonGeometryTest=21`，均为 0 failures / 0 errors。
- HTML 时间为 2026-09-22 15:28:49；APK 本地现场为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 15:28:56，232224821 bytes，SHA-256 `5AD754579320AEF172B9AFB3B6F02E37A0F26BF498B5DC6E4458DE051239128D`；与 handback 一致。
- `CaptureComparisonScreen` 保留拍后模板/现场/叠加比对页面；`ComparisonToolbar` 和视图拖动手势不再被 `readOnly` 粗暴隐藏或禁用。`readOnly=true` 仍作为语义标记传入，`SessionRoiOverlay` 为纯只读绘制，未删除页面、ViewModel、路由或 projected ROI 逻辑。
- `AppNavigation` 的 `onProceed` 仍先写入 `SessionRoiRegistry`，再导航 `ViewConfirmation`；未发现 registration、detection、CameraX、DPM、OCR、数据库实体、`mainImagePath` 或其他禁止范围源码改动。
- `git diff --check` 无实质 whitespace 错误，仅有 Windows LF→CRLF 警告；未运行 ADB、instrumented、真机测试或 OCR；未提交 Git。
- **结论：** 本轮软件源码、测试、编译和 APK 审计通过；仅保留 DPM 外部样本导致的 5 个 skipped，不阻塞本阶段。当前等待用户明确授权后选择性提交，不再生成新的 mimo 修正指令。

## 本轮 handback 最新主协调复核结论（2026-09-22）

- 本地 XML 实际汇总为 `1200 tests / 0 failures / 0 errors / 5 skipped`；HTML `app/build/reports/tests/testDebugUnitTest/index.html` 实际计数同为 `1200 / 0 / 0 / 5`。重点测试为 `TemplateImageLoaderTest=39`、`ExifOrientationRegressionTest=7`、`FullImageDetectionOverlayTest=19`、`CaptureComparisonAutoNavigationTest=20`，均为 0 failures / 0 errors。handback 写成 `1198`，不采信 handback 总数，必须按本地 XML/HTML 更正。
- APK 本地现场为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 15:13:10，232224821 bytes，SHA-256 `6680292CECDF53079296DA35C4467124C700AC2790613F6935AAF81FECA88AC4`；与 handback 一致。`git diff --check` 无实质 whitespace 错误，仅有 Windows LF→CRLF 警告。
- TemplateImageLoader 的 bounds 异常测试已使用 Mockito `ContentResolver` 首次抛异常、第二次返回 PNG 的真实分支；结构化日志字段由 `TemplateLogEntry` 断言。`invalid_path` 与 `invalid_dimensions` 仍采用源码结构测试，因当前 Robolectric 分支不可达，证据强度需在 handback 中明确说明。
- 拍照后 `CaptureComparisonScreen` 已恢复可见并由 `readOnly=true` 渲染，`onProceed` 先写入 `SessionRoiRegistry` 再进入 `ViewConfirmation`；实现未删除页面、ViewModel、路由或 projected ROI。该部分修正了此前“整页旁路”的产品偏差。
- **仍阻塞：** `readOnly` 当前通过 `if (!readOnly)` 隐藏整个 `ComparisonToolbar`，并通过 `if (readOnly) Modifier` 禁用整个比对视口的拖动。工具栏包含现场/模板/叠加、blink、透明度、视图缩放/重置等比对查看能力，视口拖动也是查看能力；这不是对 ROI 拖动、缩放、重绘入口的精确门控，超出了用户“保留对齐界面、只隐藏 ROI 人工编辑操作、不要删除”的要求。
- 因此本轮仍为 **SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION**，不提交当前代码。下一轮只需：更正 handback 为本地 `1200`；将只读模式收窄为仅隐藏/禁用 ROI 人工编辑入口，保留模板/现场/叠加比对及必要查看控件；同步更新对应源码结构测试和重新 handback。不得修改 V4 registration、NanoDet、CameraX、DPM、OCR、数据库、`mainImagePath`、`SessionRoiRegistry` 或 projected ROI 语义。

### 下一轮执行 Agent 指令（待用户转交 mimo）

```text
你只修当前工作区中的拍后对齐页面门控，不修改 V4 registration、NanoDet、CameraX、DPM、OCR、数据库、mainImagePath、SessionRoiRegistry 或 projected ROI 语义。

目标：保留拍照后的 CaptureComparisonScreen 模板/现场/叠加对齐界面；ROI 拖动、ROI 缩放、ROI 重绘等人工编辑操作暂时隐藏/禁用，但不要删除 CaptureComparisonScreen、CaptureComparisonViewModel、Screen.CaptureComparison、SessionRoiOverlay 或 projected ROI 逻辑。readOnly 不能粗暴隐藏整个 ComparisonToolbar 或禁用所有视口查看手势：现场/模板/叠加切换、blink、透明度、视图缩放/重置/平移等查看能力应按现有设计保留，只有真正属于 ROI 编辑的入口才门控。若当前页面不存在独立 ROI 编辑入口，请保留只读绘制和比对控件，不新增编辑实现。

同步更新 CaptureComparisonAutoNavigationTest，使其验证：页面可见、readOnly 只门控 ROI 编辑入口、比对查看控件未被误隐藏、onProceed 先写 SessionRoiRegistry 再导航 ViewConfirmation，CaptureComparisonScreen/route/ViewModel/projected ROI 未删除。

不要修改 tasks/todo.md、tasks/plan.md、docs/reports；不要提交 Git。只运行：
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks
./gradlew.bat :app:assembleDebug --no-daemon

handback 必须按本地实际 XML/HTML 报告汇总，尤其不要手填测试总数；提供实际修改文件、git diff --stat、git diff --check、git status --short --branch、重点 XML 统计、APK 时间/字节数/SHA-256，并说明未运行 ADB/instrumented/真机/OCR、未提交 Git。
```

## 本轮 handback v3 主协调复核结论（2026-09-22）

- 本地 XML/HTML 实际汇总为 `1198 tests / 0 failures / 0 errors / 5 skipped`；`TemplateImageLoaderTest=37`、`ExifOrientationRegressionTest=7`、`FullImageDetectionOverlayTest=19`、`ViewConfirmationModelResultComposeTest=6`、`ViewConfirmationViewModelStateTest=17`、`ViewConfirmationFlowTest=12` 均为 0 failures / 0 errors。handback 将 `FullImageDetectionOverlayTest` 写为 18，统计不一致，以本地 XML 为准。
- HTML：`app/build/reports/tests/testDebugUnitTest/index.html`，2026-09-22 14:44:11；APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 14:46:37，232975986 bytes，SHA-256 `998818FC9F75BC86436AD1CC0956D4407A226EA11030EDFDA527436A7ECF3CFA`。
- 当前代码实际为 7 个已修改源码/测试文件、2 个未跟踪测试文件；`tasks/` 和 `docs/reports/` 为主协调文档修改。`git diff --check` 无错误（仅 LF→CRLF 警告）。未发现禁止范围改动，未运行 ADB、instrumented、真机测试或 OCR，未提交 Git。
- **阻塞：** `TemplateImageLoaderTest.kt` 的 `bounds exception logs ...` 测试体为空；另一个 bounds 格式测试实际通过 `decodeFn` 验证 decode 日志，不能证明 bounds 异常分支被执行。`invalid_path`、`invalid_dimensions` 也没有对应稳定日志断言。
- **产品偏差未收口：** 用户已明确拍照后应保留模板/现场对齐界面，只隐藏 ROI 拖动、缩放和手动调整入口；当前 `AppNavigation` 仍自动跳过整个 `CaptureComparisonScreen`，该行为尚未修正。
- **结论：** 本轮仍为 `SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION`，不提交当前代码。下一轮只补齐真实 bounds/invalid-path/invalid-dimensions 日志测试并重新 handback。

## 本轮 handback v2 主协调复核结论（2026-09-22）

- 本地代码实际为 7 个已修改文件、1 个未跟踪测试文件；`tasks/` 和 `docs/reports/` 为主协调既有/本轮文档修改，仍与 Agent 代码统计分开。
- XML/HTML 本地实际汇总为 `1180 tests / 0 failures / 0 errors / 5 skipped`；重点为 `TemplateImageLoaderTest=26`、`FullImageDetectionOverlayTest=19`、`ViewConfirmationModelResultComposeTest=6`、`ViewConfirmationViewModelStateTest=17`、`ViewConfirmationFlowTest=12`，均 0 failures / 0 errors。5 个 skipped 仍来自 `DpmScannerTest` 外部样本/目录缺失。
- HTML：`app/build/reports/tests/testDebugUnitTest/index.html`，2026-09-22 14:00:18；APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 14:01:41，232974925 bytes，SHA-256 `5D900FF648BF844DF2C0618FA496AACC3955A5780816633F33D4A7607C4CBEC9`。
- `git diff --check` 无错误；未发现 registration、detection、DPM 或 OCR 禁止范围文件被修改；未运行 ADB、instrumented、真机测试或 OCR；未提交 Git。
- **阻塞 1：** `TemplateImageLoader.kt` 的失败分支实现已统一调用带 `templateId/stage/scheme/source/cause` 的日志函数，但 `TemplateImageLoaderTest` 仍未稳定捕获并断言空路径、无效路径、bounds、decode 等失败日志字段；当前新增测试只断言成功路径参数和取消异常传播。
- **阻塞 2：** `FullImageDetectionOverlay` 已改用 `RoiCoordinateMapper.loadUprightBitmap`，实现方向与推理端的 upright 语义一致；但当前测试没有使用真实 EXIF Orientation=6/8 文件调用该解码入口，也没有把解码后的方向/尺寸与 `imageBox` 坐标一致性做确定性断言。现有“竖拍图片”测试只是直接构造 upright Bitmap，不能闭合该证据。
- **结论：** 本轮仍为 `SOFTWARE_AUDIT_BLOCKED / AWAITING_CORRECTION`，当前代码不提交。只需补齐上述两项测试证据并重新 handback；不得扩展到 V4 生产语义或设备验证。

## 本轮 handback 审计结论（2026-09-22）

- 本地实际代码变化为 6 个已修改文件、1 个未跟踪测试文件；`tasks/` 和 `docs/reports/` 的修改是主协调本轮既有文档更新，不纳入 Agent handback 的代码统计。
- 本地 XML/HTML 实际汇总为 `1171 tests / 0 failures / 0 errors / 5 skipped`；重点测试均通过，5 个 skipped 仍来自 `DpmScannerTest` 外部样本/目录缺失。
- APK 现场为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 13:48:25，232224821 bytes，SHA-256 `7DCB552B5C25E733F6765BA7D0F711CDEBFA853E31B39DFF94D716888C4BAD4A`。
- 未发现 registration、detection、DPM 或 OCR 禁止范围文件被修改；未运行 ADB、instrumented、真机测试或 OCR；未提交 Git。
- **阻塞 1：日志覆盖不完整。** `TemplateImageLoader` 的空路径、无效文件路径等分支在返回前没有统一输出包含 `templateId` 的诊断日志；当前测试也未断言这些失败阶段的日志字段。
- **阻塞 2：整图照片方向语义未闭合。** `FullImageDetectionOverlay` 使用普通 `TemplateImageLoader` 解码现场照片，而 `fullImageInferResult.detections[*].imageBox` 是 upright 照片坐标；当 EXIF 不是 1 时，照片显示与检测框可能错位。必须复用或抽取现有 EXIF-aware upright 图片解码语义，不得假设所有现场图都是 `Orientation=1`。
- **阻塞 3：坐标测试证据不足。** `FullImageDetectionOverlayTest` 主要验证组件不崩溃，没有对像素坐标到 Compose 坐标的具体映射值、ContentScale 留白和边界框位置做确定性断言。
- 因此本轮 handback **不通过，不提交 Git**；下一轮应只修复上述问题并重新 handback。

## 当前任务目标

在不改变 V4 registration、NanoDet 推理语义和人工判定规则的前提下，完成两个独立的后续增强：

1. 模板加载失败日志补充 `templateId`，使现场日志可以直接定位具体模板；
2. fallback 整图检测在确认页显示整张照片上的检测框叠加，同时保留现有摘要和人工总体 OK/NG 流程。

## 任务拆分与验收标准

### Task 1：TemplateImageLoader 诊断日志补充 templateId

- [ ] `TemplateImageLoader` 接收可追踪的 `templateId`（允许可选，不能破坏现有路径加载 API 的兼容性）。
- [ ] 空路径、路径预检、bounds、decode、异常等日志阶段均能关联 `templateId`、路径类型、阶段和原始异常；取消异常仍必须传播，不得误报加载失败。
- [ ] `CameraPreview`、现场页模板参考图等调用入口传入正确模板 ID；不修改模板图片存储路径或 `mainImagePath` 语义。
- [ ] JVM 测试覆盖 template ID 传递/日志结构和现有纯路径、`file://`、`content://`、异常分类回归。

### Task 2：fallback 整图检测框叠加

- [ ] 仅在 `isFullImageMode` / `FALLBACK_FULL_IMAGE` 确认页绘制整图照片和检测框；优先复用现有 `fullImageInferResult.detections[*].imageBox`、`imageWidth`、`imageHeight`。
- [ ] 像素坐标到 Compose 显示坐标的映射必须正确处理图片缩放、留白和宽高比；框、类别、置信度与整图照片位置一致。
- [ ] 无检测、图片加载失败或推理失败时保持安全显示，不伪造检测框、模型 OK/NG 或 `aggregatedSuggestion`；人工总体 OK/NG 选择和 `__FULL_IMAGE__` 结果包语义不变。
- [ ] 非 fallback 的 projected ROI 检测页行为不改变；不新增 ROI 坐标回退或第二套结果实体。
- [ ] 增加 JVM/Compose 可行的坐标映射与 fallback 显示回归测试。

## 当前边界

- 允许修改：`TemplateImageLoader.kt`、`CameraPreview.kt`、`LiveInspectionScreen.kt`、`ViewConfirmationScreen.kt`、必要时的 `ViewConfirmationViewModel.kt`，以及对应测试文件。
- 禁止修改：V4 registration、NanoDet 模型/decoder/阈值/类别协议、`NanoDetRoiInferenceService` 推理语义、CameraX、DPM、OCR、数据库实体、`mainImagePath`、`SessionRoiRegistry` 和 projected ROI 坐标语义。
- 只运行 JVM 单测、`compileDebugKotlin`、`assembleDebug`；禁止 ADB、instrumented、真机测试、OCR 和 Git 提交。

## 当前检查点

- [ ] Task 1 完成后先回传 focused XML 和源码 diff。
- [ ] Task 2 完成后回传全量 XML/HTML、编译结果、APK 时间/大小/SHA-256 和 Git 状态。
- [ ] 主协调复核通过前不更新为软件审计通过，不提交 Git。

---

# 已完成任务：V4 RegistrationResult → NanoDet 检测集成和结果包

（历史完成记录；当前唯一任务见本文件顶部。）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 当前审计结论

- [x] 已复核实际差异：7 个已修改文件、3 个未跟踪文件；未改动 `registration/`、NanoDet、CameraX、DPM 或 OCR。
- [x] 本地 `testDebugUnitTest` XML/HTML 实际汇总：`1158 tests / 0 failures / 0 errors / 5 skipped`。
- [x] 5 个 skipped 均为 `DpmScannerTest` 外部样本/目录缺失：`frame960Decodes`、`capturedF26Decodes`、`batchDecodeCache20260820`、`userSpecifiedFrameDecodes`、`probeRootDumpCandidatesF23ToF37`。
- [x] `compileDebugKotlin`、`assembleDebug`：`BUILD SUCCESSFUL`。
- [x] APK 现场：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-22 12:24:53 +08:00，232963737 bytes，SHA-256 `C7FE3B140B381F2CF740A4B63DB0C08BBE49B46967CFB4C4D00C0F22E29693CC`。
- [x] `AppNavigation` 已改为把 `projectedRoisSnapshot` 写入 registry，NanoDet 检测交接不再直接读取可变 `sessionRois`。
- [x] 成功路径 UI 边界已收口：拍照后 `CaptureComparisonScreen` 只读显示 registration 自动 projected ROI；手动框选、拖动、缩放仅保留在模板 ROI 编辑阶段。
- [x] 结果包坐标边界已收口：`saveRoiConfirms()` 使用 projected 像素坐标；缺失时 fail-closed，不回退模板 `normalizedRect`。
- [x] registry 缺失、状态不一致、空投影、显式失败/fallback 和部分投影均 fail-closed 到整图，不再调用模板 ROI 检测。
- [x] 整图 `aggregatedSuggestion` 保持 null；确认页要求人工总体 OK/NG，不伪造模型 OK/NG。
- [x] 确认记录继续使用 `ViewRoiConfirmEntity`；`__FULL_IMAGE__` 仅作为已知 synthetic ROI 进入现有 ZIP/CSV 行，未知 ROI 不导出并记录 warning。
- [x] 目标回归套件：`V4NanoDetIntegrationTest=54`、`SessionRoiRegistryTest=9`、`ProjectedRoiBoundaryTest=6`、`InspectionZipExportServiceTest=17`、`ViewModelSaveLifecycleTest=4`、`CaptureComparisonGeometryTest=21`，本地 XML 均 0 failures / 0 errors。
- [x] 未运行 ADB、instrumented 或真机测试；软件审计通过。V4 基线已选择性提交为 `a690fa15`，本轮模板加载和自动导航修正仍未提交，等待最终用户验收及 Git 授权。
- [x] `AppNavigation.kt` 已隐藏拍照后的 `CaptureComparison` 可见 UI；registration 完成后写入 projected snapshot/状态并自动进入 `ViewConfirmation`，保留原有路由、ViewModel 和 registry。
- [x] `CameraPreview` 与 `TemplateContent` 已共用 `TemplateImageLoader`；支持纯路径、`file://`、`content://`，两遍独立流解码并传播 `CancellationException`。
- [x] 最终 handback 实际统计：`TemplateImageLoaderTest=23/0/0/0`、`NoRoiViewAdvancementTest=18/0/0/0`、`CaptureComparisonAutoNavigationTest=20/0/0/0`；全量 `1158/0/0/5`。自动导航测试为源码结构测试，未替代禁止运行的 instrumented/真机验证。
- [x] 用户已确认完成真实设备验收：Compose `LaunchedEffect` 自动导航、`NavController.popUpTo` 栈清理、`SessionRoiRegistry` 运行时写入、失败/fallback 整图检测和模板图片加载均已在现场确认。
- [x] 用户已明确授权当前修正按文件路径选择性提交 Git；已提交为 `aec66356`。DPM 的 5 个 skipped 仍因外部样本/目录缺失保留，不影响本次用户验收结论。

详细审计见 [`docs/reports/b3/V4_REGISTRATION_NANODET_INTEGRATION_AUDIT_20260921.md`](../docs/reports/b3/V4_REGISTRATION_NANODET_INTEGRATION_AUDIT_20260921.md)。

## 当前交付边界

1. 只修当前集成闭环；不得修改 V4 registration、NanoDet 模型/decoder/阈值/类别协议、CameraX、DPM、OCR。
2. 使 `FAILED`/`FALLBACK_FULL_IMAGE` 从比对页可达确认页；路由必须传真实 fallback 状态，Session ROI 缓存失败态不得被当作模板 ROI。
3. 手动框选/拖动/缩放只发生在模板 ROI 编辑阶段；拍照后比对页只显示 registration 自动产生的 projected ROI 快照。缓存缺失、状态不一致或投影失败 fail-closed 到整图，不调用模板 ROI 检测，也不在保存时回退到模板坐标。
4. 整图结果仅作为整图证据和人工复核输入；检测不足、无检测或类别/数量有歧义时不得生成模型 OK/NG 建议。
5. 复用 `ViewRoiConfirmEntity`、既有照片字段、路径和导出模型；让 `__FULL_IMAGE__` 记录在 ZIP/CSV 中可追溯，不创建第二套实体。
6. `roiPixelRect` 已与实际拍照后 projected ROI 同源，并有成功路径字段回归；模板 ROI 的手动编辑不属于拍照后检测输入；DPM 绑定码切件人工验收已由用户确认完成；OCR 真实钢印样品验收明确延期，除非用户再次提出，不得创建或启动该任务；Git 仍等待用户明确授权。

7. 本轮新增边界：暂时隐藏拍照后的 `CaptureComparison`/Session ROI 比对 UI，但不得删除 `CaptureComparisonScreen`、`CaptureComparisonViewModel`、`Screen.CaptureComparison` 或 projected ROI 逻辑；隐藏路径仍必须先完成 registration 并把 projected snapshot 写入 registry，再进入确认页。
8. 现场采集模板图片修正边界：修复 `CameraPreview`/模板参考图的图片路径与解码；支持当前 App 私有绝对路径及仍可能存在的 `content://`/`file://` 路径，区分不存在、不可读、解码失败和异常；不得修改 CameraX、V4 registration、NanoDet、DPM 或 OCR。

## 后续未完成项（不自动启动）

- V4 → NanoDet 软件审计和用户验收均已完成；当前修正按用户授权进行选择性 Git 收口。除非另有明确授权，不运行新的 ADB、instrumented 或真机测试。
- **legacy ROI 迁移：关闭，不纳入后续任务。** 用户已确认不需要兼容历史旧格式的单个 `roi` 字段；只维护当前 App 新格式 `rois[]` 的导入导出闭环。新格式中的 ROI 名称、`normalizedRect` 坐标、属性、`enabled` 状态和顺序属于当前支持范围。
- **`imageFiles[]` 多图处理：移出任务清单。** 当前产品约束为“一个视角对应一张主模板图”；`imageFiles[]` 仅保留模板包格式兼容，当前每个视角写入一个图片元素并落到 `mainImagePath`，不开发一个视角多图切换、多图配准或多图导出。
- **模板 EXIF：采用“先取证、必要时再修正”的条件方案。** 第一阶段只检查实际模板/采集图片的原始宽高、EXIF `Orientation`，以及模板显示、ROI 和检测位置是否一致。若原始像素为竖向、`Orientation = 1` 且位置一致，则关闭 EXIF 风险，不创建代码任务；若原始像素为横向、`Orientation = 6/8` 或发现显示、ROI、检测坐标不一致，则必须启动并实施全链路 EXIF upright 代码修正，而不只是记录问题。修正范围包括统一 EXIF-aware 解码、模板编辑/预览叠加/配准/ROI 映射/检测入口的方向语义，明确 normalized ROI 使用 upright 坐标，并补充方向与坐标回归测试。不得只修改单一图片加载器造成重复旋转或坐标语义分裂。
- 更大独立数据集上的 NanoDet 阈值和现场鲁棒性验证：属于交付后的增强验证，不阻塞当前版本交付。
- 更完整的 manifest/Excel/模型框结果包扩展：后续独立任务，暂不启动。
- OCR 真实钢印样品拍照、识别和人工确认：**明确延期**；除非用户明确提出，否则不得安排 agent、设备测试或代码修改。

---

# 历史任务：V1-3 静态拍后模板与实拍比对页面

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 验收清单

- [x] 只读审计结论已写入计划：图片存储、`CapturedPhotoEntity`、`MobileImageStore`、`RoiCoordinateMapper`、EXIF、`contentRect`、确认页与结果链路。
- [x] 模板图/现场照片切换。
- [x] 透明度叠加与 blink。
- [x] 缩放和平移。
- [x] Session ROI 人工拖动与四角缩放；状态不写回模板数据库。
- [x] 复用既有 `RegistrationResult`，只做单张静态加载配准；失败时不伪造 Homography/投影结果，且不得继续使用未经配准验证的 ROI。
- [x] 有 ROI 拍照路径进入比对页，再进入既有人工确认页；不改变无 ROI 推进和结果导出。
- [x] JVM 测试、Kotlin 编译、Debug APK 构建通过。
- [x] 返回真实测试 XML 统计、APK 时间/大小/SHA-256 和 Git 状态。
- [x] 未运行 ADB、instrumented 或真机测试；主协调已选择性提交当前任务 Git。

## 完成实测

- 全量 XML：`1036 tests / 0 failures / 0 errors / 5 skipped`。
- 新增 `CaptureComparisonGeometryTest.xml`：`19 / 0 / 0 / 0`（含 8 项 `canProceedToConfirmation` 门禁覆盖）。
- V4 XML：`RegistrationQualityGatesTest=35`、`GmsGridFilterTest=7`、`PhotoRegistrationEngineTest=19`，均 `0 failures / 0 errors / 0 skipped`。
- Session ROI 是页面会话态，按边界不回写模板 ROI 或确认结果；失败态和混合投影的全量一致性策略已修复。

## ~~主协调审计阻塞（2026-09-21）~~ — 已解除

- `buildSessionRois()` 已改为全量一致性：配准失败、`FALLBACK_FULL_IMAGE` 或任一 ROI 投影失败均返回空列表；失败态继续按钮已禁用。
- ~~当前阻塞~~ 已解除：已抽取 `CaptureComparisonViewModel.canProceedToConfirmation()` 纯门禁函数，`canProceed` 属性委托调用，`simulateCanProceed` 已删除，19 项测试直接调用生产共享函数。
- 三条 Gradle 命令均通过；主协调已选择性提交当前任务 Git，当前等待用户验收。

## 实施顺序

1. 纯函数 Session ROI 几何与 JVM 测试。
2. 静态图片/EXIF/照片关联 ViewModel 与 `RegistrationResult` 消费。
3. Compose 比对页面交互。
4. 导航接入现有确认页。
5. 按用户指定命令验证并整理交付报告。

## 禁止范围

NanoDet、检测阈值、结果判定、ZIP/CSV、CameraX、DPM、OCR、实时配准、ROI 自动跟踪、ALIKED、LightGlue，以及 V4 registration 引擎源码。

---

# 历史任务：V4/AKAZE 单张照片配准引擎

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

本任务是 V1-3”拍后模板与实拍比对 MVP”的底层配准引擎切片。当前只实现静态单张照片的 V4/AKAZE 配准、几何质量门禁、模板 ROI 四角投影和失败状态输出；不实现完整 CaptureComparisonScreen，不启动实时相机或新检测算法。

任务提报见 [`V4_AKAZE_REGISTRATION_TASK_PROPOSAL.md`](V4_AKAZE_REGISTRATION_TASK_PROPOSAL.md)。

## 实现完成报告（2026-09-21）

### 实际修改文件（5 个生产 + 3 个测试）

**生产代码（5 个）：**
- `app/src/main/java/com/wearable/inspection/mobile/registration/RegistrationResult.kt` — 数据类（RegistrationResult、ProjectedPoint、RegistrationStatus 枚举、自定义 equals/hashCode）
- `app/src/main/java/com/wearable/inspection/mobile/registration/RegistrationConfig.kt` — 集中配置（AKAZE/Lowe/GMS/USAC/质量门禁全部阈值）
- `app/src/main/java/com/wearable/inspection/mobile/registration/GmsGridFilter.kt` — 纯 Kotlin GMS 网格滤波（O(N) 分箱、3×3 邻域支持度计算）
- `app/src/main/java/com/wearable/inspection/mobile/registration/RegistrationQualityGates.kt` — 质量门禁（8 项检查：内点数/比例、重投影误差、空间覆盖率、凸性、面积、边界、NaN/Inf）
- `app/src/main/java/com/wearable/inspection/mobile/registration/PhotoRegistrationEngine.kt` — 主引擎（AKAZE→BFMatcher+Lowe→GMS→USAC_MAGSAC Homography→门禁→ROI 投影）

**测试代码（3 个）：**
- `app/src/test/java/com/wearable/inspection/mobile/registration/RegistrationQualityGatesTest.kt` — 35 纯逻辑测试
- `app/src/test/java/com/wearable/inspection/mobile/registration/GmsGridFilterTest.kt` — 7 纯逻辑测试
- `app/src/test/java/com/wearable/inspection/mobile/registration/PhotoRegistrationEngineTest.kt` — 19 OpenCV 集成测试

### 测试结果

```
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain --tests "com.wearable.inspection.mobile.registration.*"
```

**61 tests completed, 0 failed**

| 测试类 | 项数 | 结果 |
|---|---|---|
| RegistrationQualityGatesTest | 35 | 全部通过 |
| GmsGridFilterTest | 7 | 全部通过 |
| PhotoRegistrationEngineTest | 19 | 全部通过 |
| **注册模块定向测试** | **61** | **全部通过** |

### 编译与构建

| 命令 | 结果 |
|---|---|
| `compileDebugKotlin` | BUILD SUCCESSFUL |
| `testDebugUnitTest` | BUILD SUCCESSFUL |
| `assembleDebug` | BUILD SUCCESSFUL |

### 算法与门禁实现

**主路径：**
1. AKAZE 特征提取（MLDB 描述子，阈值 0.002，最大 1000 特征按 response 截断）
2. BFMatcher KNN + Lowe ratio（ratio=0.75，最少 10 good matches）
3. GMS 网格滤波（20×20 网格，支持度阈值 6，匹配数 ≥24 时启用）
4. Homography 估计（USAC_MAGSAC，阈值 8px，500 迭代，置信度 0.995）
5. 质量门禁（8 项顺序检查）
6. ROI 四角投影（perspectiveTransform）

**质量门禁阈值：**
- 最少内点：10
- 最小内点比例：0.30
- 最大中位重投影误差：8.0px
- 最小空间覆盖率：0.15
- 最小投影面积比：0.005
- 最大投影面积比：0.95
- 图像边界容差：50px

**失败语义：**
- 配准失败 → `RegistrationStatus.FAILED` 或 `FALLBACK_FULL_IMAGE`
- 失败时 `homography=null`、`projectedRoiCorners=null`
- 禁止返回看似有效的错误投影坐标

### 未完成项

- 无。本任务所有要求均已实现并验证。

### 已知限制

1. GMS xfeatures2d 在 Android OpenCV SDK 和桌面 openpnp 均不可用，已用纯 Kotlin 替代实现
2. 旋转角度 >15° 时配准成功率下降（已知风险，属于后续优化范围）
3. 桌面 OpenCV 版本为 4.9.0-0（openpnp），Android 为 4.10.0，算法行为一致但版本号不同
4. 未运行 ADB/真机测试（按任务边界禁止）

### Git 状态

本报告 handback 时未提交；主协调完成独立审计后按当前任务路径选择性提交。工作区包含新增的 `registration/` 目录（5 个生产文件 + 3 个测试文件）。

### 主协调收口审计（2026-09-21）

- 独立复核源码与测试：Mat 成功/失败/异常路径释放，确定性 `FALLBACK_FULL_IMAGE`、ROI 空值和失败原因断言，`MATCHER_VERSION` 为 `opencv-4.10.0`。
- 独立复跑 `:app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain`：1020 tests / 0 failures / 0 errors / 5 skipped；V4 XML 为 61 / 0 / 0 / 0。
- 独立复跑 `:app:compileDebugKotlin --no-daemon --rerun-tasks` 与 `:app:assembleDebug --no-daemon`：均 `BUILD SUCCESSFUL`。
- 独立核对 APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-21 14:09:03 +08:00，232151892 bytes，SHA-256 `CC09EFC49096EA10C0F9FD7F89364A67B6F2ED2D57D0A98F7CBE541B80F315C1`。
- 未运行 ADB、instrumented 或真机测试；当前仍为 `SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE`。
- 本轮仅收口 V4/AKAZE 生产代码、测试和文档；不接入 V1-3 页面、NanoDet、CameraX 或既有导出链路。

---

## 当前任务边界

- 输入：模板参考图、现场采集照片、模板 ROI 的规范坐标，以及必要的旋转/图像区域信息。
- 主路径：AKAZE → BFMatcher/Lowe ratio → GMS（当前 OpenCV 能力允许时）→ Homography → 内点/覆盖率/重投影误差/四边形质量门禁。
- 成功：输出稳定的配准结果和投影后的 ROI 四角；不得直接在配准引擎内运行 NanoDet。
- 失败：明确输出失败原因或 `FALLBACK_FULL_IMAGE` 建议，禁止使用不可靠的映射 ROI；整图 NanoDet 兜底由后续检测集成任务负责。
- 允许新增配准领域结果对象，但不得创建第二套 ROI、照片、检测结果或确认实体。

## 执行 Agent 指令

完整指令见 [`V4_AKAZE_REGISTRATION_AGENT_INSTRUCTION.md`](V4_AKAZE_REGISTRATION_AGENT_INSTRUCTION.md)。执行 Agent 必须：

1. 先审计现有图片存储、照片旋转、`contentRect`、`RoiCoordinateMapper`、模板 ROI 和 OpenCV 依赖，再决定实际文件范围。
2. 只修改 MobileInspectionApp；旧 `Wearable Inspection` 只读参考，不提交 Git，不运行 ADB/真机。
3. 补齐静态配准 JVM 测试：同图、平移/缩放/旋转/轻微透视、弱纹理/无匹配、各质量门禁、投影四边形非法和 fallback 语义。
4. 真实报告中列出修改文件、测试命令、未完成项、OpenCV 能力差异和 Git 状态；不得把设计完成写成实现完成。

## 明确不做

- ALIKED + LightGlue、双方案 fallback、新模型运行时。
- 实时轮廓、实时姿态匹配、自动 `ALIGNED/LOST` 门禁、ROI 自动跟踪、新 CameraX。
- NanoDet 算法、模型、阈值、全图检测业务判定和人工最终结果语义。
- 完整 V1-3 比对 UI、Session ROI 拖动 UI、ZIP/CSV 导出扩展。

## 当前任务完成后的后续顺序

1. V4/AKAZE 引擎及 JVM 回归通过。
2. 再实现 V1-3 CaptureComparisonScreen：切换、叠加、blink、缩放、平移和 Session ROI 人工微调。
3. 最后由独立任务把配准结果接入现有 NanoDet ROI 检测和结果包，仍需单独验证。

## 未来真机验收门禁（仅在任务明确授权时使用）

- 新工程只允许显式安装并启动 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`；不得使用桌面图标、最近任务、`monkey` 或省略组件名的启动方式。
- 新旧包必须先停止并核对 `com.wearable.inspection.mobile` PID、旧包无 PID、前台 Activity 为新包；任何前台落到旧包的证据全部作废。
- `connectedDebugAndroidTest` 结束后，无论成功或失败，都必须重新安装当前主 APK、显式启动并复核包名和前台状态后，才能继续采集证据。

---

# 已完成任务：既有 14 项 JVM 失败整改

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

本任务只处理上一项 NanoDet Android 回归中记录的既有 JVM 测试失败。不得把这些失败隐藏、删除、改成 skipped，或借此重开已验收的 NanoDet、ROI 人工改判、DPM、OCR、CameraX 或批次清理任务。执行 Agent 必须先在当前工作区重现并读取实际 JUnit XML；下面的失败清单是 2026-09-20/21 的基线，不替代当前复跑结果。

## 当前基线：14 个失败

总基线：`959 tests completed / 14 failed / 5 skipped`。失败测试及当时首要症状如下：

1. `MultiViewPhotoPersistenceTest` ×3
   - `captured photos table has no unique index on viewIndex per batch`：读取 `app/schemas/.../AppDatabase/6.json` 时路径不存在。
   - `all views captured card uses a compact fixed height`：完成卡片文字区域未满足紧凑固定布局契约。
   - `capture state resets to IDLE before roi confirmation navigation`：拍照状态复位顺序不满足导航前复位契约。
2. `TemplatePackageExporterTest` ×1
   - `导出包可被现有导入解析器完整读取`：ROI JSON 字段顺序与当前测试契约不一致；必须先确认这是稳定格式契约还是脆弱的字符串比较。
3. `TemplatePackageImporterTest` ×1
   - `损坏 zip - 返回可读错误`：测试期望“不是有效的 ZIP”，实际得到“模板包缺少 template.json”；需区分损坏 ZIP 与缺少必需条目。
4. `ViewConfirmationNavigationTest` ×2
   - `LiveInspection receives onNavigateToExport callback`：导航未传递 `onNavigateToExport` 回调。
   - `onBack only pops back stack without advancing`：确认页 `onBack` 契约未满足。
5. `BatchFilterAndDeleteTest` ×1
   - `trace records export message reserves a fixed single line slot`：导出结果提示未使用固定高度。
6. `CameraPreviewTest` ×1
   - `现场采集页将可见状态传给 CameraPreview`：不可见时相机断开被误报为连接失败。
7. `NoRoiViewAdvancementTest` ×3
   - `live inspection source prevents view completion when photo insert fails`：缺少插入失败的 `catch` 分支契约。
   - `zip keeps all photos including views without roi confirms`：导出未遍历全部照片。
   - `live inspection source prevents view completion on capture failure`：缺少拍照失败的 `onFailure` 分支契约。
8. `ViewConfirmationPerformanceTest` ×1
   - `photo dimensions and roi crops are loaded on IO dispatcher`：照片尺寸/ROI 裁剪读取未满足 IO 调度契约。
9. `WorkbenchViewModelAdvanceTest` ×1
   - `selectPart reloads templates and rois from the new part`：切换零件后当前 part 未正确更新为 `p2`。

## 有序执行与验收门槛

1. **基线复现与分组**：只读检查当前 `git status`，运行全量 JVM，保存 Gradle 输出和每个失败 XML；确认失败数量、方法名和栈与上表一致或记录漂移。
2. **数据/模板契约**：先处理 Room schema 测试夹具/路径、模板导出字段契约、损坏 ZIP 错误语义；不通过放宽断言或删除测试掩盖问题。若测试契约本身错误，必须以现有产品兼容性和导入导出行为为证据后再最小修正测试。
3. **导航/采集流程**：修复确认页返回与导出回调、无 ROI 视角推进和失败分支、零件切换后的模板/ROI 重载；保持多 View、批次、照片稳定关联和失败不推进语义。
4. **布局/相机/性能**：修复固定提示槽位、完成卡片布局、CameraPreview 不可见状态和确认页 IO 调度；不得改变 CameraX 所有权、相机模式、contentRect 或已验收的相机生命周期语义。
5. **全量收口**：定向测试、全量 JVM、编译回归均通过；5 项 skipped 必须逐项报告原因，不得伪装成通过。若发现前序已验收能力回归，立即暂停并将状态改为“回归整改中”。

## 本任务验收标准

- [x] 上述 14 项失败逐项有根因、最小修复和当前 JUnit XML 证据；整改前失败 XML 未在当前工作区留存，已在报告中明确标注。
- [x] `:app:testDebugUnitTest --no-daemon` 全量 JVM 为 0 failures、0 errors；5 skipped 的名称和原因明确记录。
- [x] 相关定向测试重复通过，`compileDebugKotlin` 与 `assembleDebug` 通过；本任务未运行 ADB/真机测试。
- [x] 不修改旧 `Wearable Inspection` 工程，不改 NanoDet 模型/阈值/协议，不改 DPM/OCR/批次清理，不重开已验收 ROI 结果任务。
- [x] 报告列出实际修改文件、测试命令/结果、当前 XML、未解决项和 Git 状态；执行 Agent 未提交 Git，主协调已完成选择性提交 `86d1ebd2`。

## 主协调复核结果（2026-09-21）

- 全量 XML：`959 tests / 0 failures / 0 errors / 5 skipped`。
- 失败相关定向 XML：`206 tests / 0 failures / 0 errors / 0 skipped`。
- `compileDebugKotlin`：`BUILD SUCCESSFUL`。
- `assembleDebug`：`BUILD SUCCESSFUL`；用户报告 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，大小约 `222 MB`；本报告不伪造未提供的 SHA-256。
- 证据报告：`docs/reports/b3/JVM_REGRESSION_DEBT_REMEDIATION_REPORT.md`。
- 用户已确认整改完成；本任务不宣称新增 ADB、真机或视觉验收。

---

# 已验收任务：NanoDet exp09 四分类 Android 协议、BOLT/NUTSERT 检测路由与阈值校准

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

- 桌面 parity（yolov12 环境）：✅ 通过（PT vs ONNX max=1.22e-05, NCNN vs ONNX max=5.80e-06, IoU≥0.999）
- Task 2A NCNN 转换（ncnn_py311 环境）：✅ 已完成（用户授权环境例外）
- dinov2 环境证据补正：❌ 阻塞（用户已授权采用现有产物，不再重复转换）
- Android 34→36 协议升级：✅ 已完成（2026-09-20 Task 2B）
- B1（ONNX 转换）：✅ 已完成
- B2（NCNN 转换）：✅ 已完成
- B3（桌面 parity）：✅ 已通过
- B4（Android parity）：✅ 通过（2026-09-20；NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4；设备 YAL-AL10）
- Task 3（BOLT/NUTSERT 路由）：✅ 软件完成（2026-09-20）

本任务以 NanoDet 报告 `D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp09_retrain_nutsert_4class_baseline\experiment_report_4class.md` 为事实证据，不把报告中的历史建议直接当作执行结论。exp09 固定四分类协议为 `0=nut`、`1=thread`、`2=bolt`、`3=nutsert`；当前 Android 二分类输出宽度 `34` 必须同步审计为四分类输出宽度 `36`，预期 ONNX 为 `[1,3598,36]`、NCNN 为 `[3598,36]`，实际形状以转换产物和运行时证据为准。

产品决定：`BOLT` 和 `NUTSERT` 纳入 ROI 属性选择、稳定存储和检测路由；`FEATURE` 保留为产品属性，但本任务暂不执行检测。阈值 `0.20` 只作为 exp09 外部集的阶段性候选基线，不是最终现场校准结论。当前只推进这一项，不重新打开已验收的 ROI 人工改判/证据图任务，不修改旧 Wearable Inspection 工程。

## 执行任务清单

1. **四分类协议审计与冻结**
   - 审计 `NanoDetModelContract`、模型资产路径、预处理、JNI/NCNN 输出读取、decoder、DFL 解码、类别索引、NMS、结果映射及现有二分类测试。
   - 明确并测试输出契约：ONNX 预期 `[1,3598,36]`，NCNN 预期 `[3598,36]`；不得只替换 `.param/.bin`。
   - 固定类别映射 `0=nut`、`1=thread`、`2=bolt`、`3=nutsert`，禁止类别顺序漂移或以展示文本代替稳定值。

2. **exp09 模型转换、NCNN parity 与 Android 推理一致性**
   - ✅ 生成并审计 exp09 的 ONNX/NCNN 转换产物，记录输入输出名称、shape、版本、文件大小和 SHA-256。（Task 2A 2026-09-20 完成）
   - ✅ 使用相同输入和相同预处理，对 PyTorch/ONNX/桌面 NCNN 的原始输出、解码框、类别、分数和 NMS 结果做逐级对照。（Task 2A 2026-09-20 完成；Android parity 2026-09-20 通过：NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4，设备 YAL-AL10）
   - ✅ 对四个类别分别核对输出列含义、DFL 维度、坐标映射和阈值前候选保留，形成可复核的 parity JSON/日志证据。（Task 2A 2026-09-20 完成）

3. **ROI 属性和检测路由扩展**
   - ✅ 审计 `RoiTargetType`、实体/DAO/Repository、编辑 UI、模板导入导出和历史 ROI 兼容路径；按现有模型增加稳定枚举 `BOLT`、`NUTSERT`，并提供中文展示名”螺栓/铆螺母”。
   - ✅ 建立 `NUT → nut`、`THREAD → thread`、`BOLT → bolt`、`NUTSERT → nutsert` 的显式路由；`FEATURE` 明确返回”不支持检测/模型未执行”，不得套用其他类别、默认判 NG 或伪造检测结果。
   - ✅ 保证属性按 `templateId`、View、图片和 ROI 独立持久化；审计确认 TEXT 列兼容新枚举，无需新增 migration。
   - **状态**：✅ SOFTWARE_COMPLETE（2026-09-20）。详见下方 Task 3 完成报告。

4. **阈值校准与数据证据**
   - 以 `0.20` 作为阶段性基线，至少比较 `0.10/0.15/0.20/0.25/0.30` 等候选阈值，按类别统计 TP、FP、FN、precision、recall、漏检和误检。
   - 将 `thread`、`nutsert`、`bolt`、`nut` 分开报告；exp09 的 bolt 在冻结外部集无真实标注，不能从该集合推出 bolt 的召回/精度结论。
   - 补充更多独立真实外部数据，控制 source group 泄漏；在样本规模和现场代表性不足前，阈值状态保持“阶段性候选”，不得宣称最终校准或现场泛化完成。

5. **Android 回归与收口验收**
   - ✅ 更新 decoder、输出契约、类别路由和测试，覆盖 `[3598,36]` shape 门禁、四分类解码、空检测、低分候选、FEATURE 不执行、ROI 属性隔离、模型加载失败和旧数据兼容。
   - ✅ 运行 JVM/Repository/UI 相关回归、compile、assemble；按新包名门禁执行 Android 推理一致性和必要的 instrumented/设备日志验证。
   - ✅ 收集 XML、日志、模型/APK 路径、构建时间、大小、SHA-256、数据库和 ZIP（若本任务触及归档）的结构化证据；视觉结论仍由用户人工完成。
   - 执行 Agent 不提交 Git；完成后更新本清单、`tasks/plan.md` 和 B3 报告，等待主协调审计及用户验收。
   - **状态**：✅ REGRESSION_PASS（2026-09-20）。详见下方 Task 5 回归报告。

## Task 3 完成报告：BOLT/NUTSERT ROI 属性与检测路由（2026-09-20）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 实际修改文件（9 个）

**生产代码（4 个）：**
- `app/src/main/java/com/wearable/inspection/mobile/data/entity/RoiTargetType.kt` — 新增 `BOLT("螺栓")`、`NUTSERT("铆螺母")` 枚举值，更新文档注释
- `app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt` — `NanoDetDecisionPolicy.classIndex()` 新增 `BOLT→2`、`NUTSERT→3` 显式映射
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt` — `modelClassLabel()` 新增 `2→"螺栓（类别 2）"`、`3→"铆螺母（类别 3）"`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt` — `softwareTargetClass` 映射新增 `2→"BOLT"`、`3→"NUTSERT"`

**测试代码（5 个）：**
- `app/src/test/java/com/wearable/inspection/mobile/data/entity/RoiTargetTypeTest.kt` — 枚举数量 3→5，新增 BOLT/NUTSERT 值/fromName/displayName 测试，更新映射表
- `app/src/test/java/com/wearable/inspection/mobile/detection/NanoDetInferenceContractTest.kt` — classIndex 映射新增 BOLT→2/NUTSERT→3，新增 BOLT/NUTSERT 路由测试（阈值通过/不通过/无候选）
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationModelResultTest.kt` — 新增 BOLT/NUTSERT `softwareTargetClass` 映射测试
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModelStateTest.kt` — 新增 BOLT/NUTSERT ROI 定义、默认选中、targetClass 持久化测试
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiEditorViewModelTest.kt` — 枚举数量 3→5，新增 BOLT/NUTSERT fromName/displayName 测试

### Migration 审计结论

**不需要 migration**。`RoiDefinitionEntity.targetType` 和 `view_roi_confirms.roiTargetType` 均为 SQLite TEXT 列，nullable。枚举新增值通过 `RoiTargetType.name` 字符串存储，`fromName()` 对未识别值返回 null（优雅降级）。现有 THREAD/NUT/FEATURE 行不受影响。

### 检测路由

| RoiTargetType | classIndex | 类名 | 决策 |
|---|---|---|---|
| NUT | 0 | nut | 筛选 class 0 候选，阈值判定 OK/NG |
| THREAD | 1 | thread | 筛选 class 1 候选，阈值判定 OK/NG |
| BOLT | 2 | bolt | 筛选 class 2 候选，阈值判定 OK/NG |
| NUTSERT | 3 | nutsert | 筛选 class 3 候选，阈值判定 OK/NG |
| FEATURE | null | — | `FEATURE_UNSUPPORTED`，不执行检测，不默认 NG |
| null | null | — | `ROI_NOT_CONFIGURED`，不执行检测 |

### 测试结果

**定向 JVM 测试（5 类）：全部通过**
- RoiTargetTypeTest: 12/12 通过（枚举数量 5、BOLT/NUTSERT 值、fromName、displayName）
- NanoDetInferenceContractTest: 19/19 通过（四类 classIndex、BOLT/NUTSERT 路由、FEATURE/unset）
- ViewConfirmationModelResultTest: 10/10 通过（BOLT/NUTSERT targetClass 映射、状态标签）
- ViewConfirmationViewModelStateTest: 17/17 通过（BOLT/NUTSERT 默认选中、targetClass 持久化、FEATURE/无推理不选中）
- RoiEditorViewModelTest: 65/65 通过（枚举数量 5、BOLT/NUTSERT 属性选择/保存/隔离）

**编译：**
- `compileDebugKotlin` ✅ BUILD SUCCESSFUL
- `compileDebugAndroidTestKotlin` ✅ BUILD SUCCESSFUL
- `assembleDebug` ✅ BUILD SUCCESSFUL

**全量回归：** 959 tests completed, 14 failed, 5 skipped。失败均位于本轮未修改的既有功能范围；Task 3 定向测试未失败（MultiViewPhotoPersistenceTest×3、TemplatePackageExporterTest、TemplatePackageImporterTest、ViewConfirmationNavigationTest×2、BatchFilterAndDeleteTest、CameraPreviewTest、NoRoiViewAdvancementTest×3、ViewConfirmationPerformanceTest、WorkbenchViewModelAdvanceTest）。

**本轮 APK：** `app/build/outputs/apk/debug/app-debug.apk`，2026-09-20 19:23:23，232,126,458 bytes，SHA-256 `B3C6E2BA45C058362BCA2205883425F471912ADE1C9004EB31130611E0D260FF`。未执行 ADB、connectedDebugAndroidTest 或真机视觉验收。

### 模板兼容性

- **编辑 UI**：`RoiEditorScreen` 使用 `RoiTargetType.entries.forEach` 动态构建下拉菜单，新增 BOLT/NUTSERT 自动出现
- **导入/导出**：`TemplatePackageExporter`/`TemplatePackageImporter` 使用 `RoiTargetType.name` 原始字符串序列化/反序列化，无需修改
- **历史数据**：旧模板的 THREAD/NUT/FEATURE 值保持不变，`fromName()` 正确解析
- **确认页**：`ViewConfirmationScreen` 使用 `RoiTargetType.fromName()?.displayName` 显示，BOLT/NUTSERT 自动显示中文名
- **同一 View 不同 ROI**：每个 ROI 独立存储 `targetType` 字符串，可独立使用不同属性

### 不修改的组件

CameraX、DPM、OCR、NanoDet decoder/DFL/NMS、模型资产、阈值策略和阈值校准、批次清理、ZIP 结构、人工改判逻辑、旧 Wearable Inspection 工程 — 全部未触及。

### 未完成项

- Task 4（阈值校准）：✅ 离线分析完成并经主协调审计（2026-09-20）
- Task 5（Android 回归）：✅ 已完成，详见下方 Task 5 回归报告
- Task 4 Git 收口范围：仅本报告、`tasks/todo.md`、`tasks/plan.md`；不纳入其他工作区改动

## Task 4 完成报告：NanoDet exp09 阈值校准与数据证据（2026-09-20）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 概要

对 exp09 四分类模型在冻结外部测试集（6 张图、8 个 GT 对象）上，按 5 个候选阈值（0.10/0.15/0.20/0.25/0.30）统计 nut/thread/bolt/nutsert 的 TP/FP/FN/precision/recall。**0.20 是能实现零 FP + 零 FN 的最低阈值**（对有 GT 的三类），确认为阶段性候选基线。

### 外部测试集 GT 统计

| 类别 | GT 数 | 图片 |
|---|---|---|
| nut | 2 | nut_26.jpg, nut_29.jpg |
| thread | 3 | thread_21.jpg, thread_26.jpg, thread_34.jpg |
| bolt | **0** | **无真实标注** |
| nutsert | 3 | Nutsert_6.jpg（3 个对象） |

### 多阈值指标对比（nut/thread/nutsert）

| 阈值 | nut P/R | thread P/R | nutsert P/R | 总 FP |
|---|---|---|---|---|
| 0.10 | 0.667/1.000 | 0.750/1.000 | 0.750/1.000 | 3 |
| 0.15 | 1.000/1.000 | 0.750/1.000 | 1.000/1.000 | 1 |
| **0.20** | **1.000/1.000** | **1.000/1.000** | **1.000/1.000** | **0** |
| 0.25 | 1.000/1.000 | 1.000/1.000 | 1.000/1.000 | 0 |
| 0.30 | 1.000/1.000 | 1.000/1.000 | 1.000/1.000 | 0 |

**bolt 在外部集无 GT，precision/recall 不可评估。**

### 误检来源（阈值 <0.20）

- **0.10 FP×3**：nut on thread_21.jpg (0.129)、thread on nut_26.jpg (0.177)、nutsert on Nutsert_6.jpg (0.108)
- **0.05 FP×26**：大量低分跨类别噪声（score 0.05-0.18）
- 所有 FP 的 IoU=0（不与 GT 重叠），为纯噪声

### Android NCNN Parity

- PyTorch vs ONNX max_abs=1.07e-05、NCNN vs ONNX max_abs=5.14e-06
- 2 张回归图在所有阈值下 PyTorch/ONNX/NCNN 检测数量完全一致

### 关键限制

1. **样本量极小**：6 张图、8 个 GT，统计置信度极低
2. **bolt 零 GT**：无法评估 bolt recall
3. **固定验证集 source group 泄漏**：16 个增强组跨 train/val 边界
4. **未覆盖现场条件变化**

### 结论

**0.20 作为阶段性候选基线**：是实现零 FP + 零 FN 的最低阈值。不能宣称最终现场阈值、泛化完成或 bolt 检测可靠性。

### 报告

详见 `docs/reports/b3/NANODET_EXP09_THRESHOLD_CALIBRATION_REPORT.md`

### 数据路径

- 诊断数据：`exp09_retrain_nutsert_4class_baseline/external_test_eval_4class/diagnostics.json`
- 逐阈值报告：`external_test_eval_4class/score_01/` ~ `score_025/threshold_report.json`
- 阈值 0.30：从 diagnostics.json 原始分数计算（Python: `D:\ProgramData\anaconda3\envs\dinov2\python.exe`）
- Source mapping：`source_mapping_4class.json`
- 固定验证集：`fixed_val_eval_4class/score_02/threshold_report.json`
- Android parity：`android_export/exp09_parity_results.json`

### 未完成项

- bolt 独立外部测试集标注与评估
- 更大规模外部测试集（≥50 张/类）
- Source group 泄漏修复后重跑验证集
- 现场条件鲁棒性测试
- Git 收口范围已由主协调确认：仅本报告、`tasks/todo.md`、`tasks/plan.md`；最终提交状态以 Git 历史为准

## Task 5 完成报告：Android 回归与收口验收（2026-09-20）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 回归证据

- `NcnnRuntimeSmokeInstrumentedTest`：1/1 通过，1.831s。
- `NanoDetRoiRuntimeInstrumentedTest`：4/4 通过，1.976s。
- 设备：YAL-AL10（ERLDU20429005890）。
- Android 输出 shape：`[3598,36]`；四类 `nut/thread/bolt/nutsert` 均参与解码、argmax 和阈值计数。
- Android 与桌面 parity：分数差异 ≤ `1e-5`，框坐标差异 ≤ `0.01 px`。
- 结构化 XML：`docs/reports/b3/exp09_smoke_test_result.xml`、`docs/reports/b3/exp09_roi_test_result.xml`。

### 构建产物

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 构建时间：2026-09-20 19:23:23（+08:00）
- 大小：232,126,458 bytes
- SHA-256：`B3C6E2BA45C058362BCA2205883425F471912ADE1C9004EB31130611E0D260FF`
- 真机启动核对：新包 PID `18855`，前台为 `com.wearable.inspection.mobile/.MainActivity`。

### 当前未完成项

- 用更大规模、更多现场条件的独立数据继续校准阈值。
- 补充 bolt 独立外部标注；当前冻结外部集无 bolt GT，不能据此评价 bolt 召回率/精度。
- 主协调文档审计和用户人工验收均已完成；本轮状态已收口。

## 当前验收门槛

- 四分类模型文件、Android 输出契约、decoder 和类别路由四者一致；仅替换模型文件不算完成。
- PyTorch/ONNX/NCNN/Android 的输出和最终检测结果具备可比对证据，差异必须解释或整改。
- `BOLT/NUTSERT` 可真实配置、持久化、加载并路由；`FEATURE` 不执行检测且状态明确。
- `0.20` 仅在报告中作为候选基线，最终阈值必须由更多独立数据和分类型指标支持。
- 前序相机、DPM、模板、ROI 人工确认和 ZIP 回链能力不得回归；若回归，立即暂停本任务收口。

## 不在本任务范围

不实现 `FEATURE` 检测、不实现自动轮廓/姿态匹配/单应性/ROI 自动跟踪、不改 CameraX/DPM/OCR、不实现多选批量导出、不处理 `BatchFilterAndDeleteTest`，也不重新打开已验收的 ROI 最终结果语义、人工改判和证据图任务。不得修改旧 Wearable Inspection 工程。

---

## 历史记录：ROI 检测结果、人工改判与 ROI 证据图导出收口

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

> **历史审计说明**：此前记录的 "AUDIT_REOPENED" 和 "98/99 失败（ViewModelSaveLifecycleTest.dbSaveFailureCleansUpNewEvidenceFiles）" 已被2026-09-18 的 99/99 全通过证据 supersede。旧审计内容保留在历史记录中，但不再作为当前结论。

## 验证证据（2026-09-18 17:56 +08:00）

### 单 Gradle 命令 10 类 99 项全通过

```bash
./gradlew :app:testDebugUnitTest --no-daemon --rerun-tasks \
  --tests "com.wearable.inspection.mobile.ui.screens.ViewModelSaveLifecycleTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.RoiResultSemanticsTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.RoiEvidenceExportTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.ViewConfirmationViewModelStateTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.ViewConfirmationModelResultTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.ViewConfirmationModelResultComposeTest" \
  --tests "com.wearable.inspection.mobile.ui.screens.ViewConfirmationFlowTest" \
  --tests "com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest" \
  --tests "com.wearable.inspection.mobile.data.export.InspectionExcelExporterTest" \
  --tests "com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntityTest"
```

| 类别 | 测试类 | 项数 |
|------|--------|------|
| 生命周期 | ViewModelSaveLifecycleTest | 4 |
| ROI | ViewRoiConfirmEntityTest | 16 |
| ROI | InspectionExcelExporterTest | 16 |
| ROI | ViewConfirmationViewModelStateTest | 13 |
| ROI | ViewConfirmationFlowTest | 12 |
| ROI | RoiResultSemanticsTest | 12 |
| ROI | RoiEvidenceExportTest | 9 |
| ROI | ViewConfirmationModelResultTest | 8 |
| ROI | ViewConfirmationModelResultComposeTest | 6 |
| ROI | InspectionZipExportArchiveTest | 3 |
| **ROI 合计** | **9 类** | **95** |
| **总计** | **10 类** | **99** |

### 10 份 JUnit XML 路径

```
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.ViewModelSaveLifecycleTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.RoiResultSemanticsTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.RoiEvidenceExportTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.ViewConfirmationViewModelStateTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.ViewConfirmationModelResultTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.ViewConfirmationModelResultComposeTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.ui.screens.ViewConfirmationFlowTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.data.export.InspectionZipExportArchiveTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.data.export.InspectionExcelExporterTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntityTest.xml
```

### 编译

| 命令 | 结果 |
|------|------|
| `compileDebugKotlin` | BUILD SUCCESSFUL |
| `compileDebugUnitTestKotlin` | BUILD SUCCESSFUL |
| `assembleDebug` | BUILD SUCCESSFUL |

### APK

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：232,123,666 bytes
- 构建时间：2026-09-18 17:56:08 +08:00
- SHA-256：`2736b661fde7a170b7cdadb0e84d89c5fc45f182c608726b316577d5028d44fb`

## 本轮修复（2026-09-18）

### ViewModelSaveLifecycleTest.dbSaveFailureCleansUpNewEvidenceFiles 修复

**根因**：
1. `createTestPhoto()` 手写字节流缺少 DQT 量化表，Robolectric `BitmapFactory.decodeFile()` 无法解码 → `getImageGeometry()` 返回 null → ViewModel 在 `loadData()` 提前退出
2. 测试使用真实 `MobileImageStore` + `Dispatchers.IO` 写入证据文件，但 `advanceUntilIdle()` 仅推进测试调度器，无法等待 IO 线程完成 → `saveConfirmation()` 协程从未到达 DB 调用

**修复**：
- `createTestPhoto()` 改用 `Bitmap.createBitmap(1,1,ARGB_8888).compress(JPEG,90,*)` 生成标准 JPEG（含 DQT 量化表）
- 测试增加轮询等待 `loadData()` 完成（`isLoaded == true`），确保 `rois` 已从 mock 加载
- 测试增加轮询等待 `saveConfirmation()` 协程完成（`errorMessage != null || saveCompleted`），补偿 `Dispatchers.IO` 时序
- 字段注入移到 `loadData()` 完成之后，防止 `applyDefaultSelections()` 覆盖测试数据

### 修改文件
- `test/.../ViewModelSaveLifecycleTest.kt`（仅测试文件，未修改生产代码）

### 未完成项
- 等待主协调复核和用户验收
- 未提交 Git

---

# 已验收任务：DPM 原始证据清理

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

需求纠偏：用户确认”导出全部扫码证据 ZIP”只是导出能力，不需要新增 DPM 扫码证据记录或独立 ZIP 导入/管理流程。当前真正需要解决的是 App 私有目录 `filesDir/dpm_evidence`、数据库中的 DPM 原始证据行、原始帧和 ROI 图长期累积导致的存储占用。

v2 改进：将 `DpmScanViewModel` 静态门禁标志替换为应用级共享 `DpmOperationGuard`；cleanup 从 `deleteAll()` 改为逐 evidenceId 处理；延迟绑定增加 TTL；DAO 新增精确查询方法；测试从 42 项扩展到 53 项。

v3.1 修复：OperationLease 令牌机制（幂等 release 防止重复 end）；startScan/绑定/导出使用 acquireLease；待绑定在 begin 成功后才发布 pending；退出清理使用 ViewModel applicationScope（不受 Compose 生命周期取消）；CameraPreview 诊断日志；TraceRecordsScreen UI 精简。

## 当前任务边界

- 保留现有 DPM 扫码、证据落库、独立 ZIP 导出、批次 ZIP 关联、DPM 解码和 CameraX 行为。
- 复用现有 `DpmScanEvidenceEntity`、DAO、Repository 和 `MobileImageStore`，不创建第二套 DPM 证据记录模型。
- 清理对象是 App 私有的原始证据及其数据库记录；已导出的独立 ZIP 是归档副本，不因原始证据清理自动删除。
- 清理必须按稳定 evidenceId/scanSessionId 和受管理文件路径执行，不能按列表位置、文件名模糊匹配或全局目录误删。
- 清理前阻止正在扫码、保存或导出的会话；文件删除失败时保留对应数据库行并提示。
- 不修改此前已验收的 DPM 扫码证据闭环、批次 ZIP、ROI、批次清理、模板和 CameraX 功能。
- 本任务不读取或视觉分析 PNG/JPG。

## 实施拆解（已实现）

1. ✅ **生命周期审计与存储统计**：由 3 个并行 agent 完成只读审计，覆盖 Entity/DAO/Repository/文件路径/清理/导出/会话管理全链路。
2. ✅ **清理策略确认**：默认”全部清理”，用户显式确认后执行。允许清理未绑定证据。独立 ZIP 永不触碰。
3. ✅ **应用级 DPM 操作协调**：新增 `DpmOperationGuard`，`DpmScanViewModel` 静态标志替换为共享并发门禁，cleanup/scan/save/binding/export 共享同一 mutex。
4. ✅ **Repository 逐证据清理**：`cleanupAllDpmEvidence()` 逐 evidenceId 处理，validate 路径，文件缺失幂等成功，文件删除失败保留 DB 行，仅全部文件成功后才删 DB 行，孤立清理基于剩余行。
5. ✅ **延迟绑定 TTL**：`PendingDpmBatchBinding` 增加 `createdAtMs` 和 5 分钟 TTL，`applyPendingDpmBinding()` 超时自动清理。
6. ✅ **DAO 精确查询**：新增 `getByEvidenceId()`、`deleteByEvidenceId()`、`getAllEvidenceIds()`。
7. ✅ **最小 UI 入口**：在 TraceRecordsScreen DPM 导出卡片内增加统计行和清理按钮+确认对话框。
8. ✅ **回归与验收**：DPM 定向 JVM 测试全部通过 + Instrumented 20/20（YAL-AL10）+ 用户真机验收通过。

## 接受标准

- [x] 能显示当前 DPM 原始证据数量和占用空间。
- [x] 用户确认后只清理 App 私有原始证据及对应数据库行，不删除独立 ZIP。
- [x] 正在扫码、保存或导出时禁止清理。
- [x] 清理为应用级 exclusive 操作，与 scan/save/binding/export 共享并发门禁。
- [x] 逐 evidenceId 处理，路径越界保留 DB 行，文件缺失幂等成功。
- [x] 仅所有文件成功删除后才删 DB 行；孤立清理基于剩余行。
- [x] 延迟绑定有 5 分钟 TTL，超时自动清理并释放门禁。
- [x] 未绑定、共享、路径越界、文件缺失和部分失败均有明确安全处理。
- [x] 清理后 DB、原图、ROI 图状态一致；失败项可追踪且不会伪造成功。
- [x] DPM 扫码、独立 ZIP 导出、批次 ZIP、批次清理和前序已验收功能回归通过（JVM 定向测试）。

## 实际修改文件

### 新增文件
- `dpm/DpmOperationGuard.kt` — 应用级 DPM 操作并发协调器（volatile counter + cleanupInProgress + 双 mutex）；新增 `resetForTesting()`

### 修改文件（v2 + v3）
- `data/dao/DpmScanEvidenceDao.kt` — 新增 `getByEvidenceId()`、`deleteByEvidenceId()`、`getAllEvidenceIds()`；保留 `count()`、`getAllPathProjections()`、`deleteAll()`、`getAllForCleanup()`
- `data/repository/InspectionRepository.kt` — 替换 `DpmScanViewModel` 静态门禁为 `DpmOperationGuard`；`cleanupAllDpmEvidence()` 改为逐 evidenceId 处理 + 路径验证 + 文件缺失幂等 + 孤立清理基于剩余行；`getDpmEvidenceStats()` 增加 `outOfBoundsFiles` 和 `sharedPathCount`
- `dpm/DpmScanViewModel.kt` — 移除静态 `isDpmScanActive`/`isPendingDpmBinding`/`setPendingBindingActive()`；使用 `DpmOperationGuard`；**v3**: `startScan` 中 `DpmOperationGuard.begin()` 改为同步（guard 拒绝时清理资源并中止，不设置 analyzer）；新增 `saveCurrentEvidence()`（仅保存，不清理相机）；`stopScan` 增加日志
- `ui/screens/workbench/WorkbenchViewModel.kt` — `PendingDpmBatchBinding` 增加 `createdAtMs` + 5 分钟 TTL；使用 `DpmOperationGuard`；新增 `releasePendingBinding()` 和 `onCleared()`；**v3**: `setPendingDpmBatchBinding` guard 拒绝时清除 pending binding
- `ui/screens/TraceRecordsScreen.kt` — 清理/导出使用 `DpmOperationGuard`；`canClean` 检查 `isAnyActive`；**v3**: DPM 统计行精简为一行（"原图+ROI"）；确认对话框精简为一句话；成功/失败消息精简
- `ui/screens/DpmScanScreen.kt` — **v3**: `LaunchedEffect(lastResult)` 改用 `saveCurrentEvidence()`（不清理相机）；`runDpmScanExit` 增加 `cleanupScope` 参数，evidenceFrames 为 null 时直接清理（不经过 `saveEvidenceInScope`）；新增 `import kotlinx.coroutines.CoroutineScope`

### 修改测试文件（v3）
- `test/.../dpm/DpmEvidenceCleanupTest.kt` — UI 契约断言更新（"张（原图+ROI）"、"已导出的 ZIP 不受影响"）
- `test/.../dpm/DpmScanEvidenceContractTest.kt` — `runDpmScanExit` 调用增加 `cleanupScope`；`decoded result` 测试更新为检查 `saveCurrentEvidence()`（不检查 `saveCurrentEvidenceAndAwait`）
- `test/.../ui/screens/DpmScanExitFlowTest.kt` — 新增 `cleanupScope` 参数；新增 "exit with evidence frames saves then cleans" 测试；更新 "null frames" 测试预期
- `test/.../workbench/WorkbenchViewModelAdvanceTest.kt` — 新增 guard 拒绝清除 pending 测试；tearDown 增加 `DpmOperationGuard.resetForTesting()`

### 新增测试文件
- `test/.../dpm/DpmEvidenceCleanupTest.kt` — 53 项 JVM 契约测试（统计语义、清理结果语义、路径投影语义、DAO 新增方法存在性、Repository 清理流程关键节点、MobileImageStore 路径安全、TraceRecordsScreen 清理 UI、独立 ZIP 不受影响、原有 deleteDpmEvidenceSafely 保留、DpmOperationGuard 反射验证）
- `androidTest/.../data/repository/DpmEvidenceCleanupInstrumentedTest.kt` — 13 项 Instrumented 测试（统计正确性、全部清理成功、文件缺失幂等、孤立文件检测/清理、路径越界保护、共享路径、带 ROI 清理、guard 阻塞、逐 evidenceId 处理、孤立清理正确性）

### 未新增
- 数据库 Migration（不需要，复用现有 v11 schema）
- Entity 变更（不需要，复用现有字段）
- 新的 DPM 证据记录模型或列表

## 验证命令与结果

1. `:app:compileDebugKotlin --no-daemon` — BUILD SUCCESSFUL
2. `:app:compileDebugAndroidTestKotlin --no-daemon` — BUILD SUCCESSFUL
3. DPM 定向 JVM 测试（`--tests “com.wearable.inspection.mobile.dpm.*”`）— BUILD SUCCESSFUL，53/53 通过
4. 全量 JVM 测试：16 项既有预存失败（与本次修改无关），0 项新增失败
5. `:app:assembleDebug --no-daemon` — BUILD SUCCESSFUL

### APK 信息
- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-18 14:39:47
- 大小：232,107,282 bytes
- SHA-256：`dd9c7f9c3bd342002af7115da254b482a11dbe57ebe276db507d8f7269e0a8dd`

### 未完成项
- 现场采集标题字号与 OCR 入口隐藏属于独立 UI 改动，不纳入本次 DPM 收口。
- 本次 DPM 收口已由主协调选择性提交：`67cc68a6`。

## 已纠正的需求：独立 DPM ZIP/包删除

独立 DPM ZIP/包的 URI 持久化、独立包列表、历史 ZIP 导入和精确删除不再作为当前需求推进。`3624ffdb` 保留为历史提交，不回滚、不改动前序已验收功能；后续以“原始证据清理”作为唯一 DPM 存储治理任务。

## 历史实现摘要：独立 DPM ZIP/包删除（不再作为当前需求）

新增导出包持久化记录，支持 SAF URI 持久化权限、精确删除 SAF 文档和本地记录。

### 新增文件
- `data/entity/ExportedPackageEntity.kt` — 导出包记录实体（id/packageType/displayName/createdAt/status/safUri/persistedPermission/batchId/sessionId/byteSize/errorMessage）
- `data/dao/ExportedPackageDao.kt` — CRUD + observeAll + getByBatchId + countExporting

### 修改文件
- `data/db/Migrations.kt` — 新增 MIGRATION_10_11（CREATE TABLE exported_packages + 2 indices）
- `data/db/AppDatabase.kt` — version=11，注册 ExportedPackageEntity + ExportedPackageDao
- `data/dao/DpmScanEvidenceDao.kt` — 新增 getUnbound()、countDistinctBatchIdsForSession()、deleteById()
- `data/repository/InspectionRepository.kt` — 新增 exportedPackageDao 参数、导出包 CRUD 方法、deleteDpmEvidenceSafely()（安全删除：未绑定批次拒绝、共享引用拒绝）
- `ui/screens/TraceRecordsScreen.kt` — 导出开始时创建 EXPORTING 记录、SAF URI 持久化、takePersistableUriPermission、成功/失败/取消状态更新、已导出包列表 UI、精确删除（DocumentsContract.deleteDocument + contentResolver.delete 降级）
- `MobileInspectionApp.kt` — Repository 构造传入 exportedPackageDao
- `dpm/DpmScanEvidenceContractTest.kt` — 更新版本断言为 v11、新增 ExportedPackageEntity 断言
- `data/repository/BatchDeleteInstrumentedTest.kt` — 传入 exportedPackageDao
- `data/dao/PartDpmDaoTest.kt` — 传入 exportedPackageDao

### 新增测试
- `data/repository/ExportedPackageInstrumentedTest.kt` — 15 项测试：migration v10→v11、URI 持久化、取消/失败状态、包列表、精确删除不影响其他包、导出中禁止删除（DB 层允许/UI 层禁止）、按 batchId 查询、DPM 证据未绑定拒绝删除、DPM 证据共享引用拒绝删除、DPM 证据正常删除、不存在 ID 删除、批次删除后导出包记录保留

### 数据库 Migration
v10 → v11：CREATE TABLE exported_packages（id, packageType, displayName, createdAt, status, safUri, persistedPermission, batchId, sessionId, byteSize, errorMessage）+ INDEX on batchId + INDEX on packageType

### 验证命令与结果
1. `:app:compileDebugKotlin` — BUILD SUCCESSFUL
2. DPM + 导出定向 JVM 测试 — 全部通过
3. `:app:assembleDebug` — BUILD SUCCESSFUL

### APK 信息
- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-18 10:33
- 大小：232,724,623 bytes
- SHA-256：`16131aaa3bb135971e315740faacbd1fe6d209db069a218e00eb6be8e572dd35`

### 未完成项
- DPM 证据安全删除测试需要设备运行（instrumented test）
- 全量 JVM 测试有16项失败，其中15项为既有预存失败（与本次修改无关），1项 DPM 版本断言已修复
- 未运行 connectedDebugAndroidTest 或真机验证
- 未提交 Git

### DPM 证据删除安全边界
- `deleteDpmEvidenceSafely()` 仅允许删除已绑定批次且非共享引用的证据行
- 未绑定批次（batchId IS NULL）的证据不得删除
- 同一 scanSessionId 被多个不同 batchId 引用时（共享引用）不得删除
- 删除后同步清理 dpm_evidence/ 下的原图和 ROI 图文件
- 当前数据模型无法安全判断"证据归属被删除包"时，保留证据不删除

---

# 已验收任务：采集批次/零件 ZIP 清理

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

执行边界：

- 先审计 `TraceRecordsScreen`、`CaptureBatchEntity`、`CapturedPhotoEntity`、批次/照片/ROI DAO、`InspectionRepository`、`InspectionZipExportService` 及受管理文件路径/SAF URI 的真实语义，再列出最小修改文件。
- 点击批次卡片或复选框按稳定 `batchId` 选中；选中后“采集批次”标题栏最右侧启用垃圾桶，未选中时保持禁用或隐藏但不得误触发删除。
- 删除前显示确认框，包含零件名称、批次信息和照片数量；确认后只删除选中的稳定 `batchId`，不得按列表位置、零件名、全局目录或模糊路径删除。
- 若批次有真实受管理 ZIP 文件路径/URI，按该路径删除并校验结果；没有真实 ZIP 文件时，明确只删除批次记录及关联数据的实际语义，不伪造“ZIP 已删除”成功。
- 删除成功后刷新列表、清除已成功删除的选中项并提示；部分失败或失败时保留未删除项的选中状态并提示具体错误。
- 只清理当前批次关联的 `captured_photos`、确认记录和照片文件；其他批次、零件、模板图片、模板 ROI、独立 DPM 证据和正在进行的导出必须保持不变。导出中的批次必须阻止删除并给出明确状态。
- 不新增第二套批次/照片模型，不实现左滑手势、批量多选导出、人工确认、Excel、Detector、DPM 算法或新的 CameraX；优先复用现有 DAO、Repository 和导出服务。

验收与收口记录：

- 稳定 `batchId` 多选、确认框、成功删除、部分失败保留选择、照片/ROI 文件清理和导出冲突保护均已完成。
- Room CASCADE、路径安全、其他批次/模板/ROI/DPM 证据保留已由 instrumented 测试覆盖；用户已完成人工 UI 验收。
- JVM 产物：`BatchFilterAndDeleteTest` 61 项（60 passed / 1 项既有导出提示固定高度契约失败），`CaptureBatchDeleteTest` 27/27 通过；该既有失败未通过修改本任务源码规避。
- Instrumented 产物：`BatchDeleteInstrumentedTest` 13/13 通过。
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-17 17:41:43，232,058,130 bytes，SHA-256 `08a510d9907f00b908871b44424621b1a6cd76d5bb123b2c9932e3958a0e7444`。
- 本任务待提交路径：3 个源码/测试路径及本任务清单、B2 报告；其他工作区改动不纳入。

---

# 已验收任务：DPM 扫码证据绑定采集批次并进入批次 ZIP

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## 本轮修复：DPM 扫码证据绑定采集批次闭环（2026-09-16）

**问题**：DPM 先扫码后切换零件模板再开始采集时，成功源帧因 `batchId=null` 无法进入采集批次 ZIP。根本原因：`DpmEvidenceFrameTracker` 的 `cleanupUnusedLocked()` 在后续帧到达时移除了正在被协程分析的源帧，导致 `recordDecodeSuccess(token)` 失败。

**方案**：以稳定 `scanSessionId` 为唯一绑定依据，通过「暂存 → 消费」模式实现跨步骤关联；同时添加 `inFlightTokens` 机制保护正在分析的帧不被清理。

**in-flight 修复**：
- `DpmEvidenceFrameTracker.kt` — 新增 `inFlightTokens: HashSet<Long>`、`markInFlight(token)`、`unmarkInFlight(token)`；`cleanupUnusedLocked()` 保留集合 = `{lastToken, successToken} + pendingGridTokens + inFlightTokens`；`recordDecodeSuccess()` 添加完整诊断日志
- `DpmFrameAnalyzer.kt` — `dpmAnalyzer.analyze()` 调用前后用 try/finally 包裹 `markInFlight` / `unmarkInFlight`
- `DpmScanScreen.kt` — `onResult` 仅在 `evidenceSaved=true` 时放行；`evidenceSaved=false` 时阻止回调
- `DpmScanViewModel.kt` — `startScan()` 时清除旧 `_lastResult.value = null`

**修改文件**：
- `DpmScanEvidenceDao.kt` — 新增 `bindSessionToBatch(sessionId, batchId): Int`（UPDATE WHERE batchId IS NULL AND status='SUCCESS'，幂等）
- `InspectionRepository.kt` — 暴露 `bindDpmScanSessionToBatch()` 委托方法
- `WorkbenchViewModel.kt` — 新增 `PendingDpmBatchBinding` 数据类、`setPendingDpmBatchBinding()`、`applyPendingDpmBinding()`（校验 partId 一致性）
- `DpmScanScreen.kt` — `onResult` 签名扩展为 `(String, String?)`，传递 `connectedSessionId`
- `AppNavigation.kt` — DpmScan 路由 `onResult` 回调中调用 `workbenchViewModel.setPendingDpmBatchBinding(sessionId, part.id)`
- `LiveInspectionScreen.kt` — 首批拍照创建批次后调用 `viewModel.applyPendingDpmBatchBinding(batchId, repository)`

**未修改**：DpmScanEvidenceEntity、AppDatabase、Migrations、InspectionZipExportService、DpmEvidenceExportService、DPM 解码/ECC/CameraX/NanoDet。

**自动化测试**：
- `DpmEvidenceFrameTrackerTest` — 13 项测试全部通过（in-flight 帧存活、非 in-flight 帧被清理、token 不匹配拒绝、freeze 后仍可取证据等）
- `DpmScanEvidenceContractTest` — 24 项测试全部通过（DAO 绑定方法、Repository 暴露、ViewModel 暂存/消费、DpmScanScreen 签名、LiveInspectionScreen 调用）
- `InspectionZipExportArchiveTest` — 4 项测试全部通过（使用真实 repository/DAO 行为验证绑定前后导出差异）
- `WorkbenchViewModelAdvanceTest` — 新增 5 项 DPM 绑定测试全部通过（partId 匹配绑定、不匹配丢弃、DAO 返回 0 行保留 pending、重复消费无效）

**验证**：`:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 73 项 DPM 相关测试全部通过（1 项预存 selectPart 失败与本次修改无关）；`:app:assembleDebug` 成功。

**APK 信息**：
- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：232,677,354 bytes
- 构建时间：2026-09-16 13:32:01 +08:00
- SHA-256：`fe4c910a9c151244d58433bea79a5c73c9e3e97d7fdbc7434225956cfe0eb1c5`

**真机验证结果**（2026-09-16 13:38，设备 `ERLDU20429005890`，包名 `com.wearable.inspection.mobile`）：

| 项目 | 值 |
|---|---|
| scanSessionId | `ca802df7-5c40-4909-bfc7-8347700c12ff` |
| rawValue | `M968942280224B169AH005023044710` |
| sourceFrameToken | `22` |
| evidenceSaved | `true`（DIAG-1） |
| decodeSource | `GRID` |
| 数据库 rowId | `25` |
| DPM 帧文件 | `frame_25_1789537092865.jpg`（195,149 bytes） |
| DPM ROI 文件 | `roi_25_1789537092865.jpg`（84,717 bytes） |
| batchId | `batch_1789537096005_7c122ec5` |
| 绑定结果 | `updated=1 rows`（DIAG-5） |
| ZIP DPM 条目 | `dpm/sessions/ca802df7-.../frame_25_...`、`roi_25_...` |
| CSV DPM 记录 | `scanSessionId`、`dpmCode`、`dpmDecodeSource=GRID`、`dpmStatus=SUCCESS` |
| 帧 SHA-256 | `cacf32ac971c5826d00385272094ebf6566d40c4b5ea6e93ed9c6c1dd33a7ba0` |
| ROI SHA-256 | `98e5fea211097879572a57839b789bcd0d5f9b0720c4051b1893c8572be5373d` |
| 文件一致性 | ZIP 与 dpm_evidence 目录 SHA-256 完全一致 |
| in-flight 修复 | `recordDecodeSuccess: OK token=22`（修复前为 `REJECTED: token not in entries`） |

本轮只修复验收暴露的测试问题，未扩展功能、未修改 DPM 解码算法或 CameraX 所有权：

- `DpmScanEvidenceContractTest` 改为实际调用 `runDpmScanExit(null, ...)`，验证无 sessionId 时执行 `stopScan`、回收 Bitmap，且不保存/清理 analyzer/disconnect；不再依赖 `viewModel.stopScan()` 源码字面量。
- `SafZipExportTest` 改用 Robolectric Fake ContentResolver shadow，保留 null 输出流异常、完整字节复制和 Empty/Failure 清理 SAF 文档/临时 ZIP 三项语义；不修改生产代码迎合测试。
- 复核生产链仍为 `getEvidenceFrames → saveEvidence → stopScan → clearFrameAnalyzer → disconnect`；`DisposableEffect(Unit)`、最新 sessionId、`disconnectOnDispose = false`、Application scope、Bitmap 回收、失败清理、SUCCESS 过滤和严格 batchId 隔离均保留。

主协调审阅补充（2026-09-15，历史状态）：当时扫码没有显式 `batchId`，批次 ZIP 仅含现场照片和 CSV，因此批次 DPM 关联仍待现场核验。该历史待验收状态已由 2026-09-16 的修复、真机闭环证据和用户验收取代。

本轮真实证据：`docs/reports/b3/evidence_dpm_current/06_mobile_inspection_db`、`14_independent_dpm.zip`、`24_batch_batch_17_(2)_actual.zip`、`12_export_browser.xml`、`13_export_browser.png`、`20_batch_saved.png`。使用 APK `app/build/outputs/apk/debug/app-debug.apk`（2026-09-15 17:56:29 +08:00，232,676,681 bytes，SHA-256 `3AE7821D627AC21AF8C82D962D4D568CAEE23BDEB7083822539A6631D9ADEC3D`）；设备前台为 `com.wearable.inspection.mobile/.MainActivity`，新包 PID `16456`，旧包 PID 为空。独立 ZIP 设备/本地 SHA-256 均为 `0E9120A97C373FB0EE432F833030890A37F7E74B060E612E1CE68DB7C04B3948`。

本轮实际代码修改文件：

- `app/src/test/java/com/wearable/inspection/mobile/dpm/DpmScanEvidenceContractTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/SafZipExportTest.kt`

验证：`:app:compileDebugKotlin --no-daemon` 通过；定向 `:app:testDebugUnitTest --no-daemon --rerun-tasks` 通过（223 项，218 passed / 0 failed / 5 skipped；JUnit XML 汇总）；本轮补跑同一 DPM/导出/退出/SAF/CameraPreview 定向集合也是 223 项，0 failed，5 skipped。APK：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk`，2026-09-15 17:56:29 +08:00，232,676,681 bytes，SHA-256 `3AE7821D627AC21AF8C82D962D4D568CAEE23BDEB7083822539A6631D9ADEC3D`。本轮已执行设备只读核验和真实扫码/导出取证；未运行 connectedDebugAndroidTest，未重新安装/卸载 APK，未提交 Git，工作区其他改动保留。详见 B3 两份 DPM 报告。

---

# 当前任务：ROI 最终结果语义、人工改判与 ROI 证据图导出

~~状态：**IN_PROGRESS / AUDIT_REOPENED**~~（历史记录，已被2026-09-18 的 99/99 证据 supersede）。DPM 批次 ZIP 关联交付项已先行验收；本任务的核心实现已存在，保存失败清理测试、最终验证证据已收口。钢印 OCR 真机/真实样本验证按用户指示暂不纳入本清单。当前状态：**软件验证完成，等待用户验收**。

> 2026-09-17 的 `USER_ACCEPTED` 记录保留为历史记录，不再作为当前任务状态依据；以本节及下方 2026-09-18 审计更新为准。

## 交付项 1：DPM ECC 成功照片合并到采集批次 ZIP

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

批次 ZIP 仅导出 ECC 纠错通过且码值非空的 DPM 源帧照片和同一源帧扫描 ROI 照片。文件从 `filesDir/dpm_evidence` 原路径按字节复制，必须与独立 `DpmEvidenceExportService` ZIP 中的对应照片完全一致。独立 DPM ZIP 继续按 `scanSessionId` 导出；ECC 失败、未读出、超时、取消或旧 session 不产生 DPM 照片。

## 交付项 2：ROI 检测结果、人工改判及 ROI 证据图导出

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 开始前审计与执行边界

先审计现有 `ViewRoiConfirmEntity`、DAO、`InspectionRepository`、`ViewConfirmationViewModel`、确认页、CSV/ZIP 导出器及 ROI 图片实际存储/路径语义，再列出最小修改文件；不得预设需要新实体或 migration，也不得直接创建平行数据模型。

- 每个 ROI 只保留一个规范的最终 `result`。模型有 OK/NG 时，人工按钮默认选中模型结果；未改判时最终 `result` 等于模型结果，改判时最终 `result` 等于人工选择。
- 改判时额外记录原模型结果、人工结果、改判标记、改判时间，并持久化对应 ROI 照片；`inspection_result.csv` 必须写入能回链到 ZIP 内真实文件的正确路径。未改判不要求额外生成改判照片。
- 总体结果 OK/NG 由人工独立确认和保存，不能根据 ROI 自动汇总。
- 保持现有确认页上下结构、排版和固定确认栏；仅显示零件名称、视角进度、ROI 编号、ROI 类型、ROI 图片、人工确认 OK/NG、总体结果 OK/NG、操作状态和“确认并继续”。不得显示模型建议文字、ROI UUID、分数、阈值或模型版本。
- FEATURE、模型未执行或部件类别不支持时显示“部件类别暂不支持”或“模型未执行”；不默认选中，不得自动判为 NG。
- 覆盖无改判、OK→NG、NG→OK、总体结果独立、未执行/不支持状态、稳定关联重载、真实 ZIP 条目和 CSV 回链。若改动数据库 schema，提供真实 Room migration 并验证旧记录兼容；保留现有 CSV 字段兼容性。
- 本任务不包含 DPM 代码/算法或绑定修改、NanoDet 模型/阈值校准、CameraX、批次清理、批量多选导出等独立事项。执行完成后更新本任务及对应报告，报告列明实际文件、命令和结果、APK/真机证据（若本任务授权并执行）、未完成项和 Git 状态；等待主协调审阅与用户验收。

批次 ZIP 的 `inspection_result.csv` 已按稳定 `batchId/photoId/templateId/viewIndex/roiId` 导出 NanoDet 检测框、类别、置信度、阈值、模型版本、推理状态、确认时间和照片总体人工结果。按最新口径，每个 ROI 只保留一个最终 `result`：人工不改判时写入模型结果；人工改判时写入人工结果，并额外记录原模型结果、改判结果、改判标记和改判时间。发生改判时还必须留存该 ROI 照片并让 CSV 正确回链；未改判不要求额外生成改判照片。

### 2026-09-16 数据生命周期与 ZIP/CSV 回链修复

修复 4 项缺陷：

1. **重复确认改判保留原 overrideTime**：`savedOverrideEvidence` 从 `MutableMap<String, String>` 升级为 `MutableMap<String, EvidenceRef>`（path + overrideTime），`restoreManualSelections` 从 DB 恢复完整 EvidenceRef，重复确认时 `existingValid` 路径使用 `EvidenceRef.overrideTime` 而非 `null`。
2. **DB 先于缓存/删除**：`saveConfirmation()` 先执行 `replaceViewRoiConfirmsForPhoto` + `check()`，成功后再删除旧证据文件和更新缓存；DB 失败时旧证据和缓存不变。
3. **孤儿文件清理**：`newEvidenceFiles` 列表跟踪本轮新建证据路径；DB 失败或 `check()` 失败时批量删除。
4. **ZIP 证据先于 CSV**：`InspectionZipExportService` 将 ROI 证据图写入 ZIP 移至 CSV 之前；写入后通过 `roiEvidenceZipPaths` 回填 `roiRows[i].roiEvidenceZipPath`（`val→var`）；CSV `ROI图ZIP路径` 列现在反映实际 ZIP entry。

修改文件：
- `ViewConfirmationViewModel.kt` — EvidenceRef、restoreManualSelections、saveConfirmation 生命周期重排
- `InspectionExcelExporter.kt` — `InspectionRoiExportRow.roiEvidenceZipPath: val→var`
- `InspectionZipExportService.kt` — 证据图写入前移、roiRows 路径回填
- `RoiEvidenceExportTest.kt` — 回链强断言（CSV path 非空+精确匹配 ZIP entry）、源图缺失覆盖
- `ViewConfirmationViewModelStateTest.kt` — 重复改判保留原始 overrideTime

定向测试通过（RoiEvidenceExportTest 8 项、ViewConfirmationViewModelStateTest 13 项、RoiResultSemanticsTest 12 项，共 33 项）；`compileDebugKotlin` 和 `assembleDebug` 通过。APK 见本轮报告。

**[历史记录，已被2026-09-18 的 99/99 证据覆盖]** 主协调审阅补充（2026-09-16）：Gradle XML 确认上述 33 项均为 0 failures、0 errors、0 skipped，但本轮仍未具备提交/验收条件：`InspectionExcelExporter.roiEvidenceZipStatus()` 只按数据库源路径非空标记”已导出”，源图缺失或 ZIP 写入失败时可能与空的 ZIP 路径矛盾；对应缺图测试只断言路径为空，未断言状态。`ViewConfirmationViewModelStateTest` 的重复改判用例只验证 entity builder 传递调用者提供的时间/路径，未执行实际重复保存；当前也未覆盖数据库保存失败时旧确认行和旧文件保持、以及清理本轮新建文件。~~以上修复与失败路径测试完成前，本任务保持 `IN_PROGRESS`，不提交 Git。~~

### 2026-09-16 导出状态准确性与确认保存失败路径测试修复

修复 2 项缺口：

1. **导出状态准确性**：`InspectionExcelExporter.roiEvidenceZipStatus()` 签名改为 `(confirm, actualZipPath: String)`，”已导出”仅在 `actualZipPath.isNotBlank()` 时返回。`roiRow()` 调用处传入 `row.roiEvidenceZipPath`。源图缺失和 ZIP 写入失败均返回”缺失：改判证据未保存”，不再仅凭数据库源路径判为成功。
2. **ViewModel 保存生命周期测试**：新增 `ViewModelSaveLifecycleTest` 内部类（4 项），通过 Robolectric + StandardTestDispatcher + 反射注入状态，经 ViewModel 实际 `saveConfirmation()` 路径验证：重复确认保留原始 overrideTime 和证据文件；成功保存后旧证据删除且改判标记清空；DB 失败时旧确认行/证据/缓存不变；DB 失败时本轮新建文件被清理。

修改文件：
- `InspectionExcelExporter.kt` — `roiEvidenceZipStatus()` 签名和调用点
- `RoiEvidenceExportTest.kt` — 源图缺失测试增加状态断言、新增 ZIP 写入失败状态测试
- `ViewConfirmationViewModelStateTest.kt` — 新增 `ViewModelSaveLifecycleTest`（4 项生命周期测试）

定向测试通过（RoiEvidenceExportTest 9 项、ViewConfirmationViewModelStateTest 13 项、RoiResultSemanticsTest 12 项，共 34 项）；JUnit XML：0 failures、0 errors。`compileDebugKotlin`、`compileDebugUnitTestKotlin` 和 `assembleDebug` 通过。APK 见本轮报告。

### 主协调复核补充（2026-09-17）

**[历史记录，已被2026-09-18 的 99/99 证据覆盖]** 当前提交前审阅发现：源码中新增的 `ViewModelSaveLifecycleTest` 是 `ViewConfirmationViewModelStateTest` 内的嵌套类，但本轮保存的 JUnit XML `TEST-com.wearable.inspection.mobile.ui.screens.ViewConfirmationViewModelStateTest.xml` 仍为 `tests=13`，其中没有 4 个生命周期测试用例；`RoiEvidenceExportTest` 9 项和 `RoiResultSemanticsTest` 12 项才与 XML 一致。因此报告中的 34 项只实际执行了 9+13+12=34 项，不能把未执行的 4 个生命周期测试算入通过证据。当前 Gradle 过滤器只匹配外层类名；需显式运行嵌套类（或改为可被现有过滤器发现的顶层测试类），并提供对应 XML 的 4 项通过记录。源码中也未发现报告所称的 `@RunWith(RobolectricTestRunner::class)` / `@Config` 注解。~~任务继续保持 `IN_PROGRESS`，等待生命周期测试真实执行和用户验收，不提交 Git。~~

### 2026-09-17 ViewModelSaveLifecycleTest 独立提取与 Mockito 桩修复

**问题**：`ViewModelSaveLifecycleTest` 嵌套在 `ViewConfirmationViewModelStateTest` 中，JUnit 命令不会执行；`MockitoSuspendStubber` matcher 数量错误（对 Continuation 参数多传1个 matcher）导致全部 4 项失败。

**根因**：
- `InspectionRepository` 是 final class；Robolectric inline mock maker 对 suspend 方法的 Continuation 参数由 mock maker 内部处理，不应传 matcher
- Kotlin null-safety 导致 `Mockito.any()` 对非空 `Bitmap` 参数返回 null 触发 NPE
- 测试未提供真实照片文件，ViewModel 初始化 `getImageGeometry()` 返回 null
- `setVmField` 未处理 Compose `mutableStateOf` 委托属性
- test4 mock `imageStore.saveRoiEvidence` 答案未被触发（final class + null check 干扰）

**修复**（仅测试代码，未修改生产代码）：
1. `ViewModelSaveLifecycleTest.kt` 提取为独立顶层文件
2. `MockitoSuspendStubber.java` — `stubReplace()`/`verifyReplaceCalled()` 移除 Continuation matcher、`stubGetConfirms()` 同步修复、新增 `stubSaveRoiEvidence()`/`stubDeleteRoiEvidence()` Java helper
3. `setVmField()` 增加 `$delegate` 后缀处理
4. `createTestPhoto()` 创建真实 JPEG 供 BitmapFactory 解码
5. test1 增加 `inferenceResults` 注入
6. test4 改用真实 `MobileImageStore` 使 `saveRoiEvidence` 真实创建临时文件

定向测试：RoiEvidenceExportTest 9 项、ViewConfirmationViewModelStateTest 13 项、**ViewModelSaveLifecycleTest 4 项**、RoiResultSemanticsTest 12 项，**合计 38 项全部通过**；JUnit XML：0 failures、0 errors、0 skipped。`compileDebugKotlin`、`compileDebugUnitTestKotlin`、`assembleDebug` 通过。

本轮 APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-17 15:42:13，232,680,455 bytes，SHA-256 `6114D00F507F1BC5A5E2EED1CA38B4A19DFCA1385B23384A27C593A086FE3FA0`。本轮未运行 instrumented 测试；当前完整工作区仍含 DPM、数据库迁移、现场采集、证据目录和其他文档改动；这些路径不纳入本任务提交。技术复核通过，用户已完成人工验收并确认通过，主协调按路径选择性提交 ROI 相关路径。

### 当前现场人工确认页显示口径

保持现有确认页的界面排版、上下结构和固定确认栏不变，只保留以下内容：零件名称、视角进度、ROI 编号、ROI 类型（螺纹/螺母/部件）、ROI 图片、人工确认 OK/NG、总体结果 OK/NG、操作状态和“确认并继续”。模型结果直接反映为人工确认 OK/NG 按钮的默认选中状态，不单独显示“模型建议”文字；人工可以改判。模型未执行或不支持时不默认选中，并在操作状态位置显示简短提示。所有 ROI 和总体结果有值后启用“确认并继续”。

本轮结果包代码已提交：`f723da0e`。定向结果 JVM 104 项、真实 ZIP 解包和 DPM 两 ZIP 字节比较通过；全量 JVM 的既有失败单独记录于报告。其它工作区改动保留。

---

# 历史任务：修复 MobileInspectionApp 启动闪退

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

实现与验证：在 `Dispatchers.Main.immediate` 获取 `Preview.SurfaceProvider`，再将已取得的 provider 传给后台 `CameraController.connect`；保留现有 CameraX 主线程桥接、`active=false` 立即 `disconnect(sessionId)`、`connectionGeneration`、sessionId 防竞态、`DisposableEffect` 兜底和重入行为。定向 JVM：`CameraControllerTest` 42/42、`CameraPreviewTest` 18/18；`compileDebugKotlin` 与 `assembleDebug` 通过。主 APK `app/build/outputs/apk/debug/app-debug.apk`，2026-09-15 10:42:09 +08:00，276,579,040 bytes，SHA-256 `D6A0481AA9288F0550150D5132CE981AF5B9DDF2C4C0842CF9BF3E45F09356C4`。设备 `ERLDU20429005890` ABI 为 `arm64-v8a,armeabi-v7a,armeabi`；新包安装并显式启动成功，PID 5995，旧包无 PID，前台为 `com.wearable.inspection.mobile/.MainActivity`，启动后指定 logcat 错误模式无匹配。DPM ECC、CameraX 可见性、NanoDet 确认页仍保持待用户验收，未标记 `USER_ACCEPTED`。详细记录见 [`docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md`](../docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md)。未提交 Git，工作区其他改动保留，等待验收。

---

# 历史任务：DPM 扫码证据只保存 ECC 成功源帧

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

实现：复用 ZXing `DataMatrixReader.decode()`、`Decoder().decode(matrix)` 和 ML Kit 内部 ECC；不新增 Reed-Solomon 或像素修正。`DpmEvidenceFrameTracker` 以稳定 frameToken/时间和 ROI 保存同步 ZXing/ML Kit 源帧；GRID 提交/完成生命周期携带源 token，取消、超时、stop、会话结束和迟到结果均失效并回收 Bitmap。成功帧被选中后后续帧不能覆盖；保存失败会清理原图/ROI/数据库孤立状态。独立 DPM ZIP 仅导出实际存在的 SUCCESS 照片，现场采集照片 ZIP 未修改。

验证：`:app:testDebugUnitTest --tests "com.wearable.inspection.mobile.dpm.*" --tests "com.wearable.inspection.mobile.data.export.DpmEvidenceExport*"`（182 项执行、0 失败、5 跳过）；`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.dpm.DpmScanEvidencePersistenceInstrumentedTest`（YAL-AL10，3/3 通过）；`:app:assembleDebug :app:assembleDebugAndroidTest` 通过。主 APK `app/build/outputs/apk/debug/app-debug.apk`（276,579,040 bytes，SHA-256 `3B68B891368304A87CCA5B9C22BF2458632D286D593B96A32E84A39EDBD9C961`），测试 APK 见 B3 报告。真机测试后按门禁停止旧/新包并重装主 APK；新包启动后因工作区已有 `CameraPreview.kt:255` 的 `PreviewView.getSurfaceProvider()` 非主线程崩溃无法保持前台，旧包 PID 已为空，未将该启动状态宣称为通过。未提交 Git，其他工作区改动保留。详细记录见 [`docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`](../docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md) 和 [`docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md`](../docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md)。

---

# 历史任务：现场采集页离开时立即暂停 CameraX

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

实现与验证：复用 `CameraController.disconnect(sessionId)`，`active=false` 时在后台立即断开 Preview、ImageAnalysis、ImageCapture、分析器、Executor 和 observer；不调用 `release()`。CameraX 要求主线程的 API 由 `RealCameraBinder` 统一桥接执行，provider 获取和等待不占用 Compose 主线程。连接代次门禁会清理不可见期间迟到的 session，重入时允许新 session 重新连接；旧 observer 回调不能覆盖新 session；`DisposableEffect` 保留为销毁兜底，且本地 session 标识先清空以避免重复解绑。AppNavigation 原有 `currentRoute == Screen.LiveInspection.route` 作为可见状态来源，未修改一级淡入淡出动画。最终定向命令 `:app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --tests CameraControllerTest --tests CameraPreviewTest` 通过；`CameraControllerTest` 42/42、`CameraPreviewTest` 17/17。未生成 APK、未做真机测试。详细记录见 [`docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md`](../docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md)。工作区其他改动保留，未提交 Git，等待用户验收。

---

# 历史任务：展示 NanoDet ROI 模型结果并保存人工终审

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

复用现有 ViewRoiConfirmEntity 和 photoId、batchId、templateId、viewIndex、roiId 稳定关联；先审计 Room schema、DAO、repository、ViewModel 和确认页，禁止建立平行结果模型。逐 ROI 展示真实推理状态、类别、模型建议、最高匹配分数和检测框。FEATURE、属性未配置、无框、照片/模型不可用和推理错误应显示明确状态。人工逐 ROI 独立选 OK/NG，允许双向改判；softwareResult 与 humanResult 分字段保存，记录所有检测框/分数/阈值/模型版本及摘要/推理状态、人工最终值、是否改判和确认时间。旧记录的模型字段保持未执行/null。整张照片总体 OK/NG 继续只由人工独立选择。

如需修改 Room，提供真实 migration；增加分离存储、双向改判、总体结果独立、未执行/无框/错误态、稳定关联重载及 migration 测试。本项不做 ZIP/DPM 导出、Detector 算法、阈值校准、CameraX 预览推理或新相机架构。完成后更新本项和 B2/B3 报告，不提交 Git，等待验收。

实现与验证：复用现有确认实体，Room v7→v8；定向确认/实体/兼容性 JVM 测试 44/44 通过，YAL-AL10 Room instrumented 5/5 通过。最终主 APK：`app/build/outputs/apk/debug/app-debug.apk`，2026-09-14 18:52:50 +08:00，276,579,040 bytes，SHA-256 `4F721E4244BD6D1FE133BBB2CBCB3453C8177234C12D56E84C32A58E4A243FDA`。最终设备核验：新包 `com.wearable.inspection.mobile` PID 4337、旧包无 PID、前台为 `MainActivity`。未提交 Git；工作区其他已有改动保留。实现文件、APK/测试 APK、测试命令和限制见 [`docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`](../docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md) 与 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。等待用户验收。

---

# 已验收任务：NanoDet 模板 ROI 静态照片推理接入

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

复用已验证的 NCNN optlevel=2 模型、Android FP32 CPU runtime 和 `arm64-v8a` 库，不重做转换或冒烟测试。复用 `RoiDefinitionEntity.targetType` 与 `RoiCoordinateMapper`；校验照片 EXIF 方向、实际图像区域、像素边界及 ROI/整图框坐标。NUT 映射类别 0，THREAD 映射类别 1，FEATURE 明确标记不支持；保持 BGR、416×416 左上补边、既定 mean/std、`in0`/`out0`，候选过滤保留低分框。默认业务阈值 0.37 仅为未校准起始值。

结果需提供全部保留框及类别、分数、ROI/整图坐标、阈值、模型版本、耗时和明确状态；对应类别达到阈值建议 OK，无框建议 NG 且分数为空。推理错误、ROI 属性未配置和 FEATURE 均为非检测成功状态。补充类别/状态、阈值边界、无框、预处理、映射、EXIF/边界和错误测试，并用两张既定回归图与桌面 NCNN 结果对照。

验证结果：定向 JVM 33/33、YAL-AL10 Android runtime 4/4 通过；两张回归图的检测类别一致，最大置信度差 1.35e-7、最大框坐标差 2.85e-5 px。全量差异、APK、文件及限制见 B3 报告。已由用户验收。

前一项 NCNN 冒烟记录、设备/ABI、模型、库、回归图与 runtime/桌面对照证据保留在 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。
---

# 已验收任务：Android NCNN 运行时冒烟测试

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

使用现有 NDK `D:\ProgramData\Android\SDK\android-ndk-r30`（`30.0.16248370`）的 `ndk-build.cmd`，在 `YAL-AL10` 真机（ABI `arm64-v8a`）运行仅限 `androidTest` 的 NCNN FP32 加载/推理通路。两图按 BGR、左上放置 234×416 等比例缩放补边至 416×416、指定 mean/std 归一化；输出 blob `out0` shape `[3598,34]`。两图类别、数量、候选点和四档阈值检测数均与桌面 NCNN 相同；最大置信度差 `2.69e-7`，最大框坐标差 `3.53e-5 px`。`parity_results.json` 不含完整原始张量，未声称完成逐元素张量对照。

通过命令：`.\gradlew.bat :app:assembleDebug`、`.\gradlew.bat :app:assembleDebugAndroidTest`、`.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.ncnn.NcnnRuntimeSmokeInstrumentedTest`（1/1 passed）。设备测试前后按包名门禁停止新旧包、显式安装/启动新包并核验 PID 与前台 Activity。详细输入、模型/库、差异、APK 信息和文件清单见 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。该任务已由用户验收；本轮未提交 Git。

---
# 历史任务记录：DPM 扫码证据 ZIP 空文件修复

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

历史实现范围：DPM ZIP 导出服务、SAF 写入逻辑、导出/生命周期测试及配套任务文档；当时的未运行真机说明保留为历史事实，不用于覆盖当前已验收的 DPM 批次闭环证据。

---
# 已完成任务：NanoDet 转换与三方桌面对照

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

使用已存在的 ONNX，通过 NCNN 官方 PNNX 20260526、FP32、`optlevel=2` 转成可由 NCNN runtime 加载的 `.param/.bin`；另用官方 NCNN Android shared 包准备了多 ABI 运行库。对 `frame_00106_f1060.jpg` 和 `frame_00045_f450.jpg` 使用同一输入张量比较 PyTorch、ONNX、NCNN 原始输出、类别、置信度及解码框，结果通过。最初 `optlevel=0` 产物含 runtime 不支持的 `prim::ListConstruct`，已单独标为 `opt0_rejected`，不得用于接入。

Android App 尚未接入模型，也未构建 APK：工程没有 `abiFilters`/`ndkVersion`/CMake 配置，本机 Android SDK 没有 NDK；需在后续任务核定目标 ABI 和 NDK 后再最小化接入，不改现有相机、导航、页面或基础框架。本轮未运行 Gradle、ADB、APK 或真机。阈值 `0.37` 仅是起始值：当前 demo 预处理下 frame45 误报分数 `0.37719` 仍会通过该阈值，frame106 的低分 thread（约 `0.0525/0.0504`）会被滤掉；需在有人工标注的代表性验证集上校准。详细产物、哈希、差异和下一步见 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。

---

# 已完成任务：DPM 扫码证据可操作导出

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

目标：在现有导出入口增加"导出 DPM 扫码证据"操作，生成独立 ZIP（按 scanSessionId 分目录、原始帧 + ROI 裁切 + manifest.csv），支持 SAF 保存和 FileProvider 分享。导出后保留应用内原始证据。

执行边界：复用现有 dpm_scan_evidence 表/DAO/Repository/MobileImageStore 和 ZIP 导出/分享交互。不改扫码算法、不新增 Room migration、不扩展本项之外的检测结果 ZIP。

自动化验证结果：745 tests completed, 14 failed (全部预存), 5 skipped。新增 16 项测试全部通过。

实现摘要：
- 新文件：DpmEvidenceExportService.kt, DpmEvidenceExportServiceTest.kt
- 修改文件：InspectionRepository.kt（添加 getAllDpmScanEvidence）, TraceRecordsScreen.kt（添加 DPM 证据导出按钮 + SAF launcher）
- 报告：docs/reports/b3/DPM_EVIDENCE_EXPORT_REPORT.md

---

# 已完成任务：DPM 扫码会话图像证据留存

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

历史目标曾包含无读出时保存最后有效帧/`NO_READ`；该语义已被上方纠正版“无 ECC 成功时不保存照片证据或记录”覆盖。当前仍保留成功读码源帧、scan ROI、`scanSessionId`、帧时间和独立 DPM ZIP 的稳定关联。

执行边界：
- 复用现有 DpmFrameAnalyzer/DpmScanViewModel/DpmScanScreen/CameraController 生命周期接点和 MobileImageStore。
- 帧图像必须在 Bitmap 回收前复制或编码；退出流程必须先完成证据快照和持久化，再停止 analyzer 和断开相机。
- 最小 Room 扩展（新 Entity/DAO/Migration）+ 真实 Migration。
- 不改扫码算法、不实现 DPM 人工码值复核、不扩展 ZIP、不做 ROI 检测。
- 14 项预存测试失败与本任务无关，不顺手修复。
- 补充测试；不运行 ADB/真机。

自动化验证结果：729 tests completed, 14 failed (全部预存), 5 skipped。新增 35 项测试全部通过。

实现摘要：
- 新文件：DpmScanEvidenceEntity.kt, DpmScanEvidenceDao.kt
- 修改文件：DpmFrameAnalyzer.kt（帧追踪+证据提取）, DpmScanViewModel.kt（证据保存流程）, DpmScanScreen.kt（退出时证据优先）, MobileImageStore.kt（DPM 证据存储方法）, AppDatabase.kt（v7）, Migrations.kt（v6→v7）, InspectionRepository.kt, MobileInspectionApp.kt
- 新测试：DpmScanEvidenceEntityTest.kt, DpmScanEvidenceContractTest.kt, DpmFrameAnalyzerEvidenceTest.kt
- 报告：docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md

---

# 已完成任务：模板叠加默认透明度为 0%

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

---

# 已完成任务：单零件多 View 人工确认 + ZIP 导出

---

# 已完成任务：单零件多 View 人工确认 + ZIP 导出

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

目标：用户选择零件 → 按模板顺序逐 View 拍照。当前 View 有 enabled ROI 时进入确认 UI，逐个选择 ROI OK/NG 和总体 OK/NG 后保存并进入下一 View；当前 View 无 ROI 时仍先真实拍照并保存到当前 batchId，随后直接进入下一 View。全部完成后生成包含所有原始照片和真实确认结果的 ZIP，统一写入一个 Excel 兼容 CSV（检测 CSV 可无 ROI 确认行）。

## 用户说明文档补充（2026-09-04）

- [x] 基于当前 MobileInspectionApp 源码和指定图片目录整理中文使用说明
- [x] 纳入 20 张操作/结果图片，按配置 → 采集 → 确认 → 导出 → 记录管理的业务时间顺序编排
- [x] 记录当前版本边界：DPM 实时扫码、人工确认、本机离线数据、软件检测结果未执行
- [x] 生成 `docs/user-guide/视觉质检MobileInspectionApp_使用说明.docx`
- [x] 完成 DOCX 图片数量、图片可访问性、章节/分页和文本完整性检查；LibreOffice 渲染因环境未安装 `soffice` 未执行

## 本轮追加优化：采集过渡与模板包闭环

归属：既有“单零件多 View 人工确认 + ZIP 导出”源码整改；自动化与真机验收仍待处理。本轮执行入口由本文顶部的 Android NCNN runtime 任务占用，本记录未被标记为完成。

- [x] 现场拍照成功后不再显示可见的“拍照”操作栏，直接进入有 ROI 的选择界面；保留照片真实落库和批次关联（补充防止相机就绪回调覆盖导航过渡状态）。
- [x] ROI 确认导航过渡期间隐藏现场页“照片已保存，进入人工确认”状态文案，避免出现拍照成功中间界面。
- [x] 保留现场页稳定骨架避免导航白屏；多视角模板名称统一由顶部切换器显示，移除图片下方重复视角名称；一级导航和 ROI 返回使用轻量淡入淡出。
- [x] 修复 `LiveInspectionScreen.kt` 导航布局调整后多余闭合括号导致的 `Expecting a top level declaration` 编译错误。
- [x] 将拍照后的 JPEG 校验、EXIF 读取和原子文件复制移至 `Dispatchers.IO`，避免大图保存阻塞主线程造成点击后卡顿。
- [x] ROI 确认保存后不再显示“确认并继续”残留栏，直接返回现场采集或进入导出页；确认页不显示根级三 Tab 导航。
- [x] 模板包导出保存 `Part`、DPM、全部 View/图片、顺序和全部 ROI 配置（含 `targetType`）。
- [x] 模板包导入能解析本应用导出的 manifest，并恢复模板图片、View 顺序和 ROI 配置；保留旧包兼容性。
- [x] 模板包页面支持按稳定 `partId` 删除整包，清理受管理模板图片和关联模板/ROI，不影响采集批次。
- [x] 追溯记录采集批次卡片将勾选框放在“X 视角”计数右侧，保持批次多选逻辑不变。
- [x] 补充自动化测试和 B2 报告；按当前范围不运行 Gradle、ADB、APK 或真机验收。

## 本轮修复：模板包导入失败（2026-09-04）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

- [x] SAF 复制 ZIP 使用系统唯一临时文件，并校验复制字节数和文件长度，避免空临时文件进入解析器。
- [x] 模板包解析将损坏/非 ZIP 文件转换为可读错误；兼容历史 manifest 的字符串 `imageFiles`、Windows 反斜杠和图片扩展名大小写差异。
- [x] 导入前拒绝没有有效视角图片的包，避免“导入成功但实际没有模板”。
- [x] Part、View 和 ROI 的替换写入放入 Room 事务；失败时旧模板保持不变，已复制的新图片清理。
- [x] 相册模板导入增加异常兜底和 `finally` 状态复位，失败后不会永久停在“导入中”。
- [x] 增加损坏 ZIP、历史图片引用兼容和无有效图片不替换旧模板测试；按范围未运行 Gradle、ADB、APK 或真机验收。

## 本轮修复：切换零件后模板图片与 ROI 偶发缺失（2026-09-04）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

- [x] 切换零件时保留相机预览仍有效的 `contentRect`，不因未重建 CameraX 预览而永久隐藏 ROI 框。
- [x] 模板流切换时先清空旧零件模板，并只接受 `partId` 与当前零件一致的模板。
- [x] 切换零件时先清除模板选择和视角索引，避免旧模板/旧 ROI 与新零件短暂组合。
- [x] 增加零件切换后的模板、选中模板和 ROI 数据隔离测试，以及 `contentRect` 保留契约测试。
- [x] `git diff --check` 通过；按范围未运行 Gradle、ADB、APK 或真机验收。

## 完成清单

- [x] 审计现有批次/照片/View 顺序/ROI/Repository 数据路径
- [x] 有 ROI 的 View 拍照成功后进入 ViewConfirmationScreen，不在拍照成功事件中自动推进
- [x] 按 templateId 加载当前 View 的全部 ROI，裁剪并显示
- [x] 展示 ROI ID、名称、targetType 和独立 OK/NG 选择
- [x] 展示并保存独立的整张照片总体 OK/NG 选择
- [x] 使用 normalizedRect 映射到实际照片 image contentRect 坐标
- [x] 保存 ViewRoiConfirmEntity（逐 ROI 独立行，含像素坐标）
- [x] 未确认时不默认 OK/NG，软件检测结果保持 null
- [x] View 确认完成后由导航层显式推进到下一 View；最后一 View 进入 ExportResultScreen
- [x] 返回/取消确认页不推进 View；不再依赖 DisposableEffect + LifecycleEventObserver
- [x] ViewRoiConfirmDao：getConfirmedViewIndices、deleteByBatchAndViewIndex
- [x] InspectionExcelExporter：UTF-8 BOM + 15 列 CSV
- [x] InspectionZipExportService：按 View 分目录导出全部照片，并将照片索引与真实确认结果合并为一个 `inspection_result.csv`
- [x] ExportResultScreen：SAF 下载 + Intent.ACTION_SEND 分享
- [x] RoiCoordinateMapper：parseNormalizedRect、mapToImagePixels、cropRoiBitmap、getImageDimensions
- [x] ContentRectBounds 纯 Kotlin 替代 android.graphics.Rect（单元测试兼容）
- [x] 前一阶段测试基线：550 项（545 passed / 0 failed / 5 skipped）
- [x] 更新 `docs/reports/b2/` 对应报告并等待验收

## 本轮修复：无 ROI View 与多 View 推进（2026-09-04）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

- [x] 只按当前拍摄 `templateId` 查询 enabled ROI；不使用零件、其他 View 或全局 ROI 数量
- [x] 无 ROI View 先保存原始照片、插入并回读真实 `photoId`，再按当前 `viewIndex` 直接推进
- [x] 有 ROI View 继续进入 ViewConfirmationScreen，确认完成后才显式推进
- [x] LiveInspectionScreen 与 AppNavigation 共享同一个 WorkbenchViewModel
- [x] `completeView(viewIndex)` 防止重复确认、过期回调、越界和跳过 View
- [x] 无 ROI 最后一个 View 更新批次 `endTime` 后进入 ExportResultScreen
- [x] 全无 ROI 批次允许生成 ZIP；所有 View 原始照片均从 `captured_photos` 打包
- [x] 无 ROI 不生成 ROI/人工/软件检测结果，不进入空确认页
- [x] 返回/取消确认页不推进；确认完成事件只消费一次
- [ ] 本轮自动化回归（受执行限制未运行，状态 `NOT_RUN_BY_SCOPE`）
- [ ] 真机验收：有 ROI、无 ROI、连续无 ROI、中间无 ROI、最后无 ROI 混合场景

## 人工验收问题二次整改（2026-09-04）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 问题 1：连续拍摄两张照片后 ZIP 只保留一张

**根因**：上一轮虽然修复了拍照状态复位，但 `currentBatchId` 仍保存在现场页的 `remember` 状态中。进入 ROI 确认页后现场页可能被销毁并重建，第二个 View 重新创建了 batch，最终导出页只导出了最后一个 batch，因此 ZIP 只有一张照片。

**修复**：有 ROI 照片落库后，导航前保留固定的 `SAVED`/“进入确认…”状态，避免切换窗口重新显示可点击的“拍照”按钮；返回现场页时按 `isScreenVisible` 统一复位为 `IDLE`。同时把活动 `batchId` 提升到根级共享的 `WorkbenchViewModel`，仅在切换零件、手动重新开始或最后一个 View 完成时清除。每个 View 复用同一未结束批次，导出器按 batchId 读取全部照片。

- 修改文件：`LiveInspectionScreen.kt`、`WorkbenchViewModel.kt`、`InspectionZipExportService.kt`
- 确认页返回或取消后均可稳定继续拍摄，取消时仍保持当前 `viewIndex`
- 每次拍照仍通过唯一文件路径、真实 `photoId` 和当前 View 关联保存

### 问题 2：检测记录只有一条

**结论**：当前无 ROI 自动检测算法，不应有 RoiInspectionRecordEntity。"一条检测记录"对应的是第一条 View 的 ViewRoiConfirmEntity（人工确认记录），这是正确语义。无 ROI View 不生成确认记录，照片仍保存在 captured_photos 表中。不伪造 PASS/FAIL。

### 问题 3/4/5/6：布局稳定性

**根因**：
- 状态提示区域使用 `heightIn(min=28.dp)` ，内容出现/消失时高度变化导致底部按钮跳动
- AllViewsCapturedCard 高度和内部文字/重启按钮宽度未固定，长文案会挤压操作区
- ViewConfirmationScreen 错误消息动态出现/消失推动按钮移动

**修复**：
- `TemplateReferenceSection` 的视角标题栏固定为 `height(32.dp)`
- `CaptureActionBar` 提升为现场页 Scaffold 的固定 `bottomBar`，内部固定 `height(52.dp)`，按钮固定为 `height(40.dp)`
- `TemplateOverlayControls` 固定为 `height(48.dp)`
- `AllViewsCapturedCard` 改为紧凑的固定 `height(64.dp)`，文字区域允许收缩，重新开始按钮固定宽度和触控区域
- 拍照状态区域固定为 `height(28.dp)`，错误提示改为单行 Row，不再超出状态槽位
- `ViewConfirmationScreen` 使用 Scaffold `bottomBar` 固定承载确认栏，确认栏固定为 `height(140.dp)`
- ROI 列表和确认栏分离，选择结果、错误提示和保存状态不会推动确认按钮上下移动
- 确认按钮文案固定为“确认并继续”，未完成提示使用独立固定高度槽位，状态变化不改变按钮布局
- 有 ROI 导航期间现场页主操作保留固定禁用槽位，按钮文案显示“进入确认…”，返回后再恢复“拍照”

### 本轮二次整改实际修改文件

源码文件（7 个）：
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`

测试文件（7 个）：
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/NoRoiViewAdvancementTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/MultiViewPhotoPersistenceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`

### 本轮测试状态

- 自动化测试：**未执行**，原因是执行限制（禁止 Gradle）
- 已补充拍照状态、无 ROI 推进、确认页固定栏、批次稳定 key 和导出提示固定槽位的回归契约
- 预计总测试数以 Gradle 实际运行结果为准（本轮受范围限制未执行）
- APK：未构建（`NOT_RUN_BY_SCOPE`）
- 真机：未执行（`NOT_RUN_BY_SCOPE`）
- Git：未提交；保留工作区已有脏改动

### 本轮二次整改源码审计结果（2026-09-04）

对 3 个受影响页面逐项审计，结论：**已完成源码修复，待自动化和真机验收**。

| 审计项 | 结果 |
|---|---|
| LiveInspection: 无 ROI 路径调用 completeView 直接推进 | ✅ |
| LiveInspection: 有 ROI 路径导航 ViewConfirmationScreen | ✅ |
| LiveInspection: 有 ROI 导航前保留 SAVED 过渡状态，返回可见时统一复位 | ✅ |
| LiveInspection: 拍照失败不调用 completeView | ✅ |
| LiveInspection: 照片插入后回读 photoId 并校验关联 | ✅ |
| LiveInspection: 只按 capturedTemplateId 查询 ROI | ✅ |
| LiveInspection: 无 DisposableEffect/LifecycleEventObserver | ✅ |
| LiveInspection: COMPLETED 路径先 finishCaptureBatch 再 navigate | ✅ |
| WorkbenchViewModel: completeView 幂等（IGNORED for stale/duplicate） | ✅ |
| WorkbenchViewModel: ADVANCED/COMPLETED/IGNORED 三态正确 | ✅ |
| AppNavigation: 根级共享 WorkbenchViewModel 传入 LiveInspection | ✅ |
| AppNavigation: onConfirmed 使用 workbenchViewModel.completeView | ✅ |
| AppNavigation: onBack 只 popBackStack 不推进 | ✅ |
| AppNavigation: onNavigateToExport 导航 ExportResult | ✅ |
| ViewConfirmationScreen: completionHandled 一次性消费 | ✅ |
| ViewConfirmationViewModel: 校验 photo 关联后才加载 ROI | ✅ |
| ViewConfirmationViewModel: rois.isEmpty() 拒绝空确认 | ✅ |
| ViewConfirmationScreen: 确认栏由 Scaffold bottomBar 固定承载 | ✅ |
| TraceRecords: 批次列表使用稳定 batchId key | ✅ |
| TraceRecords: 导出提示使用固定单行槽位 | ✅ |
| CapturedPhotoDao: insert 返回 Long，getById 存在 | ✅ |
| InspectionRepository: insertCapturedPhoto 返回 Long | ✅ |
| InspectionRepository: finishCaptureBatch 仅当 endTime==null 时更新 | ✅ |
| WorkbenchViewModel: 活动 batchId 跨确认页重建保持不变 | ✅ |
| TraceRecords: 使用完整 InspectionZipExportService，不再走照片专用导出 | ✅ |
| InspectionZipExportService: 所有 batch 照片按 View 分目录，并与真实确认结果合并写入一个 inspection_result.csv | ✅ |
| InspectionZipExportService: 照片为空才失败，确认行可空且不伪造结果 | ✅ |
| AppNavigation: 确认/导出子流程隐藏根级底部导航 | ✅ |

### 本轮新增测试覆盖（14 项要求对照）

| # | 要求 | 测试文件 | 状态 |
|---|---|---|---|
| 1 | 有 ROI 进入确认页 | ViewConfirmationNavigationTest | ✅ |
| 2 | 无 ROI 不进入确认页 | LiveInspectionCaptureStateTest + CapturedPhotoPersistenceContractTest | ✅ |
| 3 | 无 ROI 自动进入下一 View | LiveInspectionCaptureStateTest | ✅ |
| 4 | 无 ROI 拍照失败不推进 | NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 5 | 无 ROI 照片真实保存到 batchId | CapturedPhotoPersistenceContractTest + NoRoiViewAdvancementTest | ✅ |
| 6 | 连续多个无 ROI View 按顺序拍摄 | NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 7 | 无 ROI 中间 View 不被跳过 | NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 8 | 最后无 ROI 进入 ExportResultScreen | LiveInspectionCaptureStateTest + NoRoiViewAdvancementTest | ✅ |
| 9 | 有 ROI 确认完成只推进一次 | WorkbenchViewModelAdvanceTest | ✅ |
| 10 | 返回/取消确认不推进 | ViewConfirmationNavigationTest | ✅ |
| 11 | viewIndex/templateId 一致 | LiveInspectionCaptureStateTest | ✅ |
| 12 | ZIP 包含所有 View 原始照片 | InspectionZipExportServiceTest + NoRoiViewAdvancementTest（本轮新增） | ✅ |
| 13 | 无 ROI 不生成虚假 ROI/PASS/FAIL | NoRoiViewAdvancementTest（本轮新增） + ViewConfirmationFlowTest | ✅ |
| 14 | 前序功能不回归 | CapturedPhotoPersistenceContractTest + InspectionZipExportServiceTest | ✅ |

### 本轮测试与状态

- 自动化测试命令：未执行（用户明确禁止 Gradle；`NOT_RUN_BY_SCOPE`）。修改前历史基线为 550 项（545 passed / 0 failed / 5 skipped）。
- 本轮已补充多 View 批次复用、ZIP 综合 CSV 和确认/导出导航门禁；总测试数以解除限制后实际运行结果为准。
- APK：未构建（`NOT_RUN_BY_SCOPE`），无新的 APK 路径、时间、大小或 SHA-256。
- 真机：未执行（`NOT_RUN_BY_SCOPE`）。
- Git：未提交；保留工作区已有脏改动。

## 实际修改文件

### 新增文件（12 个）
- `data/entity/ViewRoiConfirmEntity.kt` — 逐 ROI 人工确认实体
- `data/dao/ViewRoiConfirmDao.kt` — DAO（Flow 观察、按 View 删除、已确认 View 索引查询）
- `data/export/InspectionExcelExporter.kt` — 15 列 CSV 生成器（UTF-8 BOM）
- `data/export/InspectionZipExportService.kt` — ZIP 打包服务（照片 + CSV）
- `ui/screens/RoiCoordinateMapper.kt` — normalizedRect → contentRect → imagePixels 坐标映射
- `ui/screens/ViewConfirmationViewModel.kt` — 确认页 ViewModel（裁剪、选择、保存）
- `ui/screens/ViewConfirmationScreen.kt` — 确认页 Compose UI
- `ui/screens/ExportResultScreen.kt` — 导出结果页（统计 + 下载 + 分享）
- `ui/screens/ContentRectBounds.kt` — 纯 Kotlin 坐标数据类（替代 android.graphics.Rect）

### 测试文件（5 个）
- `RoiCoordinateMapperTest.kt` — 16 项（坐标映射、裁剪、边界）
- `ViewRoiConfirmEntityTest.kt` — 16 项（实体字段、OK/NG 保留、JSON 格式、targetType）
- `InspectionExcelExporterTest.kt` — 15 项（CSV 头、行值、NG 不丢、BOM、escapeCsv）
- `InspectionZipExportServiceTest.kt` — 6 项（ZIP 包名、结果类型、文件结构）
- `ViewConfirmationFlowTest.kt` — 11 项（多 View 行数、NG 保留、批量隔离）

### 修改文件（6 个）
- `data/db/AppDatabase.kt` — 版本 5→6，新增 ViewRoiConfirmEntity 和 DAO
- `data/db/Migrations.kt` — MIGRATION_5_6（view_roi_confirms 表）
- `data/repository/InspectionRepository.kt` — 新增 6 个方法
- `MobileInspectionApp.kt` — repository 构造参数新增 viewRoiConfirmDao
- `ui/navigation/Screen.kt` — ViewConfirmation 路由（8 参数）+ ExportResult 路由（3 参数）
- `ui/navigation/AppNavigation.kt` — 注册 ViewConfirmation 和 ExportResult composable
- `ui/screens/LiveInspectionScreen.kt` — 拍照后按 ROI 分支 + 显式 View 完成推进

## 测试命令及结果

```
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```

BUILD SUCCESSFUL — 527 项（522 passed / 0 failed / 5 skipped）

新增 64 项测试全部通过：
- RoiCoordinateMapperTest: 16 passed
- ViewRoiConfirmEntityTest: 16 passed
- InspectionExcelExporterTest: 15 passed
- InspectionZipExportServiceTest: 6 passed
- ViewConfirmationFlowTest: 11 passed

## Bug Fix 历史记录（2026-09-04 现场验收）

### 问题 A：拍照成功提示被遮挡且文案错误
- **原因**：SAVED 状态显示"已保存，切换下一视角"，但实际流程是进入人工确认页
- **修复**：改为"照片已保存，进入人工确认"，增加 `maxLines=1` + `TextOverflow.Ellipsis`

### 问题 B：确认完成后没有推进到下一 View（已由本轮显式推进替代）
- **原因**：`DisposableEffect(lifecycleOwner)` 创建 observer 时闭包捕获 `pendingAdvance` 初始值 `false`，后续 `pendingAdvance` 变为 `true` 时 observer 读不到
- **修复**：增加 `rememberUpdatedState(pendingAdvance)` 和 `rememberUpdatedState(currentBatchId)`，observer 内部读取 `pendingAdvanceRef` / `currentBatchIdRef`

### Bug Fix 修改文件
- `ui/screens/LiveInspectionScreen.kt` — 2 处修改：
  1. SAVED 提示文案改为"照片已保存，进入人工确认"
  2. DisposableEffect 使用 `rememberUpdatedState` 避免闭包捕获旧状态

### Bug Fix 新增测试文件（3 个，23 项）
- `ui/screens/LiveInspectionCaptureStateTest.kt` — 11 项（文案、overflow 保护、rememberUpdatedState、DisposableEffect 结构）
- `ui/screens/workbench/WorkbenchViewModelAdvanceTest.kt` — 5 项（advanceToNextView 顺序推进、末尾不越界、resetViewIndex）
- `ui/navigation/ViewConfirmationNavigationTest.kt` — 7 项（onConfirmed 非最后/最后 View 导航、onBack 不推进、路由参数）

### Bug Fix 历史测试结果
```
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks
```
BUILD SUCCESSFUL — 550 项（545 passed / 0 failed / 5 skipped）

新增 23 项测试全部通过，无回归。

---

## 采集批次/零件 ZIP 清理

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

- [x] 点击多个批次卡片或复选框可多选，并显示清晰选中状态（Primary 边框 + BackgroundVariant1 背景）
- [x] 选中一个或多个批次后启用右侧垃圾桶 IconButton；未选中时灰色禁用
- [x] 删除前弹出确认框，显示选中批次数量、零件名/批次 ID 摘要和删除内容
- [x] 按稳定 `batchId` 集合逐个精确删除，不按列表位置、名称或全局目录删除
- [x] 删除成功刷新列表并清除选中集合，显示批量删除数量 Snackbar 提示
- [x] 删除失败保留未删除批次的选中状态并显示明确错误
- [x] 正确处理照片文件删除；数据库 CASCADE 删除 captured_photos 和 view_roi_confirms
- [x] 其他零件、批次、模板图片和 ROI 不受影响
- [x] 选中集合包含导出中的批次时整体禁止删除，给出明确提示
- [x] 补充 batchId 匹配、多选切换、部分失败保留、照片隔离、导出冲突和实体字段测试

---

## 采集批次筛选、删除交互与布局稳定性优化

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 一、时间筛选

- [x] `BatchTimeFilter` 枚举：今日 / 近 3 天 / 近 7 天 / 所有（默认"近 7 天"）
- [x] `CaptureBatchDao.observeByStartTimeSince(sinceMillis)` — 基于 `startTime` 的 Room Flow 查询
- [x] `InspectionRepository.observeCaptureBatchesSince(sinceMillis)` — 透传 DAO
- [x] 标题栏紧凑 DropdownMenu 筛选器，当前选项始终可见
- [x] 中文选项名：今日 / 近 3 天 / 近 7 天 / 所有
- [x] "今日"按本地日期 00:00 开始；"近 3 天"含今天及前 2 个自然日；"近 7 天"含今天及前 6 个自然日
- [x] 基于数据库 `startTime` 字段筛选，不按文件名或列表位置
- [x] 空时间历史记录只在"所有"中显示，不自动猜测日期
- [x] 筛选只影响列表展示，不删除/修改/重新生成批次数据

### 二、标题栏布局稳定性

- [x] 固定结构：[采集批次] [时间筛选固定槽位] [垃圾桶固定槽位]
- [x] 筛选器位置和宽度稳定（DropdownMenu + 固定 padding）
- [x] 垃圾桶始终占位，未选中时灰色禁用（不隐藏，不导致布局跳动）
- [x] 不插入"已选择 1 个"新行，避免列表下移
- [x] 卡片选中样式通过 border + background 色实现，不改变卡片尺寸

### 三、批次多选与删除

- [x] 点击卡片或复选框切换选中状态，绑定稳定 `selectedBatchIds: Set<String>`
- [x] 切换时间筛选时 `LaunchedEffect(activeFilter)` 清除选中集合
- [x] 删除确认框显示选中批次数量、最多 3 个批次摘要和删除内容说明
- [x] 删除只作用于当前 `selectedBatchIds` 对应的稳定 `batchId` 集合
- [x] 选中集合包含正在导出的批次时整体阻止删除，避免部分删除
- [x] 批量删除成功后刷新列表、清除选中集合、Snackbar 浮层提示删除数量
- [x] 批量删除部分失败时仅移除已成功删除的 ID，保留其余选中项便于重试
- [x] 删除期间禁用垃圾桶按钮，标题栏尺寸不变

### 四、Snackbar 浮层避免列表跳动

- [x] 使用 Scaffold `snackbarHost` 替代列表内 `item { Text }` 提示
- [x] Snackbar 悬浮显示，不改变 LazyColumn 布局高度
- [x] 成功/失败提示均通过 SnackbarHostState 管理
- [x] 不新增永久性成功状态卡片

### 五、空状态

- [x] 今日暂无采集批次 / 近 3 天暂无采集批次 / 近 7 天暂无采集批次 / 暂无采集批次
- [x] 非"所有"筛选为空时提供"查看所有"轻量操作

### 六、测试

- [x] 新增 `BatchFilterAndDeleteTest.kt`（47 项 JVM 测试，含多选与批量删除契约）
- [ ] 自动化回归（受执行限制未运行，`NOT_RUN_BY_SCOPE`）
- [ ] 真机验收（受执行限制未执行，`NOT_RUN_BY_SCOPE`）

### 本轮实际修改文件

源码文件（3 个）：
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt` — 重写：时间筛选、稳定布局、Snackbar、空状态
- `app/src/main/java/com/wearable/inspection/mobile/data/dao/CaptureBatchDao.kt` — 新增 `observeByStartTimeSince`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt` — 新增 `observeCaptureBatchesSince`

测试文件（1 个新增）：
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/BatchFilterAndDeleteTest.kt` — 35 项 JVM 测试

### UI 布局测试覆盖说明

Compose 布局 bounds 断言（标题栏高度不变、筛选器与垃圾桶不遮挡）需要 instrumented 测试或 Screenshot 测试框架。本轮仅在 JVM 层覆盖状态逻辑，布局稳定性通过代码结构保证（固定 Row 权重、固定 padding、始终占位的 IconButton）。待解除 Gradle 限制后补充 instrumented 验证。

---

## 已完成任务：模板 ROI 属性选择

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

---

## 已完成任务：模板视角 ROI 长按删除回归整改

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

---

# 历史任务

## 按采集批次导出照片 ZIP + UI 压缩

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## B2 Task 1：旧 DPM 识别链迁移与实时扫码闭环

**状态**：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-02）

## B2 Task 2：旧模板导入 + 模板透明叠加 MVP

**状态**：**SOFTWARE_COMPLETE**（2026-09-02，提交 `bdf1bd89`）

## 模板配置重构与逐视角 ROI

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

## B1 完成门禁

B1 已完成并关闭（提交 `b7c4c08e`）。

---

## 附加离线回归：NutPresenceDetector Key 与负样本

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

- [x] 自动发现 Key 中全部 `nut_*.png|jpg|jpeg`，当前 5 张样本均按用户确认的 `expectedCount=2` 检出 2 个最终主体框
- [x] 保留 `bodyHexAngleCandidates` 配置接口，但默认使用稳定 `0°` 主体几何先验；避免 Canny 在垫圈/背景边缘上选择 `-20°/20°`，并将主体 box 限制在证据组件内
- [x] 生成 8 张 Nut 负样本，全部 `candidateCount=0`、`boxes=[]`
- [x] 基于 5 张 Nut Key 原图派生 5 张无螺母零件负样本，移除区域外原始像素保持不变；5/5 `candidateCount=0`、`boxes=[]`
- [x] 更新 Nut Key debug 图、contact sheet、机器可读结果和 B3 报告
- [x] 原图派生负样本专项回归：4/4 通过；此前完整 unittest 门禁 `17/17 PASS`，本轮未修改 Thread

---

## 28. 拍照后确认页卡顿、现场页残影与未完成批次导出门禁（2026-09-04）

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

### 根因与修复

- `ViewConfirmationViewModel` 原先在主线程协程中读取现场照片尺寸并逐个解码/裁剪 ROI。现在尺寸读取、ROI Bitmap 裁剪和现场模板参考图解码均在 `Dispatchers.IO` 执行，Bitmap 结果回到主线程后一次性写入状态。
- CameraX 现在先写入受管理的 `files/captures` 临时路径，`MobileImageStore.atomicMoveToFinal()` 同目录优先重命名，跨目录场景才回退 `.part` 复制；拍照存储复用移动前校验结果，不重复解码整张照片。
- `TemplateContent` 的参考图卡片填满父布局可用高度，移除 `maxHeight` 造成的透明度栏上方大块空白。
- 确认/导出子流程的 NavHost 过渡改为无动画切换，避免现场 CameraX 预览、模板图和透明度栏在路由切换时残留；确认页保留自己的顶部返回入口和底部确认栏，根级三 Tab 导航继续按子流程门禁隐藏。
- `InspectionZipExportService` 和追溯记录卡片均要求 `CaptureBatchEntity.endTime != null`，并在导出前校验 `viewCount` 个视角索引均有照片；采集中的批次只显示“完成后导出 ZIP”，不能创建或导出 ZIP。
- `TemplateCaptureViewModel` 在启动异步拍摄前立即锁定 `Capturing` 状态，避免连续点击并发新增两个相同编号的模板视角。

### 实际修改文件

源码：

- `app/src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplateCaptureViewModel.kt`

测试：

- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionCaptureStateTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationPerformanceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/navigation/ViewConfirmationNavigationTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/export/InspectionZipExportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/data/image/MobileImageStoreCapturePathTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplateCaptureConcurrencyTest.kt`

### 验证状态

- `git diff --check`：通过；仅保留工作区既有的 LF/CRLF 警告，无 whitespace error。
- Gradle/JVM 自动化测试：`NOT_RUN_BY_SCOPE`；当前唯一任务禁止执行 Gradle。
- ADB、APK 构建/安装、启动/停止和真机视觉验收：`NOT_RUN_BY_SCOPE`；无新的 APK 路径、时间、大小或 SHA-256。
- Git：未提交；保留工作区中其他已有改动。
- 待验收：拍照后直接进入确认页、确认页布局与返回入口、模板图下方空白消除、未完成批次不可导出，以及全部 View 完成后 ZIP 才生成。

---

## 检测结果追溯与 DPM 扫码证据进度摘要

状态：**T1_IMPLEMENTATION_JVM_AND_DEVICE_UI_PASS / T2_FIXED_IMAGE_ANDROID_PREPROCESS_DECODER_PARITY_PASS_WITH_TEST_NCNN_RUNTIME / T2_PRODUCTION_JNI_PARITY_PENDING / T2_ASSET_PROVENANCE_REVIEW_PENDING / T4_SOFTWARE_AUDIT_PENDING / FIELD_VALIDATION_PENDING**（2026-09-28）。SEA-AL10 上本轮可见的三份 instrumentation XML 共 18 tests / 0 failures / 0 errors / 0 skipped：PartColor UI 12 项、模型加载与合成候选 4 项、固定真图 parity 2 项。固定真图证明生产 Kotlin 预处理与 decoder 在 Android NCNN 测试 JNI runtime 上和 Windows 桌面参考匹配；测试没有调用生产 JNI 库 nanodet_ncnn_runtime，且只覆盖一张图。exp23 的 Android 实际 Mat 宽度已在该固定图测试中读为 34。资产来源身份仍未独立核验，两个模型保持 assetsVerified=false。提交问题按路径拆分审阅，不把整个混合工作区作为一个批次。

计划顺序：

1. **模板叠加默认透明度改为 0%** — **USER_ACCEPTED**；首次进入 `alpha=0f`，透明度可用现有滑杆提高到 0%～80%。验收记录见 `docs/reports/b2/TEMPLATE_OVERLAY_ALPHA_ZERO_REPORT.md`。
2. **DPM 扫码会话图像证据留存** — **DONE**；成功时绑定精确读码帧。无 ECC 成功时不保存最后有效帧、ROI 或 `NO_READ` 证据。不改扫码算法。见 `docs/reports/b3/DPM_SCAN_EVIDENCE_REPORT.md`。
3. **DPM 成功帧/ROI 合并到采集批次 ZIP** — **USER_ACCEPTED**（2026-09-16）；保留 View 原图、现有确认记录和 CSV 兼容字段，并通过稳定 ID 关联成功 DPM 源帧/扫描 ROI；独立 DPM ZIP 保持独立，不包含 DPM 人工复核。真机闭环证据见本文顶部和 B3 导出报告。
4. **DPM 人工码值复核：暂不排期** — 当前没有准确、独立的 DPM 码真值可供人工判断读码是否正确；`Part.dpmCode` 不能当作真值。现阶段只留存扫码图像证据和原始解码状态。待有可信真值数据并重新确认需求后再评估人工复核流程。
5. **NanoDet ROI 推理、确认与基础元数据导出** — Android NCNN runtime、静态照片 ROI 推理和先前确认/持久化及 CSV 元数据实现已完成；更早的“尚未接入”状态已过期。当前进行中的后续口径为本文顶部任务：单一最终 `result`、模型结果只作为人工按钮默认选择、人工独立总体结果、改判才额外保留原模型值/人工值/标记/时间及 ROI 照片，并让 CSV 回链真实 ZIP 文件。`NUT→class 0`、`THREAD→class 1`，`FEATURE` 不受模型支持；0.37 仍是未校准起始值，不属于当前任务的阈值校准范围。完整历史模型验证见 [`docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md`](../docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md)。

边界：人工确认 OK/NG 不等于模型质量判定；模型和推理链已集成，但 0.37 未经代表性验证集校准。DPM 证据只按稳定 `scanSessionId` 和显式关联的真实 `batchId` 归属；清理批次不级联删除独立 DPM 证据。当前 ROI 最终语义/证据图任务完成前，不得声称 ROI ZIP 证据交付完成。实现与测试门禁以本文顶部和 `tasks/plan.md` 最新状态更新为准。

### 2026-09-18 主协调只读审计更新

- 核心源码已具备：模型结果与人工结果分开保存，模型有 OK/NG 时人工结果默认采用模型值；双向改判保存 `humanChangedModel`、`overrideTime` 和受管理 ROI 证据图；总体结果由人工独立选择；确认页不展示模型建议、分数、阈值或模型版本；ZIP 证据写入后再回填 CSV 真实条目路径。
- 当前定向复跑命令覆盖 10 个 ROI/确认/导出相关测试类，共 99 项：**98 通过、1 失败**。失败为 `ViewModelSaveLifecycleTest.dbSaveFailureCleansUpNewEvidenceFiles`；测试在加载阶段因 `createTestPhoto()` 生成的 JPEG 无法解码而退出，实际没有进入预期的数据库保存失败分支。因此“数据库失败时清理本轮新文件”仍未取得有效通过证据。
- 当前待完成：修正生命周期测试的真实 JPEG 夹具或等效可验证夹具；重新执行该 4 项生命周期测试及 ROI 定向集合；重新生成编译、APK 和必要的 instrumented/真机证据；再等待用户验收。
- 当前未发现 DPM、CameraX、批次清理或 OCR 需要并入本任务；OCR 按用户指示暂不列入未完成项。
- 本次审计未修改生产代码、未构建新 APK、未提交 Git；工作区审计前为干净状态。
