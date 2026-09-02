# 旧功能迁移清单（Legacy Migration Map）

**目标**：将 Wearable Inspection 旧工程中的功能迁移到 MobileInspectionApp（新工程）
**创建时间**：2026-08-31
**状态**：B1 已完成；B2 Task 1 SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING；V1 功能开发中

> **Contour-based live alignment**: DEFERRED / EXPERIMENTAL。轮廓提取成熟度不足，继续优化会阻塞可交付版本。V1 改为模板原始图片透明叠加 CameraX 实时画面。`tools/contour_extraction/` 工具和数据保留，但不进入 V1 实时现场采集功能。

> 当前状态与执行边界以根目录 `AGENTS.md`、`tasks/todo.md` 和 `tasks/plan.md` 为准。本文件中标为“历史目标”的 B0/B1 内容只保留审计背景，不得据此重新创建 `SharedCameraSession`、`PhoneCameraController` 或第二套 CameraX。

## 0. B2 Task 1 当前迁移结论

### 0.1 已确认的新工程基线

- B1 已完成唯一 `CameraController`、`CameraMode`、`FrameAnalyzer`、真实 `CameraPreview`、会话安全拍照和 `MobileImageStore`；B2 必须在这些生产实现上增量接线。
- `CameraMode.DPM_SCAN` 已定义为 `Preview + ImageAnalysis`，不需要也不得绑定 `ImageCapture`。
- 新工程已经声明 ML Kit Barcode Scanning 与 ZXing Core 依赖，不重复引入同类扫码 SDK。
- `LiveInspectionScreen` 的“扫一扫”图标语义已存在，但点击行为仍是 TODO；B2 Task 1 负责接入真实扫码路由。
- `ScanImportBottomSheet.kt` 当前无生产引用，却残留“扫一扫/导入”和 DPM 相册码图识别文案。B2 Task 1 实现提交必须删除该文件，不能仅隐藏入口。

### 0.2 固定产品边界

- 只识别 `DATA_MATRIX`，不兼容普通 QR Code。
- “扫一扫”只进入手机相机实时扫码，不提供相册选择、码图导入、相关权限、回调或隐藏路由。
- B2 Task 1 保持旧工程顺序：ZXing `DataMatrixReader` 主解码，ML Kit 仅限定 `DATA_MATRIX` 作为兜底，满足旧门控条件时再执行网格重建；不得调换主备关系。
- 本 Task 不实现未知码绑定、已绑定码切件、冲突处理、OCR、模板、轮廓、ROI 或检测算法。
- OpenCV 预处理、帧质量门控、中心对焦、`ImportedDpmScanner` 和网格重建均属于旧版有效识别链，随 B2 Task 1 迁移；只解除旧设备和页面耦合，不删减旧默认策略。Task 3 负责同样本回归后的性能诊断和参数优化。

### 0.3 代码级迁移表

| 旧文件 | 旧职责 | 新文件 | 本 Task 复用 | 本 Task 排除 | 对应测试 |
|---|---|---|---|---|---|
| `camera/DpmAnalyzer.kt` | 多阶段实时 DPM 解码、节流、防抖、对焦和网格兜底 | `mobile/dpm/DpmFrameAnalyzer.kt`、`mobile/dpm/DpmDecodePipeline.kt` | DATA_MATRIX 限定、解码短路、节流和响应门思想 | QR Code、OpenCV、网格重建、Debug 大量落盘、数据库写入 | 主解码成功、兜底、双失败、空结果、停止后不回调 |
| `camera/DpmAnalyzer.kt` 中 `DpmRespondGate` | 同码防连扫、换码立即响应、离开视野后重新武装 | `mobile/dpm/DpmResultGate.kt` | 可注入时钟和纯 Kotlin 状态机 | 旧页面弹窗和切件回调 | 同码抑制、换码、miss 后重扫、stop/reset |
| `camera/DpmPreprocessor.kt` | OpenCV 多策略二值化和增强 | `mobile/dpm/DpmPreprocessor.kt` | 旧策略、顺序、默认参数和 Debug 配额边界 | 仅移除旧包名/页面耦合 | 迁移旧预处理测试与同样本回归 |
| `camera/DpmFrameQuality.kt` | 清晰度、亮度等帧质量门控 | `mobile/dpm/DpmFrameQuality.kt` | 旧指标和阈值 | 无依据的重新调参 | 迁移旧质量门控测试 |
| `camera/DpmGridGate.kt`、`DpmGridReconstructor.kt` | 重型网格任务门控与重建 | `mobile/dpm/DpmGridGate.kt`、`mobile/dpm/DpmGridReconstructor.kt` | 旧 miss 门槛、冷却、会话代数、取消和超时 | 阻塞相机分析线程 | 迁移旧测试并补取消/超时测试 |
| `vision/dpm/imported/*` | 第三方/导入式 DPM 网格扫描 | `mobile/dpm/imported/*` | 旧扫描控制、尺寸模式和实际使用实现 | 与 DPM 相册导入无关的 UI/文件入口 | 迁移旧测试与性能基准 |
| `ui/shared/CameraSessionViewModel.kt` 的 DPM 部分 | 扫码模式、事件流和相机切换 | 现有 `CameraController` + B2 扫码 ViewModel | 模式进入/退出和事件流思想 | 旧 VideoSource、Leion、USB、后台服务 | 页面往返、会话恢复、无重复绑定 |
| 旧扫码 UI | 实时取景、扫描区域和结果反馈 | `ui/feature/dpm/DpmScanScreen.kt` | 实时预览、扫描状态、取消操作 | 相册导入、普通 QR、绑定/切件 | Compose/UIAutomator 与真机扫码 |

