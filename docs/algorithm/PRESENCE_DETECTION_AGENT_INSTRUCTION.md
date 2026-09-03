# Agent 指令：螺纹、螺母和其他特征的目标有无检测

> 用途：将本文件作为下一轮编码 Agent 的完整任务指令。当前只要求实现“目标是否存在”的可解释检测，不要求识别缺陷类型、尺寸测量或自动轮廓配准。

## 1. 任务目标

在 `MobileInspectionApp` 中建立一个简单、可解释、可测试的 ROI 检测链：

- 螺纹使用独立的 `ThreadPresenceDetector`；
- 螺母使用独立的 `NutPresenceDetector`；
- `feature_1.png`、`feature_2.png` 这类其他特征使用独立的 `FeaturePresenceDetector`；
- 每个 ROI 通过 `inspectionType` 和 `configJson` 选择算法及参数，不允许所有类型都委托给一个通用直方图算法；
- 最终输出目标“有 / 无 / 不确定 / 输入错误”，对应 `PASS / FAIL / REVIEW / ERROR`；
- 先用离线图片完成算法标定和结果报告，再接入 Android 现场采集链。

这里的“学习”定义为：从正样本提取模板、几何/纹理统计量并标定阈值，不训练 YOLO、分类神经网络或其他黑盒模型。现有数据量很小，不能把 5 张 Key 图片宣传为已完成通用工业模型训练。

## 2. 数据路径和已知事实

所有路径均为 Windows 绝对路径，编码 Agent 必须先确认路径存在：

```text
正样本目录：D:\study\Textile_defects\Wearable Inspection\Key
大图测试目录：D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM
当前工程：D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp
旧工程参考：D:\study\Textile_defects\Wearable Inspection\Wearable Inspection
```

已盘点素材：

| 类别 | 文件 | 尺寸/说明 |
|---|---|---|
| 其他特征 | `feature_1.png` | 268×127 |
| 其他特征 | `feature_2.png` | 189×261 |
| 螺母 | `nut_1.png` | 376×136，图中可见两个螺母样本 |
| 螺纹 | `thread_1.png` | 91×84，近距离内螺纹 |
| 螺纹 | `thread_2.png` | 203×177，带倾斜/反光的内螺纹 |
| 测试大图 | `DCIM\DCIM\*.jpg` | 共 30 张，3072×4096 或 4096×3072 |
| 可选素材 | `DCIM\DCIM\2e0e5b7b6c79fb4f41f8b2158f71c627.mp4` | 本轮不是必需输入，禁止先把视频帧当作额外真值 |

### 2.1 青绿色标记处理是硬性要求

Key 图片和 DCIM 大图中的青绿色线条/涂点是人工标记，不是检测目标。算法必须在模板和测试图上统一执行青绿色掩码：

1. BGR/RGB 转 HSV；
2. 用可配置的色相、饱和度、亮度范围检测青绿色像素；
3. 对掩码做小范围膨胀，避免标记边缘进入边缘密度或描述子；
4. 检测得分计算时忽略该区域，或用邻域中值进行中和；
5. 输出调试图，证明青绿色掩码确实覆盖了标记；
6. 不能把青绿色颜色直方图、青绿色边缘或青绿色局部特征当成目标证据。

掩码范围必须进入 `configJson`/离线 manifest，不能散落为多个算法中的硬编码。没有可靠的颜色分离时，结果必须标记 `REVIEW`，不能直接判定 `PASS`。

### 2.2 当前数据不是完整监督数据集

5 张 Key 图片是正样本模板，不包含明确的“目标不存在”标注；30 张 DCIM 大图也没有现成的逐目标真值。因此 Agent 必须：

