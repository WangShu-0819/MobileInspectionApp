# Task 2 报告：CameraPreview 状态与画幅

**执行时间**：2026-09-01
**执行人**：Agent
**状态**：🔧 代码收口完成（待真机验收）

---

## 一、修改文件清单

### 核心修改

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ContentRectCalculator.kt` | **新增** | 生产级 FIT_CENTER 纯函数，不依赖 Android SDK，可直接被单元测试覆盖 |
| `CameraPreview.kt` | **重构** | 调用生产函数、CameraState 观察驱动 onCameraReady/onCameraError、流信息等待 |
| `CameraController.kt` | **增强** | 新增 `cameraStateFlow`、`CameraStateType` 枚举；流信息来自 UseCase ResolutionInfo |
| `ContentRectCalculatorTest.kt` | **重写** | 10 个测试直接调用生产函数，无重复算法 |
| `build.gradle.kts` | **依赖** | 添加 `kotlin-test` 测试依赖 |
| `libs.versions.toml` | **依赖** | 添加 `kotlin-test` 版本声明 |

---

## 二、核心架构变更

### 2.1 ContentRectCalculator 生产函数

```kotlin
// ContentRectCalculator.kt — 不依赖 Android SDK
data class ContentRectBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun calculateContentRectBounds(
    viewWidth: Int, viewHeight: Int,
    rotatedStreamWidth: Int, rotatedStreamHeight: Int
): ContentRectBounds
```

- `CameraPreview.kt` 调用 `calculateContentRectBounds()` 后转换为 `android.graphics.Rect`
- 单元测试直接调用 `calculateContentRectBounds()`，无需复制算法
- 所有坐标统一 `Math.round()` 四舍五入，不使用 `toInt()` 截断

### 2.2 相机状态观察

**之前**：`connect().onSuccess { onCameraReady() }` — BOUND 即触发就绪
**之后**：

```kotlin
// connect() 成功只表示 BOUND，不触发 onCameraReady
cameraController.connect(...).onFailure { onCameraError(...) }

