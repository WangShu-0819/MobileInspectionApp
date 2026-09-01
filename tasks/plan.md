# Implementation Plan: MobileInspectionApp 当前阶段

## Overview

先完成 B1 共享 CameraX 的真实接线和验收，再依次迁移 DPM、钢印 OCR、模板样本、轮廓与 ROI。当前不并行推进多个相机业务，避免共享接口尚未稳定时产生重复实现。

## Architecture Decisions

- 唯一 CameraController 管理 CameraX 所有权，业务分析器彼此独立。
- 完整取景优先：4:3 流、竖屏 3:4、FIT_CENTER、记录 content rect。
- 当前任务和阶段门禁只在 `tasks/todo.md` 维护，历史报告不再声明进度。
- 源码按 feature 渐进整理，不进行一次性大搬迁。

## Task List

### B1：共享 CameraX 收口

- [x] Task 1：审计活跃页面并归档未引用旧 Screen
- [x] Task 2：接入真实 CameraPreview，完成权限、状态、画幅与 content rect（真机提交 `28d692d`）
- [x] Task 3：完成 CameraController 模式重绑、互斥与生命周期（真机验收完成）
- [ ] 完成真实 ImageCapture 与 ImageStore
- [ ] 完成自动化和真机验收

### 已验收 Task 2：CameraPreview 状态与画幅

执行顺序：

1. **入口收敛**：让 `AppNavigation` 进入真实 CameraPreview 实现；移除 `PlaceholderScreens.kt` 中的相机占位职责，保留检测结果和模板详情占位。
2. **状态模型**：使用明确状态表达无权限、请求中、初始化、ACTIVE、临时拒绝、永久拒绝和错误；回调必须由真实 CameraX/权限事件驱动。
3. **权限恢复**：临时拒绝可再次请求，永久拒绝可进入系统应用设置，返回页面后重新检查权限；错误态提供有效重试。
4. **完整画幅**：`PreviewView.ScaleType = FIT_CENTER`；优先统一 4:3 UseCase；竖屏按实际流显示 3:4 内容，允许 letterbox，不允许裁切、拉伸或固定 60/40 强撑。
5. **坐标基础**：计算 PreviewView 内真实图像 `contentRect`；Debug 模式记录 View 尺寸、流尺寸、旋转、缩放方式和 content rect。
6. **验证收口**：编译、单元测试、生成并安装当前 APK；在至少 `360x800`、`412x915` 和一台真机验证四边标记完整、圆形不变形、权限分支可恢复。

Task 2 交付物：源码改动、更新后的 `tasks/todo.md`、`docs/reports/b1/TASK2_CAMERA_PREVIEW_REPORT.md`、测试命令和结果、APK 路径/时间/大小/SHA-256、真机截图或录屏证据。

Task 2 已完成并通过真机验收，证据位于 `docs/reports/b1/evidence/task2/`。

### 当前 Task 3：CameraController 模式与生命周期

执行顺序：

1. **状态审计**：画出 connect、switchMode、disconnect、release、生命周期事件和分析器所有权关系，先补测试再调整接口。
2. **串行切换**：通过 Mutex 或等价串行机制保证 unbind/clear analyzer/bind 原子执行；快速重复请求只保留确定的最终模式。
3. **资源互斥**：每次切换确认旧 analyzer 停止、旧 Executor 关闭、旧 observer 移除、所有 ImageProxy 关闭，同一时刻只有一组 UseCase。
4. **生命周期**：页面离开只暂停/解绑，可再次连接；永久 release 清空引用并关闭资源；不得持有 Activity、PreviewView 或已离开的 LifecycleOwner。
5. **故障恢复**：绑定失败时进入真实错误状态并清理半绑定资源；重试不得产生第二套 CameraProvider/UseCase/Executor。
6. **验证收口**：自动化覆盖并发切换、重复连接、异常分析器和 release 后行为；真机执行 Tab 10 次、前后台 10 次、模式切换 20 次并检查 logcat。

Task 3 交付物：源码与测试、更新后的 `tasks/todo.md`、`docs/reports/b1/TASK3_CAMERA_LIFECYCLE_REPORT.md`、真机循环日志和当前 APK 信息。完成后暂停等待验收，禁止进入 Task 4。

### Checkpoint：B1

- [ ] `tasks/todo.md` 的 B1 验收全部通过
- [ ] 用户确认进入 B2

### B2：DPM 迁移

- [ ] 相机实时扫码、预处理、ML Kit/ZXing 兜底、绑定与切件

### B3：钢印 OCR 迁移

- [ ] 拍照、预处理、离线 OCR、确认和留档

### B4-B6：模板与 ROI

- [ ] 模板拍摄/相册导入/模板包
- [ ] 真实轮廓提取
- [ ] ROI 编辑和配置

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| 多页面重复绑定相机 | 高 | 唯一 CameraController 与模式状态机 |
| FIT_CENTER 后坐标映射错误 | 高 | 保存 content rect 并用边缘标记测试 |
| 报告与真实状态冲突 | 中 | 进度只由 todo 验收项决定 |
| 一次性目录重构破坏构建 | 中 | 按 feature 小批移动并逐次编译 |
