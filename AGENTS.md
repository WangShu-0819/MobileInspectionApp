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
- B1 共享 CameraX：当前进入 Task 4，完成真实 ImageCapture 与 MobileImageStore 落盘闭环；完整 B1 回归属于 Task 5。
- B2 DPM 迁移：禁止开始，直到 B1 的全部验收项完成。
- DPM 只支持手机相机实时扫一扫，不提供相册码图导入。

## 当前唯一任务

执行 **Task 4：真实拍照与存储**。只完成从现场主快门到有效 JPEG 私有存储的闭环，不执行检测算法、不生成识别结果、不创建假成功记录。Task 5 和 B2 禁止开始。

开始 Task 4 编码前，先提交 Task 3 收口基线：主 Manifest 移除测试入口、`CameraModeTestActivity` 从 `src/main` 删除、控制文档更新。`tools/contour_extraction/` 不属于当前阶段，继续冻结且不得混入提交。

Task 4 必须满足：

1. 现场采集页只有一个主快门；点击后使用当前 `CameraSession` 的现有 ImageCapture，不得为拍照创建第二套 CameraX 或重新绑定相机。
2. 拍照前验证当前 session、`CameraStateType.OPEN`、ImageCapture 可用、零件/模板/ROI 配置完整；拍照期间禁用重复点击并显示真实进行状态。
3. CameraController 的拍照 API 必须校验 sessionId；过期页面或模式切换后的旧请求不得写入文件。拍照、disconnect、switchMode、release 的资源关系必须明确且可测试。
4. ImageCapture 先写 App 私有目录中的唯一临时 JPEG，再交给 `MobileImageStore` 校验非空、可解码、尺寸和方向，最后原子移动到正式路径；失败或取消必须删除临时文件。
5. 文件名必须抗并发冲突；不得覆盖旧照片。保存结果返回稳定路径、大小、宽高、方向、时间和校验信息。
6. UI 状态至少包含 `IDLE/CAPTURING/SAVED/ERROR`；失败可重试且不丢失当前页面选择。Task 4 不显示“检测通过”，不写 SessionEntity/ROI 结果，不伪造识别图。
7. 单元测试和真机连续拍摄 20 张必须通过：无空文件、重名、方向错误、临时残留、重复回调或相机回归；同时重跑 Task 2/3 的受影响门禁。

Task 3 已验收的以下能力是 Task 4 的强制回归基线：

1. 明确 `IDLE/INSPECTION/DPM_SCAN/STAMP_OCR/TEMPLATE_CAPTURE` 模式状态；本轮只验证模式基础设施，不实现各业务算法。
2. `switchMode()` 必须串行停止旧分析器、清除旧 UseCase、关闭未交接的 ImageProxy，再绑定新模式所需 UseCase；禁止并发重绑。
3. 同一时刻只能存在一组已绑定 UseCase、一个活动分析器和一个分析 Executor；重复切换不得累积 observer、线程或相机绑定。
4. 区分页面暂时离开、生命周期 stop 和应用永久 `release()`；Tab 返回后可恢复，永久释放后不得复用已关闭 Executor。
5. CameraController 不得长期强引用 Activity、LifecycleOwner 或 PreviewView；所有 ImageProxy 在成功、异常、取消和模式切换路径都必须关闭。
6. 完成单元/集成测试与真机循环：Tab 往返 10 次、前后台 10 次、模式往返至少 20 次，无黑屏、重复绑定、Camera already in use、Executor rejected 或 ImageProxy 泄漏。

Task 4 不接入真实 DPM/OCR 分析器，不执行轮廓/ROI 检测，不导出结果包。仅保存原始拍摄 JPEG 和必要文件元数据。

完成声明必须基于当前源码生成的新 APK。以下都不算完成证据：单个类能编译、旧 APK 能启动、UI 有按钮、代码中保留 TODO、报告写着“核心完成”。

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
