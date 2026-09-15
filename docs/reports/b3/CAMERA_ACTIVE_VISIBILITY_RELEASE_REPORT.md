# 现场采集页离开时立即暂停 CameraX 报告

## 状态

**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-15）

现场采集页可见性修复部分未修改一级页面动画；本次在其基础上追加启动闪退修复，并完成 APK 构建和设备启动门禁验证。

## 启动闪退追加修复（2026-09-15）

### 根因

`CameraPreview` 在 `withContext(NonCancellable + Dispatchers.Default)` 的 `cameraController.connect(...)` 参数中直接写入 `previewView.surfaceProvider`。Kotlin 会先在后台线程求值参数，再进入 `CameraController.connect`，因此 `RealCameraBinder` 的 CameraX 主线程桥接无法保护 `PreviewView.getSurfaceProvider()`，启动时触发线程错误并闪退。

### 本轮实现

- 在 `Dispatchers.Main.immediate` 中读取一次 `previewView.surfaceProvider`。
- 将已取得的 `Preview.SurfaceProvider` 传入后台 `CameraController.connect`；后台代码不再访问 `previewView.surfaceProvider`。
- 保留已有 `RealCameraBinder` 主线程桥接、`active=false` 立即 `disconnect(sessionId)`、连接代次、sessionId 防竞态、迟到连接清理、observer 门禁和 `DisposableEffect` 销毁兜底。
- 未调用 `release()`，未修改导航架构、CameraX 所有权、DPM/ECC、NanoDet、ROI、Room、ZIP 或 ABI 配置。

### 本轮新增/修改文件

| 文件 | 本轮作用 |
|---|---|
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt` | 主线程获取 `Preview.SurfaceProvider`，后台只使用已取得对象 |
| `app/src/test/java/com/wearable/inspection/mobile/ui/screens/CameraPreviewTest.kt` | 增加 surfaceProvider 线程归属回归契约 |
| `tasks/todo.md` | 增加启动闪退任务状态、APK 和设备门禁记录 |
| `docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md` | 追加本次根因、验证和限制 |

`CameraController.kt`、`LiveInspectionScreen.kt` 及其已有生命周期改动在本轮只审计并保留，未为启动闪退再次修改；工作区中的其他未提交改动同样未回滚或清理。

## 根因

`AppNavigation` 已有 `currentRoute == Screen.LiveInspection.route` 的 `isScreenVisible` 状态，但 `CameraPreview` 之前没有消费该状态。相机只在 `DisposableEffect(Unit)` 销毁时异步调用 `disconnect(sessionId)`；一级 Tab 切换和过渡期间，Preview、ImageAnalysis、ImageCapture、分析器、Executor 与 observer 仍然存在，导致切换页面卡顿。

## 实现方式

- `CameraPreview` 增加 `active: Boolean = true`，保留其他调用方的默认行为。
- `LiveInspectionScreen` 将现有 `isScreenVisible` 透传到 `CameraPreviewSection`/`CameraPreview`；不可见时清除现场页本地 session/contentRect，并忽略迟到的 session/frame 回调。
- `active=false` 时立即取出本地 `sessionId` 并在 `NonCancellable + Dispatchers.Default` 上调用已有 `CameraController.disconnect(sessionId)`，不调用 `release()`，不等待淡出动画或页面销毁。
- `CameraPreview` 的连接请求使用递增代次校验。页面离开、重试或销毁时使旧代次失效；若连接晚于这些状态完成，在不可取消的后台段内按返回的 sessionId 清理，不触发“相机连接失败”回调，即使外层 `LaunchedEffect` 已取消也不会遗留 session。
- `RealCameraBinder` 将 CameraX 要求主线程的 bind/unbind、observer、analyzer 和 Preview surface 操作统一桥接到主线程；`CameraPreview` 在后台执行 Controller 的连接/断开流程，阻塞等待不发生在 Compose 主线程。
- `CameraController` 的 CameraState observer 绑定 sessionId；CameraX 迟到的旧 observer 回调不能覆盖新 session 的状态。
- `DisposableEffect(Unit)` 继续保留为销毁兜底；主动断开先清空本地 session 标识，因此正常路径不会重复提交同一 session，Controller 的 sessionId 校验继续防止旧 disconnect 解绑新 session。
- 重入仍通过同一个单例 `CameraController` 建立新 `CameraSession`，Controller 既有 Mutex、先 `unbindAll` 再 bind、observer/analyzer/executor 清理和 sessionId 防竞态机制保持不变。
- `AppNavigation.kt` 已具备可见状态来源，本轮无需修改；一级页面淡入淡出动画未调整。

## 实际修改文件

| 文件 | 修改 |
|---|---|
| `app/src/main/java/com/wearable/inspection/mobile/camera/CameraController.kt` | CameraX 主线程 API 桥接；CameraState observer 增加 sessionId 门禁 |
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt` | `active` 参数、立即后台断开、连接代次门禁、不可见状态回调保护、销毁/重试清理 |
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt` | 透传 `isScreenVisible`，不可见时清除本地 session/contentRect，忽略不可见回调 |
| `app/src/test/java/com/wearable/inspection/mobile/camera/CameraControllerTest.kt` | 验证 analyzer、observer、executor、UseCase 清理及断开后重连 |
| `app/src/test/java/com/wearable/inspection/mobile/ui/screens/CameraPreviewTest.kt` | 验证 active 连接/断开、代次竞态、兜底和现场页透传契约 |
| `tasks/todo.md` | 增加本轮任务状态与验证记录 |
| `docs/reports/b3/CAMERA_ACTIVE_VISIBILITY_RELEASE_REPORT.md` | 本轮实现、回归矩阵和限制 |

工作区已有的 `AppNavigation.kt`、DPM/OCR、模板、ROI、确认页、导出和其他未提交改动均未被本轮覆盖或回滚。

## 测试命令与真实结果

### 编译与定向 JVM

```text
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --tests com.wearable.inspection.mobile.camera.CameraControllerTest --tests com.wearable.inspection.mobile.ui.screens.CameraPreviewTest
BUILD SUCCESSFUL in 26s
CameraControllerTest: 42/42 passed
CameraPreviewTest: 18/18 passed
```

中间一次定向测试编译曾因新增测试函数名含 Kotlin 非法点号失败；改为合法名称后按同一命令重跑并通过，最终测试结果以上述结果为准。

覆盖点包括：

- active=true 且权限就绪时连接；active=false 时不连接并立即走 `disconnect(sessionId)`。
- 断开后可再次连接并取得不同 sessionId。
- 旧 session 的延迟 disconnect 不影响新 session。
- 旧 session 的延迟 CameraState observer 回调不覆盖新 session 状态。
- disconnect 清理 analyzer、observer、executor 和 UseCase，重新连接不重复绑定。
- 不可见期间不触发相机连接失败状态；`DisposableEffect` 兜底仍存在。

### 相关现场采集状态回归

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests com.wearable.inspection.mobile.camera.CameraControllerTest --tests com.wearable.inspection.mobile.ui.screens.CameraPreviewTest --tests com.wearable.inspection.mobile.ui.screens.LiveInspectionCaptureStateTest
BUILD FAILED in 28s
92 tests completed, 2 failed
CameraControllerTest: 42/42 passed
CameraPreviewTest: 18/18 passed
LiveInspectionCaptureStateTest: 33 tests, 2 failed
```