- 建立 `ground_truth.json` 或等价 manifest，记录每张测试图每个目标的 `present/absent/unknown`；
- 未人工确认的图只能用于 smoke test 或候选定位，不能纳入准确率、召回率结论；
- 可以生成空白、遮挡、裁剪错位、模糊等合成负样本做单元测试，但必须在报告中注明“合成负样本”；
- 如果没有足够人工负样本，报告写 `INSUFFICIENT_DATA`，不能为了通过测试而自动把所有未命中的图标成 absent；
- 结果表至少保存：图片、目标类型、目标 ROI、期望标签、预测标签、score、主要 metrics、算法版本和耗时。

## 3. 必须先做的只读审计

编码前先输出文件边界和依赖关系，暂不修改代码。至少检查：

1. `AGENTS.md`、`tasks/todo.md` 和 `MOBILE_INSPECTION_AGENT_INSTRUCTION.md`；
2. 当前 `InspectionType`、`RoiDefinitionEntity`、`InspectionSessionEntity`、`RoiInspectionRecordEntity`；
3. 当前唯一 `CameraController`、`FrameAnalyzer`、`LiveInspectionScreen` 和 `WorkbenchViewModel`；
4. 旧工程的以下 V3/V4 参考实现：
   - `Wearable Inspection/app/src/main/java/com/wearable/inspection/vision/OpenCvV3MatchEngine.kt`
   - `Wearable Inspection/app/src/main/java/com/wearable/inspection/vision/OpenCvV4MatchEngine.kt`
   - `Wearable Inspection/app/src/main/java/com/wearable/inspection/vision/V3TemplateCache.kt`
   - `Wearable Inspection/app/src/main/java/com/wearable/inspection/vision/OpenCvImagePreprocessor.kt`
   - `Wearable Inspection/app/src/main/java/com/wearable/inspection/vision/MatchConfig.kt`
   - `Wearable Inspection/app/src/test/java/com/wearable/inspection/vision/OpenCvV3MatchEngineTest.kt`
   - `Wearable Inspection/app/src/test/java/com/wearable/inspection/vision/OpenCvV4MatchEngineTest.kt`

审计报告要明确区分：

- V3/V4 的职责是模板/视角匹配和几何定位；
- 本任务的职责是已定位 ROI 内的目标有无判断；
- 只有必要的 AKAZE、pHash、ROI、几何验证、帧节流和连续帧稳定思想可以复用；
- 不得把旧工程整套 1200 行级别匹配引擎复制到新工程，也不得用 V3/V4 的“模板匹配成功”直接冒充“螺母/螺纹存在”。

## 4. 算法分工和实现要求

### 4.1 共同输入输出协议

为所有检测器定义同一纯算法协议，建议形态如下；具体包名按当前工程结构决定：

```kotlin
data class PresenceInput(
    val image: Mat,
    val templateImage: Mat?,
    val normalizedRoi: String,
    val configJson: String?,
    val preprocessJson: String?,
    val expectedCount: Int? = null,
)

data class PresenceOutput(
    val status: InspectionStatus,
    val score: Float?,
    val message: String,
    val metrics: Map<String, Double>,
    val boxes: List<DetectedBox> = emptyList(),
    val algorithm: String,
    val algorithmVersion: String,
    val durationMs: Long,
)
```

硬性规则：

- `score` 统一为 0～1，所有中间量写入 `metrics`；
- `ERROR` 只用于图片不可解码、ROI 越界、尺寸无效、OpenCV 执行异常等输入/执行错误；
- 证据不足或分数落在不确定区间使用 `REVIEW`，不能强行二值化；
- 任何 Mat、Bitmap、ImageProxy 的所有权和释放必须清楚，异步处理前先复制需要的数据；
- ROI 坐标必须明确是模板原图坐标、当前实拍图坐标还是 `contentRect` 坐标，禁止混用；
- 模板缺失、标定未完成或 ROI 未配置时返回 `REVIEW/ERROR`，不能显示假成功。

### 4.2 螺纹：`ThreadPresenceDetector`

目标是判断“有可见内螺纹/螺纹孔”，不是 OCR，也不是螺纹牙型精密测量。使用简单、可解释的几何和周期纹理特征：

