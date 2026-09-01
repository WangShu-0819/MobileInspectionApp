# B2 Task 1: DPM Legacy Pipeline Migration — Parity Report

## 概述

从旧版 Wearable Inspection 项目迁移 DPM（Direct Part Mark）扫码流水线到新版 MobileInspectionApp 架构（CameraX）。

**迁移范围**：纯解码流水线（无相册导入、无 QR Code、无 OCR/模板/轮廓/ROI/检测算法）

**源仓库**：`D:\study\Textile_defects\Wearable Inspection\Wearable Inspection`
**目标仓库**：`MobileInspectionApp`（基于 CameraX 单例 CameraController）

---

## 检查点总览

| 检查点 | 内容 | 提交哈希 | 状态 |
|--------|------|----------|------|
| CP1 | 纯 Kotlin 控制逻辑 | `b35efc1d` | ✅ |
| CP2 | OpenCV 预处理 & 网格链 | `e08a9740` | ✅ |
| CP3 | 真实解码器 + 组装版 DpmAnalyzer | `caf69811` | ✅ |
| CP4 | CameraX 帧分析器集成 | `3b6b547d` | ✅ |
| CP5 | 扫码 UI 与导航 | `8f0dcb4d` | ✅ |
| CP6 | 自动化 & 设备验证 | 本次 | ✅ |

---

## CP1: 纯 Kotlin 控制逻辑（5 文件，38 测试）

| 文件 | 旧版来源 | 忠实度 |
|------|----------|--------|
| `DpmDimensionMode.kt` | `camera/DpmDimensionMode.kt` | 参数完全保留：AUTO/DIM_16/DIM_18/DIM_20，配额 Top8+4/Top24+12 |
| `DpmGridGate.kt` | `camera/DpmGridGate.kt` | 会话代数、missStreak、canSubmit 条件一致 |
| `DpmDumpBudget.kt` | `camera/DpmDumpBudget.kt` | 线程安全、DEFAULT_MAX_FRAME_SETS=30 |
| `DpmScanControl.kt` | `camera/DpmScanControl.kt` | 协作截止 + 取消锁存，abortReason 枚举 |
| `DpmRespondGate.kt` | `camera/DpmRespondGate.kt` | REARM_MISSES=10、MAX_HOLD_RESPOND_MS=5000 |

**测试覆盖**：38 项 JVM 单测全部通过

---

## CP2: OpenCV 预处理 & 网格链（4 文件，OpenCV 桌面测试）

| 文件 | 旧版来源 | 忠实度 |
|------|----------|--------|
| `DpmPreprocessor.kt` | `camera/DpmPreprocessor.kt` | 4 策略完全保留：针打/反色/激光蚀刻/CLAHE 灰度 |
| `DpmFrameQuality.kt` | `camera/DpmFrameQuality.kt` | 过曝/欠曝/动态范围/纹理阈值一致 |
| `DpmGridReconstructor.kt` | `camera/DpmGridReconstructor.kt` | 薄包装委托 ImportedDpmScanner |
| `ImportedDpmScanner.kt` | Python 参考实现 | ~1850 行完整移植：网格候选/旋转网格/九宫格×变体 |

**测试覆盖**：OpenCV 渲染 + 解码验证、质量门控测试、扫描器全链测试

---

## CP3: 真实解码器 + 组装版 DpmAnalyzer（3 文件，11 测试）

| 文件 | 旧版来源 | 忠实度 |
|------|----------|--------|
| `ZxingDataMatrixDecoder.kt` | `camera/DpmZxingDecoder` 实现 | TRY_HARDER、双二值化器 + 反色双试 |
| `MlKitDataMatrixDecoder.kt` | `camera/DpmMlKitDecoder` 实现 | FORMAT_DATA_MATRIX、600ms 超时 |
| `DpmAnalyzer.kt` | `camera/DpmAnalyzer.kt` | 组装版：ZXing 主→ML Kit 兜底、策略轮转、200ms 节流、单飞、漏检对焦回调、网格异步 |

**关键参数保留**：
- 中心 ROI：1200×1200
- 下缩放阈值：1800×1800，因子 3
- 漏检触发：missTriggerCount=6
- 节流：success 1000ms/3000ms，fail 100ms/200ms

**测试覆盖**：4 项 ZXing 解码测试 + 7 项 DpmAnalyzer 测试（含可注入时钟）

---

## CP4: CameraX 帧分析器集成（1 文件，3 测试）

| 文件 | 说明 |
|------|------|
| `DpmFrameAnalyzer.kt` | 实现 FrameAnalyzer 接口，ImageProxy→Bitmap 转换，委托 DpmAnalyzer，SharedFlow 发射结果 |

**测试覆盖**：接口实现验证、stop 安全性、results 流可达性

---

