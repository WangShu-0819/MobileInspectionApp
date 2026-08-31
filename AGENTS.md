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
- B1 共享 CameraX：未完成，当前约 5/9；完整工程可构建，但权限回调、真实拍照、模式切换和完整真机验收尚未通过。
- B2 DPM 迁移：禁止开始，直到 B1 的全部验收项完成。
- DPM 只支持手机相机实时扫一扫，不提供相册码图导入。

## 当前唯一任务

执行 `tasks/todo.md` 中的“B1 收口”。不要同时迁移 DPM、OCR、模板编辑、轮廓算法或 ROI 算法。

完成声明必须基于当前源码生成的新 APK。以下都不算完成证据：单个类能编译、旧 APK 能启动、UI 有按钮、代码中保留 TODO、报告写着“核心完成”。

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
