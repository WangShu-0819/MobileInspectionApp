# MobileInspectionApp 新 Codex 对话续接指令

将本文件作为新对话的项目协作说明。当前续接指令已更新于 2026-09-30，详细当前状态以 `docs/CODEX_CONTINUATION_PROMPT_MOBILE_INSPECTION_20260929.md` 和 `tasks/todo.md` 顶部为准；本文件下方较早的状态段落保留作历史审计记录，不应覆盖新状态。开始工作时重新核对用户最新消息、工作区源码/diff 和完整 Git 状态。新续接指令已明确用户本轮报告 T7.1、T7.2、T7.3–T7.5、T7.7 设备验收完成；T7.6 根因未定位；T7.8 handback 已由 Mimo 报告；T7.9 仍有待复核事项。

## 新对话首条指令（可直接复制）

```text
请继续协作项目 MobileInspectionApp。我会把旧 Codex 对话作为背景提供；把旧回答、Mimo handback、日志和旧任务状态当作待核对材料。先读 `docs/CODEX_CONTINUATION_PROMPT_MOBILE_INSPECTION_20260929.md`，再通读本 handoff、`tasks/todo.md` 顶部及完整 T7 规格、`tasks/plan.md` 当前指针，并核对最新用户消息、相关源码、完整 Git 状态和对应 diff。不要重复已完成的 T1/T2；冲突时以我最新明确指示和当前工作区证据为准。

协作边界：你负责中文方案分析、只读代码/diff/报告审查、用户明确要求的文档更新，以及编写可由用户手动转交给 Mimo 的详细中文指令。代码修改、测试、Gradle/构建、APK 和设备操作由外部执行者 Mimo 完成；用户会手动转交指令并带回结果。Mimo 不是 Codex 子 agent；不要调用子 agent、delegation 或任何 collaboration 工具，也不要联系 Mimo。不要修改生产代码，不要自行运行项目测试、Gradle、ADB、设备操作或 OCR。保留共享工作区现有改动，不整体暂存，不使用 reset/clean/stash，也不 push。仅当用户明确授权提交时，才按授权范围选择性提交；不得将文档提交与未完成代码/测试混在一起。用户明确指定文档并要求更新时才改文档。附件截图对 Mimo 不可见，必须把视觉要求写成文字验收标准，不能要求 Mimo 打开附件或 `app_launch.png`。

当前状态：用户本轮报告 T7.1、T7.2、T7.3–T7.5、T7.7 设备验收完成；Mimo 2026-09-30 handback 报告拍照至相似度流程 3 轮未复现闪退，用户随后报告亲自验证通过、不卡顿、不闪退，但 T7.6 仍须记录根因未定位/`DEVICE_AVAILABLE_BUT_REPRO_FAILED`。T7.8 的 1,621 项测试汇总和 Debug 构建结果来自 Mimo handback，不是 Codex 本轮运行。T7.9 简洁卡片和 `NanoDet 检测中…`/`相似度检测中…` 阶段提示获 handback 与用户验收支持，设备性能样本仅覆盖单 ROI；当前 diff 的只读复核事项见 `tasks/todo.md`，不要把静态疑点描述为已运行测试失败。ECC 仅为方案分析，用户未要求实施。其他当前任务状态以 todo 为准。

每轮结束时，明确区分 Mimo/用户报告与自己实际只读复核的事实；无本轮证据就写未验证。若需要给 Mimo 指令，提供完整可复制的中文文本、文件范围、验收标准和设备不可用时的状态，不通过工具转交。
```

## 角色与协作方式

你是 MobileInspectionApp 项目的主协调 Codex，始终使用中文。你负责拆解任务、只读审查工作区和 Mimo handback、给用户可直接转交的完整 Mimo 指令，并仅在用户明确授权范围内更新文档。

代码修改、测试、Gradle/APK 构建及设备操作由外部执行者 Mimo 完成。Mimo 不是 Codex 子 agent；用户会手动转交指令并带回 handback。不要调用子 agent、delegation 或 collaboration 工具，也不要自行联系 Mimo。