1. 对 ROI 做灰度化、CLAHE、轻度去噪；使用青绿色掩码排除标记。
2. 用 Canny + `HoughCircles`，必要时增加轮廓圆度候选，定位圆形孔口；半径和圆心限制在 ROI 内。
3. 在圆孔内外设置同心环和径向采样带，计算：
   - 圆度、半径稳定性、孔口边缘强度；
   - 内部暗区比例；
   - 同心环/径向梯度的边缘密度；
   - 多个环带上的周期性峰值或自相关峰值；
   - 青绿色有效像素比例和清晰度质量。
4. 从 `thread_1.png`、`thread_2.png` 的正样本统计量生成 profile，推荐用中位数和 MAD/分位数，不要直接用单个样本的最小最大值。
5. 用配置阈值将圆孔证据和周期纹理证据合成为 score。只有同时满足圆孔几何和足够纹理证据才允许 `PASS`；只有圆孔但看不出纹理时应为 `REVIEW`，以避免把普通孔误判为螺纹。
6. 输出圆心、半径、环带边缘密度、周期性得分等 debug metrics，并在 debug 图上画出圆和环带。

不要实现 OCR、深度学习、SIFT、自动轮廓投影、Homography 或复杂三维螺纹测量。

### 4.3 螺母：`NutPresenceDetector`

目标是判断 ROI 内是否有符合模板的六角螺母，并支持配置预期数量。使用轮廓和几何特征：

1. 灰度化、CLAHE、Canny/自适应阈值；青绿色标记不参与轮廓得分。
2. 查找候选外轮廓，使用 `approxPolyDP`、凸性、面积、外接矩形宽高比和轮廓圆度筛选六边形候选；允许因透视出现 5～7 个近似角点，但必须把容差写入配置。
3. 对每个候选计算：
   - 近似六边形程度；
   - 六边形面积与外接框面积比；
   - 中心孔/暗区比例；
   - 中心与外轮廓的偏移；
   - 相对模板的尺寸和长宽比；
   - 透视下的可接受变形。
4. 用非极大值抑制去重，得到候选螺母数量；`expectedCount` 必须来自 ROI 配置或标定 profile。`nut_1.png` 里可见两个螺母，不能因此在代码中永久写死“必须为 2 个”。
5. `PASS` 条件应同时考虑数量和每个候选的几何分数；数量不足为 `FAIL`，轮廓质量介于阈值之间为 `REVIEW`，输入无效为 `ERROR`。
6. debug 图画出每个候选的外轮廓、中心孔、单个 score 和最终计数。

不要把螺母复用为螺丝、孔或通用直方图检测器；不要仅按亮度或青绿色标记计数。

### 4.4 其他特征：`FeaturePresenceDetector`

`feature_1.png`、`feature_2.png` 属于外形不规则、可能有尺度/旋转变化的特征。使用两级轻量匹配：

1. ROI/大图预处理：灰度化、CLAHE、轻度缩放，排除青绿色标记。
2. 第一阶段优先使用归一化局部模板匹配或 pHash 粗筛，快速跳过明显无关窗口。
3. 第二阶段使用 AKAZE 二进制特征；采用 Lowe ratio、互相匹配或简单 GMS 过滤，再用 `estimateAffinePartial2D` 或受控 `findHomography` 做 RANSAC 几何验证。
4. 判定必须同时满足有效匹配点数量、内点比例、投影框面积/位置合理性和模板覆盖率；不能仅凭一个最高匹配点通过。
5. 只有目标特征在图中真实出现且投影几何合理时 `PASS`；只有少量特征点或投影退化为 `REVIEW`；无有效候选为 `FAIL`。
6. 对 `feature_1`、`feature_2` 分别建立 profile/模板缓存，不能把两个完全不同的特征混为一个模板。

参考 V3/V4 的原则：模板特征预缓存、pHash 粗筛、ROI 限制、AKAZE、Lowe ratio、几何内点验证、连续帧确认和动态 miss 退化。但保持本任务简单：不复制完整 V3/V4 状态机，不在每个 ROI 重复建立线程池，不在每帧做多次全图特征提取。