## CP5: 扫码 UI 与导航（2 文件 + 路由注册）

| 文件 | 说明 |
|------|------|
| `DpmScanViewModel.kt` | 管理 DpmAnalyzer + DpmFrameAnalyzer 生命周期，收集结果 |
| `DpmScanScreen.kt` | Compose 扫码页面：CameraPreview + 扫描框覆盖层 + 结果卡片 |

**路由**：`Screen.DpmScan` → `AppNavigation` 注册

---

## CP6: 自动化 & 设备验证

### JVM 单元测试

```
./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL in 24s
30 actionable tasks: 6 executed, 24 up-to-date
```

### Android 设备测试

```
./gradlew :app:connectedDebugAndroidTest
Starting 20 tests on YAL-AL10 - 10
20/20 completed. (0 skipped) (0 failed)
BUILD SUCCESSFUL in 1m 10s
```

**测试设备**：HUAWEI YAL-AL10 (Android 10)

---

## 文件清单

### 新增文件（16 文件）

| # | 文件 | 行数 |
|---|------|------|
| 1 | `dpm/DpmDimensionMode.kt` | ~80 |
| 2 | `dpm/DpmGridGate.kt` | ~83 |
| 3 | `dpm/DpmDumpBudget.kt` | ~50 |
| 4 | `dpm/DpmScanControl.kt` | ~55 |
| 5 | `dpm/DpmRespondGate.kt` | ~95 |
| 6 | `dpm/DpmPreprocessor.kt` | ~326 |
| 7 | `dpm/DpmFrameQuality.kt` | ~65 |
| 8 | `dpm/DpmGridReconstructor.kt` | ~71 |
| 9 | `dpm/ImportedDpmScanner.kt` | ~1850 |
| 10 | `dpm/ZxingDataMatrixDecoder.kt` | ~80 |
| 11 | `dpm/MlKitDataMatrixDecoder.kt` | ~50 |
| 12 | `dpm/DpmAnalyzer.kt` | ~310 |
| 13 | `dpm/DpmFrameAnalyzer.kt` | ~130 |
| 14 | `dpm/DpmScanViewModel.kt` | ~120 |
| 15 | `ui/screens/DpmScanScreen.kt` | ~260 |
| 16 | `vision/OpenCvTestSupport.kt` | ~58 |

### 测试文件（9 文件）

| # | 文件 | 测试数 |
|---|------|--------|
| 1 | `DpmDimensionModeTest.kt` | 11 |
| 2 | `DpmGridGateTest.kt` | 7 |
| 3 | `DpmDumpBudgetTest.kt` | 6 |
| 4 | `DpmScanControlTest.kt` | 5 |
| 5 | `DpmRespondGateTest.kt` | 9 |
| 6 | `DpmPreprocessorTest.kt` | OpenCV 渲染验证 |
| 7 | `DpmFrameQualityTest.kt` | 质量门控测试 |
| 8 | `DpmScannerTest.kt` | 扫描器全链测试 |
| 9 | `ZxingDataMatrixDecoderTest.kt` | 4 |
| 10 | `DpmAnalyzerTest.kt` | 7 |
| 11 | `DpmFrameAnalyzerTest.kt` | 3 |

### 修改文件（3 文件）

| 文件 | 变更 |
|------|------|
| `app/build.gradle.kts` | 添加 `testImplementation(libs.opencv.desktop)` |
| `ui/navigation/Screen.kt` | 添加 `DpmScan` 路由 |
| `ui/navigation/AppNavigation.kt` | 注册 `DpmScanScreen` |

---

## 忠实度断言

1. **无算法省略**：所有旧版预处理策略（4 种）、网格重建（旋转+正向+九宫格×变体）、解码器（ZXing+ML Kit）均完整迁移
2. **无参数放松**：所有阈值、配额、超时参数完全保留旧版默认值
3. **无测试删除**：所有旧版测试逻辑保留，新增测试覆盖新架构
4. **无硬编码结果**：所有解码均为真实运行，未硬编码任何返回值
5. **CameraX 无回归**：CameraController 单例架构未修改，DPM_SCAN 模式正常工作
6. **冻结目录未动**：`tools/contour_extraction/` 保持冻结状态

---

## 已知差异

1. **包名**：从 `com.wearable.inspection.camera` 迁移到 `com.wearable.inspection.mobile.dpm`
2. **协程替代线程**：旧版使用 `ExecutorService` + `Future`，新版使用 `CoroutineScope` + `Job`
3. **时钟可注入**：`DpmAnalyzer` 接受 `DpmClock` 接口，测试使用 `FakeDpmClock` 替代 `System.currentTimeMillis()`
4. **DpmRespondGate 替代 DpmResultGate**：旧版 `DpmResultGate` 重命名为 `DpmRespondGate`，功能完全一致
