# 检测结果与 DPM 证据追溯计划

**日期**：2026-09-14
**当前状态（2026-09-16）**：DPM 成功证据批次关联 **USER_ACCEPTED**；ROI 最终结果语义、改判图片及 CSV 回链 **IN_PROGRESS**。具体唯一任务指针以 `tasks/todo.md` 为准。
**范围**：现有采集结果 ZIP 的 NanoDet/人工终审/DPM 成功证据追溯实现与验证；DPM 独立证据 ZIP 保持独立。

**当前边界**：结果包工作已拆分为“DPM ECC 成功照片合并”和“ROI 检测/人工结果及 ROI 证据图导出”两个交付项。前者已完成人机验收；DPM 独立 ZIP 保持独立。后者当前进行中：按最新口径每个 ROI 只有一个最终 `result`；改判时保留原模型结果、人工结果、改判标记及时间，并将对应 ROI 照片与 CSV 路径回链；未改判不要求额外图片。总体结果由人工独立确认。当前没有准确、独立的 DPM 码真值，因此 DPM 人工码值复核不排期。实施任务必须保留现有基础框架，只做当前任务必要的局部修改。

## 2026-09-16 当前执行口径

本计划后文的 2026-09-15 实现状态和分字段历史描述仅作为历史实现记录；如与本节及 `tasks/todo.md` 冲突，以本节最新产品口径为准。执行开始前先审计 `ViewRoiConfirmEntity`、DAO、Repository、ViewModel、确认页、CSV/ZIP exporter 和 ROI 图片存储路径，不预设需增加实体或 migration。确认页保持现有布局和固定确认栏，只显示零件名、视角进度、ROI 编号/类型/图片、人工 ROI OK/NG、人工总体 OK/NG、操作状态及“确认并继续”；不显示模型建议文案、UUID、分数、阈值或模型版本。FEATURE/未执行必须显示适当状态且不得自动判 NG。实施边界和验证项详见 `tasks/todo.md` 当前唯一任务。

## 2026-09-15 实施更新

本轮已将规划的结果追溯范围落到现有 `InspectionZipExportService`：保留全部现场照片和旧 CSV 入口，统一 `inspection_result.csv` 增加稳定 batch/photo/scanSession/part/template/view/roi 字段、NanoDet 全检测框/类别/分数/阈值/版本/耗时/状态/模型建议、人工最终结果/改判/时间及 DPM 路径/状态。DPM 批次筛选只接受明确相等 `batchId`、`SUCCESS`、非空码值、`ZXING/ML_KIT/GRID` 合法来源和真实非空源帧；源帧和 ROI 由独立证据目录原样复制。统一 CSV 照片行的总体人工结果/时间、照片 ZIP 路径和状态落在对应列。独立 `DpmEvidenceExportService` 未合并。

## 2026-09-15 任务边界拆分

本项拆分为两个独立交付项，避免将 DPM 照片和 ROI 检测结果的验收标准混在一起：

1. **DPM ECC 成功照片合并到采集批次 ZIP**：保留独立 DPM ZIP；批次 ZIP 只复制 ECC 纠错通过且码值非空的 DPM 源帧照片和同一源帧扫描 ROI 照片。两个 ZIP 使用同一份文件字节，不重新拍照、裁切或压缩。ECC 失败、未读出、超时、取消或旧 session 不产生 DPM 照片。
2. **ROI 检测结果、人工改判及 ROI 证据图导出**：当前唯一 `IN_PROGRESS`。按本计划“2026-09-16 当前执行口径”和 `tasks/todo.md` 的单一最终 `result` 规则实现，改判时额外保存原模型/人工值、标记、时间和对应 ROI 图片，并让 CSV 回链到 ZIP 中真实文件；未改判不要求额外图片。总体结果继续由人工独立选择。

这两个交付项共用现有批次 ZIP 和稳定 ID 关联，但必须分别报告、分别验收；独立 DPM ZIP 不删除、不改造成批次 ZIP。

