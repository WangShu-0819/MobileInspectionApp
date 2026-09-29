# B3 ROI 相似度与照片配准离线实验

生成时间：2026-09-24T15:45:51+08:00。实验种子：`20260924`。

## 数据与标注

- 原图目录：`D:\study\Textile_defects\Wearable Inspection\Key_update_black`；清点 33 张 JPG（Nutsert: 6 | nut: 11 | thread: 16）。
- 对应标注目录：`D:\study\Textile_defects\Wearable Inspection\Key_update_black_label`；33/33 张原图有 YOLO 框标注，共 45 个框。逐文件尺寸、EXIF orientation、类别 ID 与框坐标见 [`dataset_inventory.csv`](dataset_inventory.csv)。
- 图片先按 EXIF 转成 upright；本批 orientation 均缺省，原图尺寸与 upright 尺寸相同。原图未写入或移动。ROI 相似度只在标注框内计算，填充区及配准插值边界由有效 mask 排除。
- 成对不同来源图像作为同类别/跨类别对照，不是缺陷、缺件负样本，也不用于误报率。数据没有 OK/NG、缺件/缺陷的人工标签。

## 主要结果

- 合成扰动 561 例；Homography 可估 561 例；完整门禁通过 538 例；有 H 但被门禁拒绝 23 例；无可用 H 0 例。
- 内点数中位数 155.0；内点比例中位数 0.9901；中位重投影误差 0.1971 px；空间覆盖中位数 0.7602。
- 配准前 ROI 灰度 SSIM 均值 0.4035；按估计 H 对齐后 0.9722；已知合成矩阵上限参照 0.9745。梯度 SSIM 均值依次为 0.2125、0.9375、0.9447。
- 估计对齐的有效全图重叠率中位数 0.9674；配准耗时中位数 57.0920 ms。
- 已知矩阵仅作合成数据上限参照；不同来源对照没有已知几何矩阵。估计 H 即使被 App 质量门禁拒绝，仍可用作诊断性相似度计算；这类分数不代表 App 会接受该配准。
- 本批 561 例均估出 H；23 例被原质量门禁拒绝：21 例越过 50 px 边界余量，2 例投影 ROI 面积小于 0.005；没有特征不足或 Homography 估计失败。
- 528 个不同来源对照中，同类别 190 对的状态为 {'SUCCESS': 53, 'FALLBACK_FULL_IMAGE': 11, 'FAILED': 126}；跨类别 338 对为 {'FAILED': 263, 'FALLBACK_FULL_IMAGE': 75}。这些只描述特征配准行为，不代表缺陷/缺件误报。

### 按扰动统计

误差和耗时列同时给出中位数 / P90；门禁成功率分母为该组全部图片。失败原因只列出非零项。

| Transform / intensity | Cases | H estimated | Gate pass | Corner error median / P90 px | Reprojection median / P90 px | Mean ROI SSIM pre → estimated → known | Overlap median / P90 | Time median / P90 ms | Failure reasons |
|---|---:|---:|---:|---:|---:|---|---:|---:|---|
| combined:moderate | 33 | 33 | 31 (93.9%) | 0.3779 / 0.9500 | 0.2336 / 0.3271 | 0.2525 → 0.9694 → 0.9715 | 0.8600 / 0.8637 | 56.5980 / 135.4584 | projected_corners_outside_50px_margin × 2 |
| identity:none | 33 | 33 | 33 (100.0%) | 0.0000 / 0.0000 | 0.0000 / 0.0000 | 1.0000 → 1.0000 → 1.0000 | 1.0000 / 1.0000 | 57.6490 / 157.9284 | 无 |
| perspective:light | 33 | 33 | 33 (100.0%) | 0.0877 / 0.2225 | 0.0687 / 0.0853 | 0.8139 → 0.9701 → 0.9703 | 0.9998 / 1.0000 | 57.4920 / 161.1492 | 无 |
| perspective:moderate | 33 | 33 | 33 (100.0%) | 0.1433 / 0.4007 | 0.1032 / 0.1378 | 0.5224 → 0.9663 → 0.9666 | 0.9997 / 0.9999 | 57.5230 / 136.6258 | 无 |
| perspective:strong | 33 | 33 | 33 (100.0%) | 0.1687 / 0.3886 | 0.1486 / 0.1945 | 0.3878 → 0.9652 → 0.9657 | 0.9996 / 0.9999 | 57.3980 / 139.3406 | 无 |
| rotation:10deg | 66 | 66 | 66 (100.0%) | 0.3117 / 0.6336 | 0.1453 / 0.1961 | 0.2984 → 0.9677 → 0.9687 | 0.9263 / 0.9268 | 57.8895 / 164.0730 | 无 |
| rotation:20deg | 66 | 66 | 60 (90.9%) | 0.4187 / 0.9822 | 0.2060 / 0.2888 | 0.2430 → 0.9669 → 0.9694 | 0.8762 / 0.8769 | 57.3195 / 160.1585 | projected_corners_outside_50px_margin × 6 |
| rotation:30deg | 66 | 66 | 54 (81.8%) | 0.4706 / 1.1746 | 0.2592 / 0.3658 | 0.2092 → 0.9657 → 0.9696 | 0.8449 / 0.8456 | 57.4260 / 164.5175 | projected_corners_outside_50px_margin × 12 |
| scale:0.85x | 33 | 33 | 31 (93.9%) | 0.4464 / 1.0649 | 0.5619 / 0.7136 | 0.2485 → 0.9512 → 0.9573 | 0.9991 / 1.0000 | 58.2070 / 137.4780 | projected_area_below_0.005 × 2 |
| scale:1.15x | 33 | 33 | 32 (97.0%) | 0.7771 / 1.6910 | 0.5993 / 0.7254 | 0.2646 → 0.9708 → 0.9759 | 0.7568 / 0.7579 | 56.8260 / 137.7276 | projected_corners_outside_50px_margin × 1 |
| shear_x:12pct | 66 | 66 | 66 (100.0%) | 0.2506 / 0.5639 | 0.2040 / 0.2587 | 0.4873 → 0.9839 → 0.9863 | 0.9698 / 0.9701 | 55.8405 / 181.6080 | 无 |
| shear_y:12pct | 66 | 66 | 66 (100.0%) | 0.2468 / 0.7004 | 0.2113 / 0.2804 | 0.4468 → 0.9830 → 0.9856 | 0.9700 / 0.9904 | 54.6275 / 166.5880 | 无 |