// 观察 CameraState：仅 OPEN 触发 onCameraReady
val cameraState by cameraController.cameraStateFlow.collectAsState()
LaunchedEffect(cameraState) {
    when (cameraState) {
        CameraStateType.OPEN -> { if (!hasCalledReady) { hasCalledReady = true; onCameraReady() } }
        CameraStateType.ERROR -> { onCameraError(CameraError.Unknown(...)) }
        else -> { /* PENDING_OPEN / CLOSED → 保持加载 */ }
    }
}
```

- `hasCalledReady` 防止重复 OPEN 事件多次调用 `onCameraReady`
- `CameraController.cameraStateFlow` 是 `StateFlow<CameraStateType?>`
- 页面离开时 `DisposableEffect` 自动清理

### 2.3 流信息来源

**之前**：`sensorRotationDegrees`（传感器旋转，非输出流旋转）
**之后**：`resolutionInfo.rotationDegrees`（UseCase 的实际输出流旋转）

```kotlin
val resInfo = analysis.resolutionInfo ?: capture.resolutionInfo
if (resInfo != null) {
    _streamResolution = resInfo.resolution
    _streamRotation = resInfo.rotationDegrees
}
// 为 null 时保持 null，UI 显示等待状态
```

- Preview/ImageAnalysis/ImageCapture 使用统一 `RATIO_4_3_FALLBACK_AUTO_STRATEGY`
- 流信息为 null 时不计算 ContentRect，显示加载指示器
- 流信息变化时（cameraState 变化触发 LaunchedEffect 重算）重新计算 ContentRect

---

## 三、测试覆盖

10 个测试直接调用生产函数 `calculateContentRectBounds()`：

| 测试 | 场景 | 验证点 |
|------|------|--------|
| testLandscapeMode | 1080x600, 4032x3024 | 左右留边, width=800, height=600 |
| testPortraitMode | 600x1080, 4032x3024 | 上下留边, width=600, height=450 |
| testSameRatio | 1200x900, 4032x3024 | 无留边, 完全填满 |
| testWiderStream_inTallerContainer | 720x1280, 1920x1080 | 左右留边, top=438 |
| testWiderStream_inWiderContainer | 1280x720, 1920x1080 | 同比例, 无留边 |
| testBoundaries_contentRectWithinPreview | 1080x1920, 4032x3024 | left/top>=0, right<=vw, bottom<=vh |
| testBoundaries_aspectRatioMatchesStream | 1080x1920, 4032x3024 | contentRect 宽高比 ≈ 流宽高比 |
| testRotation90 | 3024x4032→4032x3024 | 交换宽高, left=0, top=555 |
| testRotation270 | 3024x4032→4032x3024 | 交换宽高 |
| testRotation0 | 4032x3024 | 不交换宽高 |

---

## 四、编译验证

```
:app:testDebugUnitTest      — BUILD SUCCESSFUL (10/10 通过)
:app:compileDebugKotlin     — BUILD SUCCESSFUL
:app:assembleDebug          — BUILD SUCCESSFUL
```

---

## 五、APK 信息

| 项目 | 值 |
|------|-----|
| **APK 路径** | `./app/build/outputs/apk/debug/app-debug.apk` |
| **构建时间** | 2026-09-01 11:47 |
| **文件大小** | 170M |
| **SHA-256** | `17bedc47900754ae2ec775706bdf040530bda2933333617bddb803158db974da` |

---

## 六、验收状态

### 编译与构建

- ✅ `:app:testDebugUnitTest`：10/10 通过
- ✅ `:app:compileDebugKotlin`：BUILD SUCCESSFUL
- ✅ `:app:assembleDebug`：BUILD SUCCESSFUL

### 功能验收（代码层面）

| 验收项 | 状态 | 说明 |
|--------|------|------|
| 权限请求 | ✅ | 自动请求，hasRequestedPermission 追踪 |
| 权限临时拒绝 | ✅ | ON_RESUME 重新检查，可再次请求 |
| 权限永久拒绝 | ✅ | 提供"设置"按钮跳转系统设置 |
| FIT_CENTER | ✅ | PreviewView.ScaleType.FIT_CENTER |
| 4:3 画幅 | ✅ | ResolutionSelector RATIO_4_3_FALLBACK_AUTO_STRATEGY |
| ContentRect 精度 | ✅ | 生产函数 Math.round()，10 个单元测试覆盖 |
| ContentRect 比例 | ✅ | 1% 容差断言，Debug 模式 assert |
| 流信息来源 | ✅ | ResolutionInfo.rotationDegrees（非 sensorRotationDegrees） |
| 流信息等待 | ✅ | null 时不计算 ContentRect，显示加载 |
| onCameraReady 触发 | ✅ | 仅 CameraState.OPEN，hasCalledReady 防重复 |
| onCameraError 触发 | ✅ | CameraState.ERROR + connect 失败 |
| 加载状态 | ✅ | OPEN 前显示 CircularProgressIndicator |

### 真机验收待完成

| 项目 | 状态 |
|------|------|
| 四边标记完整 | ⏳ 待真机验证 |
| 圆形不变形 | ⏳ 待真机验证 |
| 临时拒绝后可再次请求 | ⏳ 待真机验证 |
| 永久拒绝可进入设置并恢复 | ⏳ 待真机验证 |
| 初始化状态只在 OPEN 后消失 | ⏳ 待真机验证 |

---

## 七、代码结构

```
com.wearable.inspection.mobile.ui.screens
├── ContentRectCalculator.kt  # FIT_CENTER 纯函数（internal，可测试）
├── CameraPreview.kt          # 相机预览 + CameraPreviewScreen 入口
├── PlaceholderScreens.kt     # InspectionResultScreen、TemplateDetailScreen
└── LiveInspectionScreen.kt   # 现场采集页

com.wearable.inspection.mobile.camera
├── CameraController.kt       # 单例 CameraX 管理器 + cameraStateFlow
├── CameraStateType.kt        # 枚举（PENDING_OPEN/OPEN/CLOSED/ERROR）
└── CameraError.kt            # 错误类型 sealed class
```

---

## 八、下一步

- ⏸️ **暂停等待 Task 2 真机验收**
- 📋 验收通过后，继续 **Task 3：CameraController 模式与生命周期**
- 🚫 **不得开始**：Task 4、Task 5、B2
