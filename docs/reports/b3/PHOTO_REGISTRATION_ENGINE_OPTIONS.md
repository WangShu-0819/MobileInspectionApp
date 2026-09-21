# 单张照片配准引擎方案选型

状态：**设计完成 / IMPLEMENTATION_AUTHORIZED / 尚未实现**（2026-09-21）

## 1. 目标

拍照时不显示透明度模板投影。照片保存后，优先将样本图与采集图配准，取得样本 ROI 在采集照片中的位置，再交给现有 NanoDet 检测螺纹、螺母、螺栓和铆螺母。配准质量不足时，不使用错误的 ROI，改为整图检测。

共同流程：

```text
样本图 + 样本 ROI
        ↓
采集照片
        ↓
尝试图像配准 / Homography
        ├─ 配准可靠 → ROI 四角投影 → 透视裁剪 → NanoDet
        └─ 配准不可靠 → 放弃映射 ROI → 整图 NanoDet
```

配准失败不等于整次检测失败。整图检测完成后，按目标类别和期望数量判定；只有整图检测结果不足或歧义时，才判 NG、人工复核或要求重新拍摄。

## 2. 配准优先、全图检测兜底

### 模板配置

每个模板 ROI 除保存四边形位置外，还应保存检测约束：

```text
目标类型：THREAD
期望数量：2
```

例如“两个螺孔的内螺纹有无”，ROI 表示两个目标的预计区域，但 ROI 本身不代表检测通过。

### 视角正常时

```text
样本图 + 采集图
        ↓
V4 静态照片配准
        ↓
Homography 投影 ROI 四角
        ↓
透视矫正 ROI
        ↓
NanoDet 检测 THREAD
```

如果找到 2 个有效内螺纹，则 `PASS` 并显示两个检测框；找到 0 个或 1 个，则 `NG`。

工程上应统一称为“平面配准变换”：轻微视角变化可以使用仿射变换，但 V4 主流程应优先使用四点 Homography 处理透视变化。

### 视角差异过大时

当配准的内点数、内点比例、重投影误差、空间覆盖或投影四边形检查不满足门槛时：

1. 丢弃本次不可靠的映射 ROI；
2. 对整张采集照片运行 NanoDet；
3. 只保留当前 ROI 配置允许的目标类别，例如 `THREAD`；
4. 执行 NMS、置信度过滤和目标数量统计。

对于期望 2 个内螺纹的 ROI：

```text
找到 2 个有效目标 → PASS
找到 0 个或 1 个 → NG
找到超过 2 个 → 结果歧义，人工复核或要求重新拍摄
```

如果整张照片中可能出现多个相同螺孔，仅凭“找到两个”不能证明它们就是目标 ROI 对应的两个螺孔。此时需要零件粗定位、目标数量约束或人工复核，不能静默选择任意两个目标。

### 统一结果

两条路径都应输出统一结果：

```text
registrationStatus: ALIGNED / FALLBACK_FULL_IMAGE
detectionSource:    MAPPED_ROI / FULL_IMAGE
targetType:         THREAD
expectedCount:      2
foundCount:         实际检测数量
detections:         检测框、类别、置信度
result:             PASS / NG / REVIEW
```

当前 `0.20` 仍是阶段性候选阈值，不能直接作为全图检测的最终生产阈值。全图路径必须用包含不同位置、尺度、光照、遮挡和背景的真实数据单独验证。

## 3. 方案 A：基于 V4 几何核心的单张照片配准

### 核心

复用旧工程 V4 的几何能力，在新工程中重写为一次性输入输出：

```text
模板特征缓存
→ AKAZE
→ BFMatcher + Lowe ratio
→ GMS
→ USAC_MAGSAC Homography
→ 内点/覆盖率/重投影/四边形校验
→ ROI 四角投影
→ warpPerspective
```

参考：[旧工程 OpenCvV4MatchEngine.kt](../../../../Wearable%20Inspection/Wearable%20Inspection/app/src/main/java/com/wearable/inspection/vision/OpenCvV4MatchEngine.kt)

### 保留

- AKAZE、BFMatcher、Lowe ratio；
- GMS 网格过滤；
- USAC_MAGSAC；
- 内点数、内点比例、空间覆盖、重投影误差；
- 投影面积和凸四边形检查；
- 模板特征缓存。

### 删除或改写

- 连续帧、历史 ROI、上一帧 Homography；
- tracking state、TemporalMatchConfirmer、HUD；
- 实时动态搜索区域；
- pHash 强制拒绝。pHash最多用于候选排序；单模板场景可暂时不用；
- Rect 直接裁剪，改为四点 ROI + 透视矫正。

### 优点

- 当前 Android 工程已经使用 OpenCV；
- 旧工程已有实现和几何测试经验；
- 不需要新增深度模型和模型运行时；
- 延迟、内存和失败原因更容易控制。

### 风险

- 弱纹理、强反光、遮挡和大视角变化下可能匹配失败；
- 单应性主要适合近似平面或局部平面目标；
- 需要用真实金属零件照片重新校准门槛，不能直接沿用旧实时阈值。

## 4. 方案 B：ALIKED + LightGlue-ONNX

