# 采集页控件压缩报告

日期：2026-09-03
状态：**SOFTWARE_COMPLETE**（待用户验收）

## 问题描述

现场采集页面的 TemplateOverlayControls 和 TemplateReferenceSection 占用过多垂直空间，导致模板参考图显示区域被压缩。

需求：
- 压缩 TemplateOverlayControls 和 TemplateReferenceSection 的视觉高度/内边距/背景
- 不修改 CameraPreview、模板图显示、FIT_CENTER、contentRect、CameraX
- 释放更多空间给模板参考图

## 实施方案

### 修改文件

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt` | 压缩 6 个 Composable 组件的垂直高度/内边距/字号 |

### 压缩详情

| 组件 | 属性 | 修改前 | 修改后 |
|---|---|---|---|
| TemplateOverlayControls | minHeight | 48dp | 36dp |
| TemplateOverlayControls | IconButton size | 48dp | 32dp |
| TemplateOverlayControls | icon size | 20dp | 16dp |
| TemplateOverlayControls | padding | 12dp | 8dp |
| TemplateOverlayControls | spacedBy | 8dp | 4dp |
| TemplateReferenceSection | padding vertical | 8dp | 4dp |
| TemplateReferenceSection | spacedBy | 6dp | 4dp |
| TemplateReferenceSection | view row minHeight | 40dp | 28dp |
| CaptureActionBar | button height | 48dp | 40dp |
| CaptureActionBar | icon size | 24dp | 18dp |
| CaptureActionBar | fontSize | 16sp | 14sp |
| CaptureActionBar | padding | 16dp | 12dp/2dp |
| TemplateSelector | minHeight | 48dp | 28dp |
| TemplateSelector | fontSize | bodySmall | 11sp |
| AllViewsCapturedCard | icon size | 24dp | 18dp |
| AllViewsCapturedCard | padding | 12dp/6dp | 10dp/4dp |
| 拍照状态提示 | minHeight | 36dp | 28dp |
| 拍照状态提示 | icon size | 20dp | 16dp |

### 未修改组件

| 组件/属性 | 状态 |
|---|---|
| CameraPreview | ✅ 未修改 |
| 模板图显示 | ✅ 未修改 |
| FIT_CENTER | ✅ 未修改 |
| contentRect | ✅ 未修改 |
| CameraX | ✅ 未修改 |

## 测试结果

### Gradle 命令

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（12s） |
| `:app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL（34s） |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL（7s） |

### 真机范围

`NOT_RUN_BY_SCOPE`（本轮禁止 adb）

### 回归测试建议

1. 模板叠加：透明度 Slider 正常调节，显示/隐藏切换正常
2. View 切换：视角选择器正常工作，视角编号正确显示
3. 零件选择：零件下拉选择正常，切换后模板正确加载
4. 拍照流程：拍照按钮可点击，状态提示正常显示
5. 全部完成：重新开始按钮可点击

## 验收标准对照

| 标准 | 状态 |
|---|---|
| TemplateOverlayControls 垂直高度压缩 | ✅ minHeight 48→36dp |
| TemplateReferenceSection 垂直高度压缩 | ✅ padding/spacedBy 压缩 |
| 不修改 CameraPreview | ✅ |
| 不修改模板图显示 | ✅ |
| 不修改 FIT_CENTER | ✅ |
| 不修改 contentRect | ✅ |
| 不修改 CameraX | ✅ |
| 三条 Gradle 命令全部通过 | ✅ |

## Git 状态

`5772fbd5`
