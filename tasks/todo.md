# 当前任务：B1 共享 CameraX 收口

状态：进行中。B2 禁止开始。

## Task 1：整理活跃源码边界

- [x] 用导航和全仓引用确认当前活跃 Screen
- [x] 列出旧/新重复 Screen 的引用与替代关系
- [x] 将 `LiveInspectionScreen.kt.backup` 移出 `src/main`
- [x] 不删除仍被引用的 Screen

复核记录（2026-08-31）：上次执行报告未通过验收。报告称“所有 Screen 均在 AppNavigation 中被引用”，但全仓检索显示 `WorkbenchScreen`、`RecordListScreen`、`TemplateListScreen`、`SettingsScreen` 只有定义、没有调用；同时“共 10 个”实际列出 13 个 UI 文件。执行 Agent 必须先给出“文件、职责、调用方、保留/归档结论”四列表，再处理未引用旧页面。不得仅以函数名不同判断不存在重复职责。

验证：每个小批改动后执行 `:app:compileDebugKotlin`。

## Task 2：CameraPreview 状态与画幅

- [ ] 接通 onCameraReady、权限拒绝、永久拒绝和错误回调
- [ ] 永久拒绝提供系统设置入口，错误状态提供真实重试
- [ ] 加载状态在相机 ACTIVE 后消失
- [ ] `PreviewView.ScaleType` 使用 `FIT_CENTER`
- [ ] Preview/ImageAnalysis/ImageCapture 优先统一 4:3
- [ ] 竖屏内容保持 3:4 或使用实际流比例，不被固定 60/40 拉伸
- [ ] 输出 PreviewView、流尺寸、旋转和 content rect 诊断

验收：四边测试标记全部可见，圆形不变椭圆，允许留边但不裁切。

## Task 3：CameraController 模式与生命周期

- [ ] switchMode 真正停止旧分析器并重绑目标 UseCase
- [ ] 同一时刻只有一组 UseCase 和一个分析器
- [ ] 页面离开与永久 release 分开
- [ ] 不持有 Activity/LifecycleOwner/PreviewView 强引用
- [ ] 明确 ImageProxy 所有权并覆盖异常/取消路径

验收：Tab 往返 10 次、前后台 10 次，无黑屏、重复绑定或 Executor 错误。

## Task 4：真实拍照与存储

- [ ] 拍照按钮调用真实 ImageCapture
- [ ] 拍照中禁用重复点击
- [ ] 直接写临时 JPEG，再由 MobileImageStore 校验和原子移动
- [ ] 文件名唯一、方向正确、空文件失败、失败清理临时文件
- [ ] 本阶段不创建假检测成功记录

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
