# B3 Feature Presence 离线原型最终报告

## 最终状态

**OFFLINE_PROTOTYPE_COMPLETE / APP_INTEGRATION_DEFERRED**

本轮只完成离线 Python 算法、自动化测试、数据清点/评估和报告。没有修改 Android App、Gradle、Room、Kotlin 或 CameraX，也没有执行 Gradle、adb 或真机测试。

## 范围与工作区恢复

- 工作目录：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp`
- Nut Key：自动发现 5 张 `nut_*.png|jpg|jpeg`，全部可读；Key 图片本身是完整 ROI，统一使用 `[0.0, 0.0, 1.0, 1.0]`。
- DCIM：30 张 JPG，全部可读；视频已排除。
- DCIM 没有人工 ROI 和人工标签，因此不执行无 ROI 的目标检测。
- 指定 7 个越界 App Kotlin 文件在本轮开始时均不存在且均未被 Git 跟踪：
  `AlgorithmRegistry.kt`、`FeaturePresenceDetector.kt`、`NutPresenceDetector.kt`、
  `PresenceInspectionUseCase.kt`、`PresenceModels.kt`、`PresenceSupport.kt`、
  `ThreadPresenceDetector.kt`。因此没有删除或重建这些文件。

## 实际修改文件

### 离线源码、测试和说明

- `tools/feature_presence/presence_detectors.py`
- `tools/feature_presence/test_presence_detectors.py`
- `tools/feature_presence/evaluate_presence.py`
- `tools/feature_presence/prepare_dataset.py`
- `tools/feature_presence/README.md`
- `docs/reports/b3/feature_presence/OFFLINE_PROTOTYPE_REPORT.md`
- `docs/reports/b3/feature_presence/THREAD_REFINEMENT_REPORT.md`
- `docs/reports/b3/feature_presence/NUT_REFINEMENT_REPORT.md`

### 命令生成或更新的离线产物

- `docs/reports/b3/feature_presence/dataset_manifest.json`
- `docs/reports/b3/feature_presence/ground_truth.json`
- `docs/reports/b3/feature_presence/TASK1_DATASET_REPORT.md`
- `docs/reports/b3/feature_presence/evaluation/offline_evaluation.json`
- `docs/reports/b3/feature_presence/evaluation/offline_evaluation.csv`
- `docs/reports/b3/feature_presence/debug/` 和 `evaluation/debug/` 下的 debug 图片

算法版本为 `presence-offline.3`。没有新增依赖、虚拟环境、`site-packages` 或 Conda 环境。

## 三个算法

### ThreadPresenceDetector

使用 OpenCV `HoughCircles` 生成圆孔候选，再对候选执行局部圆心/半径精修，并计算：

- 圆形几何证据：圆周角度边缘覆盖率和半径一致性；
- 中心暗孔比例；
- 同心环/径向周期纹理、纹理边缘密度和跃迁数；
- 暗孔与圆心共心度、环带梯度响应和径向边缘离散惩罚；
- 图像质量与 Laplacian sharpness。

综合分数输出 `PASS`、`FAIL` 或 `REVIEW`，无候选时为 `FAIL`，模糊、纹理不足或几何证据不足时不会 `PASS`。普通圆孔即使有圆形边界，也必须同时满足内部纹理证据。

### NutPresenceDetector

使用 CLAHE、Otsu threshold、Canny 和多亮度阈值分支，再用 `RETR_TREE`/`approxPolyDP`、solidity、凸包恢复和中心孔证据筛选 5～7 边的近六边形主体。重叠候选使用可配置 IoU/containment NMS 去重；最终六边形角度在 `[-20,-10,0,10,20]` 中由 Canny 边缘支持选择，主体 box 限制在证据组件内，避免框住局部亮斑、垫圈或背景轮廓。

`expectedCount` 只来自运行时 `config`。未配置时固定返回 `REVIEW`；数量不符时返回 `FAIL`；数量正确但几何/中心孔质量低于门槛时保留 `REVIEW`，不会抬高 score。用户确认的五张 Nut Key 样本均使用 `expectedCount=2` 进行离线回归，未写死到 detector 或样本分支。

### FeaturePresenceDetector

按以下顺序组合轻量证据：

1. DCT pHash 粗筛；
2. 多尺度 `cv2.matchTemplate`，输出最佳分数、scale 和匹配框；
3. AKAZE + Hamming BFMatcher + Lowe ratio；
4. 必要时用 `estimateAffinePartial2D` 的 affine RANSAC 计算 inlier ratio 和覆盖范围。

输出包含 pHash 相似度、模板匹配分数、AKAZE keypoints/good matches、inlier ratio、coverage 和位置框。无模板/无效图像为 `ERROR`，粗相似度过低为 `FAIL`，证据不足为 `REVIEW`。没有使用 Homography、SIFT、自动姿态对齐或自动 ROI。

三个 detector 都保留统一 `DetectionResult`、`detector_for()` 注册器、`crop_normalized()` 严格 ROI 校验和可选 debug 输出。

## Key 自检结果

评估脚本对自动发现的五张 Nut Key 样本使用用户确认的 `expectedCount=2` 作为一次性运行时配置；全部最终数量为 2，逐框几何/中心孔分数、角度和完整 boxes 见 `NUT_REFINEMENT_REPORT.md`。

| 文件 | detector | 结果 | 关键结果 |
|---|---|---|---|
| `thread_1.png` | ThreadPresenceDetector | `PASS` | 精修圆 `(43.93, 50.66, r20.11)`，score `0.5450` |
| `thread_2.png` | ThreadPresenceDetector | `PASS` | 精修圆 `(82.52, 94.47, r24.15)`，score `0.7049` |
| `nut_1.png` | NutPresenceDetector | `PASS` | `candidateCount=2`，主体恢复框，score `0.6945`，默认六边形角度 `0°`；全部 Nut Key 结果见 `NUT_REFINEMENT_REPORT.md` |
| `feature_1.png` | FeaturePresenceDetector | `PASS` | pHash、模板匹配、AKAZE/RANSAC 均有证据 |
| `feature_2.png` | FeaturePresenceDetector | `PASS` | pHash、模板匹配、AKAZE/RANSAC 均有证据 |

另有合成测试覆盖普通圆孔、模糊螺纹、螺母数量错误、未配置数量、无关图、无模板、无效图像和无效 ROI；特征多尺度测试验证了非原尺寸匹配框输出。

## DCIM 数据政策与评估结果

- `ground_truth.json`：30 张图片、120 个目标记录。
- 120/120 的 `label` 为 `unknown`、`roi` 为 `null`、`annotationStatus` 为 `UNANNOTATED`。
- 评估对 120 个 DCIM 目标全部记录 `SKIPPED`，没有读取图片执行无 ROI 搜索。
- `absentLabels=0`，没有生成 `absent` 标签。
- `accuracyMetricsComputed=false`，未报告准确率、召回率或混淆矩阵。
- ground truth 和总体评估状态均为 `INSUFFICIENT_DATA`。

因此，离线算法完成状态与数据评估状态保持分离：算法可以用 Key 自检，DCIM 仍不能作为有标签准确率数据集。

## 验证命令及真实结果

运行时均使用：`D:\ProgramData\anaconda3\envs\dinov2\python.exe`

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe -m unittest discover -s tools\feature_presence -p "test_*.py" -v
```