### 0.4 B2 Task 1 实现顺序

1. 删除未引用的 `ScanImportBottomSheet.kt`，全仓确认不存在 DPM 相册导入入口或路由。
2. 建立可注入的 ML Kit/ZXing 解码适配边界和纯 Kotlin 结果门。
3. 实现 `DpmFrameAnalyzer`，复用唯一 `CameraController` 的 `DPM_SCAN` 模式。
4. 新增扫码页面和导航，把现场采集“扫一扫”TODO 接通。
5. 接入 `DpmFrameAnalyzer`、唯一 CameraController、扫码页面和扫码框 contentRect/rotation ROI 映射。
6. 完成 JVM/Instrumented 测试、CameraX 累积回归、真机框内/框外扫描及旧/新 App 同样本 A/B 验收。
7. 更新本表实际新文件路径和迁移状态，提交报告后暂停，不进入 B2 Task 2。

---

## 概述

本文件记录旧 Wearable Inspection 工程的功能模块，评估迁移可行性、依赖关系和迁移阶段。

**旧工程路径**：`D:/study/Textile_defects/Wearable Inspection/Wearable Inspection`

**新工程路径**：`D:/study/Textile_defects/Wearable Inspection/MobileInspectionApp`

**迁移原则**：
- ✅ 可以直接复用的代码（纯 JVM 逻辑、无 Leion/USB/G40 耦合）
- ⚠️ 需要解除 Leion/USB/G40 耦合后迁移
- ❌ 不迁移 Leion SDK、USB 外接、G40 专用代码

---

## 1. CameraX 与视频源模块

### 1.1 VideoSource.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/VideoSource.kt`

**功能**：
- 视频源接口定义
- VideoFrame 数据类（时间戳、分辨率、旋转角度、OpenCV Mat）
- 抽象接口：connect、disconnect、frames、attachPreview、detachPreview、captureStill

**新工程目标**：
- 新增 `app/src/main/java/com/wearable/inspection/mobile/camera/SharedCameraSession.kt`
- 提取接口作为共享基础，但移除 OpenCV Mat 依赖（改用 ImageProxy）

**可复用代码**：
- ✅ 接口设计（connect/disconnect/frames 生命周期）
- ✅ VideoFrame 结构（替换 Mat 为 Bitmap）
- ✅ captureStill 签名

**必须解除的耦合**：
- ❌ `org.opencv.core.Mat` → 改为 `android.graphics.Bitmap` 或 `ImageProxy`

**依赖项**：
- androidx.camera.core.Preview
- androidx.lifecycle.LifecycleOwner
- OpenCV（待移除）

**对应测试**：无（接口类）

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

### 1.2 CameraXVideoSource.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/CameraXVideoSource.kt`

**功能**：
- CameraX 视频源实现（后置摄像头 + USB 外接摄像头）
- ImageAnalysis 分析器（KEEP_ONLY_LATEST 背压策略）
- YUV_420_888 → RGBA Mat 转换
- ImageCapture 静态拍照
- 连续自动对焦（CONTINUOUS_VIDEO）
- 中心锁定对焦（3s 自动取消）
- 闪光灯控制
- 变焦控制
- OCR 拍照对焦模式（CONTINUOUS_PICTURE）
- 首帧诊断日志

**新工程目标**：
- 迁移到 `SharedCameraSession.kt`，但**只保留 PHONE_CAMERA**
- 移除 USB 外接摄像头支持
- 移除 Leion 专用代码
- 保持 CameraX 核心功能（Preview + ImageAnalysis + ImageCapture）

**可复用代码**：
- ✅ `PhoneCameraVideoSource` 类（后置摄像头选择）
- ✅ ImageAnalysis 配置（STRATEGY_KEEP_ONLY_LATEST + YUV_420_888）
- ✅ YUV → RGBA 转换逻辑（rgbaMat + copyYuvPlane）
- ✅ 连续自动对焦（enableContinuousAutoFocus）
- ✅ 中心锁定对焦（focusCenterLocked + 3s 自动取消）
- ✅ 变焦控制（applyZoom + maxZoomRatio）
- ✅ 闪光灯控制（setTorch）
- ✅ OCR 对焦模式（setOcrFocusMode）
- ✅ ImageCapture 实现（captureStill）
- ✅ analyze() 背压策略和错误处理

**必须解除的耦合**：
- ❌ `UsbCameraVideoSource`（移除 USB 外接）
- ❌ `LeionProVideoSource`（移除 Leion 眼镜）
- ❌ OpenCV Mat（改为 ImageProxy 或 Bitmap）
- ❌ `com.llvision.glxss.*`（Leion SDK）
- ❌ Camera2CameraControl 实验性 API（保留但添加 @SuppressLint）

**依赖项**：
- androidx.camera:camera-core
- androidx.camera:camera-camera2
- androidx.camera:camera-lifecycle
- androidx.camera:camera-view
- OpenCV（待移除）

**对应测试**：无（集成测试依赖真实设备）

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

### 1.3 VideoSourceFactory.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/VideoSourceFactory.kt`

**功能**：
- VideoSourceType 枚举（PHONE_CAMERA / USB_CAMERA / LEION_PRO）
- 工厂方法创建不同类型的视频源

**新工程目标**：
- 简化为只创建 PhoneCameraVideoSource
- 移除 USB 和 Leion 分支

**可复用代码**：
- ⚠️ VideoSourceType 枚举（只保留 PHONE_CAMERA）

**必须解除的耦合**：
- ❌ USB_CAMERA → 删除
- ❌ LEION_PRO → 删除
- ❌ `com.llvision.glxss.*` → 删除