真实归档回归通过：批次、多 View、多 ROI、模型 OK/NG 双向人工改判、总体结论独立、FEATURE/未配置/无框/未执行状态、NO_READ/无 batchId/缺失源帧隔离、稳定排序及两个 ZIP 的 DPM 文件字节完全一致。样例目录：`C:\Users\ws\AppData\Local\Temp\inspection-export7435539282332082454`；隔离样例：`C:\Users\ws\AppData\Local\Temp\inspection-export-isolation16208509294163065671`。本轮未运行真机；APK 和 Git 状态记录于 B2/B3 追加报告。

## 需求归纳

最终采集结果包要继续包含各 View 原图和现有 Excel 兼容 CSV，并能回链：

- 当前近期结果包范围为 View 原图、现有 ROI/总体人工确认、DPM 扫码图像证据和原始读码状态/内容；不包含 DPM 人工复核数据。
- ROI 检测与人工终审元数据已纳入批次 ZIP；ROI 图片与最新单一最终结果语义的回链仍在当前任务中完成，不能以空路径或状态文字代替真实文件。
- 未来 ROI 人工核对只检查每个 ROI 的模型 OK/NG；允许双向改判，包括模型 OK 改 NG、模型 NG 改 OK。不要求另选误检、漏检或其它分类。
- 若人工改判，记录对应照片和具体 ROI、模型原始 OK/NG、人工最终 OK/NG、改判时间及关联检测结果图。模型原始值与人工结论分字段保存，不能相互覆盖；照片总体 OK/NG 仍由人工单独选择。
- DPM 只保存 ECC 纠错通过且码值非空的成功源帧及同一帧扫描 ROI；未读出、ECC 失败、超时、取消或旧 session 不保存照片、不创建 `NO_READ` 证据行。
- 目前没有准确、独立的 DPM 码真值可供人工对照，因此不安排人工码值确认、读码正确性分类或复核状态记录。已绑定的 `Part.dpmCode` 不能作为实物码真值。
- 每个 DPM 会话只保存一组证据，不存预览视频；现有 DPM 识别策略和唯一 CameraX 架构保持不变。

## 当前数据路径审计

本轮只做只读源码审计，核对文件：