不自行修改生产代码；不运行项目测试、Gradle、ADB、instrumented tests、真机操作或 OCR。只读检查源码、XML、APK 身份、日志和 Git 状态可按需进行。没有本轮运行就明确写“本轮未运行”；历史 XML/APK 不能说成本轮新结果。

## 每轮审计流程

开始审计依次执行：

1. 查看 `tasks/todo.md` 顶部。
2. 查看 `tasks/plan.md` 当前指针。
3. 查看当前或最近相关报告/handback。
4. 执行 `git status --short --branch --untracked-files=all`。
5. 执行 `git log -5 --oneline`。
6. 查看 `git diff` 并列出未跟踪文件。

之后按需只读核对路径级源码 diff、测试 XML、APK 生成时间/大小/完整 SHA-256 和 `git diff --check`。结束前再次获取完整 Git 状态，区分本轮 Codex 的文档改动与开始前已有改动。不要将旧文件或旧构建误报为当前产物。

## 当前续接状态（2026-09-30）

`tasks/todo.md` 顶部仍是唯一详细任务状态来源；`tasks/plan.md` 开头是当前执行顺序。

- T1/T2 已完成；T3 离线实验已完成但不是现场校准；T4 软件实现按历史 handback 完成。当前 SSIM 设置默认值 `0.50`，由用户明确且本轮只读源码确认；Lowe 比率 `0.75` 是不同参数。T5/T6 等现场采集和校准数据。
- T7.6：Mimo 报告在 SEA-AL10 / Android 10 / API 29 / arm64-v8a 上 3 轮未复现；用户报告本轮亲自验证通过。根因仍未证实，不得写成已定位或永久修复。Mimo 所报 APK SHA-256 为 `dc2af170c4d60972f372eec45a445b4e890358537c69f68d9b68e5a959cd6ea0`，242,570,104 bytes；详情与限制见 todo 和 handback。
- T7.9：Mimo 报告 1,621 tests、0 failures/errors、5 skipped，Debug 构建成功；单 ROI 的设备阶段时延及 Native PSS 有记录。用户确认当前界面简洁、带明确 NanoDet/相似度阶段提示且不卡顿。多 ROI 真机性能未验证。ECC 仅做了方案分析，尚未授权实施。
- ECC 插入点只读定位在 ROI `warpPerspective` 初始变换后、`grayscaleSsim` 前。若获用户明确授权，先做标签样本离线 A/B，从单位变换和受限平移开始，检查变换上限、有效重叠、失败回退、误通过、每 ROI 时延和 Native PSS；保持逐 ROI 几何门禁及人工确认。当前无 ECC 代码改动或验证。
- 最近只读 Git 状态：`main` HEAD 与 `origin/main` 均为 `e7fab67f`，23 tracked paths modified、11 untracked paths、0 staged。任务分批提交：`3866c3a6` T7.1/T7.3、`06bee44e` T7.2、`a7c6ea16` T7.4、`c2853341` T7.5、`e7fab67f` T7.6。远端跟踪 ref 更新来源未确认；本轮无 commit/push。新对话必须重新读取实时状态。

### 2026-09-29 历史任务状态快照（已被上方续接状态覆盖）

`tasks/todo.md` 顶部是唯一当前任务状态来源；`tasks/plan.md` 开头是当前执行指针。旧聊天、历史 handback、XML、APK 或下方历史任务段落都需要与最新状态核对。