**依赖项**：无

**对应测试**：无

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

### 1.4 CameraSessionViewModel.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/ui/shared/CameraSessionViewModel.kt`

**功能**：
- 导航级共享相机会话 ViewModel
- 管理视频源生命周期（connect/disconnect/attach/detach）
- DPM 扫码高分辨率模式切换（720p ↔ 1080p）
- 扫码变焦控制
- DPM 扫码事件流（SharedFlow）
- DPM 切件回调注入
- 钢印 OCR 对焦模式
- 闪光灯控制
- 分析开关和间隔控制
- 全局会话快照（后台巡检服务桥接）

**新工程目标**：
- 迁移到新工程作为共享相机 ViewModel
- **只支持 PHONE_CAMERA**
- 移除 Leion 相关代码（attachedLeionPreview、detachLeionPreviewIfCurrent）
- 移除后台巡检服务桥接（InspectionForegroundService）
- 保留 DPM 扫码和 OCR 对焦接口（待 B2/B3 实现）

**可复用代码**：
- ✅ 相机会话状态管理（CameraSessionState）
- ✅ connect/disconnect/attach/detach 逻辑
- ✅ DPM 扫码模式切换（setDpmScanActive + 分辨率切换 + 变焦）
- ✅ DPM 事件流（dpmEvents SharedFlow）
- ✅ DPM 切件回调注入（attachDpmAnalyzer）
- ✅ 分析开关（setRouteAnalysisActive / setAnalysisActive）
- ✅ 分析间隔控制（setAnalysisInterval）
- ✅ OCR 对焦模式（setOcrFocusMode）
- ✅ 闪光灯控制（setTorch）
- ✅ 中心对焦（focusCenterLocked）

**必须解除的耦合**：
- ❌ Leion 视频源支持（attachedLeionPreview / detachLeionPreviewIfCurrent / LeionProVideoSource）
- ❌ 后台巡检服务（InspectionForegroundService 桥接）
- ❌ `com.llvision.glxss.*`（Leion SDK）
- ❌ 全局会话快照（globalSession）→ 可以保留但标记为临时

**依赖项**：
- VideoSource（接口）
- VideoSourceFactory
- CameraXVideoSource
- DpmAnalyzer（待 B2 迁移）
- InspectionForegroundService（待移除）

**对应测试**：无（集成测试）

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

### 1.5 VideoPreview.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/ui/components/VideoPreview.kt`

**功能**：
- CameraX PreviewView 承载组件
- 权限请求（相机 + Leion 叠加权限）
- 权限永久拒绝处理（跳转系统设置）
- 连接状态占位提示
- LIVE FPS/分辨率角标
- Leion SDK 预览兼容

**新工程目标**：
- 迁移到新工程作为通用相机预览组件
- **只支持 CameraX PHONE_CAMERA**
- 移除 Leion 相关逻辑
- 简化权限处理（只请求 CAMERA）

**可复用代码**：
- ✅ CameraX PreviewView 配置（PreviewView + ImplementationMode.COMPATIBLE）
- ✅ 权限请求逻辑（rememberLauncherForActivityResult）
- ✅ 权限永久拒绝处理（shouldShowRequestPermissionRationale → 跳转设置）
- ✅ 连接状态占位提示（DeviceStatus → PlaceholderOverlay）
- ✅ LIVE 角标（设备名 + FPS + 分辨率）

**必须解除的耦合**：
- ❌ Leion CameraTextureView → 删除
- ❌ `com.llvision.glxss.common.ui.CameraTextureView` → 删除
- ❌ Leion 权限（RECORD_AUDIO / READ_MEDIA_*）

**依赖项**：
- androidx.camera.view.PreviewView
- CameraSessionViewModel
- DeviceStatus 枚举

**对应测试**：无（UI 集成测试）

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

## 2. 图片和模板拍摄

### 2.1 ImageStore.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/data/image/ImageStore.kt`

**功能**：
- 私有目录图片存储（filesDir/templates/{regionId}/）
- 从 SAF Uri 复制图片
- 从本地文件复制
- 文件名生成（时间戳 + UUID）
- 删除图片和目录
- 保存匹配帧（match_results/）
- 批量删除匹配结果

**新工程目标**：
- 迁移到新工程作为图片存储工具类
- 保留私有目录存储逻辑
- 文件名格式调整：`capture_<partId>_<timestamp>.jpg`

**可复用代码**：
- ✅ 私有目录规划（filesDir/templates/）
- ✅ 从 Uri 复制逻辑（copyFromUri）
- ✅ 从文件复制逻辑（copyFromFile）
- ✅ 文件名生成（时间戳 + UUID）
- ✅ 删除逻辑（delete / deleteRegion）
- ✅ 保存匹配帧逻辑（saveMatchFrame）

**必须解除的耦合**：
- ⚠️ OpenCV Mat 依赖（saveMatchFrame 参数改为 Bitmap）

**依赖项**：
- OpenCV（Imgcodecs / Imgproc）- 待移除

**对应测试**：无

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

### 2.2 TemplateViewModel.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/ui/screens/templates/TemplateViewModel.kt`

**功能**：
- 零件检测模板管理
- 零件选择显式推送（PartSelectionBus）
- 视角管理（增删改 + 拖拽排序）
- 模板样本管理（增删 + 数量限制 1~10）
- 拍照模板保存（saveCapturedTemplate + manualRoiRect）
- 模板包导入（TemplatePackageImporter）
- 模板包导出（TemplatePackageExporter）
- DPM 码绑定（bindDpmCode）
- DPM 扫码切件（onDpmScanned）
- 钢印 OCR 目录校验（validateStampInCatalog）
- 钢印结果确认（confirmStampResult）