- `app/src/main/java/com/wearable/inspection/mobile/data/entity/ViewRoiConfirmEntity.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt`
- `app/src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- `docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`

核对结果：

- `ViewRoiConfirmEntity` 已将人工 ROI 结果和总体人工结果、确认时间与 `batchId`、`photoId`、View、template、ROI、像素框关联；`softwareResult` 当前为 nullable，确认页写入 `null`。
- 当前导出为按 batch 打包的 View 照片和 Excel 可打开的 `inspection_result.csv`。此数据结构尚未覆盖多检测框、置信度、检测图和 DPM 帧证据。
- `DpmFrameAnalyzer` 将每个 `ImageProxy` 转换为 upright Bitmap，调用 `DpmAnalyzer`，然后 recycle；结果 SharedFlow 传递解码结果但不包含源帧。`DpmScanViewModel.stopScan()` 会停止 analyzer 并清理 CameraController，因此新证据路径必须明确在关闭前快照并完成受管理文件持久化。
- **DPM 人工码值复核不在计划内**：现有 UI 没有历史证据查看和人工确认流程，而且当前没有准确、独立的实物码真值可供人员判断。`Part.dpmCode` 仅是绑定信息，不能作为真值。当前重点是保留扫码会话图像帧供后续优化算法。
- 现场模板叠加目前由 `LiveInspectionScreen.kt` 以 `overlayAlpha = 0.45f`（45%）初始化；本轮新增要求改为默认 `0f`（0%），但本轮不改源码。
- DPM 帧留存可独立先做；ROI Detector 与人工最终复核依赖真实可复现的检测器输出，尚未集成，当前不能产生检测框、置信度或软件 PASS/FAIL。

## 计划顺序

1. **独立 UI 微任务：模板叠加默认 alpha 0f**：首次进入现场采集时模板图透明，CameraX 预览不受影响，滑杆范围保持原值。
2. **DPM 扫码图像证据留存**：每次关闭扫码会话前完成一组图像快照和持久化；成功时绑定精确成功解码帧，未读出时保存退出前最近有效帧，同时保存原图、扫描 ROI 裁剪图和原始读码状态/内容。保留现有解码链。
3. **结果 ZIP/CSV 扩展（近期范围）**：保留原字段、View 原图与现有人工确认数据，加入 DPM 成功/未读出图像证据与原始读码状态/内容，不添加人工复核记录。
4. **DPM 人工码值复核：暂不排期**。目前缺少可供对照的准确实物码真值；待有可信真值数据且用户重新确认后再评估。
5. **ROI 检测和逐 ROI 模型结果核对：暂缓、不排期**。ROI 算法评估/植入和模型人工终审均不启动，只有用户重新授权后另行安排。

当前唯一进行中的任务验收完成前，不开始下一项源码实现。先单独完成第 1 项透明度微任务，再进行 DPM 会话图像留存，随后扩展结果包。DPM 人工码值复核和 ROI 检测也不在当前排期中。

## DPM 人工码值复核状态

- 当前产品没有 DPM 人工复核 UI；由于也没有准确、独立的实物码真值，目前不建设人工码值录入、正确性对比、异常分类或复核状态持久化。
- `Part.dpmCode` 是业务绑定值，不等于独立真值；不可用它自动判定扫码内容正确或要求操作人员据此复核。
- 当前需求聚焦每次 DPM 扫码会话的图像证据：成功时保存产生读码结果的帧；未读出时在用户退出前保存最近有效帧。证据与原始解码状态/内容关联，供后续算法优化。
- 以后若获得可信 DPM 真值数据并重新确认需求，再另行评估人工复核功能；不得把该功能或复核记录写成当前能力或本轮交付项。

## 模板叠加透明度需求状态

- 新要求：现场采集首次进入时模板图叠加 alpha 默认为 `0f`（0% 透明度/完全透明）；相机预览仍正常显示，现有滑杆可从 0% 调至 80%。
- 当前实现仍是 `0.45f`，尚未修改；因此这项需求状态为 **REQUIREMENT_RECORDED / NOT_IMPLEMENTED**。
- 作为独立轻量 UI 任务排在当前活动任务之后、DPM 帧证据任务之前。保持 `contentRect`、相机资源、滑杆范围及显隐切换行为；不添加持久化设置。

## DPM 帧留存任务验收条件（透明度微任务验收后）

- 每次会话退出时都已完成证据快照：有成功解码则关联精确成功帧，无成功解码则保存退出前最后一张有效帧；原图与实际 scan ROI 裁剪图坐标一致且可重新打开。
- 证据绑定 `scanSessionId`、帧来源类型、源帧时间、原始解码内容/状态和解码来源；未读出时解码内容为空。图片或数据库写失败不得报告留存成功。
- 退出、分析回调与 CameraController 清理串行安全；重复退出或迟到回调不会覆盖、重复创建或复活会话。
- 每会话最多一组 DPM 证据；只有显式稳定 ID 可关联 `partId`/`batchId`，删除 batch 不删除独立 DPM 算法样本。
- 所有 DPM 解码策略与已验收行为回归；Room schema 变化有真实 Migration，自动化测试覆盖路径隔离、裁剪和错误清理。
- 任务报告列出实际修改文件、Migration、自动化结果、图片路径/关联语义、未完成项和 Git 状态。按后续任务的实际授权执行；不擅自提交 Git 或运行真机验收。

## 本轮变更与验证

- 本次文档更新：`tasks/plan.md`、`tasks/todo.md` 和本报告。`MOBILE_INSPECTION_AGENT_INSTRUCTION.md` 未修改；应用源码与基础框架未改。
- 源码、自动化测试、APK 和真机：未修改/未运行；本轮范围为计划与需求澄清。
- 文档检查：`git diff --check` 通过；仅有 `tasks/plan.md`、`tasks/todo.md` LF/CRLF 转换提示，无 whitespace error；新增报告无行尾空格。
- Git：不提交；保留工作区原有的其他未提交/未跟踪内容。

## 后续计划更新：NanoDet ROI 检测与人工改判（2026-09-14）

用户已明确 ROI 的产品判定语义：按 `targetType` 检查相应目标是否存在；螺母/螺纹对应检测类别的置信度达到阈值为模型建议 OK，低于阈值或未检出为模型建议 NG。此规则只判断目标存在，不判断零件损坏或加工质量。

此前“ROI 检测暂缓、不排期”现更新为“已有实施计划、源码尚未开始”。计划要求复用模板 ROI、现有拍照后确认页和照片/批次结果链路，模型与人工结果分字段保存，允许人工双向改判，总体照片结果仍单独人工选择，并把模型框/类别/分数、阈值、模型版本、改判结果和图像证据关联进 ZIP。详细分阶段计划见 [`tasks/plan.md`](../../tasks/plan.md#新增计划nanodet-接入模板-roi-与逐-roi-人工改判)。

ONNX 已导出；转换工具与 Android NCNN 运行库仍然缺失。下一工具链方案是优先验证 NCNN 官方 PNNX 从现有 ONNX 转换，不要求同时取得 `onnx2ncnn`；在用户授权前不安装或下载。`0.37` 是待验证的起始阈值，需继续覆盖 frame_00106 低分 thread 和 frame45 的误报/阈值回归。本次仍是计划更新，没有修改应用源码或运行构建/设备验证。

## 2026-09-15 结果包扩展收口

本任务已在现有工作区实现并等待用户验收。真实数据链路为：`CaptureBatchEntity.batchId` → `CapturedPhotoEntity.photoId/filePath/viewIndex/templateId`；确认通过 `ViewRoiConfirmEntity.batchId/photoId/templateId/viewIndex/roiId` 关联；DPM 仅接受扫描启动时显式写入、且与当前 `batchId` 严格相等的 `DpmScanEvidenceEntity`。不按零件名、文件名、列表位置或时间猜测关联。

批次 ZIP 结构为 `views/view_NN/photo_...`、`dpm/sessions/{scanSessionId}/frame_...jpg`、可选 `roi_...jpg` 和 `inspection_result.csv`。DPM 独立 ZIP 仍由 `DpmEvidenceExportService` 按 `sessions/{scanSessionId}` 导出。批次过滤为 `SUCCESS`、非空码值、`ZXING/ML_KIT/GRID` 和真实非空源帧；NO_READ、无 batchId、跨 batch、缺失/空源帧均不进入批次 DPM 目录。源文件使用字节复制，不重拍、不裁切、不重压缩。

本轮实际修改：`InspectionZipExportService.kt`（严格 DPM 过滤）、`InspectionExcelExporter.kt`（照片行列位和总体人工字段）、`InspectionZipExportArchiveTest.kt`（跨批次/缺失源帧/列位及真实 ZIP 回读断言）。工作区已有的实体、v8→v9 migration、DAO、Repository、DPM 独立导出和 NanoDet 确认链路均保留。

验证：结果相关 JVM 104/104 通过；真实 `ZipInputStream` 归档测试通过；`:app:compileDebugKotlin`、`:app:assembleDebug` 通过。全量 JVM 为 792 项完成、779 通过、13 失败、5 跳过；失败来自工作区已有并行改动/基线断言，未归因本任务。归档样例为 `C:\Users\ws\AppData\Local\Temp\inspection-export7435539282332082454`，批次/独立 ZIP 的成功 DPM 原图和 ROI 均为 4 bytes 且 SHA-256 分别为 `952B50FD4FE30AEE9420F479FF3F4C6268F2865EE65A82E1F8E157ABC1455272`、`EE10DA4AEFE61A37DF1DEE937CA3221AFA3B2351F9EA34EDBBB769573C6785F7`，字节完全相等。APK 为 `app/build/outputs/apk/debug/app-debug.apk`，2026-09-15 13:12:25 +08:00，276579040 bytes，SHA-256 `D2D7B57FF523EA82D48E7F1EEDCE4ED1FCAB7D32EC5CC192CAD2DEED06B6B35B`。未运行 ADB、connectedDebugAndroidTest 或真机；未提交 Git。
