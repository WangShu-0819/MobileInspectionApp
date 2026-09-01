# Task 3：CameraController 模式与生命周期 — 整改报告

> 状态：🔄 整改中
> 日期：2026-09-01
> 关联需求：tasks/todo.md → Task 3

---

## 一、整改内容

### 1. 统一并发边界

**问题**：原实现中 `disconnect()`/`release()` 是非 suspend 函数，绕过 Mutex；`setFrameAnalyzer()`/`clearFrameAnalyzer()` 也不经过 Mutex。

**修复**：
- 所有公共方法（`connect`/`switchMode`/`disconnect`/`release`/`setFrameAnalyzer`/`clearFrameAnalyzer`）统一经过同一 `Mutex` 串行执行
- `disconnect()` 和 `release()` 改为 `suspend fun`
- `isReleased`/`currentMode` 检查在锁内完成
- 绑定失败时清理半绑定资源

### 2. ImageProxy 所有权

**问题**：原实现中 `FrameAnalyzer.analyze()` 负责关闭 `ImageProxy`，违反所有权原则。

**修复**：
- `CameraController` 拥有 `ImageProxy`，在 `finally` 中关闭
- `FrameAnalyzer.analyze()` 不调用 `image.close()`
- 更新 `FrameAnalyzer.kt` 注释和 `TestCountingAnalyzer`

### 3. 引用和 Observer 管理

**问题**：原实现持有强引用 `LifecycleOwner`，Observer 未跟踪/移除导致累积。

**修复**：
- `LifecycleOwner` 使用 `WeakReference` 保存
- 保存 `CameraState` `Observer` 实例引用
- 每次重绑/断开/释放前显式 `removeObserver`
- `surfaceProvider` 也使用 `WeakReference` 保存

### 4. 可注入测试架构

**问题**：原测试无法验证并发行为和资源协调。

**修复**：
- 提取 `CameraBinder` 接口，抽象所有 CameraX 交互
- `RealCameraBinder` 实现生产逻辑
- `FakeCameraBinder` 实现测试逻辑（可控延迟、失败注入）
- 30 个单元测试覆盖：状态机、并发串行化、资源清理、Observer 管理、UseCase 配置、20 次 round-trip 压力测试

---

## 二、架构变更

### CameraBinder 接口

```kotlin
interface CameraBinder {
    fun hasCameraPermission(): Boolean
    fun getProvider(): Any?
    fun hasBackCamera(provider: Any): Boolean
    fun createPreview(surfaceProvider: Any): Any
    fun createAnalysis(): Any
    fun createCapture(): Any
    fun bindToLifecycle(...): BindResult
    fun unbindAll(provider: Any)
    fun getCameraInfo(camera: Any): Any?
    fun observeCameraState(...)
    fun removeCameraStateObserver(...)
    fun setAnalyzer(useCase: Any, executor: ExecutorService, callback: (Any) -> Unit)
    fun clearAnalyzer(useCase: Any)
    fun getResolutionInfo(useCase: Any): Pair<Size, Int>?
}
```

### 状态机流转

```
IDLE → connect() → INSPECTION/DPM_SCAN/STAMP_OCR/TEMPLATE_CAPTURE
任意模式 → switchMode() → 新模式（Mutex 内串行）
任意模式 → disconnect() → IDLE（可恢复）
任意模式 → release() → 永久释放
```

---

## 三、测试覆盖

| 测试类别 | 数量 | 说明 |
|---------|------|------|
| 基础状态 | 5 | 初始状态、connect、release |
| 模式切换 | 4 | 成功切换、相同模式、未连接、所有模式 round-trip |
| 并发串行化 | 4 | connect+switchMode、2x switchMode、switchMode+disconnect、switchMode+release |
| 资源清理 | 4 | disconnect、release、connect 失败、switchMode 失败 |
| Observer 管理 | 4 | connect 设置、switchMode 替换、disconnect 移除、多次不累积 |
| 分析器管理 | 3 | 设置、清除、异常时 ImageProxy 仍关闭 |
| Executor 管理 | 1 | switchMode 关闭旧 Executor |
| UseCase 配置 | 4 | INSPECTION、DPM_SCAN、TEMPLATE_CAPTURE、STAMP_OCR |
| 压力测试 | 1 | 20 次模式 round-trip |
| **总计** | **30** | |

---

## 四、待完成

- [ ] 真机验收：5 种模式 round-trip 20 次
- [ ] 真机验收：Tab 切换 10 次
- [ ] 真机验收：前后台切换 10 次
- [ ] logcat 验证：无 Camera already in use / RejectedExecutionException / ImageProxy leak / duplicate observer / closed Executor
- [ ] assembleDebug + 安装 + SHA-256
- [ ] Git commit