**新工程目标**：
- 部分迁移到新工程的"我的 > 模板配置"页面
- **本轮不迁移**（阶段 B 仅迁移 CameraX 和图片存储）

**可复用代码**：
- ⚠️ 零件选择逻辑（selectPart / createPart / deletePart）
- ⚠️ 视角管理逻辑（addRegion / deleteRegion / moveRegionsTo）
- ⚠️ 模板样本逻辑（saveTemplate / deleteTemplate）

**必须解除的耦合**：
- ❌ 钢印 OCR 目录校验（validateStampInCatalog）- 不迁移 B3
- ❌ 钢印结果确认（confirmStampResult）- 不迁移 B3
- ❌ DPM 扫码切件（onDpmScanned）- 不迁移 B2

**依赖项**：
- InspectionRepository
- SettingsStore
- TemplatePackageImporter
- TemplatePackageExporter
- PartSelectionBus

**对应测试**：无

**迁移阶段**：B3（模板配置页面）

**当前状态**：✅ 不迁移（本轮）

---

### 2.3 TemplateScreen.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/ui/screens/templates/TemplateScreen.kt`

**功能**：
- 模板库页面 UI
- 视角列表 + 样本组展示
- 拍照入口
- 导入/导出模板包
- 零件选择器
- OCR 扫码预览

**新工程目标**：
- 迁移到新工程的"我的 > 模板配置"页面

**可复用代码**：
- ⚠️ UI 布局结构（视角卡片 + 样本网格）

**必须解除的耦合**：
- ❌ OCR 扫码预览（不迁移 B3）
- ❌ 模板包导入/导出（待 B3）

**依赖项**：
- TemplateViewModel
- CameraSessionViewModel
- OcrResultDialog

**对应测试**：无

**迁移阶段**：B3（模板配置页面）

**当前状态**：✅ 不迁移（本轮）

---

### 2.4 TemplatePackageImporter.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/template/TemplatePackageImporter.kt`

**功能**：
- 离线模板包（template.zip）解析
- JSON 解析（template.json）
- 图片解压和去重
- 安全校验（路径遍历防护、条目数限制、字节数限制）
- ROI 校验

**新工程目标**：
- 迁移到新工程作为模板包导入工具
- 纯 JVM 实现，可直接复用

**可复用代码**：
- ✅ 完全复用（纯 JVM，无 Android API）

**必须解除的耦合**：
- 无（纯 JVM 实现）

**依赖项**：
- org.json（JSONObject）
- java.util.zip

**对应测试**：
- `app/src/test/java/com/wearable/inspection/...`（旧工程测试待迁移）

**迁移阶段**：B3（模板配置页面）

**当前状态**：✅ 未迁移

---

### 2.5 TemplatePackageExporter.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/data/exporter/TemplatePackageExporter.kt`

**功能**：
- 零件模板包导出（template.zip）
- JSON 构造（template.json）
- FileProvider Uri 生成
- 清理旧导出包防堆积

**新工程目标**：
- 迁移到新工程作为模板包导出工具

**可复用代码**：
- ✅ writeTemplateZip 纯 JVM 实现
- ✅ buildManifest JSON 构造

**必须解除的耦合**：
- ⚠️ Android Context（只在最外层封装层使用，可保留）

**依赖项**：
- androidx.core.content.FileProvider
- InspectionRepository（待替换为新工程 Repository）

**对应测试**：无

**迁移阶段**：B3（模板配置页面）

**当前状态**：✅ 未迁移

---

## 3. DPM 模块

### 3.1 DpmAnalyzer.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/DpmAnalyzer.kt`

**功能**：
- DPM Data Matrix 标识码解析器
- ZXing DataMatrixReader 主解码器（tryHarder + 反转极性）
- ML Kit BarcodeScanning 兜底解码器
- 预处理策略轮转（4 种策略）
- 中心 ROI 优先解码（只识别绿框内码）
- 响应门控（防连扫 + 支持连续扫同一码）
- 网格重建兜底（ImportedDpmScanner，连续 miss 触发）
- 连续 miss 触发中心对焦

**新工程目标**：
- 迁移到新工程（B2 阶段）

**可复用代码**：
- ✅ DpmAnalyzer 类结构
- ✅ ZXing 主解码器 + ML Kit 兜底
- ✅ 预处理策略轮转（DpmPreprocessor）
- ✅ 响应门（DpmRespondGate）
- ✅ 网格任务门控（DpmGridGate）
- ✅ 中心对焦触发（onFocusRequest）

**必须解除的耦合**：
- ❌ ImportedDpmScanner（重型网格重建）→ B2 单独评估
- ❌ DpmGridReconstructor（网格重建）→ B2 单独评估
- ❌ OpenCV Mat 转换（改为 Bitmap）

**依赖项**：
- com.google.mlkit:barcode-scanning
- com.google.zxing:core
- OpenCV（待移除）
- DpmPreprocessor
- DpmRespondGate
- DpmGridGate
- ImportedDpmScanner（B2 评估）

**对应测试**：
- `app/src/test/java/com/wearable/inspection/vision/dpm/imported/DpmScannerTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/dpm/imported/DpmScanControlTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/dpm/imported/DpmDimensionModeTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/dpm/experiment/DpmPolarityExperimentTest.kt`

**迁移阶段**：B2（DPM 迁移）

**当前状态**：✅ 未迁移

---

### 3.2 DpmPreprocessor.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/DpmPreprocessor.kt`

