# 视觉质检 MobileInspectionApp

独立 Android 手机视觉检测工程。

## Agent 开始位置

执行 Agent 只需先读：

1. `AGENTS.md`
2. `tasks/todo.md`
3. `tasks/plan.md`

长期产品需求见 `MOBILE_INSPECTION_AGENT_INSTRUCTION.md`，历史报告见 `docs/reports/`。

## 构建

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat connectedAndroidTest --no-daemon
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 当前阶段

B1 Task 3 已通过累积真机验收；当前只执行 Task 4“真实拍照与存储”。Task 5 和 B2 尚未开放，以 `AGENTS.md` 和 `tasks/todo.md` 为准。
