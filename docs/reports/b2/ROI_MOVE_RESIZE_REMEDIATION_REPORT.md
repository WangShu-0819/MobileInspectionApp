# ROI 移动/缩放整改报告

**日期**: 2026-09-03
**状态**: SOFTWARE_COMPLETE / AWAITING_USER_REVIEW
**任务**: ROI 移动/缩放整改 — 模板配置逐 View ROI 后续收口

---

## 1. 背景

2026-09-03 只读审计发现 `RoiEditorScreen.kt` 中已有 ROI 的移动/缩放回调仍为空 TODO，`RoiEditorViewModel` 没有 `updateRoi` 调用。本轮整改目标：补齐已有 ROI 的点击选中、拖拽移动、四角缩放、边界约束和 `normalizedRect` 持久化。

## 2. 实际修改文件

| 文件 | 改动摘要 |
|---|---|
| `RoiEditorViewModel.kt` | 新增 `moveRoi()` 和 `resizeRoi()` 方法（调用 `InspectionRepository.updateRoi`）；`NormalizedRect` 新增 `move()`、`resize()` 方法和 `MIN_SIZE` 常量；移除未使用的 `InteractionMode` 枚举 |
| `RoiEditorScreen.kt` | 替换两个 TODO 空回调为真实 ViewModel 调用；重写 `RoiCanvas` 实现点击选中、拖拽移动、四角缩放、角点控制柄绘制；新增 `Rect.contains(Offset)` 扩展 |
| `RoiEditorViewModelTest.kt` | 从 7 项扩展到 40 项测试 |

## 3. 实现细节

### 3.1 NormalizedRect 扩展

- `move(deltaNormX, deltaNormY)`: 偏移矩形并 `coerceIn` 约束到 `[0, 1-w]` / `[0, 1-h]`，保持宽高不变。
- `resize(cornerIndex, newCornerNormX, newCornerNormY)`: 拖拽指定角点（0=左上/1=右上/2=左下/3=右下），对角点不变，`minOf/maxOf` 保证 `left<right, top<bottom`，`MIN_SIZE=0.02f` 防止退化为点，全部 `coerceIn(0, 1)`。
- `MIN_SIZE = 0.02f`（约 2% 的图片宽/高）。

### 3.2 RoiEditorViewModel 新方法

- `moveRoi(roiId, deltaNormX, deltaNormY)`: 读取当前 `normalizedRect`，调用 `NormalizedRect.move()`，更新 DB 和本地 `_rois`。
- `resizeRoi(roiId, cornerIndex, newCornerNormX, newCornerNormY)`: 读取当前 `normalizedRect`，调用 `NormalizedRect.resize()`，更新 DB 和本地 `_rois`。
- 移除未使用的 `InteractionMode` 枚举（死代码清理）。

### 3.3 RoiCanvas 手势处理

**编辑模式**（`!isDrawingMode`）：
- `onDragStart`: 命中检测 — 先检查已选中 ROI 的四角控制柄（半径 24px），再检查 ROI 内部，最后检查其他 ROI。
- `onDrag`: 角点命中 → `onRoiResized(roiId, cornerIndex, normX, normY)`；内部命中 → `onRoiMoved(roiId, deltaNormX, deltaNormY)`。
- `onDragEnd`: 短拖拽视为点击选中。

**绘制模式**（`isDrawingMode`）：保留原有 `detectDragGestures` 逻辑不变。

**角点控制柄**：选中 ROI 时在四角绘制黄色实心圆 + 白色边框圆（半径 14.4px）。

### 3.4 删除的 TODO

- `RoiEditorScreen.kt:165` — `// TODO: 更新已有 ROI 位置` → 已删除
- `RoiEditorScreen.kt:168` — `// TODO: 更新已有 ROI 大小` → 已删除

## 4. 测试结果

### 4.1 Gradle 命令

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin --no-daemon` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest --no-daemon` | BUILD SUCCESSFUL |
| `:app:assembleDebug --no-daemon` | BUILD SUCCESSFUL |