**功能**：
- DPM 码预处理策略轮转（4 种策略）
- 针撞码自适应双极性 + 闭运算
- 反色码双候选
- 激光蚀刻码 Otsu 双极性 + 膨胀
- 增强灰度（CLAHE）
- Debug 落盘配额控制

**新工程目标**：
- 迁移到新工程（B2 阶段）

**可复用代码**：
- ✅ 完全复用（纯 JVM 图像处理）

**必须解除的耦合**：
- OpenCV（改为 Bitmap 操作）

**依赖项**：
- OpenCV（Imgproc / cvtColor /  morphology）

**对应测试**：无独立测试（集成在 DpmAnalyzerTest）

**迁移阶段**：B2（DPM 迁移）

**当前状态**：✅ 未迁移

---

### 3.3 DpmFrameQuality.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/DpmFrameQuality.kt`

**功能**：
- DPM 帧质量评估

**新工程目标**：
- 迁移到新工程（B2 阶段，可选）

**可复用代码**：
- ⚠️ 待评估

**必须解除的耦合**：
- 待评估

**依赖项**：待评估

**对应测试**：无

**迁移阶段**：B2（DPM 迁移）

**当前状态**：✅ 未迁移

---

### 3.4 DpmGridGate.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/DpmGridGate.kt`

**功能**：
- 网格重建任务门控
- miss 门槛 + 冷却 + 会话代数

**新工程目标**：
- 迁移到新工程（B2 阶段）

**可复用代码**：
- ✅ 完全复用（纯 Kotlin 逻辑）

**必须解除的耦合**：
- 无

**依赖项**：无

**对应测试**：无

**迁移阶段**：B2（DPM 迁移）

**当前状态**：✅ 未迁移

---

### 3.5 DpmGridReconstructor.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/camera/DpmGridReconstructor.kt`

**功能**：
- DPM 网格重建（重型任务）
- anchors → 粗搜 → 精修

**新工程目标**：
- B2 评估（性能/复杂度）

**可复用代码**：
- ⚠️ 待评估（可能涉及 OpenCV 重型运算）

**必须解除的耦合**：
- OpenCV（待评估）

**依赖项**：
- OpenCV
- ImportedDpmScanner（第三方库）

**对应测试**：无

**迁移阶段**：B2（DPM 迁移）

**当前状态**：✅ 未迁移

---

### 3.6 vision/dpm/imported/*

**旧路径**：`app/src/main/java/com/wearable/inspection/vision/dpm/imported/`

**功能**：
- ImportedDpmScanner（第三方 DPM 扫描器）
- DpmScanControl（扫描控制接口）
- DpmDimensionMode（尺寸模式枚举）

**新工程目标**：
- B2 评估（是否保留重型网格重建）

**可复用代码**：
- ⚠️ DpmScanControl 接口
- ⚠️ DpmDimensionMode 枚举

**必须解除的耦合**：
- ImportedDpmScanner 实现（第三方库，待评估）

**依赖项**：
- ImportedDpmScanner（第三方库）

**对应测试**：
- `app/src/test/java/com/wearable/inspection/vision/dpm/imported/DpmScannerTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/dpm/imported/DpmScanControlTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/dpm/imported/DpmDimensionModeTest.kt`

**迁移阶段**：B2（DPM 迁移）

**当前状态**：✅ 未迁移

---

## 4. 钢印 OCR 模块

### 4.1 SteelStampOcrAnalyzer.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/vision/ocr/SteelStampOcrAnalyzer.kt`

**功能**：
- 钢印 OCR 分层分析器
- ROI 输入（字符高 30~60px）
- 协作式超时防跑飞（OCR_TIMEOUT_MS 绝对预算）
- 多候选增强（clahe/gamma/unsharp/adaptive × 双极性 = 8 条候选）
- ML Kit 文本识别
- 几何行聚类（SteelStampLineCluster）
- 字符级加权融合（SteelStampCharFusion）
- 版式/目录状态机（SteelStampResultMachine）

**新工程目标**：
- 迁移到新工程（B3 阶段）

**可复用代码**：
- ⚠️ 完全复用（纯 JVM + ML Kit）

**必须解除的耦合**：
- 无（纯算法逻辑）

**依赖项**：
- com.google.mlkit:text-recognition
- OpenCV（待评估是否移除）

**对应测试**：
- `app/src/test/java/com/wearable/inspection/vision/ocr/SteelStampOcrAnalyzerTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/SteelStampCharFusionTest.kt`

**迁移阶段**：B3（OCR 功能）

**当前状态**：✅ 未迁移

---

### 4.2 OcrResultDialog.kt

**旧路径**：`app/src/main/java/com/wearable/inspection/ui/dialogs/OcrResultDialog.kt`

**功能**：
- 钢印 OCR 识别结果弹窗
- 可变行编辑（1~N 行）
- 不确定字符高亮（黄色底色 + 下划线）
- ROI 缩略图显示
- 状态标签（EXACT / NEED_CONFIRMATION / FAILED）
- 按钮组：重新拍照 / 复制 / 确认保存 / 搜索零件

**新工程目标**：
- 迁移到新工程（B3 阶段）

**可复用代码**：
- ✅ Composable 布局结构
- ✅ 状态标签颜色逻辑
- ✅ 不确定字符高亮（UncertainHighlightTransformation）
- ✅ 逐行编辑框

**必须解除的耦合**：
- ⚠️ LocalInspectionColors → 改为新工程主题颜色

**依赖项**：
- Compose Material 3
- SteelStampResult 数据类

**对应测试**：无（UI 测试）

**迁移阶段**：B3（OCR 功能）

**当前状态**：✅ 未迁移

---

### 4.3 OCR 辅助模块