- **T1/T2：**按当前任务文档既有记录已完成；不因旧对话内容重复做已完成工作，不重编/重转模型，除非有新证据。
- **T3/T4：**T3 离线实验完成但不是现场校准；T4 ROI SSIM 实现按历史 handback 完成。原 SSIM 阈值 0.75 和 Lowe 配准比率 0.75 是独立参数。
- **T5/T6：**软件交付后的现场数据、人工标签、校准与独立留出工作。
- **T7.1、T7.3–T7.5、T7.7：**实现有 handback 报告，仍需对应设备验收。
- **T7.2 现场采集悬浮参考图：**用户最新要求收起态只显示参考图片；没有编号、说明、状态或切换入口。浮窗紧凑，白边和边距小，视角多于两个时也不扩张占据大区域。图片保持原始宽高比完整显示；只有展开态显示视角信息与简洁切换操作。详见 `tasks/todo.md`。无设备视觉证据不得标记验收通过。
- **T7.6 拍后 ROI 闪退：**用户最新报告当前版本仍会闪退，根因未定位；这轮没有新的崩溃 logcat、tombstone 或可核对复现步骤。此前状态 `DEVICE_AVAILABLE_BUT_REPRO_FAILED` 的 SEA-AL10 报告记录 Android 10/API 29/arm64-v8a、App 1.0.0；当时设备安装 APK 和本地 APK 均 82,788,154 bytes，SHA-256 `0885ad58bbf0c5afce1cc7e5ef037b71b406d1110823f1b980062ac8534b7d96`，与另一 Mimo handback 所报约 241,718,669 bytes / `2d9400895cf...` 不一致。报告称重装后的前三次启动发生系统 OOM kill，PSS 约 3.5–5.3 GB；没有 App Java FATAL EXCEPTION、近期 tombstone 或 `[DIAG-VC]`，稍后的 PID 32441 稳定约 156 MB。数据库 user_version=0 且无表，无法复现拍照→确认页。最新 Mimo handback 报告设备验证 `DEVICE_UNAVAILABLE`。这些报告均不是当前 Codex 直接采集的设备证据。启动 OOM 与拍后闪退之间没有证据关联；需要对用户当前复现的确切 APK 收集完整日志、设备/APK 身份、真实操作步骤与内存时间线。确认页先出现，任务关注页面显示后推理结果延迟，不要将工作改写为调整首次显示时机。
- **T7.9 拍后 ROI 推理、人工确认与阈值：**
  - NanoDet 与 ROI SSIM 可对支持的 THREAD、NUT、BOLT、NUTSERT 并行计算，任务总量有界。只在 NanoDet 明确 NG 时采用相似度候选；NanoDet OK 立即发布 NanoDet OK 候选并忽略 SSIM 结果；若该 ROI 的 SSIM 已启动，人工控件等其完成或取消并清理后再显示。错误、缺结果、目标不支持或无法明确判 NG 时不采用 SSIM、不自动判 NG。
  - 每 ROI 单独发布。NanoDet NG ROI 在自身相似度结束前不得设置自动默认 NG。该 ROI 的 NanoDet 与所有适用后台工作未完成前显示简洁“检测中”，隐藏/禁用该 ROI 人工确认和改判；该 ROI 已启动的后台工作完成或取消并清理后才展示人工控件及有效候选初始值。一个 ROI 不等待别的 ROI 的慢任务。候选只预选，不自动提交；人工选择一旦可用并被用户作出就不能被后台覆盖。确认卡片不显示相似度候选说明、分数、阈值或来源；“部件”属性显示“暂无”或隐藏。
  - 应用设置新增“算法调试”分组与“ROI 相似度阈值”：默认 0.75，0.00–1.00，步进 0.01，两位小数显示、恢复默认。SettingsStore/现有 SharedPreferences 独立键；非法类型、非有限或越界值需安全处理。一次推理快照一个阈值供该批所有 ROI 使用；只影响 ROI SSIM 候选，分数等于阈值判 OK；不改 NanoDet/Lowe/其他匹配阈值、类别映射或 ROI 几何。保存每条实际采用结果的真实阈值，历史不随设置变更。
  - SSIM 实际运行但 NanoDet OK 不采用时，DB/导出必须记录准确的“已计算但未采用”，不能伪写“未运行”。核对状态映射和 CSV/Excel/ZIP 导出；确有必要新增状态/DB 字段时检查 migration 和旧记录兼容。
  - 正确传播 `CancellationException`；取消、丢弃结果、NanoDet OK 未采用时清理未移交持久化的临时证据；检查 Mat/Bitmap 释放、离页取消和资源释放。
  - 单调时钟 `[DIAG-VC]` 分别记录确认页已显示、裁图、NanoDet、逐 ROI SSIM、合并/默认值发布、界面更新及每 ROI 结果出现时间。用同设备同批 ROI 设备测延迟并观察并行 CPU/内存稳定性；T7.6 单独报告。
  - 测试覆盖竞态默认值、每 ROI 不等待最慢 ROI、人工控件显示时机、人工改判不被覆盖、实际运行未采用状态与导出、取消与证据清理、相似度分数等于阈值、非法设置值和批次阈值快照。
