# MobileInspectionApp 新 Codex 对话续接指令

更新日期：2026-09-30。本文件用于把项目交接给新的 Codex 对话。用户会提供旧 Codex 历史对话作为背景；请直接复制下方指令，再附上旧对话。旧对话用于了解来龙去脉，不能覆盖最新用户指示、当前源码/Git 证据和本文件列出的任务状态。

## 可直接复制到新 Codex 对话

~~~text
请继续协作项目 MobileInspectionApp。我会提供旧 Codex 历史对话作为背景，请按下面的协作方式继续，不要从旧计划的早期阶段重启。

开始工作前请通读：
1. docs/CODEX_HANDOFF_MOBILE_INSPECTION_20260929.md
2. tasks/todo.md 顶部当前状态、完整 T7 规格和协作边界
3. tasks/plan.md 开头当前状态与执行顺序
4. 当前相关源码、最新报告，以及完整 Git 状态、已暂存/未暂存 diff、未跟踪文件和最近提交

旧聊天、旧 handback、旧测试/构建摘要、旧 APK 身份、日志和任务文档中的历史段落均是背景或待核对材料。遇到冲突，以我最新明确指示和当前工作区证据为准。不要重复 T1/T2 已完成工作。每次答复要分清用户提供/确认的信息、Mimo 报告和你本轮亲自只读核对的事实；没有亲自验证的内容要写“未验证”。

当前任务状态：
- T1 零件颜色、ID、模板关联和模型路由：已完成。没有新问题证据时不重做。
- T2 exp22/exp23 来源审计、资产启用、Android 路由/parity：已完成，结论为 PASS_WITH_RECORDED_LIMITATION。没有新证据时不重新导出模型或重复验证。
- T3 离线配准/相似度实验：离线工作完成，不代表现场校准。
- T4 ROI 相似度软件实现：按历史 handback 完成。当前 SSIM 设置源码默认值为 0.50，Lowe 配准比率 0.75 是不同参数。现场校准仍属 T5/T6。
- T5/T6：软件交付后的真实现场数据、人工标签、校准集和独立留出验证。
- T7.1、T7.2、T7.3–T7.5、T7.7：用户在本轮明确报告设备验收完毕。按用户报告记录，不要要求重复验收；Codex 未独立操作设备或核查截图。T7.2 的收起/展开规格见 tasks/todo.md；不得让 Mimo 打开用户附件或截图。
- T7.6 闪退/OOM：保持根因未定位和 DEVICE_AVAILABLE_BUT_REPRO_FAILED。2026-09-30 Mimo handback 报告 SEA-AL10 / Android 10 / API 29 / arm64-v8a 上完整流程 3 轮未复现；用户报告自己验收通过、不卡顿且不闪退。这些都不能证明根因已定位或永久修复。handback 报告 APK 242,570,104 bytes，SHA-256 dc2af170c4d60972f372eec45a445b4e890358537c69f68d9b68e5a959cd6ea0。旧 OOM/LMK 和约 5–6 GB PSS 不能单独定位 native 分配根因或证明永久泄漏。只有再次复现或用户要求取证时，才针对当时确切 APK/设备收集完整日志、操作步骤、设备/APK 身份、阶段 PSS 和 native allocation profile。
- T7.8：本轮 Mimo handback 已提供。Mimo 报告 :app:testDebugUnitTest 为 1,621 tests、0 failures、0 errors、5 skipped（108 个 XML），:app:assembleDebug 成功；报告了 APK/设备身份和单启用 ROI 的阶段计时、PSS 及 UI 证据。APK 报告值同上。此处是 Mimo 报告，不是 Codex 本轮亲自运行或核验。若 handback 后没有代码改动，不重复跑同一测试/构建；若有实际代码改动，让 Mimo 针对实际 diff 做匹配验证并提交完整 handback。单 ROI 数据不能代表多 ROI 真机延迟。
- T7.9：简洁人工确认卡片、明确的 NanoDet 检测中…/相似度检测中…提示和单 ROI 流程有 Mimo handback/用户验收报告；多 ROI 真机时延未验证。上轮 Codex 只读检查曾发现相似度 dispatcher limitedParallelism(1) 与慢 ROI 不阻塞快 ROI 的新增测试预期可能冲突，也未找到确认页实际显示时刻的 [DIAG-VC] 事件。它们是静态审查线索，不是已运行测试得出的失败结论；新对话必须按当前源码/diff重新核对，并由 Mimo 对实际改动验证。完整门禁以 tasks/todo.md 为准。
- ECC 目前仅做方案分析，用户尚未授权实现。不要开始代码实现或要求 Mimo 实施。若用户之后明确授权实验，遵守 tasks/todo.md 的受限配准、离线 A/B、误通过、每 ROI 延迟和 Native PSS 门禁。

工作角色与协作边界：
- 你是主协调 Codex，使用中文，负责方案分析、只读源码/diff/报告审查、用户明确要求的文档更新，以及编写可由用户手动复制转交给 Mimo 的完整中文指令。
- 代码执行者是外部 Mimo，由用户手动转交指令并带回 handback。Mimo 不是 Codex agent。不要调用子 agent、delegation、collaboration 或任何代理/多 agent 工具；不要创建代理任务，也不要通过工具联系 Mimo。
- 不修改生产代码，不自行运行项目测试、Gradle/构建、APK、ADB、设备操作或 OCR。只读检查源码、已有报告/XML/日志/APK 元数据和 Git 可以按任务需要进行。
- 保留所有共享工作区已有改动及未跟踪文件。不得整体暂存，不使用 reset/clean/stash，不 push。若用户明确要求提交，先核对完整 Git 状态和 diff，只暂存用户授权的精确文件范围，不要混入未完成的 T7.9 代码/测试。上一对话的提交授权只限当时的任务/续接文档；检查 git log 确认后不要重复提交，也不要把它理解成后续代码提交授权。
- 只有用户明确要求时才编辑任务文档。给 Mimo 的指令必须可由用户手动复制，写清文件范围、要求、验收标准、精确命令/预期结果和需要提供的证据；不要替用户发送。
- 结束每轮时简述本轮只读发现/文档改动、未运行的验证、Git 状态变化、未解决项与建议的下一步。不要为形式重复 T1/T2 等已完成事项。

新对话必须按当前用户消息重新确认下一步；详细验收条件以 tasks/todo.md 顶部为准。本文件里的 APK/Git 等快照只用于识别历史，不是当前工作区实测值。
~~~

## 使用方式

将上面的文本复制给新 Codex，并附上旧 Codex 历史对话。不要把历史对话中的旧状态原样当作当前待办；请新对话按第一段要求重新检查当前工作区。
