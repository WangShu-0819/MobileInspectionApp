# Feature Presence 离线数据与轻量检测器

本目录负责数据清点、图片方向处理、青绿色人工标记掩码、unknown-only
ground truth，以及不接入 Android 的三个轻量离线 detector。

- `ThreadPresenceDetector`：Hough 圆孔候选 + 圆度 + 中心暗孔 + 环/周期纹理证据。
- `NutPresenceDetector`：阈值/Canny 轮廓 + 近六边形几何 + 中心孔证据 + IoU 去重。
- `FeaturePresenceDetector`：DCT pHash 粗筛 + 多尺度 `matchTemplate` + AKAZE/BFMatcher Lowe ratio + affine RANSAC 一致性。

算法版本：`presence-offline.2`。阈值均可通过 detector 的 `config` 覆盖；这些启发式方法不替代后续经过标注数据标定的模型。

运行环境必须是：

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\feature_presence\prepare_dataset.py --self-test
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\feature_presence\prepare_dataset.py
```

默认读取：

- `D:\study\Textile_defects\Wearable Inspection\Key`
- `D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM`

默认输出到 `docs/reports/b3/feature_presence/`：

- `dataset_manifest.json`：文件清单、原始/归一化尺寸、方向元数据、`imageBounds`、Key 模板 ROI、目标 profile、HSV 掩码参数和覆盖率。
- `ground_truth.json`：每张 DCIM 图片的每类目标均为 `label: "unknown"`、`roi: null`、`annotationStatus: "UNANNOTATED"`。
- `TASK1_DATASET_REPORT.md`：数量、尺寸、方向异常、掩码覆盖和数据不足说明。
- `debug/key/`、`debug/dcim/`：每张图片的 raw source、normalized、HSV+dilate overlay 和 binary mask 四联 debug 图。

## 坐标和标签约定

Key 目录中的 5 张图片本身就是已经裁剪好的 ROI 小图。它们在模板 manifest 中使用整幅归一化 ROI：`roi: [0.0, 0.0, 1.0, 1.0]`、`roiSource: "KEY_CROPPED_IMAGE"`。这不是对 DCIM 大图的自动标注。

DCIM 大图当前没有人工目标 ROI。`imageBounds` 仅是整张归一化图片的边界，不是 target ROI；DCIM 未标注目标必须保持 `roi: null`、`annotationStatus: "UNANNOTATED"`。

允许标签只有 `present`、`absent`、`unknown`。没有人工确认的 DCIM 图片只能是 `unknown`，不参与准确率、召回率或混淆矩阵。`nut_1.png` 仅记录 `observedTemplateCount: 2`；`expectedCount` 保持可配置，不在 Task 1 中永久写死。

有效 EXIF Orientation 1～8 使用 Pillow `ImageOps.exif_transpose`。Orientation=0 是无效/未知元数据，记录：

```json
{
  "orientationRaw": 0,
  "orientationAssumption": "NO_TRANSFORM",
  "orientationAnomaly": true
}
```

原始输入文件不会被覆盖；debug 四联图同时保留 raw source 和归一化结果。

青绿色掩码在归一化 RGB 图上转换到 OpenCV HSV，使用 manifest 中的可配置范围，再做小范围膨胀。掩码只排除人工标记，不能被当作目标存在证据。