### 代表案例

- 成功：`nut_25.jpg` identity，状态 SUCCESS，平均角点误差 0 px；见 [`representative_success.png`](contact_sheets/representative_success.png)。
- 门禁拒绝：`Nutsert_1.jpg` 的 -30° 旋转仍估出 H，角点误差 0.81 px，但投影角点超过 50 px 边界余量，因此按 App 语义回退；无特征/无 H 失败案例未出现。失败 contact sheet 展示另一个同类边界案例 `Nutsert_6.jpg` +30°。

## App V4 对照与限制

- 离线实现按 App 配置使用 AKAZE M-LDB（threshold 0.002，4 octave、4 layer，response 前 1000）、Hamming BFMatcher、Lowe 0.75，以及 USAC_MAGSAC 的 8 px / 500 iter / 0.995。质量门禁按 App 的内点数 10、比例 0.30、重投影误差 8 px、inlier hull/bbox 覆盖 0.15、投影面积比 0.005–0.95、边界容差 50 px 检查；多个标注框逐框检查并要求全部通过。
- App 自定义 GMS 未在 Python 重现；OpenCV Python 与 Android/OpenCV 4.10.0 的运行时、USAC 实现和图像解码仍有差异。详见 `summary.json`，本实验不声称 Python 结果等同 Android V4。
- SSIM 用 OpenCV 高斯窗按 SSIM 定义实现；梯度 SSIM 在 Sobel 梯度幅值上计算。无新增依赖；没有从合成结果拟合或修改生产相似度阈值 0.50 / 候选阈值 0.05。
- 结论范围仅是已标注 ROI 在合成几何扰动下的配准和相似度变化。不能由此推断真实拍摄分布、业务误报率，也不支持把 NanoDet NG 自动翻为 OK。

## 案例与产物

- [`contact_sheets/representative_success.png`](contact_sheets/representative_success.png) 与 [`contact_sheets/representative_failure.png`](contact_sheets/representative_failure.png)：原图、变换图、估计配准图、叠加及差异热图。框为现有 YOLO 标注框。
- [`pair_results.csv`](pair_results.csv)：每个合成变换和不同来源图像对一行，包含几何、门禁、耗时及前/后/已知 H 相似度。
- [`summary.json`](summary.json)：分扰动统计、失败类型、不同来源配对结果、案例索引及环境信息。
- [`transform_manifest.jsonl`](transform_manifest.jsonl)：每例已知矩阵与参数；`artifacts/masks/` 中各 NPZ 保存已知矩阵、参数和有效像素 mask。

## Handback

- 新增离线脚本：[`tools/roi_similarity/evaluate_roi_similarity.py`](../../../../tools/roi_similarity/evaluate_roi_similarity.py)；新增产物均位于本报告目录。没有修改已跟踪的 App、模型、Room、Gradle、CameraX 或现有 FeaturePresenceDetector 文件。
- 环境：Python 3.9.23、OpenCV 4.13.0、NumPy 2.0.2、Pillow 11.1.0；未安装依赖。
- 最终 Git 状态：未提交；已跟踪的既有改动只有 `tasks/plan.md` 与 `tasks/todo.md`，保持原样；本次脚本和报告目录为未跟踪新增文件。

## 实际运行命令

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\roi_similarity\evaluate_roi_similarity.py --self-test
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\roi_similarity\evaluate_roi_similarity.py --dataset "D:\study\Textile_defects\Wearable Inspection\Key_update_black" --output "docs/reports/b3/roi_similarity" --seed 20260924
```

Git 未提交。未运行 Android/Gradle、ADB、instrumented、真机测试或 OCR。
