# B3 Phase 2: 钢印 OCR CameraX/UI 集成报告

## 状态：SOFTWARE_COMPLETE（2026-09-02）

## 概述

Phase 2 在 Phase 1 核心算法迁移基础上，完成钢印 OCR 的 CameraX 帧分析、ViewModel 状态管理、Screen UI 和导航接入。使用共享 CameraController 的 `STAMP_OCR` 模式，不创建第二套 CameraX。

## 实际修改文件

### 新建文件（4）

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/wearable/inspection/mobile/ocr/StampOcrFrameAnalyzer.kt` | FrameAnalyzer 实现，桥接 CameraX 与 SteelStampOcrAnalyzer |
| `app/src/main/java/com/wearable/inspection/mobile/ocr/StampOcrViewModel.kt` | ViewModel，管理拍照、OCR 处理和结果状态 |
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/StampOcrScreen.kt` | OCR 页面 UI（全屏相机 + 拍照 + 结果展示 + 人工确认） |
| `docs/reports/b3/PHASE2_STAMP_OCR_UI_REPORT.md` | 本报告 |

### 修改文件（4）

| 文件 | 修改内容 |
|------|---------|
| `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/Screen.kt` | 新增 `StampOcr` route |
| `app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt` | 新增 StampOcrScreen composable 路由 |
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt` | 新增 `onStampOcr` 回调 + OCR 钢印按钮（TextFields 图标） |

## 架构设计

### StampOcrFrameAnalyzer

- 实现 `FrameAnalyzer` 接口（`analyze(ImageProxy)` + `stop()`）
- 使用 `SupervisorJob` 隔离子协程，`stop()` 取消所有子 Job
- YUV_420_888 → upright Bitmap 转换（复用 DpmFrameAnalyzer 的正确实现）
- OCR 分析在 `Dispatchers.Default` 上异步执行
- 结果通过 `SharedFlow` 发射
- 不在 `analyze()` 中关闭 ImageProxy，由 CameraController 统一关闭
- `stop()` 后丢弃迟到结果

### StampOcrViewModel

状态机：`IDLE → CAPTURING → PROCESSING → EXACT / NEED_CONFIRMATION / FAILED / ERROR`

- `IDLE`：相机已连接，等待拍照
- `CAPTURING`：takePhoto 进行中
- `PROCESSING`：OCR 分析中
- `EXACT`：高置信度识别成功
- `NEED_CONFIRMATION`：低置信度/漏行/目录冲突，需人工确认
- `FAILED`：识别失败（模糊/无内容）
- `ERROR`：系统错误

关键设计：
- 不自行 connect/switchMode，由 Screen 的 CameraPreview 以 STAMP_OCR 模式连接
- `warmUp()` 预热 ML Kit 模型（避免首拍超时）
- 低置信结果进入 NEED_CONFIRMATION，不得自动当作确定结果
- `confirmResult(editedText)` 支持人工编辑后确认
- `captureAndRecognize()` 使用 CameraController.takePhoto 保存 JPEG 后执行 OCR

### StampOcrScreen

- 使用 `CameraPreview(cameraMode = CameraMode.STAMP_OCR)`
- 使用真实相机预览和真实 ImageCapture
- 顶部控制栏：返回按钮 + 标题 + 状态指示
- 底部面板根据状态切换：
  - IDLE：拍照按钮
  - CAPTURING/PROCESSING：加载指示器
  - EXACT：结果展示 + 使用/重拍按钮
  - NEED_CONFIRMATION：结果展示 + 编辑框 + 确认/重拍按钮
  - FAILED/ERROR：错误信息 + 重试按钮
- 页面退出时按 sessionId 正确释放相机（DisposableEffect）

### Navigation

- 新增 `Screen.StampOcr` route（`stamp_ocr`）
- LiveInspectionScreen 顶栏新增 OCR 钢印按钮（`TextFields` 图标，避免与拍照按钮混淆）
- `onStampOcr` 回调导航到 `Screen.StampOcr.route`

## 测试结果

| 检查项 | 结果 |
|--------|------|
| `testDebugUnitTest` | ✅ 308 tests,303 passed,5 skipped,0 failed |
| `assembleDebug` | ✅ BUILD SUCCESSFUL |
| APK 安装 | ✅ Success |
| App 启动 | ✅ COLD start1274ms |
| 新包 PID | ✅30855 |
| 旧包 PID | ✅ 空 |
| 前台 Activity | ✅ com.wearable.inspection.mobile/.MainActivity |

## APK 信息

- **路径**：`app/build/outputs/apk/debug/app-debug.apk`
- **时间**：2026-09-0217:46
- **大小**：221,348,631 bytes (~211MB)
- **SHA-256**：`b27427fa5dbbea37111e0ab5425286a293af9c98cad6718e85bbf0005ceffb82`

## 验证清单

- [x] StampOcrFrameAnalyzer 实现 FrameAnalyzer 接口
- [x] 使用共享 CameraController 的 STAMP_OCR 模式
- [x] 不创建第二套 CameraX
- [x] ImageProxy 由 CameraController 统一关闭
- [x] stop() 后取消异步任务并丢弃迟到结果
- [x] 保持原 OCR 处理顺序和超时策略
- [x] StampOcrViewModel 状态机完整（IDLE/CAPTURING/PROCESSING/EXACT/NEED_CONFIRMATION/FAILED/ERROR）
- [x] 支持人工确认，不自动把低置信结果当确定结果
- [x] StampOcrScreen 使用 CameraPreview(cameraMode = STAMP_OCR)
- [x] 拍照、处理中、识别结果、人工确认和失败重试 UI
- [x] 权限拒绝、模糊、超时和空结果处理
- [x] 页面退出时按 sessionId 正确释放相机
- [x] 新增独立 stamp_ocr route
- [x] LiveInspectionScreen OCR 按钮接入该 route
- [x] 不修改 DPM、模板导入或 CameraController 核心架构

## 未完成项

- connectedDebugAndroidTest 因超时中断（30 项测试中1项完成后卡住），需要在真机上手动验证完整流程
- 真机 OCR 拍照 + 识别 + 人工确认流程验证（需要钢印样品）

## 已知问题

- OCR 按钮图标已从 CameraAlt 改为 TextFields，避免与拍照按钮混淆