**旧路径**：
- `app/src/main/java/com/wearable/inspection/vision/ocr/SteelStampResult.kt`
- `app/src/main/java/com/wearable/inspection/vision/ocr/SteelStampLineCluster.kt`
- `app/src/main/java/com/wearable/inspection/vision/ocr/SteelStampCharFusion.kt`
- `app/src/main/java/com/wearable/inspection/vision/ocr/BmwThreeLineFormatter.kt`
- `app/src/main/java/com/wearable/inspection/vision/ocr/OcrPreProcessor.kt`
- `app/src/main/java/com/wearable/inspection/vision/ocr/OcrRoiCropUtils.kt`
- `app/src/main/java/com/wearable/inspection/vision/ocr/StampRegionLocator.kt`

**功能**：
- 钢印 OCR 辅助算法（行聚类、字符融合、预处理、ROI 裁剪等）

**新工程目标**：
- 迁移到新工程（B3 阶段）

**可复用代码**：
- ✅ 完全复用（纯 JVM 算法）

**必须解除的耦合**：
- OpenCV（待评估）

**依赖项**：
- OpenCV（部分模块）

**对应测试**：
- `app/src/test/java/com/wearable/inspection/vision/ocr/SteelStampResultTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/SteelStampLineClusterTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/SteelStampCharFusionTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/FormatterProbeTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/BmwThreeLineFormatterTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/OcrPreProcessorTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/OcrRoiCropUtilsTest.kt`
- `app/src/test/java/com/wearable/inspection/vision/ocr/StampRegionLocatorTest.kt`

**迁移阶段**：B3（OCR 功能）

**当前状态**：✅ 未迁移

---

### 4.4 StampOcrRecord 实体

**旧路径**：`app/src/main/java/com/wearable/inspection/data/entity/Entities.kt`（StampOcrRecord 类）

**功能**：
- 钢印 OCR 确认记录实体
- 字段：partId、confirmedNumber、rawNumber、status、timestamp

**新工程目标**：
- 迁移到新工程数据库（B3 阶段）

**可复用代码**：
- ✅ Entity 定义（直接迁移）

**必须解除的耦合**：
- 无

**依赖项**：
- Room Entity

**对应测试**：无

**迁移阶段**：B3（OCR 功能）

**当前状态**：✅ 未迁移

---

## 5. 其他组件

### 5.1 PartSelectionBus

**旧路径**：`app/src/main/java/com/wearable/inspection/ui/shared/PartSelectionBus.kt`（待确认）

**功能**：
- 零件选择广播（EventBus 模式）
- 采集页和模板库页联动切件

**新工程目标**：
- 迁移到新工程（B1 阶段）

**可复用代码**：
- ✅ 完全复用（纯 Kotlin）

**必须解除的耦合**：
- 无

**依赖项**：无

**对应测试**：无

**迁移阶段**：B1（本轮）

**当前状态**：✅ 未迁移

---

## 6. 迁移阶段汇总

### 阶段 B0：旧功能迁移审计（本轮）
- ✅ 建立本清单
- ✅ 识别可复用代码和必须解除的耦合

### 阶段 B1：共享 CameraX 基础（本轮）
- ✅ VideoSource 接口（简化版）
- ✅ CameraXVideoSource（只保留 PHONE_CAMERA）
- ✅ VideoSourceFactory（简化）
- ✅ CameraSessionViewModel（移除 Leion/USB）
- ✅ VideoPreview（只支持 CameraX）
- ✅ ImageStore（移除 OpenCV）
- ✅ PartSelectionBus

### 阶段 B2：DPM 迁移（待执行）
- ⚠️ DpmAnalyzer（评估 OpenCV 移除方案）
- ⚠️ DpmPreprocessor
- ⚠️ DpmRespondGate / DpmGridGate
- ⚠️ ImportedDpmScanner（性能评估）

### 阶段 B3：OCR 和模板配置（待执行）
- ⚠️ SteelStampOcrAnalyzer
- ⚠️ OCR 辅助模块
- ⚠️ OcrResultDialog
- ⚠️ TemplateViewModel
- ⚠️ TemplateScreen
- ⚠️ TemplatePackageImporter/Exporter
- ⚠️ StampOcrRecord Entity

---

## 7. 依赖项对比

### 旧工程依赖（待迁移部分）

```kotlin
// CameraX
implementation "androidx.camera:camera-core:1.3.1"
implementation "androidx.camera:camera-camera2:1.3.1"
implementation "androidx.camera:camera-lifecycle:1.3.1"
implementation "androidx.camera:camera-view:1.3.1"

// OpenCV（待逐步移除）
implementation project(':opencv')

// ML Kit
implementation "com.google.android.gms:play-services-mlkit-barcode-scanning:17.2.0"

// ZXing
implementation "com.google.zxing:core:3.5.2"

// Room
implementation "androidx.room:room-runtime:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"

// Leion SDK（不迁移）
implementation files('libs/llvision-glxss-sdk.jar')
```

### 新工程依赖（已配置）

```kotlin
// CameraX（已配置）
implementation "androidx.camera:camera-core:1.3.1"
implementation "androidx.camera:camera-camera2:1.3.1"
implementation "androidx.camera:camera-lifecycle:1.3.1"
implementation "androidx.camera:camera-view:1.3.1"

// OpenCV（已配置，待移除）
implementation project(':opencv')

// ML Kit（待添加）
implementation "com.google.android.gms:play-services-mlkit-barcode-scanning:17.2.0"

// ZXing（待添加）
implementation "com.google.zxing:core:3.5.2"

// Room（已配置）
implementation "androidx.room:room-runtime:2.6.1"
ksp "androidx.room:room-compiler:2.6.1"
```

