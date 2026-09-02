# B2 Task 1 DPM 旧版行为对等报告

**日期**：2026-09-02
**状态**：回归整改中（撤销此前"新旧处理链一致"断言）
**触发原因**：用户真机确认同一 DPM 码旧 App 可识别、新 App 不可识别；闪光灯无效

## 1. 审计发现的回归缺陷

### 1.1 解码链缺陷（导致识别失败）

| # | 缺陷 | 旧版行为 | 新版行为（整改前） | 影响 | 整改方式 |
|---|------|---------|-------------------|------|---------|
| 1 | 缺少全图 ZXing 阶段 | ROI 失败后降采样到1280 → 预处理 → ZXing | 无此阶段 | 码在画面边缘或 ROI 裁切不当时无法识别 | 已恢复：阶段2 全图降采样 + 同策略预处理 |
| 2 | 缺少 ML Kit 全图兜底 | 全图 ZXing 失败后对原图直接 ML Kit | ML Kit 仅在预处理候选上调用 | 模糊/透视码场景 ZXing 解不了时无兜底 | 已恢复：阶段3 ML Kit 直接解原图 |
| 3 | 中心 ROI 尺寸错误 | CENTER_ROI_RATIO=0.5f → 中心50%（1280×1707帧上640×854） | centerRoiWidth=1200, centerRoiHeight=1200（1280×1707帧上1280×1200） | ROI 过大，可能包含干扰区域；缩放到400px后码可能被过度缩小 | 已恢复：使用 centerCropRatio=0.5f |
| 4 | DpmGridGate 参数错误 | missThreshold=8, cooldownMs=1500 | missThreshold=5, cooldownMs=3000 | 网格兜底触发过早（5帧 vs 8帧）且冷却过长（3s vs 1.5s） | 已修复：改为8/1500 |
| 5 | gridGate.onMiss() 从未调用 | 每帧 miss 累计网格门控计数 | 只调用 respondGate.onMiss() | 网格门控 miss 计数永远为0，网格兜底永远不会触发 | 已修复：handleMiss 中调用 gridGate.onMiss() |
| 6 | DpmDimensionMode 硬编码 AUTO | 从 SettingsStore 读取，作为任务快照传入 | triggerGridDecode 硬编码 DpmDimensionMode.AUTO | 用户设置的固定尺寸模式不生效 | 已修复：通过 dimensionMode() lambda 读取 |
| 7 | ML Kit 每候选调用 | ML Kit 仅在阶段3全图调用一次 | decodePixels 中每个候选都调用 ML Kit | 不必要的性能开销；语义不一致 | 已修复：decodePixels 仅调用 ZXing |

### 1.2 闪光灯缺陷

| # | 缺陷 | 旧版行为 | 新版行为（整改前） | 整改方式 |
|---|------|---------|-------------------|---------|
| 1 | 无 hasFlashUnit 检查 | 检查 camera.cameraInfo.hasFlashUnit() | 不检查 | 已添加 hasFlashUnit() |
| 2 | 不等待异步结果 | enableTorch() 返回 ListenableFuture，旧版也不等待 | 同上，但用户报告"无实际效果" | 已改为 suspend，等待 ListenableFuture 完成 |
| 3 | 不读取真实 torchState | 旧版也不读取，但有 torchSupported StateFlow | UI 直接翻转 torchOn，不核实 | 已添加 isTorchOn() 读取 CameraX torchState |
| 4 | 退出页面不关 torch | 旧版 disconnect 时关闭 | 不关闭 | 已在 cleanupBoundResources 和 stopScan 中关闭 |

## 2. 旧/新行为逐项对照表

### 2.1 帧节流

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 节流间隔 | DpmAnalyzer.shouldProcess(): ATTEMPT_INTERVAL_MS=200 | DpmAnalyzer.shouldAllowAnalysis(): 多级节流 | ⚠️ 不同机制 | 保持新版多级节流（更精细） |
| 节流时机 | 外部调用 shouldProcess() → detect() | analyze() 内部 | ✅ 功能等价 | 无需整改 |

### 2.2 Single-flight

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 并发保护 | 无显式 single-flight（依赖外部节流） | AtomicBoolean analysisRunning | ✅ 新版更严格 | 保持 |

### 2.3 Upright Rotation

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 旋转 | CameraXVideoSource.analyze 已旋转 | DpmFrameAnalyzer.imageProxyToUprightBitmap | ✅ 一致 | 无需整改 |