历史基线结果：`Ran 10 tests ... OK`，10/10 通过；Nut 角度微调后的专项为 `Ran 7 tests ... OK`，新增原图派生负样本专项 `4/4 OK`，此前完整验收命令为 `Ran 17 tests in 1188.689s — OK`；本轮未修改 Thread。

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\feature_presence\prepare_dataset.py --self-test
```

结果：`SELF_TEST_PASS`，退出码 0。

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\feature_presence\prepare_dataset.py
```

结果：Key 5/5 可读、DCIM 30/30 可读、状态 `INSUFFICIENT_DATA`，退出码 0。

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\feature_presence\evaluate_presence.py
```

结果：Key 自检 5/5 PASS；DCIM 120/120 `SKIPPED`；unknown 120；absent 0；未计算准确率；状态 `INSUFFICIENT_DATA`，退出码 0。

另外执行了源码语法检查：`python -m py_compile tools\feature_presence\presence_detectors.py`，通过。

## 已知限制

- 只有 5 张 Key 正样本，没有人工标注的 DCIM ROI、present/absent 标签，阈值未完成正式标定。
- 线程启发式对视角、反光、低清晰度和纹理缺失敏感；保守返回 `REVIEW` 是预期行为。
- 螺母轮廓受遮挡、重叠、强反光和非正视角影响；`expectedCount` 必须由业务配置提供。
- 特征匹配只搜索配置的有限尺度范围，并使用轻量 affine 一致性；不承担自动姿态对齐或通用目标检测。
- Key 自身模板比较只能证明离线链路可运行，不能证明现场准确率。

## App 集成延期

本轮不接入 Android App。禁止范围内的 App 7 个文件没有被删除，因为它们恢复时已经不存在；没有新建第二套 CameraX、没有修改 `app/src`、Gradle、Room、Kotlin 文件，也没有构建 APK 或执行 adb/真机验收。

App 集成及后续真机验收延期到用户明确安排的 Android 任务；当前状态保留为 `APP_INTEGRATION_DEFERRED`。

## 当前 Git 状态

本轮开始前工作区已经存在大量 Android、任务文档、样本和报告改动。本轮没有还原、覆盖或清理这些已有改动，也没有修改 `tasks/todo.md` 或 `tasks/plan.md`。当前 `git status --short` 中：

- 本轮离线源码和报告显示为未跟踪路径：`tools/feature_presence/`、`docs/reports/b3/feature_presence/`；
- `AGENTS.md`、`tasks/todo.md`、`tasks/plan.md`、`app/src/` 等既有改动仍保留；
- 本轮未产生根目录临时文件；诊断阶段创建的 `tmp_plain.png` 已删除；
- 未执行 `git reset`、`git checkout` 或 `git clean`；
- 未提交 Git commit。

最终保持：**OFFLINE_PROTOTYPE_COMPLETE / APP_INTEGRATION_DEFERRED**。
