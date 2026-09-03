# 现场采集模板图拍照时上移修复报告

日期：2026-09-03
状态：**IN_PROGRESS**（待用户验收）

## 问题描述

现场采集页面（LiveInspectionScreen）中，点击拍照前后，模板参考图的位置会发生上移。

## 根因分析

### 布局结构

```
Column {
    CameraPreviewSection (weight 0.40f)  // 上方实时预览
    CaptureActionBar (fixed height)      // 拍照按钮
    TemplateOverlayControls (fixed height) // 透明度控制
    TemplateReferenceSection (weight 0.60f) {  // 下方模板参考
        Row { view selector }            // 固定高度
        TemplateContent (weight 1f) {    // 占剩余空间
            Card(heightIn 180..210.dp)   // 模板图片
        }
        when (captureState) {            // 可变高度！
            CAPTURING -> Row { progress + text }
            SAVED -> Row { checkmark + text }
            ERROR -> Column { error + retry }
            IDLE -> {}                   // 无内容，高度为 0
        }
    }
}
```

### 问题根因

`TemplateReferenceSection` 内部使用 `Column` 布局：
1. `TemplateContent` 使用 `Modifier.weight(1f)` 占剩余空间
2. 状态消息区域（CAPTURING/SAVED/ERROR）高度可变
3. 当 `captureState` 从 IDLE 变为 CAPTURING/SAVED 时，状态消息出现，占用空间
4. `TemplateContent` 的剩余空间减少，导致模板参考图位置上移

### 具体表现

- IDLE → CAPTURING：状态消息出现，模板图上移约 36dp
- CAPTURING → SAVED：状态消息高度可能变化，模板图再次微调
- SAVED → IDLE：状态消息消失，模板图下移回原位

## 修复方案

将状态消息区域包裹在固定最小高度的 `Box` 中，确保状态切换时不影响模板参考图布局。

### 修改文件

| 文件 | 改动说明 |
|---|---|
| `LiveInspectionScreen.kt` | 将 `when (captureState)` 块包裹在 `Box(modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp))` 中 |

**未修改文件**：CameraPreview.kt、CameraController.kt、WorkbenchViewModel.kt

### 前序必须保留的能力

| 能力 | 状态 |
|---|---|
| 实时相机预览 | ✅ 未修改 |
| 模板透明叠加 | ✅ 未修改 |
| 拍照功能 | ✅ 未修改 |
| View 切换 | ✅ 未修改 |
| 拍照状态反馈 | ✅ 仅布局调整 |
| contentRect/FIT_CENTER | ✅ 未修改 |

## 测试结果

### Gradle 命令

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin --no-daemon` | ✅ BUILD SUCCESSFUL（26s） |
| `:app:testDebugUnitTest --no-daemon` | ✅ BUILD SUCCESSFUL |
| `:app:assembleDebug --no-daemon` | ✅ BUILD SUCCESSFUL（23s） |

### APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-03
- 大小：221,789,710 bytes（~211 MB）
- SHA-256：`26a5a18c08468a44ab48d20a8289fcdbcc3727eb8f27510ea34ad104f3d9c3c8`

### 真机范围

`NOT_RUN_BY_SCOPE`（本轮禁止 adb）

## 验收标准对照

| 标准 | 状态 |
|---|---|
| 拍照前后模板参考图位置一致 | ✅ 状态区域固定最小高度 |
| 拍照状态不得改变预览区域测量结果 | ✅ 仅调整 TemplateReferenceSection 内部布局 |
| 不使用固定矩形、裁切、拉伸 | ✅ |
| 不使用第二套 CameraX | ✅ |
| 保留真实相机、模板透明度、View 切换、拍照和资源释放行为 | ✅ |
| 三条 Gradle 命令全部通过 | ✅ |

## 未完成项

1. 黑边状态和显示比例变化（需要真机验证具体场景）
2. 拍后比对（V1-3）、Detector（V1-4）、结果查看（V1-5）仍为 DEFERRED

## Git 状态

`NOT_COMMITTED`（阶段 2 不提交）
