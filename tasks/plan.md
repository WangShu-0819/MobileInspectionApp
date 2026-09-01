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
- [ ] **当前 Task 4：完成真实 ImageCapture 与 MobileImageStore**
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

### 已验收 Task 3：CameraController 模式与生命周期

当前状态：已通过累积真机验收，最终修复提交 `bb22f1e`，证据位于 `docs/reports/b1/evidence/task3/`。

执行顺序：

1. **前序回归恢复**：以 Task 2 提交 `28d692d`、代码收口 `a037a08` 和证据截图为基线，把完整权限、OPEN 状态、`FIT_CENTER`、实际流变换、contentRect、诊断和校准能力适配到当前 CameraController；不得整文件回滚。
2. **画幅复验**：当前设备容器约 `1080x1039` 时，真实 3:4 图像区域应约 `779x1039` 并左右留边；四角位于图像区域、中央圆不变形、上下内容不裁切。
3. **坐标复验**：LiveInspection 的轮廓/ROI 叠加接收真实 contentRect，只在图像区域绘制；letterbox 不得出现检测图形。
4. **测试入口清理**：测试 Activity 从 `src/main` 和主 Manifest 移出，只保留在 androidTest/debug；冻结并不得使用超出当前阶段的 `tools/contour_extraction/`。
5. **保持生命周期成果**：重新运行并发、模式互斥、ImageProxy、observer 和 20 轮模式测试，确认恢复预览没有破坏 Task 3 已完成部分。
6. **最终真机循环**：在修复后的同一 APK 上执行 Tab 往返 10 次、前后台 10 次，并重新检查 8 项 logcat 禁止模式。
7. **累积验收**：报告同时给出 Task 2 回归矩阵和 Task 3 生命周期矩阵；任一项失败都不得进入 Task 4。

Task 3 已完成。其权限、画幅、contentRect、会话互斥和生命周期能力继续作为后续累积门禁。

### 当前 Task 4：真实拍照与存储

执行顺序：

1. **收口基线**：先提交 Task 3 遗留的 Manifest/测试 Activity 清理和控制文档，不把 `tools/contour_extraction/` 混入 Task 4。
2. **现状审计**：读取 `CameraController.takePhoto()`、`MobileImageStore`、现场主快门和数据库接口，列出可复用能力、缺口与所有权；不另建 CameraX Controller。
3. **会话安全快门**：拍照 API 接收 active sessionId，在锁内确认当前模式需要 Capture、session 匹配且相机 OPEN；快门并发只允许一个进行中的请求。
4. **文件事务**：生成唯一临时 JPEG，ImageCapture 写入后校验非空/可解码/宽高/EXIF，再由 MobileImageStore 原子移动；异常、取消、页面离开和低存储失败全部清理临时文件。
5. **UI 状态机**：接通现场主快门，提供 IDLE/CAPTURING/SAVED/ERROR；拍摄中禁用按钮，成功仅表示原图已保存，不能显示检测成功。
6. **测试**：生产文件事务、重名、空文件、损坏 JPEG、取消、过期 session、并发点击、模式切换和清理路径均有测试。
7. **真机验收**：连续拍摄 20 张，核对文件数、唯一名、非零大小、可解码、方向、临时目录为空；重跑 FIT_CENTER、Tab/前后台和禁止日志检查。

Task 4 交付物：`docs/reports/b1/TASK4_CAPTURE_STORAGE_REPORT.md`、`docs/reports/b1/evidence/task4/`、20 张样例清单和校验摘要、当前 APK 信息、更新后的 `tasks/todo.md`。完成后暂停等待验收，禁止进入 Task 5。

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