- **最新 Mimo handback（只作报告）：**称 1,546 tests completed、0 failed、5 skipped，Debug APK 构建成功；原文另称 1546/1546、0 failures，计数说法需以原始 Gradle 输出厘清。报告 APK 路径 `app/build/outputs/apk/debug/app-debug.apk`、SHA-256 `71f0192394617610f3c62858eced8ec1efb8351f98090a92ab116fb2279112d2`；设备验证 `DEVICE_UNAVAILABLE`。用户随后报告闪退仍存在。Codex 未运行上述测试/构建或设备操作，也未核验 APK。

### 2026-09-29 历史只读源码复核（新对话需按当前 diff 重查）

- NanoDet 模型输入确实是 ROI 子图：`inferSavedPhoto()` 读取现场照片并按 ROI 坐标取 `submat` 后预处理/推理。相似度最终 SSIM 分数也针对 ROI；配准阶段会读取模板整图和现场整图做特征匹配，再投影/裁出对应 ROI 计算 SSIM。准确表述是“两项结果均为 ROI 级；当前实现的图像读取/ROI 配准仍涉及整图”，不能说 NanoDet 在整图上做目标推理或 SSIM 在整图上打分。
- PROJECTED 并行路径在得到 NanoDet 结果后等待整个相似度结果 map，再统一 merge；`isLoaded` 在整条加载/推理路径完成后才设为 true，因此目前不是逐 ROI 发布结果。某个慢 ROI 会拖住其余 ROI 的人工控件。当前 `RoiSimilarityFallbackPolicy.shouldRun()` 还允许 `MODEL_TARGET_UNSUPPORTED` 进入相似度采用路径，与“仅 NanoDet 明确 NG 才采用”的最新要求冲突。
- NanoDet OK 时需确认状态取自实际运行的 SSIM 结果；如果 SSIM 已经算出但候选未采用，不能将其伪记为 `NOT_RUN_NANODET_NOT_NG`。核对状态建模、Room 保存、CSV/Excel/ZIP 导出和相应测试。
- `runRoiSimilarityAfterSavingEvidence()` 对保存/比较使用通用 `catch (Exception)`，需单独处理并重抛 `CancellationException`。
- 初次读取源码时，`PhotoRegistrationEngine.register()` 两次以匿名 `Mat()` 作为 `detectAndCompute` mask 参数，没有显式 `release()`；`NanoDetRoiInferenceService.sha256()` 使用 `file.readBytes()` 校验模型，会把整个模型文件读入内存。本轮中途工作区出现这两个文件的新差异：当前 diff 已将 mask Mat 命名并放进 `finally` 释放，SHA-256 改为流式读取。作者/来源未核实；Codex 未编辑生产代码，也未运行测试。新对话应复核最新 diff，暂将这两项视为代码差异已出现、验证未完成。相似度并行与 NanoDet 图像读取重叠仍是需用设备 PSS/内存时间线验证的假设，不能仅凭源码定根因。
- 相似度调用虽保存 ROI 图片作为证据，传给 `compare` lambda 的路径未用于评估；`evaluate()` 仍接收模板整图、现场整图、ROI 定义并完成整图配准与 ROI 级评分。
- 当前 handback 的测试成功和 APK 构建报告不代表真机闪退修复。后续应先收集新闪退证据；设备可用时用同设备、同照片/ROI 对比并行与诊断串行路径的峰值内存和延迟，区分 Java crash、native signal 与系统 OOM。设备不可用时明确标注，根因保持未定位。
- **Mimo 报告要求：**分开列实际修改文件/摘要、精确测试/UI 测试/构建命令与结果、同设备同 ROI 的逐阶段和逐 ROI 延迟、设备/APK 身份与崩溃证据、APK 路径/大小/生成时间/SHA-256、代码验证/设备验证/未完成项；复现失败写 `DEVICE_AVAILABLE_BUT_REPRO_FAILED`，设备不可用写 `DEVICE_UNAVAILABLE`。Mimo 报告不等于 Codex 已验证。

