# MobileInspectionApp Agent 入口

## 唯一执行顺序

1. 先读本文件。
2. 再读 `tasks/todo.md`，只执行其中唯一标记为进行中的任务。
3. 需要产品边界时查 `MOBILE_INSPECTION_AGENT_INSTRUCTION.md`。
4. 需要历史证据时查 `docs/reports/`，报告不能覆盖当前任务状态。
5. 完成当前任务后更新 `tasks/todo.md` 和对应报告，然后暂停等待验收。

用户最新指令优先级最高。过期的阶段指令与历史报告仅供参考，不得作为当前执行入口。

## 当前事实

- 阶段 A：已完成。
- B0 旧功能迁移审计：已完成，见 `docs/migration/LEGACY_MIGRATION_MAP.md`。
- B1 Task 1 源码边界整理：已验收，审计提交为 `754ec5b`。
- B1 Task 2 相机状态与画幅：已通过真机验收，提交为 `28d692d`；证据位于 `docs/reports/b1/evidence/task2/`。
- B1 Task 3 CameraController 模式与生命周期：已通过累积真机验收，最终修复提交为 `bb22f1e`；最终 APK SHA-256 为 `fad6ef0ddbf1c4b59970ede6810d0e072dfa7680e2fa6d9be9290d2cc3c29720`，证据位于 `docs/reports/b1/evidence/task3/`。
- B1 Task 4 真实拍照与存储：已验收，整改提交链为 `48f7587` → `566acaea` → `3a04b658`；真机 APK SHA-256 为 `6a3ce752f2f07a09084c57499a4c1ccac8e331b9a52dd8066824c43d7ade858d`；自动化测试 81/81 通过；证据位于 `docs/reports/b1/evidence/task4/`。
- B1 Task 5 完整验证：已验收；最终补充提交为 `b7c4c08e`；APK SHA-256 为 `235f8aa8c4d65b365a93bff021041e43dca86d5eb4b121ba9d13ebd3f436768f`；JVM 78/78、Instrumented 20/20 通过；冷启动 10 次、Tab 10 轮、前后台 10 次、日志门禁 12 项全部通过；证据位于 `docs/reports/b1/evidence/task5/`。
- B1 技术验收完成，用户已确认进入 B2。
- B2 DPM 迁移：Task 1 **SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING**（2026-09-02）。APK SHA-256 `6e2ca7d3f573c1da1af7f9180c23a0dbe8f2f9081eafff5ccf466dcb09c051cc`。JVM 208 项（203 passed / 0 failed / 5 skipped），Instrumented 30/30 passed，冷启动 10/10 passed。4 项物理验收标记为 `PENDING_PHYSICAL_DPM_SAMPLE`。物理验收不阻塞后续非 DPM 功能开发。
- B2 Task 2：旧模板导入 + 模板透明叠加 MVP — **SOFTWARE_COMPLETE**（2026-09-02）。V1-1 模板导入 MVP（`bdf1bd89`）；V1-2 模板透明叠加 + Alpha Slider（`bdf1bd89`）；V1-6 MVP Profile 信息架构简化（`94e3f5f3`）：ProfileScreen 收缩为 5 个 MVP 入口、移除硬编码 TemplateStats、接真实 DB 统计、TemplatePackageScreen 接通 ZIP 导入、AppSettingsScreen 移除未生效假开关。JVM 242 项（237 passed / 0 failed / 5 skipped）。遗留边界：legacy ROI 未迁移、imageFiles[] 仅取首图、模板包导出未实现。V1-3 拍后比对为下一软件阶段。实时轮廓投影/姿态匹配/单应性对齐继续标记为 DEFERRED / POST-MVP。
- B3 Phase 1：钢印 OCR 核心算法迁移 — **完成**（2026-09-02，提交 `0df8e9c5`）。10 source files +9 test files（66 OCR tests）。包名 `com.wearable.inspection.mobile.ocr`。ML Kit text-recognition 依赖已启用。
- B3 Phase 2：钢印 OCR CameraX/UI 集成 — **SOFTWARE_COMPLETE**（2026-09-02）。StampOcrFrameAnalyzer + StampOcrViewModel + StampOcrScreen + Navigation 路由。JVM 308 项（303 passed / 0 failed / 5 skipped）。APK SHA-256 `b27427fa5dbbea37111e0ab5425286a293af9c98cad6718e85bbf0005ceffb82`。
- 当前 DPM 业务扩展：顶部入口保持不变；已接入按绑定 `dpmCode` 查询 Part、扫码命中后切换当前 Part 并重新加载其有序 Views；模板配置按 Part 提供扫码绑定/更换 DPM 码，冲突绑定拒绝。当前源码已通过 `:app:compileDebugKotlin`，完整构建和真机验收待本轮收口。
- DPM 只支持手机相机实时扫一扫，不提供相册码图导入。
- DPM 入口：顶部扫码图标 contentDescription 为"扫一扫"，只进入实时 DPM 扫描；命中已绑定码时只切换零件及模板，未知码只提示先在模板配置绑定；OCR 图标 contentDescription 为"OCR 钢印"；模板样本相册导入属于"我的 > 模板配置"。