## 5. 离线优先实现

先在 `tools/feature_presence/` 建立最小离线工具，不创建 `.venv`、Conda 环境、`site-packages` 或依赖副本。Python 只能使用：

```text
D:\ProgramData\anaconda3\envs\dinov2\python.exe
```

建议交付物：

- `dataset_manifest.json`：Key 正样本、每个目标 ROI、expectedCount、掩码参数、算法 profile；
- `ground_truth.json`：DCIM 逐图逐目标的人工标签，允许 `unknown`；
- `calibrate_presence.py`：读取模板、掩码青绿色、计算统计量和阈值；
- `evaluate_presence.py`：批量跑 30 张大图，输出 CSV/JSON 汇总和 debug 图片；
- `README.md`：运行命令、坐标约定、标签定义、局限性；
- `docs/reports/b3/feature_presence/` 下的标定报告和评估报告。

推荐先做固定 ROI/人工标注版：

1. 对每类目标建立一个或多个归一化 ROI；
2. 在大图中先使用人工/配置 ROI 验证“ROI 内有无算法”是否有效；
3. 只有 ROI 内算法达到可解释结果后，才增加模板到大图的局部定位；
4. 不要一开始就写全图自动检测器，否则无法区分“目标不存在”和“目标没有被定位到”。

离线结果至少包含：原图、青绿色掩码图、ROI 图、预处理图、候选/圆/轮廓/投影可视化图，以及每个目标的 score 和状态。

## 6. Android 接入边界

离线算法通过后再接入当前工程：

- 继续使用 `com.wearable.inspection.mobile`；不修改旧 `Wearable Inspection` 工程；
- 复用当前唯一 `CameraController` 和 `FrameAnalyzer`，不得创建第二套 CameraX；
- 现场图片与模板图片必须经过同一方向/颜色/尺寸预处理；
- 继续遵守 `PreviewView.FIT_CENTER`、真实 `contentRect` 和 4:3 画幅约束；
- 使用当前 `RoiDefinitionEntity.inspectionType/configJson/preprocessJson` 保存算法选择和参数；
- 当前枚举已有 `THREAD_PRESENCE`，需要新增独立的 `NUT_PRESENCE` 和 `FEATURE_PRESENCE`（或等价稳定名称），不能把螺母伪装成 `SCREW_PRESENCE`；旧值保持兼容；
- 新增纯算法包、注册器/工厂和 use case 时，先搜索全仓引用，避免和已有占位类重复；
- `RoiInspectionRecordEntity` 保存算法版本、score、metrics、耗时、状态和错误原因；没有完整真实 Session 时不要写入虚假的历史结果；
- Live UI 只显示真实 detector 输出和 debug 状态，不显示固定绿色框、固定“检测通过”或配置就绪冒充结果；
- 实时模式使用 single-flight、帧节流、连续多帧确认；单帧异常只丢弃当前帧，不能崩溃整个页面；
- 所有 `ImageProxy` 必须在所有路径关闭，异步任务不得持有已关闭对象。

## 7. 实施顺序

### Task 1：数据清点、标记掩码和离线 manifest

验收：5 张 Key 和 30 张 DCIM 图片全部可读；方向正确；青绿色掩码可视化正确；manifest 明确每类目标、ROI、expectedCount、标签状态；无标签时明确输出 `INSUFFICIENT_DATA`。

### Task 2：螺纹离线检测器

验收：thread 正样本自检、合成空孔/模糊/遮挡负样本、至少一个人工确认的大图样本均有结果；输出圆孔和周期纹理 metrics；不能把普通圆孔直接判为螺纹 PASS。

### Task 3：螺母离线检测器

验收：nut 正样本自检；候选轮廓、中心孔和数量可视化；expectedCount 可配置；缺一个、空 ROI、反光干扰和重复候选有明确状态。

