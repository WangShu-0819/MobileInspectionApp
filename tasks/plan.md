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
- [ ] **当前 Task 2：接入真实 CameraPreview，完成权限、状态、画幅与 content rect**
- [ ] 完成 CameraController 模式重绑
- [ ] 完成真实 ImageCapture 与 ImageStore
- [ ] 完成自动化和真机验收

### 当前 Task 2：CameraPreview 状态与画幅

执行顺序：

1. **入口收敛**：让 `AppNavigation` 进入真实 CameraPreview 实现；移除 `PlaceholderScreens.kt` 中的相机占位职责，保留检测结果和模板详情占位。
2. **状态模型**：使用明确状态表达无权限、请求中、初始化、ACTIVE、临时拒绝、永久拒绝和错误；回调必须由真实 CameraX/权限事件驱动。
3. **权限恢复**：临时拒绝可再次请求，永久拒绝可进入系统应用设置，返回页面后重新检查权限；错误态提供有效重试。
4. **完整画幅**：`PreviewView.ScaleType = FIT_CENTER`；优先统一 4:3 UseCase；竖屏按实际流显示 3:4 内容，允许 letterbox，不允许裁切、拉伸或固定 60/40 强撑。
5. **坐标基础**：计算 PreviewView 内真实图像 `contentRect`；Debug 模式记录 View 尺寸、流尺寸、旋转、缩放方式和 content rect。
6. **验证收口**：编译、单元测试、生成并安装当前 APK；在至少 `360x800`、`412x915` 和一台真机验证四边标记完整、圆形不变形、权限分支可恢复。

Task 2 交付物：源码改动、更新后的 `tasks/todo.md`、`docs/reports/b1/TASK2_CAMERA_PREVIEW_REPORT.md`、测试命令和结果、APK 路径/时间/大小/SHA-256、真机截图或录屏证据。

Task 2 完成后必须暂停。真实快门和文件落盘属于 Task 4；完整 `switchMode()`、分析器互斥和 release 生命周期属于 Task 3。

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
