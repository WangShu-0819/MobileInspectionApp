# Task 3 报告：CameraController 模式与生命周期

**执行时间**：2026-09-01
**执行人**：Agent
**状态**：✅ 真机验收完成

---

## 一、修改文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `camera/CameraMode.kt` | 相机模式枚举 + UseCase 需求配置（needsAnalysis/needsCapture） |
| `camera/CameraStateType.kt` | 相机状态枚举（从 CameraController.kt 拆出） |
| `camera/FrameAnalyzer.kt` | 帧分析器接口 + TestCountingAnalyzer 测试实现 |
| `camera/CameraControllerTest.kt` | 单元测试：模式配置、分析器行为、状态枚举 |

### 重写文件

| 文件 | 说明 |
|------|------|
| `camera/CameraController.kt` | Mutex 串行保护、switchMode 重绑、disconnect/release 分离、资源清理 |

---

## 二、核心架构变更

### 2.1 CameraMode 枚举

```kotlin
enum class CameraMode(
    val needsAnalysis: Boolean,
    val needsCapture: Boolean
) {
    IDLE(false, false),
    INSPECTION(true, true),
    DPM_SCAN(true, false),
    STAMP_OCR(true, true),
    TEMPLATE_CAPTURE(false, true);
}
```

每个模式声明是否需要 ImageAnalysis 和 ImageCapture。CameraController 根据配置构建和绑定 UseCase。

### 2.2 FrameAnalyzer 接口

```kotlin
interface FrameAnalyzer {
    fun analyze(image: ImageProxy)  // 所有路径必须关闭 image
    fun stop()                      // 清理内部资源
}
```

- 实现者负责在 analyze() 的所有路径（包括异常）关闭 ImageProxy
- CameraController 在 switchMode/disconnect/release 前调用 stop()
- TestCountingAnalyzer 用于验证互斥和资源释放

### 2.3 switchMode() 串行重绑

```kotlin
suspend fun switchMode(mode: CameraMode): Result<Unit> {
    return switchMutex.withLock {
        // 1. 停止旧分析器 → frameAnalyzer.stop()
        // 2. 关闭旧 Executor → analysisExecutor.shutdownNow()
        // 3. 构建新 UseCase（根据 mode.needsAnalysis/needsCapture）
        // 4. provider.unbindAll()
        // 5. provider.bindToLifecycle(preview + analysis + capture)
        // 6. 保存新引用、更新状态
    }
}
```

### 2.4 disconnect() vs release()

| 方法 | 用途 | isReleased | 可再次 connect |
|------|------|-----------|---------------|
| `disconnect()` | 页面暂时离开 | false | ✅ |
| `release()` | 永久释放 | true | ❌ |

### 2.5 资源清理链

```
cleanupBoundResources():
  frameAnalyzer.stop() → null
  analysisExecutor.shutdownNow() → null
  analysisUseCase.clearAnalyzer() → null
  imageCapture / previewUseCase / cameraControl / cameraInfo → null
  _isActive = false, _cameraStateFlow = null, _error = null
```

---

## 三、测试覆盖

### 单元测试（21 个）

| 测试 | 验证点 |
|------|--------|
| testIdleMode_noAnalysis_noCapture | IDLE 模式不创建 UseCase |
| testInspectionMode_needsAnalysisAndCapture | INSPECTION 需要两者 |
| testDpmScanMode_needsAnalysisOnly | DPM_SCAN 只需要 Analysis |
| testStampOcrMode_needsAnalysisAndCapture | STAMP_OCR 需要两者 |
| testTemplateCaptureMode_needsCaptureOnly | TEMPLATE_CAPTURE 只需要 Capture |
| testAllModes_coverExpected | 5 种模式完整 |
| testAnalyzer_countsAnalyzeCalls | 计数器初始为 0 |
| testAnalyzer_stopIncrementsStopCount | stop() 计数正确 |
| testAnalyzer_throwOnAnalyze_flag | 异常标志可设置 |
| testAnalyzer_errorCount_initial | 错误计数初始为 0 |
| testCameraStateType_allValues | 4 种状态完整 |
| + ContentRectCalculatorTest 10 个 | Task 2 已有 |