这 2 项失败是工作区原有源码契约（拍照失败分支字符串、IO 代码缩进字符串），不由本轮相机改动引入，本轮未修改其非相机逻辑。

### Diff 检查

```text
git diff --check
通过（仅有 Git 的 LF/CRLF 提示，无 whitespace error）
```

## 前序能力回归矩阵

| 能力 | 本轮处理/证据 | 状态 |
|---|---|---|
| 一级导航 | 复用既有 `currentRoute` 可见状态；未改导航结构和动画；JVM 源码契约通过 | ✅ 源码级 |
| 相机权限 | 权限请求/拒绝/设置入口逻辑未改；不可见时不再主动发起权限请求 | ✅ 编译/源码级 |
| CameraState.OPEN | OPEN 仍由既有 CameraState observer 驱动；不可见时忽略状态 UI 回调 | ✅ JVM/源码级，未做真机 |
| 4:3、FIT_CENTER、contentRect | 计算和映射逻辑未改；不可见时清除旧 contentRect，重连后重新计算 | ✅ 源码级，未做真机 |
| 资源释放 | `disconnect(sessionId)` 清理 UseCase、analyzer、executor、observer；Controller JVM 通过 | ✅ 42/42 |
| session 防竞态 | 既有 sessionId 校验保留；新增连接代次清理不可见期间迟到连接，observer 也增加 sessionId 门禁 | ✅ 42/42 + 18/18 |
| 错误态 | 不可见/迟到 disconnect 不调用连接失败回调；真实相机错误 UI 未改 | ✅ 源码级 |
| ImageProxy/业务分析器 | 本轮未改变 Controller `finally` 关闭 ImageProxy 和 analyzer stop 逻辑 | ✅ 既有实现保留 |

## APK、真机和 Git

- APK：
  - 命令：`.\gradlew.bat :app:assembleDebug`
  - 结果：`BUILD SUCCESSFUL in 13s`
  - 路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 时间：2026-09-15 10:42:09 +08:00
  - 大小：276,579,040 bytes
  - SHA-256：`D6A0481AA9288F0550150D5132CE981AF5B9DDF2C4C0842CF9BF3E45F09356C4`
- 真机设备：`ERLDU20429005890`
  - ABI：`arm64-v8a,armeabi-v7a,armeabi`
  - 先停止 `com.wearable.inspection` 与 `com.wearable.inspection.mobile`，再 `install -r` 当前主 APK：`Success`。
  - 完整组件启动：`com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`，`Status: ok`，`LaunchState: COLD`。
  - 安装核对：`pm list packages com.wearable.inspection.mobile` 返回新包，`pm path` 指向已安装 APK。
  - PID 核对：新包 PID `5995`；`pidof com.wearable.inspection` 为空。
  - 前台核对：`mResumedActivity` 为 `com.wearable.inspection.mobile/.MainActivity`。
  - 清空 logcat 后重新启动，指定 `FATAL EXCEPTION`、`AndroidRuntime`、`CalledFromWrongThreadException`、`surfaceProvider`/`SurfaceProvider` 及主线程错误模式均为 `NO_MATCHES`。
  - 未执行 instrumented 测试，因此不存在 instrumented 测试后的卸载恢复步骤；本次显式停止、安装、启动和前台包名门禁均通过。
- Git：未提交。工作区已有修改、删除和未跟踪文件均保留；本报告仅记录本轮新增/修改范围。

## 未处理项

- 未修改导航架构或一级页面淡入淡出动画；如用户验收仍观察到动画相关卡顿，再单独立项评估。
- 未实现 ROI、Detector、PASS/FAIL、自动轮廓、自动对齐、Session ROI 或任何结果 ZIP/导出内容。
- 未改变 DPM/OCR 识别算法和其业务流程；其他相机页面继续使用同一 `CameraPreview` 默认活跃行为并保留原有销毁兜底。