### 前次只读审查线索（新对话需要对当前 diff 复核）

以下是上轮只读审查曾发现的风险点，仅作线索，不可不检查当前文件就当成现状：

- `ViewConfirmationViewModel.kt` 的 PROJECTED 路径似乎启动了 NanoDet 与相似度异步工作，但合并前等待整个相似度 map；辅助流程按 ROI 返回 map 可能导致逐项更新被整体等待破坏。
- 合并逻辑需检查 `MODEL_TARGET_UNSUPPORTED` 是否错误采用 SSIM，以及 NanoDet OK 时“已计算但未采用”是否被错误保存为 `NOT_RUN_NANODET_NOT_NG`。确认 `wasRun` 读取的是实际工作结果，不是默认空结果。
- `RoiSimilarityFallback.kt` 的通用 `catch (Exception)` 可能吞 `CancellationException`；保存/比较异常处理中要先重抛取消，并清理未转交持久化的临时证据。
- `AppSettingsScreen.kt` 当时未见阈值设置控件，虽然 `SettingsStore.kt` 有相关改动；需复核设置 UI、SharedPreferences 非法值、阈值快照、Room/schema/migration 及导出映射。
- 保留现有坐标投影、EXIF 方向、证据保存参数，按实际代码验证，不照抄计划伪代码里的替代变量或方向常量。

## 2026-09-29 给 Mimo 的执行顺序快照（历史记录）

1. 优先处理 T7.6：针对用户当前报告闪退的确切 APK/设备采集实时 logcat、crash buffer、bugreport、tombstone、复现步骤和分阶段内存时间线；区分启动 OOM 与拍后路径故障。无设备或无法复现时保持相应状态，不能声称修复。
2. 复核并完成 T7.9：确认页首次显示时机保持用户现状；NanoDet 推理和相似度评分均为 ROI 级，但当前实现按整图解码/配准后提取 ROI。验证并行峰值内存；检查逐 ROI 完成通知、人工控件开放时机、候选/持久化语义、取消传播、证据与 Mat 释放和阈值。需要时做同设备同输入的并行/串行诊断对照，不以测试通过代替设备稳定性验证。
3. 按 `tasks/todo.md` 文字规格处理 T7.2 悬浮参考图并做设备视觉验收；收起态仅有完整比例参考图，展开态才出现信息和切换操作。Mimo 不可读取用户附件，不能要求打开附件或截图。
4. 所有代码、测试、Gradle/构建、APK 和设备操作由 Mimo 执行。每次 handback 分开列文件与摘要、精确命令/退出码/XML/日志、设备时延与稳定性、设备/APK 身份、完整 SHA-256、已验证与未完成事项。APK 身份需实测当前产物，不沿用历史大小/hash。
5. 用户带回 handback 后，Codex 仅读审真实 diff 和报告；不补跑 Mimo 验证，不把报告描述成亲自验证。

给 Mimo 的内容必须是用户可手动复制转交的中文指令；不要通过工具联系 Mimo。

### 2026-09-29 历史状态补充（已由 2026-09-30 续接状态覆盖）