### 2.4 扫码 ROI

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| ROI 类型 | RectF 归一化0..1 | Rect 像素坐标 | ✅ 功能等价 | 无需整改 |
| 框内约束 | scanRoi!=null → 只解码该区域 | scanRoi!=null → 只解码该区域 | ✅ 一致 | 无需整改 |

### 2.5 中心 50% ROI

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 裁切比例 | CENTER_ROI_RATIO=0.5f → cropCenter() | centerCropRatio=0.5f → cropRoi() | ✅ 已修复 | 已从固定1200×1200改为50%比例 |

### 2.6 ROI 目标宽 400

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 目标宽度 | DPM_ROI_TARGET_WIDTH=400 | config.roiTargetWidth=400 | ✅ 一致 | 无需整改 |

### 2.7 原始 ROI ZXing

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 快速解码 | 无独立阶段（旧版也是预处理后 ZXing） | 无独立阶段 | ✅ 一致 | 无需整改 |

注：旧版 `detect()` 中 `decodeWithStrategy()` 直接调用 `DpmPreprocessor.preprocess()`，没有"原始 ROI ZXing"独立阶段。

### 2.8 预处理策略轮转

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 轮转方式 | frameCount % 4 → 单策略 | frameCount % 4 → 单策略 | ✅ 一致 | 无需整改 |
| 策略种类 | 4种：针撞/反色/激光/CLAHE | 同上 | ✅ 一致 | 无需整改 |

### 2.9 正常/反转双极性

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 双试 | decodePixels(candidate, inverted=false) + decodePixels(candidate, inverted=true) | 同上 | ✅ 一致 | 无需整改 |

### 2.10 全图降采样

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 长边上限 | SCAN_MAX_EDGE=1280 | SCAN_MAX_EDGE=1280 | ✅ 已恢复 | 已添加 downscaleToMaxEdge() |

### 2.11 全图 ZXing

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 阶段存在 | 有（stage2：全图降采样 → 预处理 → ZXing） | 无（整改前） | ✅ 已恢复 | 已添加阶段2 |

### 2.12 ML Kit DATA_MATRIX 兜底

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 阶段位置 | stage3：全图 ML Kit 直接解码 | 阶段3：全图 ML Kit 直接解码 | ✅ 已恢复 | 已从每候选调用改为阶段3全图调用 |
| 格式配置 | FORMAT_DATA_MATRIX + FORMAT_QR_CODE | FORMAT_DATA_MATRIX only | ⚠️ 有意差异 | 产品边界：只识别 DATA_MATRIX |

### 2.13 连续 miss 对焦

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 阈值 | MISS_STREAK_TO_FOCUS=30 | config.missTriggerCount=30 | ✅ 一致 | 无需整改 |
| 冷却 | FOCUS_REQUEST_COOLDOWN_MS=5000 | 无冷却（但 miss 阈值30帧≈6s 自然冷却） | ⚠️ 轻微差异 | 可接受，后续优化 |

### 2.14 网格触发阈值与冷却

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| miss 阈值 | MISS_STREAK_TO_GRID=8 | missThreshold=8 | ✅ 已修复 | 从5改为8 |
| 冷却 | GRID_COOLDOWN_MS=1500 | cooldownMs=1500 | ✅ 已修复 | 从3000改为1500 |
| miss 累计 | gridGate.onMiss() 每帧调用 | gridGate.onMiss() 每帧调用 | ✅ 已修复 | 已添加调用 |

### 2.15 DpmDimensionMode

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| 读取方式 | lambda: dimensionMode = { settings.get() } | lambda: dimensionMode = { DpmDimensionMode.AUTO } | ⚠️ TODO | 已接通 lambda，待 SettingsStore 集成 |
| 硬编码 | 无 | 无（通过 lambda 传入） | ✅ 已修复 | 从硬编码 AUTO 改为 lambda |

### 2.16 重复响应门

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| DpmRespondGate | DpmAnalyzer 内部类 | 独立类 DpmRespondGate | ✅ 一致 | 无需整改 |
| REARM_MISSES | 10 | 10 | ✅ 一致 | 无需整改 |
| MAX_HOLD_RESPOND_MS | 5000 | 5000 | ✅ 一致 | 无需整改 |

### 2.17 stop/取消/迟到回调