参考：[ikeboo/ALIKED-LightGlue-ONNX](https://github.com/ikeboo/ALIKED-LightGlue-ONNX)

### 核心

```text
ALIKED 特征提取
→ LightGlue 特征匹配
→ Homography
→ 几何校验
→ ROI 四角投影与透视裁剪
→ 现有 NanoDet
```

该仓库提供 ALIKED 和 LightGlue 的 ONNX 推理示例，也支持预先注册模板特征以重复使用。当前仓库示例主要基于 Python `onnxruntime`，并没有现成的 Android 集成方案。

### 优点

- 学习型特征和匹配器可能更适合视角、光照和局部纹理变化；
- 模板特征可以预先提取并缓存；
- ONNX 便于离线评估和后续尝试 Android 推理。

### 风险

- 需要引入或验证 Android ONNX Runtime、CPU/NNAPI 性能和算子兼容性；
- 模型体积、内存和耗时尚未在目标设备验证；
- 仓库示例的 Homography 校验较简单，仍需补充工业级内点、覆盖率和四边形门禁；
- 不能仅凭仓库示例判断真实金属零件的成功率；
- 仓库许可证与上游 ALIKED、LightGlue、预训练权重许可证需要分别审计。

### 模型体积估算

按当前候选 ONNX 文件的约数估算：

```text
ALIKED ONNX                 ≈ 4 MB
LightGlue ONNX              ≈ 46 MB
模型文件合计                 ≈ 50 MB
```

因此，ALIKED + LightGlue 属于轻量 AI 匹配方案，但不是 2～5 MB 级别的超小模型。体积大头是 LightGlue，约占模型文件总量的 `46 / 50 ≈ 92%`；ALIKED 约占 `4 / 50 ≈ 8%`。

以上是模型文件体积估算，不包含 Android ONNX Runtime、CPU/NNAPI provider、ABI 原生库和 APK 压缩差异。最终落地前应以实际模型文件大小、APK 大小和目标设备内存实测为准。

## 5. 方案 C：双方案 fallback

```text
V4 / AKAZE + 几何校验
        ├─ 配准成功 → 映射 ROI 检测
        └─ 配准失败
              ↓ 可选
       ALIKED + LightGlue-ONNX
              ├─ 配准成功 → 映射 ROI 检测
              └─ 仍失败 → 整图 NanoDet
```

两套算法必须输出统一的 `RegistrationResult`，至少包含：

- success；
- homography；
- inlierCount；
- inlierRatio；
- reprojectionError；
- spatialCoverage；
- projectedTemplateCorners；
- failureReason；
- matcherName 和 matcherVersion。

### 优点

- AKAZE 覆盖大多数普通照片，速度和部署风险较低；
- ALIKED + LightGlue 只处理困难样本；
- 可以在不改变 ROI 映射和 NanoDet 的情况下替换匹配器。

### 缺点

- APK 体积、内存、测试和维护成本增加；
- 两套算法可能产生不同的坐标和置信度分布；
- 必须明确 fallback 顺序和最终统一的几何门禁。

## 6. 方案对比

| 项目 | V4 几何核心 | ALIKED + LightGlue-ONNX | 双方案 |
|---|---|---|---|
| Android 落地风险 | 低 | 中高 | 高 |
| 初始开发量 | 中 | 高 | 高 |
| 普通场景速度 | 预计较快 | 待设备实测 | 普通场景较快 |
| 弱纹理/大视角潜力 | 需要真实数据验证 | 可能更强，仍需验证 | 最强潜力 |
| 新增模型文件体积 | ≈ 0 MB | ≈ 50 MB | ≈ 50 MB |
| 新增模型运行时 | 无 | 有 | 有 |
| APK/内存压力 | 低 | 中高 | 高 |
| 失败行为可解释性 | 较好 | 中等 | 中等 |
| 当前推荐级别 | 第一版主方案 | 离线候选/第二阶段 | 数据证明后采用 |

## 7. 推荐落地顺序

1. 先实现 V4 单张照片配准，并保留配准质量门禁；
2. 配准可靠时使用映射 ROI 检测；
3. 配准不可靠时直接使用整图 NanoDet，不使用错误的 ROI；
4. 对整图路径单独验证 `THREAD` 两目标计数、误检、漏检和多目标歧义；
5. 只有真实数据证明 V4/整图路径仍不足时，才评估 ALIKED + LightGlue 作为第二套配准器。

因此，最终产品不是“必须配准”或“完全不要 ROI”，而是：

```text
ROI 是优先搜索区域和业务约束
配准是提高定位准确率的可选步骤
NanoDet 是最终目标判断依据
整图检测是配准失败时的兜底路径
```

本设计已由用户授权进入实现阶段，但当前只实现 V4/AKAZE 单张照片配准引擎；不实现整图 NanoDet 业务兜底、ALIKED + LightGlue、双方案 fallback、CaptureComparisonScreen 或新的 CameraX。实现任务指令见 [`tasks/V4_AKAZE_REGISTRATION_AGENT_INSTRUCTION.md`](../../../tasks/V4_AKAZE_REGISTRATION_AGENT_INSTRUCTION.md)。不改变 NanoDet 阈值，不修改旧工程。