### 4.2 测试统计

- 总计：379 项（374 passed / 0 failed / 5 skipped）
- RoiEditorViewModelTest：40 项（原 7 项 + 新增 33 项）

### 4.3 APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 时间：2026-09-03 13:43
- 大小：224,833,659 bytes（~214 MB）
- SHA-256：`d679e7a3e41f236d1958b125410ec827a54eb28c7d7e58b83fd216a8345bb56c`

### 4.4 RoiEditorViewModelTest 新增测试

| 测试 | 覆盖能力 |
|---|---|
| `move 正常偏移` | 基本移动 |
| `move 保持宽高不变` | 宽高守恒 |
| `move 负方向偏移` | 左/上移动 |
| `move 约束在左/上/右/下边界` | 四边边界约束 |
| `move 零偏移不变` | 恒等操作 |
| `move 后 normalizedRect 在 0_1 范围` | 值域验证 |
| `resize 左上/右上/左下/右下角` | 四角独立缩放 |
| `resize 对角点不变` | 对角固定 |
| `resize 自动翻转确保 left<right` | 角点越过对角 |
| `resize 最小尺寸约束` | MIN_SIZE 强制 |
| `resize 约束在 0_1 范围` | 边界约束 |
| `resize 0_1 全图范围边界` | 极端边界 |
| `corner 0/1/2/3 是正确角点` | 角点索引语义 |
| `resize 拖角越过对角后自动修正` | 翻转修正 |
| `多个 NormalizedRect 互相独立` | 多 ROI 隔离 |
| `move 后 resize 保持一致` | 组合操作 |
| `move 到精确边界 0_0 和 1_1` | 精度边界 |

## 5. 前序能力回归矩阵

| 能力 | 状态 | 证据 |
|---|---|---|
| 新 ROI 绘制/保存 | ✅ 不回归 | `saveDrawingRect` 未修改；Canvas 绘制模式逻辑保留 |
| 取消绘制 | ✅ 不回归 | `cancelDrawing` 未修改 |
| 删除 ROI | ✅ 不回归 | `deleteSelectedRoi`/`deleteRoi` 未修改；顶部删除按钮保留 |
| 按 templateId 隔离 | ✅ 不回归 | `RoiDao WHERE templateId` 未修改；ViewModel 按 templateId 加载 |
| contentRect 映射 | ✅ 不回归 | `calculateContentRect`/`pixelToNormalized`/`normalizedToPixel` 保留 |
| 绘制模式手势 | ✅ 不回归 | `detectDragGestures` 绘制分支保留原逻辑 |
| 底部工具栏 | ✅ 不回归 | 添加 ROI / 保存 / 取消按钮未修改 |
| 唯一 CameraX | ✅ 不回归 | 未涉及 CameraController |

## 6. 未完成项

1. **性能优化**：当前每次 `onDrag` 都调用 `updateRoi` 写 DB，高频拖拽时可能产生写入压力。可优化为拖拽过程中只更新本地状态，`onDragEnd` 时才持久化。
2. **拍后比对（V1-3）**：仍为 DEFERRED。
3. **Detector（V1-4）**：仍为 DEFERRED。
4. **结果查看（V1-5）**：仍为 DEFERRED。

## 7. Git 状态

- 提交状态：`NOT_COMMITTED`（未获用户明确授权前不提交）
- 本轮修改文件：3 个（RoiEditorViewModel.kt, RoiEditorScreen.kt, RoiEditorViewModelTest.kt）
- 其他工作区修改：属于前序任务的未提交改动
- APK SHA-256：`d679e7a3e41f236d1958b125410ec827a54eb28c7d7e58b83fd216a8345bb56c`

## 8. 验收等待

本轮整改已完成全部代码实现和自动化测试验证。等待用户审阅代码和测试结果后决定是否通过验收。