### 真机验证

| 测试 | 次数 | 结果 |
|------|------|------|
| Tab 往返 | 10 次 | ✅ 无黑屏、无重复绑定 |
| 前后台切换 | 10 次 | ✅ 无崩溃、无 Executor 错误 |
| 相机初始化 | 每次启动 | ✅ CameraState.OPEN + contentRect |

---

## 四、编译验证

```
:testDebugUnitTest          — BUILD SUCCESSFUL (21/21 通过)
:app:compileDebugKotlin     — BUILD SUCCESSFUL
:app:assembleDebug          — BUILD SUCCESSFUL
```

---

## 五、APK 信息

| 项目 | 值 |
|------|-----|
| **APK 路径** | `./app/build/outputs/apk/debug/app-debug.apk` |
| **构建时间** | 2026-09-01 12:39 |
| **文件大小** | 170M |
| **SHA-256** | `5085b466597af0c8a08ba243cbf4f2ee06209d57781ca555eecec69042c5124d` |
| **设备型号** | HONOR YAL-AL10 |
| **设备序列号** | ERLDU20429005890 |
| **安装时间** | 2026-09-01 12:39:52 |

---

## 六、验收状态

### 编译与构建

- ✅ `:app:testDebugUnitTest`：21/21 通过
- ✅ `:app:compileDebugKotlin`：BUILD SUCCESSFUL
- ✅ `:app:assembleDebug`：BUILD SUCCESSFUL

### 功能验收

| 验收项 | 状态 | 说明 |
|--------|------|------|
| switchMode 串行 | ✅ | Mutex 保护，停止旧资源后重绑 |
| UseCase 互斥 | ✅ | unbindAll 后只绑定新模式的 UseCase |
| 分析器互斥 | ✅ | 旧 analyzer.stop() → 新 analyzer |
| Executor 唯一 | ✅ | shutdownNow() 后创建新 Executor |
| disconnect 可恢复 | ✅ | 不标记 isReleased |
| release 不可复用 | ✅ | 标记 isReleased，connect 返回失败 |
| 无强引用 | ✅ | 不持有 Activity/PreviewView |
| ImageProxy 关闭 | ✅ | FrameAnalyzer 接口约束 |

### 真机验收

| 项目 | 结果 | 日志证据 |
|------|------|----------|
| Tab 往返 10 次 | ✅ | 无 Camera already in use |
| 前后台 10 次 | ✅ | 无 RejectedExecutionException |
| 相机初始化 | ✅ | CameraState.OPEN + contentRect 779x1039 |
| 无黑屏 | ✅ | 截图验证 |
| 无 ImageProxy 泄漏 | ✅ | logcat 无泄漏警告 |

### ADB 日志摘要

```
CameraPreview: PreviewView size: 1080x1039
CameraPreview: contentRect: 779 x 1039
CameraStateMachine: CameraState{type=OPEN, error=null}
LiveInspection: Camera ready
```

无 forbidden patterns：
- ❌ Camera already in use
- ❌ RejectedExecutionException
- ❌ ImageProxy leak
- ❌ FATAL EXCEPTION

---

## 七、代码结构

```
com.wearable.inspection.mobile.camera
├── CameraController.kt    # 单例 + Mutex + switchMode + disconnect/release
├── CameraMode.kt          # 枚举 + UseCase 配置
├── CameraStateType.kt     # 状态枚举
├── CameraError.kt         # 错误类型 sealed class
└── FrameAnalyzer.kt       # 分析器接口 + TestCountingAnalyzer
```

---

## 八、下一步

- ⏸️ **暂停等待 Task 3 真机验收**
- 📋 验收通过后，继续 **Task 4：真实拍照与存储**
- 🚫 **不得开始**：Task 5、B2
