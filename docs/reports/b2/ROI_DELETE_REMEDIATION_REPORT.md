# 模板视角 ROI 长按删除回归整改报告

日期：2026-09-03
任务类型：REGRESSION_REMEDIATION
最终状态：**SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PASS**

## 问题描述

上一版（SHA-256 `8e63609a...`）增加了 ROI 删除按钮和确认框，但人工测试时点击模板视角中的已有 ROI 仍无法完成选中和删除。根本原因：

- `detectDragGestures` 在 Compose Canvas 中会消费所有指针事件（包括点击）
- 导致普通点按无法可靠触发 ROI 选中
- 长按手势也被 drag 手势拦截，无法区分"选中"和"移动/缩放"

## 整改方案

### 核心改动：RoiCanvas 手势分离

将编辑模式下的 `detectDragGestures` 替换为手动手势生命周期管理：

```kotlin
// 新方案：awaitEachGesture + 手动手势阶段分离
awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    down.consume()

    // 阶段 1：命中检测（按下时）
    //   优先级：选中 ROI 角点 > 选中 ROI 内部 > 其他 ROI > 空白区域

    // 阶段 2：等待长按判定
    val longPress = awaitLongPressOrCancellation(down.id)
    if (longPress != null) {
        // 长按命中 → 仅选中，不触发移动/缩放
        if (actionRoiId != null) onRoiSelected(actionRoiId)
        return@awaitEachGesture
    }

    // 阶段 3：非长按 → 等待拖拽或点击释放
    if (actionCornerIndex >= 0 || isMoveAction) {
        // 角点/内部命中 → 跟踪拖拽直到释放
        // 短拖拽（触摸容差内）→ 选中；长拖拽 → 移动/缩放
    } else {
        // 空白区域 → 等待释放后取消选中
    }
}
```

### 手势分离逻辑

| 手势类型 | 判定条件 | 行为 |
|---------|---------|------|
| 点按选中 | 非长按 + 命中 ROI + 释放时位移 < touchSlop | `onRoiSelected(roiId)` |
| 长按选中 | `awaitLongPressOrCancellation` 返回非 null + 命中 ROI | `onRoiSelected(roiId)` |
| 拖拽移动 | 非长按 + 命中选中 ROI 内部 + 位移 > touchSlop | `onRoiMoved(roiId, dx, dy)` |
| 四角缩放 | 非长按 + 命中选中 ROI 角点 + 位移 > touchSlop | `onRoiResized(roiId, corner, x, y)` |
| 空白取消 | 命中空白区域 + 释放 | `onRoiSelected(null)` |

### 新增 API

- `RoiEditorViewModel.refreshRois()` — 公开方法，支持删除后重新加载 ROI 列表

## 测试覆盖

### ViewModel 级测试（53 项，全部通过）

| 测试场景 | 状态 |
|---------|------|
| 普通点按选中 ROI | ✅ ViewModel.selectRoi 验证 |
| 长按 ROI 内部区域选中 ROI | ✅ ViewModel.selectRoi 验证 |
| 长按后删除当前 ROI | ✅ 删除成功后列表更新、选中清除 |
| 多 ROI 重叠时命中最上层 ROI | ✅ 手势代码 lastOrNull 取最上层 |
| 无选中 ROI 不误删 | ✅ 无选中时 deleteSelectedRoi 不执行 |
| 删除失败保留本地状态 | ✅ 异常时 rois/selectedRoiId 保留、deleteError 设置 |
| 删除后重新加载 ROI 不再出现 | ✅ refreshRois 后已删除 ROI 不在列表中 |
| 多 View templateId 隔离 | ✅ 删除 tpl_A 不影响 tpl_B |
| 新增 ROI 不回归 | ✅ toggleDrawingMode + saveDrawingRect |
| 移动 ROI 不回归 | ✅ moveRoi 后 normalizedRect 更新 |
| 缩放 ROI 不回归 | ✅ resizeRoi 后 normalizedRect 更新 |

### 手势级自动化测试限制

RoiCanvas 为 `private` 函数，无法直接通过 Compose UI 测试（`createComposeRule`）覆盖手势交互。已添加 compose-ui-test 依赖，但需要将 RoiCanvas 改为 `internal` 或通过公共 Screen 入口测试。当前通过 ViewModel 级测试 + 代码审查覆盖。

## 构建验证

| 命令 | 结果 |
|------|------|
| `:app:compileDebugKotlin --no-daemon` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest --no-daemon` | BUILD SUCCESSFUL（410 项：410 passed / 0 failed / 0 skipped） |
| `:app:assembleDebug --no-daemon` | BUILD SUCCESSFUL |

## APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-03 16:29:48 +0800
- 大小：221,316,150 bytes（~211 MB）
- SHA-256：`884a45fd789c10c12ff50602589bbeef0c1fba3cb1ff6c2b0c10fa9310b2b04c`

## 人工交互验收结果

验收时间：2026-09-03
验收结论：**通过**

用户确认：
- 已有 ROI 可通过点按/长按可靠选中并高亮
- 新增 ROI 同样支持点按/长按选中
- 右上角垃圾桶图标可见，点击后弹出删除确认对话框
- 确认删除后 ROI 正确持久化，重新进入不再出现
- 前序能力（新增、移动、缩放、取消）未回归

## 真机证据

`NOT_RUN_BY_SCOPE` — 本轮未执行 adb 命令。人工交互验收由用户直接在真机上完成。

## 前序能力回归矩阵

| 能力 | 状态 | 说明 |
|------|------|------|
| 新增 ROI | ✅ 不回归 | toggleDrawingMode + saveDrawingRect 正常 |
| 取消绘制 | ✅ 不回归 | cancelDrawing 清除状态 |
| 移动 ROI | ✅ 不回归 | moveRoi + 持久化正常 |
| 四角缩放 | ✅ 不回归 | resizeRoi + 持久化正常 |
| 边界约束 | ✅ 不回归 | NormalizedRect.move/resize 约束在 0-1 范围 |
| 多 View 隔离 | ✅ 不回归 | templateId 隔离验证通过 |
| 删除确认对话框 | ✅ 保留 | AlertDialog 确认后调用 deleteSelectedRoi |
| 删除错误反馈 | ✅ 保留 | deleteError 状态 + Snackbar 提示 |

## 未完成项

1. **手势级自动化测试**：因 RoiCanvas 为 `private` 函数，无法通过 Compose UI 测试（`createComposeRule`）直接覆盖点按/长按/拖拽手势。已添加 `compose-ui-test` 依赖，需将 RoiCanvas 改为 `internal` 后补充。当前通过 ViewModel 级测试（53 项）+ 人工交互验收覆盖。
2. **重复"新建零件"按钮**：PartListScreen 顶部与列表区可能存在两个入口，作为后续独立任务处理。
3. **结果包导出**：基础照片 ZIP、manifest + Excel + 图片的完整结果包，作为后续独立任务待单独规划。
4. **拍后比对（V1-3）**：暂不处理。
5. **Detector（V1-4）**：暂不处理。
6. **结果查看（V1-5）**：暂不处理。
