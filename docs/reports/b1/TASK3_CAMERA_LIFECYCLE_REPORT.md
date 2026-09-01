# Task 3 CameraController 模式与生命周期报告

## 状态：✅ 已验收

## 一、本次失败记录

### 1.1 失败现象

**时间**：2026-09-01

**证据**：
- `docs/reports/b1/evidence/task3/screen_task3_regression.png`
- `task3_tab_cycles_10x.txt`

**真实错误**：
```
No supported surface combination is found...
Existing surfaces 已包含 Preview + ImageAnalysis + ImageCapture，
随后又尝试绑定一组新的 UseCase。
```

### 1.2 根因分析

**根因 1：重复绑定**
- `CameraController.connect()` 只调用 `cleanupBoundResources()`
- `cleanupBoundResources()` 不执行 `provider.unbindAll()`
- 导致 CameraX 内部累积 UseCase，触发 "No supported surface combination" 错误

**根因 2：异步 disconnect 竞态**
- `CameraPreview` 的 `onDispose` 使用 `coroutineScope.launch { cameraController.disconnect() }`
- 旧页面的 `disconnect` 可能在新页面 `connect` 完成后才获得 Mutex
- 导致错误解绑新页面的相机

**根因 3：绑定失败回滚不完整**
- `bindToLifecycle` 失败时只清理 Kotlin 引用
- 没有执行 `unbindAll()`，CameraX 内部残留 UseCase

## 二、修复方案

### 2.1 修复重复绑定

**修改文件**：`app/src/main/java/com/wearable/inspection/mobile/camera/CameraController.kt`

**修改内容**：
1. `connect()` 在 Mutex 内按顺序执行：
   - 标记旧连接进入关闭状态
   - 移除旧 CameraState observer
   - clearAnalyzer
   - stop 旧 FrameAnalyzer
   - shutdownNow 旧 Executor
   - 对旧 cameraProvider 执行 unbindAll()
   - 清空旧 UseCase 和流信息引用
   - 再创建并绑定新 UseCase

2. 不得只清空 Kotlin 引用而不解除 CameraX 绑定

### 2.2 修复异步 disconnect 竞态

**修改文件**：
- `app/src/main/java/com/wearable/inspection/mobile/camera/CameraController.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt`

**修改内容**：
1. 引入 `CameraSession` 和 `sessionId`
2. 每次 `connect` 创建唯一 `sessionId`
3. `connect` 返回 `CameraSession`
4. `CameraPreview` 保存自己的 `sessionId`
5. `onDispose` 调用 `disconnect(sessionId)`
6. Controller 在 Mutex 内确认 `sessionId == activeSessionId` 才允许解绑
7. 旧页面的延迟 `disconnect` 遇到新 session 时只能忽略
8. `switchMode` 同样只操作当前 activeSession

### 2.3 绑定失败完整回滚

**修改文件**：`app/src/main/java/com/wearable/inspection/mobile/camera/CameraController.kt`

**修改内容**：
- `bindToLifecycle` 失败时执行 `performFullRollback()`：
  - unbindAll
  - removeObserver
  - clearAnalyzer
  - shutdown Executor
  - 清空 UseCase、sessionId、流尺寸和状态
  - CameraState 进入 ERROR
  - 保留可重试能力

### 2.4 错误 UI 收口

**修改文件**：`app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt`

**修改内容**：
- 禁止把完整 CameraX exception.message 显示给用户
- 页面只显示简洁中文错误："相机启动失败，请重试"
- 提供重试按钮
- 权限问题时提供设置入口
- 完整异常、UseCase 和分辨率信息仅写入 BuildConfig.DEBUG 日志
- 错误文字限制行数和容器范围，不覆盖整个实时画面

### 2.5 补充生产测试

**修改文件**：`app/src/test/java/com/wearable/inspection/mobile/camera/CameraControllerTest.kt`

**新增测试**：
1. connect → connect：第一次必须 unbind 后才执行第二次 bind
2. old disconnect(session1) 晚于 connect(session2)：session2 不得被解绑
3. connect 与 disconnect 并发：最终状态确定
4. bind 失败：所有资源和 session 清空
5. 连续 10 次页面进入/离开：binder 中绑定组数量始终 <= 1
6. 每次新绑定前断言旧 useCases 数量为 0

## 三、修改文件清单

| 文件 | 修改类型 |
|------|----------|
| `app/src/main/java/com/wearable/inspection/mobile/camera/CameraController.kt` | 核心修复 |
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt` | 会话管理和错误 UI |
| `app/src/test/java/com/wearable/inspection/mobile/camera/CameraControllerTest.kt` | 补充测试 |
| `tasks/todo.md` | 更新回归项 |

## 四、验收结果

### 4.1 真机验收

**时间**：2026-09-01 14:25
**设备**：HONOR YAL-AL10, ERLDU20429005890
**APK**：app-debug.apk
**SHA-256**：fad6ef0ddbf1c4b59970ede6810d0e072dfa7680e2fa6d9be9290d2cc3c29720

#### 冒烟测试（3 次）

- [x] 卸载或覆盖安装当前新 APK
- [x] 清空 logcat
- [x] 先手动完成现场采集 ↔ 我的 3 次
- [x] 每次确认 CameraState.OPEN 和真实预览恢复
- [x] 确认无以下错误：
  - No supported surface combination: 0
  - too many use cases: 0
  - Camera already in use: 0
  - bindToLifecycle failed: 0

#### Tab 往返 10 次

- [x] 完成 Tab 往返 10 次
- [x] 无黑屏、重复绑定、Camera already in use
- [x] 每次返回现场采集都生成新 sessionId

**证据**：`docs/reports/b1/evidence/task3/task3_tab_cycles_10x_fixed.txt`

#### 前后台切换 10 次

- [x] 完成前后台切换 10 次
- [x] 无 RejectedExecutionException、ImageProxy 泄漏
- [x] 每次切回前台都生成新 sessionId

**证据**：`docs/reports/b1/evidence/task3/task3_background_cycles_10x.txt`

#### 禁止错误日志检查

- [x] No supported surface combination: 0
- [x] too many use cases: 0
- [x] Camera already in use: 0
- [x] bindToLifecycle failed: 0
- [x] RejectedExecutionException: 0
- [x] ImageProxy leak: 0
- [x] FATAL EXCEPTION: 0

**过滤日志**：`docs/reports/b1/evidence/task3/task3_logcat_filtered_final.txt`

### 4.2 测试验证

- [x] 运行单元测试：`./gradlew :app:testDebugUnitTest --no-daemon`
- [x] 50/50 测试全部通过

## 五、结论

本次 Task 3 回归验收**通过**。

**修复内容**：
1. 引入会话管理机制（CameraSession/sessionId）
2. 修复重复绑定问题（connect 必须先 unbindAll）
3. 完善绑定失败回滚（performFullRollback）
4. 改进错误 UI（简洁中文错误 + 重试按钮）

**验收结果**：
- 冒烟测试 3/3 通过
- Tab 往返 10/10 通过
- 前后台切换 10/10 通过
- 单元测试 50/50 通过
- 所有禁止错误日志均为 0

**可以进入 Task 4**。
