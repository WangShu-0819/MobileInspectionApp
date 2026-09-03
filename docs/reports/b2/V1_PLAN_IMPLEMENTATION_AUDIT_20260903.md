# V1 方案实现对照审计

**审计日期**：2026-09-03  
**审计范围**：当前 `MobileInspectionApp` 源码、JVM 测试、已有真机报告，以及用户提供的《MobileInspectionApp 当前 V1 方案》。  
**本轮性质**：只同步工程文档，不修改业务源码。

## 1. 结论

当前工程已经完成：

- 三 Tab 导航、真实手机 CameraX、权限/画幅/contentRect、真实拍照和私有图片存储；
- 旧模板 ZIP/相册图片导入、Part/多视角选择、视角顺序持久化；模板相机拍摄、真实缩略图、指定 View 重拍和上移/下移排序已完成软件实现；
- 模板透明叠加、透明度 Slider、模板显示/隐藏和按视角连续拍摄；
- DPM 实时扫码链、DPM 绑定/更换入口、已绑定码切换的源码路由；
- 钢印 OCR 核心算法、CameraX/UI 和人工确认界面。

尚未形成 V1 的核心检测闭环：

`拍照 → 拍后比对 → Session ROI 微调 → Detector → InspectionSession → 历史详情 → 完整结果包导出`

按最终用户主流程而不是代码量估算，当前 V1 主线约完成 **25%～35%**。当前可交付状态是“模板能用、人工辅助取景、原图能保存”，还不是最终 V1 MVP。

## 2. 方案对照矩阵

| 方案能力 | 当前状态 | 依据与边界 |
|---|---|---|
| 三 Tab、模板/设置二级入口 | ✅ 已完成 | `AppNavigation` 已接入现场采集、追溯记录、我的及模板、零件、设置、模板包入口。 |
| 真实相机、权限、画幅、资源生命周期 | ✅ 已完成 | 复用唯一 `CameraController`，模式包含 `INSPECTION/DPM_SCAN/STAMP_OCR/TEMPLATE_CAPTURE`；B1 有真机验收证据。 |
| 旧模板 ZIP 导入 | 🟡 基本完成 | 已解析 manifest、复制图片、创建 Part/Template、错误提示和基础回滚；旧 ROI 只校验不落库，`imageFiles[]` 只取第一张主图。 |
| 模板拍摄、相册导入、多视角、缩略图、重拍、View 顺序 | 🟡 软件完成、真机待验收 | SAF 多图导入、真实缩略图、`TEMPLATE_CAPTURE` 新增 View/指定 View 重拍、`displayOrder` 上移/下移已接通；待真机验证拍摄、重拍失败保留旧图、顺序持久化和现场采集按新顺序使用。 |
| 模板 EXIF 方向处理 | 🟡 需补证/补实现 | 当前导入服务主要是原样复制 URI 文件，源码没有模板导入专用 EXIF 归一化流程。 |
| Part 创建、删除、选择 | 🟡 部分完成 | 创建、删除、现场选择可用；Part 编辑 UI 缺失。手工新建 Part 时 DPM 码没有复用绑定冲突校验。 |
| 模板透明叠加和透明度调整 | ✅ 已完成 | 叠加限制在真实 camera `contentRect`，保持比例；Slider 为 0～80%，默认 45%，不触发 CameraX 重绑。 |
| 拍照后的模板/实拍比对 | ❌ 未开始 | 当前拍照成功后仅保存图片并推进下一 View，没有 `CaptureComparisonScreen`、切换、Alpha、Blink、缩放或平移。 |
| Template ROI 配置 | ❌ 未接通 | `RoiDefinitionEntity`、DAO 和列表展示存在，但没有新增、编辑、移动、缩放、删除、排序、阈值配置 UI。 |
| Session ROI 微调 | ❌ 未实现 | 没有 Session ROI 编辑器或从 Template ROI 生成/保存 Session ROI 的运行链路。 |
| Detector / AlgorithmRegistry | ❌ 未实现 | 当前只有 `InspectionType` 枚举和字符串字段，没有算法注册表、通用差异 Detector 或 ROI 执行器。 |
| PASS/REVIEW/FAIL/ERROR 判定 | ❌ 未实现 | `InspectionStatus` 枚举存在，但没有检测执行和总体状态聚合。 |
| InspectionSession / ROI 结果写入 | 🟡 只有数据模型 | Room Entity、DAO、Repository 方法存在；当前现场拍照没有调用 `insertSession` 或 `insertRoiRecord`。 |
| 检测结果详情 | ❌ 占位 | `InspectionResultScreen` 目前只显示会话 ID。 |
| 追溯记录列表、筛选、详情、人工复核 | ❌ 未实现 | 追溯页能读取会话统计，但仍固定显示“暂无检测记录”，无列表和详情操作。 |
| 完整结果包导出 | ❌ 未实现 | 当前 `ResultPackager` 只压入照片；没有 manifest、Excel、配置快照、ROI/预处理图、结果图、错误信息和校验值，且没有导出按钮接线。 |
| DPM 实时识别与绑定 | 🟡 软件接入、验收未闭环 | 已绑定查询、未知码提示、绑定冲突拦截和 `PartSelectionBus` 路由已在源码中接入；真实已绑定码切换和新旧 App A/B 仍待物理样品。 |
| 钢印 OCR | 🟡 算法/UI 已接入 | 算法、CameraX 拍照、`EXACT/NEED_CONFIRMATION/FAILED`、人工编辑确认存在；当前确认值只在 ViewModel 内存中，未保存零件关联、原始值/确认值和确认时间。 |
| 实时轮廓、Homography、自动对齐 | ⏸ 按方案暂缓 | 方案已明确列为 POST-MVP，不作为当前 V1 缺口。 |

