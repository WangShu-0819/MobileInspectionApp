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

- [ ] 修复 CameraPreview 状态和生命周期
- [ ] 完成真实 PreviewView 比例与 content rect
- [ ] 完成 CameraController 模式重绑
- [ ] 完成真实 ImageCapture 与 ImageStore
- [ ] 完成自动化和真机验收

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