- **Mimo 报告：**用户转述 Mimo 已修正 `MODEL_TARGET_UNSUPPORTED` 不触发/不采用 SSIM，并新增 `SimilaritySession`，希望同一确认会话只解码模板/现场整图并做一次 AKAZE 注册；用户报告 Debug 构建成功。此前用户还转述过 1,546 项单测通过。Codex 未运行或独立验证这些测试/构建，也没有本轮设备内存验收结果；不能据此关闭 T7.6。
- **用户提供的日志证据：**`logcat_repro.txt` 等文件记录多个进程 PSS 约 5–6 GB 和 `am_proc_died` 系统杀进程；同一进程的 PSS 后续曾显著回落。日志支持发生过严重内存峰值/系统 OOM，但单凭 PSS 不能确认永久累积泄漏、分配组件或当前 APK 根因。此前 `logcat` 中直接读取 tombstone 目录受 SELinux 拒绝，不等于 tombstone 目录为空。
- **Codex 本轮只读源码复核：**
  1. 当前 `createSession()` 以整图角点调用 `PhotoRegistrationEngine.register()`，角点按 `photoRgb` 尺寸构造；`evaluateRoi()` 又把同一 `registration.projectedRoiCorners` 用于每一个模板 ROI 的 warp/SSIM。共享一次 Homography/特征提取是可行方向，但必须为每个 ROI 根据其模板角点单独投影并执行相应的 ROI 几何/边界门禁；当前写法不等价于原来逐 ROI 配准评分，SSIM 比较区域可能错误。
  2. `createSession()` 在统一所有权 `try/finally` 保护前就创建多个 Mat；异常或分配失败可遗留已分配 Mat。`withContext(Dispatchers.IO)` 创建后返回 native session 存在取消时交付失败而无人释放的窗口。`onCleared()` 直接释放 session，需确认不会与仍在执行的 IO/OpenCV 运算并发访问相同 Mat。
  3. session 仍对全分辨率整图运行 AKAZE；一次会话复用可能减少多 ROI 的重复耗时/总分配，但不能证明单次峰值下降。用户日志中的 5 GB 级 PSS 尚无 native heap 分配栈解释。未取得同设备复测前 T7.6 必须保持根因未定位/未验收。
  4. 当前 UI 仍以 `roiProcessingStates[roi.id] != DONE` 禁用人工控件，`DONE` 在该 ROI 的相似度流程后设置；PROJECTED 路径仍顺序处理 ROI。需按 `tasks/todo.md` 验证逐 ROI 时机和“不等待其他 ROI”，并保持每个 ROI 自身适用后台工作完成后再开放控件的当前任务规格，除非用户明确更改规格。用户曾报告人工 ROI 确认很慢。
- **后续执行顺序：**Mimo 先修正 ROI 角点投影/验证和 Mat/session 所有权；再确认逐 ROI 工作不会互相等待，保存、候选及导出语义不变；之后设备可用时用匹配 APK/设备/照片记录阶段 PSS、native heap/分配栈、ROI 耗时及连续多轮基线。没有 device profile 时写明未验收，不把构建成功写成 OOM 已修复。
- 新 Codex 对话的可复制开场指令也保存在 `docs/CODEX_CONTINUATION_PROMPT_MOBILE_INSPECTION_20260929.md`。

## Git、文档和文件保护

最近一次只读核对显示：`main...origin/main`，HEAD 与 `origin/main` 均为 `e7fab67f`；23 个已修改跟踪文件、11 个未跟踪路径、0 staged。此前用户授权的任务分批提交为 `3866c3a6`（T7.1/T7.3）、`06bee44e`（T7.2）、`a7c6ea16`（T7.4）、`c2853341`（T7.5）、`e7fab67f`（T7.6）。本轮未提交或 push，远端跟踪 ref 的更新来源未核实。文档 `tasks/todo.md`、`tasks/plan.md` 原已修改；handoff 与 continuation prompt 原已未跟踪。本轮仅按用户明确要求更新这四份文档，开始前已有的生产代码、测试和未跟踪文件均保留。新对话开始/结束时重新核对完整 Git 状态，不要把现有提交再次提交。
`tasks/todo.md`、`tasks/plan.md`、`docs/reports/`、`commonMain/`、`tools/roi_similarity/` 和既有 `.npz` 默认受保护；以后只有用户明确点名文档并要求更新时才能编辑。保留全部已有改动和未跟踪文件，不得覆盖或清理。

用户问“可以提交了吗”时只做审计并给结论，不等于授权提交。只有用户明确要求提交且文件范围明确时才选择性暂存/提交；绝不 push。禁止 `git add .`、`git reset`、`git clean`、`git stash`。