## 3. “模型存在”不等于“功能完成”

当前以下结构已经为后续阶段预留，但不能作为已交付能力：

- `RoiDefinitionEntity`：有 ROI 坐标、检测类型和参数字段，但无 ROI 编辑器和导入落库链路；
- `InspectionSessionEntity`：有总体状态、图片和 `alignmentOverride` 字段，但没有现场创建和写入；
- `RoiInspectionRecordEntity`：有 ROI 快照、算法、分数、耗时和错误字段，但没有 Detector 产出；
- `Screen.InspectionResult`：有导航 route，但当前没有业务流程导航到它，目标页面仍是占位；
- `ResultPackager`：有照片 ZIP 基础工具，但不是方案要求的完整结果包。

## 4. 当前验证证据

- `:app:testDebugUnitTest --no-daemon`：**318 项，0 失败，5 跳过**；
- `:app:assembleDebug --no-daemon`：**BUILD SUCCESSFUL**；
- 当前 APK：`app/build/outputs/apk/debug/app-debug.apk`；
- 当前 APK 大小：`221,443,990 bytes`；
- 当前 APK SHA-256：`BB263970BF0E133A54FC96BDBA890A5637C66E4C34340E335A26D3810511965F`；
- 最新真机报告确认模板导入、8 视角按序真实拍摄和 DPM 绑定保存流程可运行；
- 最新报告明确：重新捕获已绑定 DPM 码的 Part 切换本轮没有实际命中，完整 `connectedDebugAndroidTest` 也因历史 camera round-trip runner 长时间无结果而未计为通过；
- DPM 物理样本框内/框外、10 次真实扫码及新旧 App A/B 仍为 `PENDING_PHYSICAL_DPM_SAMPLE`。

## 5. 后续软件顺序

在保留 DPM 物理验收待办的同时，软件主线按以下顺序推进：

1. V1-3：实现拍后 Template/Capture 比对，先完成切换、Alpha Overlay、Blink 和基础缩放/平移；
2. V1-4：实现 Template ROI 配置及当前 Session ROI 的移动、宽度、高度调整；
3. 接入一个通用差异 Detector，写入 `InspectionSession` 和 `RoiInspectionRecord`；
4. 实现结果详情、追溯记录与人工复核；
5. 最后接入完整 ResultPackager 和导出入口。

上述顺序不提前引入实时轮廓、Homography 或复杂专项 Detector。
