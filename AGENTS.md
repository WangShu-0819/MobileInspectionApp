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
- B2 DPM 迁移：当前执行 Task 1，以旧工程已验证可用的 DPM 识别链为基线完成忠实迁移与实时扫码闭环。
- DPM 只支持手机相机实时扫一扫，不提供相册码图导入。
- DPM 入口：顶部扫码图标 contentDescription 为"扫一扫"，只进入实时 DPM 扫描；OCR 图标 contentDescription 为"OCR 钢印"；模板样本相册导入属于"我的 > 模板配置"。

## 当前唯一任务

执行 **B2 Task 1：旧 DPM 识别链迁移与实时扫码闭环**。先核对 `docs/migration/LEGACY_MIGRATION_MAP.md` 和旧工程真实源码，再迁移到新工程；旧工程保持只读。

识别算法以旧工程当前生产实现为行为基线：保留 ZXing `DataMatrixReader` 主解码、中心 ROI、预处理策略轮转、双极性尝试、全图降采样、ML Kit DATA_MATRIX 兜底、帧节流、响应门、连续 miss 对焦和旧版网格兜底。只允许为适配新 `CameraController`、`FrameAnalyzer`、包名和生命周期做必要改造，不得擅自调换解码顺序、删减旧策略或用全新简化算法替代。

B2 Task 1 允许连续完成纯逻辑、OpenCV/网格链、CameraX 分析器、扫码框 ROI、页面导航和真机回归。每个内部检查点必须先测试并创建小提交；检查点通过后可继续下一个，不需要等待用户逐段确认。任一检查点出现测试失败、相机回归、旧样本识别退化或无法确认的算法差异时立即暂停，不得用后续步骤掩盖失败。

不得实现相册码图导入、未知码绑定、自动切件、OCR、模板、轮廓、ROI 或检测算法。不得创建第二套 CameraX；`tools/contour_extraction/` 继续冻结。

完成整个 Task 1 后提交 `docs/reports/b2/B2_TASK1_DPM_LEGACY_PARITY_REPORT.md` 和结构化证据，更新控制文档并暂停等待验收，不得自动进入 B2 Task 2。

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