---

## 8. 文件结构对比

### 旧工程结构

```
Wearable Inspection/
├── app/src/main/java/com/wearable/inspection/
│   ├── camera/
│   │   ├── VideoSource.kt
│   │   ├── CameraXVideoSource.kt
│   │   ├── VideoSourceFactory.kt
│   │   ├── DpmAnalyzer.kt
│   │   ├── DpmPreprocessor.kt
│   │   ├── DpmFrameQuality.kt
│   │   ├── DpmGridGate.kt
│   │   ├── DpmGridReconstructor.kt
│   │   └── LeionProVideoSource.kt
│   ├── ui/
│   │   ├── shared/
│   │   │   ├── CameraSessionViewModel.kt
│   │   │   └── PartSelectionBus.kt
│   │   ├── components/
│   │   │   └── VideoPreview.kt
│   │   ├── screens/
│   │   │   ├── templates/
│   │   │   │   ├── TemplateViewModel.kt
│   │   │   │   └── TemplateScreen.kt
│   │   │   └── capture/
│   │   │       └── CaptureScreen.kt
│   │   └── dialogs/
│   │       └── OcrResultDialog.kt
│   ├── data/
│   │   ├── image/
│   │   │   └── ImageStore.kt
│   │   ├── exporter/
│   │   │   └── TemplatePackageExporter.kt
│   │   └── entity/
│   │       └── Entities.kt（含 StampOcrRecord）
│   ├── template/
│   │   ├── TemplatePackageImporter.kt
│   │   └── TemplateViewRegion.kt
│   └── vision/
│       ├── dpm/
│       │   ├── DpmScanControl.kt
│       │   ├── DpmDimensionMode.kt
│       │   └── imported/
│       │       ├── ImportedDpmScanner.kt
│       │       └── DpmScanner.kt
│       └── ocr/
│           ├── SteelStampOcrAnalyzer.kt
│           ├── SteelStampResult.kt
│           ├── SteelStampLineCluster.kt
│           ├── SteelStampCharFusion.kt
│           ├── BmwThreeLineFormatter.kt
│           ├── OcrPreProcessor.kt
│           ├── OcrRoiCropUtils.kt
│           └── StampRegionLocator.kt
```

### 新工程结构（B1 完成后）

```
MobileInspectionApp/
├── app/src/main/java/com/wearable/inspection/mobile/
│   ├── camera/
│   │   ├── SharedCameraSession.kt（新）
│   │   ├── CameraController.kt（新）
│   │   └── CameraState.kt（新）
│   ├── ui/
│   │   ├── screens/
│   │   │   └── LiveInspectionScreen.kt（已调整）
│   │   └── components/
│   │       └── CameraPreview.kt（新，从旧 VideoPreview 迁移）
│   └── data/
│       └── image/
│           └── MobileImageStore.kt（新，从旧 ImageStore 迁移）
```

---

## 9. 迁移检查清单

### B1：共享 CameraX 基础

- [ ] 创建 `SharedCameraSession` 接口（从 VideoSource 提取）
- [ ] 实现 `PhoneCameraController`（从 PhoneCameraVideoSource 迁移，移除 OpenCV）
- [ ] 简化 `VideoSourceFactory`（只创建 PhoneCameraController）
- [ ] 迁移 `CameraSessionViewModel`（移除 Leion/USB/G40 代码）
- [ ] 迁移 `VideoPreview` 为 `CameraPreview`（只支持 CameraX）
- [ ] 迁移 `ImageStore`（移除 OpenCV Mat 依赖）
- [ ] 迁移 `PartSelectionBus`
- [ ] 接入 LiveInspectionScreen（删除 CameraPreviewPlaceholder）
- [ ] 实现基础拍照（ImageCapture）
- [ ] 验证相机权限处理

### B2：DPM 迁移（待 B1 完成后评估）

- [ ] 评估 ImportedDpmScanner 性能影响
- [ ] 迁移 DpmAnalyzer（移除 OpenCV）
- [ ] 迁移 DpmPreprocessor
- [ ] 迁移 DpmRespondGate / DpmGridGate
- [ ] 迁移 DpmGridReconstructor（如果保留）
- [ ] 单元测试迁移和验证

### B3：OCR 和模板配置（待 B2 完成后评估）

- [ ] 迁移 SteelStampOcrAnalyzer
- [ ] 迁移 OCR 辅助模块
- [ ] 迁移 OcrResultDialog
- [ ] 迁移 TemplateViewModel（部分）
- [ ] 迁移 TemplateScreen
- [ ] 迁移 TemplatePackageImporter/Exporter
- [ ] 迁移 StampOcrRecord Entity
- [ ] 单元测试迁移和验证

---

## 10. 风险和注意事项

### 风险 1：OpenCV 移除
- **影响**：ImageStore.saveMatchFrame、DpmPreprocessor 等依赖 OpenCV
- **缓解**：逐步迁移，B1 先移除 ImageStore 的 OpenCV 依赖，B2 评估 DPM 模块

### 风险 2：ImportedDpmScanner 性能
- **影响**：重型网格重建可能阻塞分析线程
- **缓解**：B2 性能测试，考虑降级为只保留 ZXing + ML Kit

### 风险 3：ML Kit 模型大小
- **影响**：ML Kit BarcodeScanning + TextRecognition 增加 APK 大小
- **缓解**：按需加载（barcode-scanning 已配置，text-recognition 按需添加）

### 风险 4：单元测试迁移
- **影响**：旧工程测试依赖 OpenCV 和 Leion SDK
- **缓解**：B1/B2/B3 分阶段迁移测试，Mock 硬件依赖

