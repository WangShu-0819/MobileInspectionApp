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

B2 Task 1 DPM 迁移 SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING。B2 Task 2 模板导入 + 透明叠加 MVP 软件层面已完成（提交 `bdf1bd89`）。B3 钢印 OCR Phase 1 核心算法迁移完成（提交 `0df8e9c5`）+ Phase 2 CameraX/UI 集成 SOFTWARE_COMPLETE。下一软件阶段：LiveInspectionScreen cleanup → V1-3 post-capture comparison。以 `AGENTS.md` 和 `tasks/todo.md` 为准。