## 真机包名门禁

- 新工程唯一 applicationId 为 `com.wearable.inspection.mobile`，启动组件为 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`，应用名为“视觉质检”。
- 旧工程包名 `com.wearable.inspection` 只允许在明确标注的旧/新 App A/B 对照中启动，不得作为新工程构建、安装、截图或验收对象。
- 每次真机验证前必须先停止两个包，再显式安装并启动新工程：
  ```powershell
  adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection
  adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection.mobile
  adb -s ERLDU20429005890 install -r app\build\outputs\apk\debug\app-debug.apk
  adb -s ERLDU20429005890 shell am start -W -n com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity
  ```
- 禁止通过桌面图标、最近任务、模糊名称、`monkey` 或省略组件名的 `am start` 作为验收启动方式；这些方式可能打开旧工程。
- 启动后必须用 `pidof com.wearable.inspection.mobile` 和 `dumpsys activity activities` 核对新包进程与前台 Activity。相机权限弹窗期间前台可暂时是系统 PermissionController；处理弹窗后必须重新核对前台为新包。
- 若前台出现 `com.wearable.inspection`，本轮截图、日志、交互和性能数据全部无效，必须停止旧包并从安装新 APK 开始重做。
- 旧/新 App A/B 对照必须分别标注 `OLD: com.wearable.inspection` 与 `NEW: com.wearable.inspection.mobile`；每轮启动一方前先停止另一方，证据中记录实际包名、APK SHA-256 和启动组件。
- **Instrumented 测试后强制恢复门禁**：`connectedDebugAndroidTest` 可能在成功、失败或 runner 崩溃后卸载新包，同时让旧包继续保留在设备上。命令返回后必须一律假定 `com.wearable.inspection.mobile` 已被卸载；禁止直接继续截图、交互、性能测试或通过任何图标启动 App。
- 每次 `connectedDebugAndroidTest` 结束后（无论退出码）都必须重新执行上面的四条命令：停止新旧包、`install -r` 当前主 APK、用完整组件名启动新 App；随后重新执行 `pm list packages`、`pidof com.wearable.inspection.mobile` 和 `dumpsys activity activities`。只有同时确认新包已安装、新包 PID 非空、旧包 PID 为空且前台为新包后，后续证据才有效。
- 若 instrumented 命令失败，仍先恢复新 App 的安装和前台包，再报告失败并暂停；不得因测试失败跳过恢复步骤，也不得把旧 App 留在运行状态交给用户。
- `connectedDebugAndroidTest` 成功不能替代主 APK 的显式安装与前台包校验；instrumented 测试期间或之后自动安装的 test APK 也不能作为主 APK 验收对象。

## 图片证据读取规则

- `mimo-v2.5-pro` 当前不能读取或视觉分析 PNG/JPG 截图；不得再让该模型重复执行 `Read <image path>`，也不得把读取失败写成截图验收通过。
- 截图视觉复核应改用当前环境中具备图片输入能力的模型或图片查看工具；若执行 Agent 没有可用视觉能力，则收集 UIAutomator XML、前台包名、控件 bounds、截图 SHA-256、logcat 等结构化证据，并明确标记“等待用户人工视觉复核”。
- 结构化证据只能证明包名、页面层级、控件位置与运行状态，不能替代颜色、裁切、拉伸、遮挡和整体美观度的视觉结论。
- 图片证据必须同时记录实际文件路径、对应新包 `com.wearable.inspection.mobile`、APK SHA-256 和采集步骤；未通过包名门禁的截图作废。

## 当前唯一任务

**拍照后人工确认 + 持久化** — **IN_PROGRESS**（2026-09-04）。

目标：每拍完一个 View 的现场照片后，展示全部模板 ROI，工人逐个选择 ROI 的 OK/NG，并单独选择整张照片的总体 OK/NG；所有人工结果、时间、照片、View、模板、零件和采集批次必须真实持久化。

执行边界：
1. 复用现有 `CaptureBatchEntity`、`CapturedPhotoEntity`、`InspectionSessionEntity`、`RoiInspectionRecordEntity`、DAO 和 `InspectionRepository`；不得创建第二套 ROI 数据模型或第二套 CameraX。
2. ROI 坐标使用模板 `normalizedRect` 映射到现场照片实际 image contentRect；不实现 Homography、自动对齐、自动轮廓或 Session ROI 编辑。
3. ROI `targetType` 继续使用稳定枚举值；历史空值显示"未选择"，不得自动猜测。
4. 软件检测结果保持 null/未执行，不伪造 Detector、PASS 或 FAIL；总体结果不能由 ROI 结果自动计算或覆盖。
5. 未确认时不得默认 OK/NG；ROI 或总体为 NG 时仍必须保存。
6. 只进行源码、自动化测试和文档修改；禁止执行 adb、Gradle、APK 安装/卸载、启动/停止真机应用。
7. 完成后更新 `tasks/todo.md` 和 `docs/reports/b2/` 对应报告，然后暂停等待验收；不提交 Git，除非用户另行明确授权。

**Bug Fix**（2026-09-03）：修复模板图片降采样导致 Canvas 绘制失败。CameraPreview 的 inSampleSize 计算逻辑已修正，8000x6000 图片现在使用 inSampleSize=4。模板图片解码已移至 Dispatchers.IO，切换 View 时旧 Bitmap 已正确回收。新增 CameraPreviewTest 14 项单元测试。

历史记录：模板拍摄、缩略图、重拍、排序 — **SOFTWARE_COMPLETE**（2026-09-03）。APK SHA-256 `56e390a067ccd1a040ea05b86b9743bc185bf2c1215630e7fc0f4f35a9e7f495`。

当前任务边界：
- 复用已有 `RoiEditorScreen`、`RoiEditorViewModel`、`RoiDefinitionEntity`、ROI DAO 和 `InspectionRepository`。
- 本轮实现模板 ROI 的目标属性选择和持久化。
- 保留 ROI 新增、取消、选中、移动、缩放、边界约束、删除和 `normalizedRect` 保存行为。
- 只进行源码、自动化测试和文档修改，禁止执行 adb、安装/卸载 APK、启动或停止真机应用。
- 不实现 Detector/PASS-FAIL、自动轮廓提取、自动对齐、Session ROI 或结果包导出。

## 已完成任务：模板 ROI 属性选择

状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**（2026-09-04，用户确认验收完成）。已实现 `RoiTargetType` 枚举、`RoiDefinitionEntity.targetType` 字段、数据库 migration v4→v5、UI 属性选择器和自动化测试（441 项通过）。

目标：模板配置中的每一个 ROI 除 `normalizedRect` 外，还必须保存一个目标属性，用于后续选择一致的 ROI 检测算法。当前支持三种属性：

- `THREAD` — 螺纹
- `NUT` — 螺母
- `FEATURE` — 部件

后续实现要求：

1. 复用现有 `RoiDefinitionEntity`、ROI DAO、`InspectionRepository` 和 `RoiEditorScreen`；优先在现有 ROI 实体增加一个规范字段（建议 `targetType`），不得创建第二套 ROI 数据模型。
2. 新增 ROI 时必须能够选择属性；属性未选择时不得静默保存为某一种检测类型，也不得显示“配置完成”。
3. 已有 ROI 必须显示当前属性，并支持重新选择/修改属性；历史 ROI 没有属性时显示“未选择”，不能自动猜测，后续检测前必须要求补选。
4. 属性必须真实持久化，并按当前 `templateId`、View 和图片隔离；切换 View、重新进入页面、删除后重建页面时属性不能串用或丢失。
5. 属性选择控件应在 ROI 创建和编辑流程中清晰可见，使用用户可理解的中文名称“螺纹/螺母/部件”，同时保存稳定枚举值，不得只保存展示文本。
6. 审计并覆盖所有 ROI 读写路径：新增、编辑、移动、缩放、加载、删除、模板导入以及现有模板包序列化路径（如果该路径当前已实现）。数据库变更必须提供真实 migration，并保留旧数据可读性。
7. 为后续检测路由保留明确映射：`THREAD` → Thread 检测、`NUT` → Nut 检测、`FEATURE` → Feature 检测。本任务只实现属性选择和存储，不实现 Detector/PASS-FAIL 或自动检测。
8. 不允许用全局 Part 属性代替 ROI 属性；同一 View 中不同 ROI 可以有不同属性，每张图片的 ROI 仍保持独立。
9. 增加自动化测试：属性枚举校验、默认未选择状态、新增/修改/重新加载持久化、按 `templateId`/View 隔离、删除后无残留，以及旧 ROI migration 回归。
10. 只进行 MobileInspectionApp 源码、自动化测试和文档修改；不得修改旧工程，不得实现自动轮廓提取、实时对齐、Session ROI、结果导出或新的 CameraX。
11. 完成后更新 `tasks/todo.md` 和 `docs/reports/b2/` 对应报告，报告必须列出实际修改文件、migration、测试结果、未完成的检测集成项和 Git 状态；本任务已于 2026-09-04 通过验收并提交 `c9aa335a`。

可直接交给执行 Agent 的任务描述：

> 在现有模板 ROI 编辑器中增加 ROI 目标属性选择，支持“螺纹（THREAD）/螺母（NUT）/部件（FEATURE）”。请先审计 `RoiDefinitionEntity`、DAO、Repository、`RoiEditorScreen` 和所有模板导入/加载路径，再以最小改动增加规范枚举字段并完成真实持久化。新增 ROI 必须选择属性；旧 ROI 没有属性时显示“未选择”，不得自动猜测或伪造检测就绪。已有 ROI 支持查看和修改属性，属性按 `templateId`、View、图片独立保存。保留 ROI 新增、取消、选中、移动、缩放、边界约束、删除和 `normalizedRect` 行为。补充 JVM/Repository/UI 状态测试，覆盖新增、修改、重载、隔离、删除和 migration。当前任务只做属性选择与数据层，不实现 Detector/PASS-FAIL、自动轮廓、自动对齐、Session ROI、结果导出或新的 CameraX；不运行 adb、安装 APK 或真机测试；完成后更新任务清单和 B2 报告，不提交 Git，等待验收。

## 当前任务详细要求：拍照后人工确认

状态：**IN_PROGRESS**。附件图片仅作为 OK/NG 弹窗的交互草图，不是验收证据；本轮先完成确认界面和持久化，不接入自动检测。

- 单张照片拍摄完成后，预留“检测结果人工确认”流程和按钮/弹窗。
- ROI 自动检测可以暂时不接入，但 UI 和数据结构应为后续 `PASS/FAIL` 结果预留，不得伪造检测结果。
- 人工确认选项为 `OK` 和 `NG`；用户未确认时不能自动标记合格或不合格。
- 后续实现需明确确认对象是整张照片还是每个 ROI，并保存照片、ROI、检测状态、人工确认结果和时间的关联。
- 本需求不改变当前 ROI 属性、ROI 编辑、检测算法和官方 DCIM 评估口径。

识别算法以旧工程当前生产实现为行为基线：保留 ZXing `DataMatrixReader` 主解码、中心 ROI、预处理策略轮转、双极性尝试、全图降采样、ML Kit DATA_MATRIX 兜底、帧节流、响应门、连续 miss 对焦和旧版网格兜底。只允许为适配新 `CameraController`、`FrameAnalyzer`、包名和生命周期做必要改造，不得擅自调换解码顺序、删减旧策略或用全新简化算法替代。

不得实现自动轮廓提取、实时轮廓投影、Homography、SIFT 姿态对齐、自动 ALIGNED 判断、ROI 自动跟踪、新 ROI 检测算法、OCR、TTS、ResultPackager、ForegroundService。不得创建第二套 CameraX；`tools/contour_extraction/` 的算法源码和验证产物继续冻结为 DEFERRED / POST-MVP。

离线 Python 工具统一使用 `D:\ProgramData\anaconda3\envs\dinov2\python.exe`；不得在 `tools/` 下创建 `.venv`、Conda 环境、`site-packages` 或依赖副本。

## 累积回归门禁

- 后续 Task 必须保留并重新验证所有前序 Task 的已验收能力；“当前 Task 新增功能通过”不能覆盖前序回归失败。
- 大幅重写已验收文件前，先列出必须保留的行为和测试。修改后对照前序报告逐项回归，禁止以更短代码或重新编译成功作为等价证明。
- 任一前序能力回归时，当前 Task 状态立即改为“回归整改中”，暂停后续工作，直到同一设备上的新 APK 重新通过对应截图、日志和交互验收。
- 每个 Task 报告必须包含“前序能力回归矩阵”，至少覆盖导航、权限、相机状态、画幅、contentRect、资源释放和错误态中与本次改动有关的项目。

## 源码目标结构

```text
com.wearable.inspection.mobile/
├── app/                       # Application、Activity、应用级依赖装配
├── camera/
│   ├── CameraController.kt    # 唯一 CameraX 所有者
│   ├── CameraMode.kt
│   ├── CameraState.kt
│   └── analyzer/              # 后续各模式独立分析器
├── data/
│   ├── dao/
│   ├── db/
│   ├── entity/
│   ├── image/
│   ├── repository/
│   └── settings/
├── domain/
│   ├── model/
│   └── usecase/
└── ui/
    ├── navigation/
    ├── theme/
    ├── components/            # 跨页面公共组件
    └── feature/
        ├── capture/           # 现场采集
        ├── records/           # 追溯记录
        ├── profile/           # 我的
        ├── templates/         # 模板配置二级页
        ├── parts/             # 零件管理
        └── settings/          # 应用设置