---

**文档维护**：Claude Code
**最后更新**：2026-09-02

---

## 11. B2 Task 1 Batch 5 验证状态（2026-09-02）

### 包名门禁

| 项目 | 值 |
|------|-----|
| **新包名** | `com.wearable.inspection.mobile` |
| **启动组件** | `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` |
| **APK SHA-256** | `00357f7c9c38cc1ff3cd36d2ffc9cb8f3cbd3c898dcb1d926a684a67adf28c1b` |
| **旧包名** | `com.wearable.inspection` |
| **旧 APK SHA-256** | `6e14a3b4995f90aff0c77e4af6d10f65ce1d482674370bab75806c3ee16d88aa` |

### 已完成验证

- [x] 包名门禁：显式安装、启动组件、前台包校验 ✓
- [x] DPM 扫码页面：UIAutomator 结构验证 ✓
- [x] 扫码页面往返 10 次：10/10 通过 ✓
- [x] 前后台切换 10 次：10/10 通过 ✓
- [x] Logcat 门禁 8 项：0 违规 ✓
- [x] 相机生命周期：DPM 退出 → INSPECTION 恢复 ✓

### 2026-09-02 新增验证

- [x] 尺寸设置接线：DpmScanViewModel 传递 `MobileInspectionApp.settings(app).dpmDimensionMode` ✓
- [x] 旧参数恢复：centerCropRatio=0.5f、roiTargetWidth=400、missTriggerCount=30、gridMissThreshold=8、gridCooldownMs=1500 ✓
- [x] 框内约束自动化：DpmFrameConstraintTest 17 项通过 ✓
- [x] DPM instrumented 测试：DpmSettingsInstrumentedTest 10 项通过 ✓
- [x] 冷启动稳定性：10/10 通过，logcat 6 项门禁 0 违规 ✓
- [x] JVM 测试总数：208 @Test（203 passed / 0 failed / 5 skipped）
- [x] Instrumented 测试总数：30/30 passed

### 待完成（需要物理 DPM 样品）

- [ ] 框内/框外码真机验证
- [ ] 10 次真实扫码
- [ ] 完整新旧 App A/B 对比（一个同码可用性对照已通过，尚缺逐样本结果和响应时间）
- [ ] 截图人工视觉复核（mimo-v2.5-pro 无法读取 PNG）

### 真实样品与闪光灯整改补充（2026-09-02）

- [x] DPM 分析器恢复旧 App 的高分辨率请求；设备实际协商为 1440×1080
- [x] 恢复策略 2 `s2-bright-otsu-dilate` 候选的旧版点阵快速链

---

## 13. 快速迁移模块（2026-09-02）

以下模块按"旧工程成熟实现优先直接移植"原则迁移，不重新设计。

### 13.1 TtsManager

| 维度 | 内容 |
|------|------|
| 旧文件 | `tts/TtsManager.kt` |
| 新文件 | `mobile/tts/TtsManager.kt` |
| 迁移方式 | 直接复制，改 package |
| 旧工程耦合 | 无（纯 Android TextToSpeech API） |
| 修改量 | 零 |
| 测试 | JVM 验证（初始化/释放逻辑） |

### 13.2 TemplatePackageImporter

| 维度 | 内容 |
|------|------|
| 旧文件 | `template/TemplatePackageImporter.kt` |
| 新文件 | `mobile/template/TemplatePackageImporter.kt` |
| 迁移方式 | 直接复制，改 package |
| 旧工程耦合 | 无（纯 JVM ZIP/JSON 解析） |
| 修改量 | 零（添加 `org.json:json:20231013` testImplementation 替代 AGP stub） |
| 测试 | `TemplatePackageImporterTest.kt` 全量迁移（17 项） |

### 13.3 ResultPackager

| 维度 | 内容 |
|------|------|
| 旧文件 | `result/ResultPackager.kt` |
| 新文件 | `mobile/result/ResultPackager.kt` |
| 迁移方式 | 直接复制，改 package |
| 旧工程耦合 | 无（纯 JVM ZIP 创建） |
| 修改量 | 零 |
| 测试 | `ResultPackagerTest.kt` 全量迁移（4 项） |

### 13.4 BatteryOptimizationHelper

| 维度 | 内容 |
|------|------|
| 旧文件 | `service/BatteryOptimizationHelper.kt` |
| 新文件 | `mobile/service/BatteryOptimizationHelper.kt` |
| 迁移方式 | 直接复制，改 package |
| 旧工程耦合 | 无（纯 Android PowerManager/Settings API） |
| 修改量 | 零 |
| 测试 | 无（纯系统 API 调用，需真机验证） |

### 13.5 InspectionForegroundService

| 维度 | 内容 |
|------|------|
| 旧文件 | `service/InspectionForegroundService.kt` |
| 状态 | ❌ 暂不迁移 |
| 原因 | 旧服务是 `connectedDevice` 类型，深度耦合 Leion 眼镜、`LeionConnectionState`、`LeionManager`、`WearableApp`；新工程明确排除 G40/Leion/USB |
| 后续条件 | 仅当手机 CameraX 确实需要息屏/后台持续巡检时，重新基于手机 App 的 CameraX 生命周期设计最薄服务 |
- [x] 使用旧 App 可识别的同一显示设备 DPM 码，新 App 成功识别 `M968942280224B169AH005023044710`
- [x] CameraController 首次绑定与模式重绑均保存 cameraControl，闪光灯开/关经用户真机复测通过
- [x] 最终 APK SHA-256：`bf93862c2ece79263ac2ef04f5cd176bcd2d726d0d738a55e0ad31428f5bb062`