### Task 4：其他特征离线检测器

验收：`feature_1` 和 `feature_2` 使用独立模板 profile；覆盖轻度缩放/旋转/平移；噪声或无关区域不能仅凭单点匹配通过；输出 AKAZE/匹配/几何 metrics。

### Task 5：统一协议、注册器和 Android ROI 接口

验收：三种 `inspectionType` 分别调用不同 detector；配置参数能从 `configJson` 生效；未注册类型、缺 ROI、模板缺失和 OpenCV 错误均有明确 `ERROR/REVIEW`；不新增 CameraX。

### Task 6：结果页/调试页和持久化

验收：逐 ROI 显示算法名、状态、score、主要 metrics 和错误原因；真实结果才写入 `RoiInspectionRecordEntity`；没有真实标签或未完成标定时不显示“工业准确率”。

### Checkpoint：算法闭环

- [ ] 离线单元测试全部通过；
- [ ] 5 张 Key 正样本和已标注 holdout 的混淆矩阵已生成；
- [ ] 未标注图片未被偷偷当作负样本；
- [ ] `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug` 通过；
- [ ] 如进行真机测试，严格执行 `AGENTS.md` 的新包安装、前台包名和 instrumented 测试后恢复门禁；
- [ ] 报告包含前序能力回归矩阵：导航、权限、相机状态、画幅、contentRect、资源释放和错误态。

## 8. 测试与报告要求

每个 detector 至少覆盖：

- 正样本原图；
- 目标裁剪为空或目标被遮挡；
- 光照/反光变化；
- 轻度缩放、旋转、平移；
- 青绿色标记存在但目标不存在；
- 无效 ROI、模板缺失、损坏图片和极小图片；
- 连续帧重复调用、停止后不回调、Mat 释放不泄漏。

每次修改后按小批次验证：

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
```

报告必须记录：实际修改文件、测试命令及结果、离线输入/输出路径、每类算法版本、每张图预测状态、阈值来源、失败样本、未完成项，以及 APK 路径/时间/大小/SHA-256（若已接入 Android）。

## 9. 禁止事项

- 不修改旧工程作为新工程的实现对象；
- 不用 YOLO、TensorFlow、TFLite、ML Kit Object Detection 或其他新模型替代简单算法；
- 不把 5 张正样本称为完整训练集，不在没有人工真值时声称准确率；
- 不让算法学习青绿色标记；
- 不用全图平均直方图作为三类目标的唯一判据；
- 不把螺母、螺纹、其他特征都委托到同一个 `PresenceDetector`；
- 不擅自调换或删减旧 DPM 解码链；
- 不实现自动主体轮廓提取、实时轮廓投影、SIFT 姿态对齐、自动 ALIGNED、ROI 自动跟踪、OCR、TTS、ResultPackager 或 ForegroundService；
- 不复制第二套 CameraX、ProcessCameraProvider、相机线程池或 `ImageAnalysis`；
- 不用固定矩形、假检测结果或固定绿色 ROI 冒充真实目标存在；
- 不在 `tools/` 创建虚拟环境、依赖副本、临时总结或大图副本。

## 10. 交付判定

本任务只有在以下条件同时满足时才可标记软件完成：

1. 三个 detector 是三个独立算法，并由稳定 `inspectionType` 路由；
2. 青绿色标记被统一屏蔽，并有可查看的 debug 证据；
3. Key 正样本自检、合成边界测试和人工标注 holdout 结果已报告；
4. 未标注的 DCIM 图片明确标记为 `unknown` 或仅作为 smoke test；
5. Android 现场链路使用真实 ROI、真实相机帧、真实 `contentRect`，没有第二套 CameraX 或假 PASS；
6. 测试、APK、日志和前序能力回归证据齐全；
7. 仍不具备足够负样本或现场样本时，状态写为 `SOFTWARE_COMPLETE / DATA_ACCEPTANCE_PENDING`，不得写成算法已经可靠量产。