```

本轮不得一次性搬迁所有 Kotlin 文件。先审计导航引用，再按 feature 逐个移动，每移动一个小组立即执行 `compileDebugKotlin`。禁止在 `src/main` 留 `.backup`、临时脚本或重复占位 Screen。

## 相机约束

- 全工程只有一个 `ProcessCameraProvider` 所有者。
- 模式：`IDLE/INSPECTION/DPM_SCAN/STAMP_OCR/TEMPLATE_CAPTURE`。
- DPM、OCR、模板拍摄和现场检测使用独立业务处理器，但共享 CameraController。
- `PreviewView` 默认 `FIT_CENTER`，优先统一 4:3 相机流；竖屏内容为 3:4，允许留边，不允许裁切或拉伸。
- 记录实际图像 content rect，后续坐标只能映射到图像区域，不能映射到 letterbox。
- 所有 ImageProxy 在所有路径关闭；异步处理先复制必要数据。

## 修改纪律

- 不修改旧 `Wearable Inspection` 工程。
- 不迁移 G40、Leion、USB、HUD 或相关 AAR。
- 不回滚用户已有改动。
- 不用假预览、固定矩形、绿色 ROI 或配置就绪冒充真实检测状态。
- 不用大范围 try/catch 吞掉错误。
- 不添加新的根目录临时脚本、总结或备份；报告进入 `docs/reports/<phase>/`，工具进入 `tools/`。
- 每次只推进一个可验收任务，修改前列出文件，修改后给出真实测试命令与结果。

## 完成汇报

必须报告：实际修改文件、测试命令及结果、新 APK 路径/时间/大小/SHA-256、真机证据、未完成项。只有 `tasks/todo.md` 的 B1 验收全部勾选后，才能申请进入 B2。
