# 当前任务：B1 共享 CameraX 收口

状态：进行中。B2 禁止开始。

## Task 1：整理活跃源码边界

状态：已验收（提交 `754ec5b`）。

- [x] 用导航和全仓引用确认当前活跃 Screen
- [x] 列出旧/新重复 Screen 的引用与替代关系
- [x] 将 `LiveInspectionScreen.kt.backup` 移出 `src/main`
- [x] 不删除仍被引用的 Screen

复核记录（2026-08-31）：上次执行报告未通过验收。报告称“所有 Screen 均在 AppNavigation 中被引用”，但全仓检索显示 `WorkbenchScreen`、`RecordListScreen`、`TemplateListScreen`、`SettingsScreen` 只有定义、没有调用；同时“共 10 个”实际列出 13 个 UI 文件。执行 Agent 必须先给出“文件、职责、调用方、保留/归档结论”四列表，再处理未引用旧页面。不得仅以函数名不同判断不存在重复职责。

验证：每个小批改动后执行 `:app:compileDebugKotlin`。

## Task 2：CameraPreview 状态与画幅

状态：✅ 已验收（提交 `28d692d`，HONOR YAL-AL10, ERLDU20429005890）。

- [x] 接通 onCameraReady、权限拒绝、永久拒绝和错误回调
- [x] 永久拒绝提供系统设置入口，错误状态提供真实重试
- [x] 加载状态在 CameraState.OPEN 后消失（isCameraReady 可观察状态）
- [x] `PreviewView.ScaleType` 使用 `FIT_CENTER`
- [x] Preview/ImageAnalysis/ImageCapture 统一 RATIO_4_3_FALLBACK_AUTO_STRATEGY
- [x] ContentRect 计算完成（使用 CameraController.streamResolution 和 streamRotation）
- [x] 竖屏内容使用实际流比例 + FIT_CENTER，无固定 60/40 拉伸
- [x] 输出 PreviewView、流尺寸、旋转和 content rect 诊断日志
- [x] 真机：PreviewView 1080x1039, 流 8000x6000 (4:3), 旋转 90°, contentRect 779x1039
- [x] 真机：四角标记可见，不进入 letterbox，中央圆不变形
- [x] 真机：权限临时拒绝后可再次请求并恢复
- [x] 真机：LiveInspectionScreen 首屏直接显示实时相机预览
- [x] 真机：顶部操作为扫一扫/OCR 钢印

## Task 3：CameraController 模式与生命周期

状态：✅ 已验收（最终修复提交 `bb22f1e`，HONOR YAL-AL10, ERLDU20429005890；APK SHA-256 `fad6ef0ddbf1c4b59970ede6810d0e072dfa7680e2fa6d9be9290d2cc3c29720`）。

### Task 2 累积回归恢复

- [x] `PreviewView.ScaleType` 恢复 `FIT_CENTER`，禁止 `FILL_CENTER` 和 1:1 裁切
- [x] 实际 4:3 流旋转后以 3:4 完整显示，允许 letterbox，不拉伸、不裁切
- [x] 只有 `CameraStateType.OPEN` 后加载消失并触发 `onCameraReady`
- [x] 权限请求、临时拒绝、永久拒绝、系统设置返回和错误重试恢复
- [x] 实际 streamResolution/rotationDegrees、生产 ContentRectCalculator 和诊断日志恢复
- [x] Debug 四角/中央圆校准通过，轮廓与 ROI 只映射到 contentRect
- [x] 正式源码和主 Manifest 不包含 exported 测试 Activity
- [x] 当前源码新 APK 真机截图与 Task 2 回归矩阵通过
- [x] 重复进入现场采集不会叠加 CameraX UseCase
- [x] 延迟 disconnect 不会解绑新 session
- [x] 相机错误使用简洁 UI，不显示原始异常

- [x] CameraMode 枚举：IDLE/INSPECTION/DPM_SCAN/STAMP_OCR/TEMPLATE_CAPTURE + UseCase 需求配置
- [x] switchMode() 串行 Mutex 保护：停止旧分析器 → 关闭旧 Executor → unbindAll → 构建新 UseCase → 重绑
- [x] 同一时刻只有一组 UseCase、一个分析器、一个分析 Executor
- [x] disconnect() 页面离开可恢复，release() 永久释放后不可复用
- [x] 不持有 Activity/LifecycleOwner/PreviewView 强引用
- [x] FrameAnalyzer 接口：analyze() 所有路径关闭 ImageProxy，stop() 清理内部状态
- [x] TestCountingAnalyzer 测试分析器验证互斥和资源释放
- [x] 真机模式 round-trip 20 次：100 次切换全部成功，0 失败（HONOR YAL-AL10, ERLDU20429005890）
- [x] logcat 禁止模式检查：8 项全部 0 次匹配
- [x] 真机 Tab 往返 10 次：无黑屏、重复绑定、Camera already in use
- [x] 真机前后台切换 10 次：无 RejectedExecutionException、ImageProxy 泄漏
- [x] 单元测试 50/50 通过（CameraControllerTest，含并发/压力测试和会话管理测试）

完成条件：上方 Task 2 回归项、Tab 10 次、前后台 10 次和重新生成的 logcat 检查必须在同一个最终 APK 上全部通过。

## Task 4：真实拍照与存储

状态：当前进行中。Task 5 和 B2 禁止开始。

- [ ] Task 3 收口基线已提交，正式 Manifest 无测试入口，`tools/contour_extraction/` 未混入
- [ ] 主快门使用当前 CameraSession/ImageCapture，不创建或重绑第二套 CameraX
- [ ] 拍照前校验 session、OPEN、Capture、零件、模板和 ROI
- [ ] UI 使用 IDLE/CAPTURING/SAVED/ERROR，拍摄中禁用重复点击
- [ ] 临时 JPEG 唯一命名并写入 App 私有目录
- [ ] MobileImageStore 校验非空、可解码、宽高和方向后原子移动
- [ ] 失败、取消、过期 session、页面离开和低存储路径清理临时文件
- [ ] 成功只表示原图保存，不创建假检测记录、识别图或 ROI 结果
- [ ] 自动化覆盖并发点击、重名、空文件、损坏 JPEG、取消和会话切换
- [ ] 真机连续拍摄 20 张：无空文件、重名、方向错误和临时残留
- [ ] Task 2/3 受影响回归矩阵在同一最终 APK 上通过


验收：连续拍摄 20 张，无空文件、重名、方向错误或临时残留。

## Task 5：完整验证

- [ ] `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
- [ ] `.\gradlew.bat :app:assembleDebug --no-daemon`
- [ ] `.\gradlew.bat connectedAndroidTest --no-daemon`
- [ ] 当前源码新 APK 安装成功
- [ ] 冷启动 10 次无 FATAL EXCEPTION
- [ ] 权限允许、拒绝、永久拒绝流程通过
- [ ] logcat 无 Camera already in use、重复绑定、ImageProxy 泄漏
- [ ] 记录 APK 路径、时间、大小和 SHA-256
- [ ] 提供真实预览截图和 JPEG 样例

## B1 完成门禁

- [ ] 上述所有验收项全部完成
- [ ] `docs/reports/b1/B1_CAMERA_FOUNDATION_REPORT.md` 与真实结果一致
- [ ] 用户确认进入 B2

未满足全部门禁前，不得将 B1 标记完成，不得开始 DPM、OCR、模板、轮廓或 ROI 实现。