| 项目 | 旧代码位置 | 新代码位置 | 是否一致 | 整改方式 |
|------|-----------|-----------|---------|---------|
| stop 行为 | setScanModeActive(false) + cancel gridJob | stop() cancelChildren + isStopped | ✅ 功能等价 | 无需整改 |
| 迟到回调 | generation 比对 | generation 比对 + isStopped | ✅ 一致 | 无需整改 |

## 3. 整改后解码链（与旧版一致）

```
帧输入 (upright Bitmap)
  │
  ├─ 扫码框存在？──是──→ 裁切框内区域 → 缩放到400px → 预处理策略 → ZXing → 成功？→ 响应门 → 返回
  │                                                          ↓ 失败
  │                                                     (跳过全图阶段)
  │
  └─ 无扫码框 ──→ 裁切中心50% → 缩放到400px → 预处理策略 → ZXing → 成功？→ 响应门 → 返回
                                              ↓ 失败
                                         全图降采样1280 → 同策略预处理 → ZXing → 成功？→ 响应门 → 返回
                                              ↓ 失败
                                         ML Kit 全图兜底 → 成功？→ 响应门 → 返回
                                              ↓ 失败
                                         网格兜底（异步）→ 绕过响应门 → 返回
                                              ↓ 失败
                                         miss 累计 → 对焦请求 / 网格触发
```

## 4. 闪光灯整改后行为

1. **开启**：检查 hasFlashUnit → suspend 等待 enableTorch 异步完成 → 读取真实 torchState 更新 UI
2. **关闭**：同上，enableTorch(false)
3. **页面退出**：stopScan 中异步关闭 torch + CameraController cleanupBoundResources 同步关闭
4. **相机会话切换**：cleanupBoundResources 同步关闭
5. **设备不支持**：hasFlashUnit()=false → toggleTorch 返回 false → UI 不翻转
6. **异步失败**：enableTorch 的 ListenableFuture 失败 → 返回 false → UI 不翻转

## 5. DPM_data 样本元数据

| 文件名 | 尺寸 | 大小 | SHA-256 |
|--------|------|------|---------|
| 45eb098523e21fa461e135dac8f7b678_720.jpg | 1280×1707 | 211,367 | 0ebe300af580a3f39f140eb589415686652e711b7f4196d60ea002fd315e73fb |
| 5ba81f191dc78bb60cf267eb9af10a54_720.jpg | 1280×1707 | 202,953 | 120d235d3ce9caab178da8aefb333d7a17dbefd2744d6773ec08be40d7218391 |
| 5fd53ffd01341e9dc10e4e977b804fad_720.jpg | 1280×1707 | 201,603 | 01f1cc9783b34450c8a836905dbccee6b4ce8cb27659306aa370614ca563404a |
| 87879f06a1081dabfc34836fd92760ab_720.jpg | 1280×1707 | 213,043 | 4a64eab5d65a0e52b1415909957f2b1de8058b0d5745dcdfe261eed3519f0a86 |
| a0a3e4f3ca567188aec2020ecacbc160.jpg | 3000×4000 | 3,532,738 | f3641bd0d8630de7c76606e98613830270387d9a66146da6f0f06c29bed39108 |
| c7f8366cd8452f313279c2b88a77eccf_720.jpg | 1280×1707 | 189,445 | 4d812796c0c4176ac58d9376835e8e2809aae71101a65b81c14dd40ac0267c21 |

## 6. 离线解码基线（纯 ZXing，无 OpenCV 预处理）

| 样本 | 全图 ZXing | 中心50% ROI+400px ZXing |
|------|-----------|------------------------|
| 45eb09... | NO_RESULT | NO_RESULT |
| 5ba81f... | NO_RESULT | NO_RESULT |
| 5fd53f... | NO_RESULT | NO_RESULT |
| 87879f... | NO_RESULT | NO_RESULT |
| a0a3e4... | NO_RESULT | NO_RESULT |
| c7f836... | NO_RESULT | NO_RESULT |

注：纯 ZXing 对工业 DPM 码（针打/激光蚀刻/低对比）识别率极低，需 OpenCV 预处理（DpmPreprocessor）才能解码。此基线仅验证测试基础设施可用。

## 7. 待完成项

- [ ] 真机验证：使用同一 DPM 码测试整改后新 App
- [ ] DpmDimensionMode 从 SettingsStore 真实读取（当前使用默认 AUTO）
- [ ] 连续 miss 对焦冷却保护（当前依赖自然帧间隔）
- [ ] 同样本 A/B 对照：旧 App vs 新 App
