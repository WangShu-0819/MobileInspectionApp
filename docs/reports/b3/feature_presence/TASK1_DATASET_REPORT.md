# Presence Detection Task 1：数据清点、标记掩码和离线 manifest

- 生成时间：`2026-09-03T05:37:53Z`
- 总状态：`INSUFFICIENT_DATA`
- 脚本版本：`task1.v1`
- Python：`D:\ProgramData\anaconda3\envs\dinov2\python.exe`

## 结论

图片解码检查通过：Key 为 5/5，DCIM 为 30/30。DCIM 目录中的视频已排除。
当前没有人工确认的目标 ROI 和目标有无标签，因此 ground truth 全部为 `unknown`，本轮只能报告 `INSUFFICIENT_DATA`，不计算准确率、召回率或混淆矩阵。

## 输入与方向处理

- Key：`D:\study\Textile_defects\Wearable Inspection\Key`；图片数量 `5`，可读 `5`。
- DCIM：`D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM`；图片数量 `30`，可读 `30`。
- 排除的非图片文件：`2e0e5b7b6c79fb4f41f8b2158f71c627.mp4`。
- 方向策略：有效 EXIF Orientation 1～8 使用 `ImageOps.exif_transpose`；Orientation=0 是无效/未知元数据，明确记录 `orientationAssumption=NO_TRANSFORM`、`orientationAnomaly=true`，不称为正常方向。
- debug 合成图同时包含 raw source、normalized、HSV+dilate overlay 和 binary mask 四个面板；原始文件不被覆盖或改写。

## HSV 青绿色人工标记掩码

```json
{
  "colorSpace": "HSV",
  "hueRangeOpenCv": [
    75,
    105
  ],
  "saturationMin": 80,
  "valueMin": 50,
  "dilateKernel": [
    3,
    3
  ],
  "dilateIterations": 1,
  "meaning": "candidate_teal_manual_mark_only",
  "calibrationStatus": "INITIAL_CONFIGURABLE_MASK_NOT_DETECTOR_CALIBRATION"
}
```

掩码只用于排除人工标记，不能作为目标存在证据。当前参数是可配置的初始掩码，不代表正式检测器标定。

### 掩码覆盖汇总

- Key：原始掩码 `1.7616%`（均值），膨胀后 `2.2596%`（均值）。
- DCIM：原始掩码 `0.4549%`（均值），膨胀后 `0.5281%`（均值）。
- 每张图片的实际覆盖率和 debug 路径见下表及 `dataset_manifest.json`。

### Key 图片清点

| 文件 | 原始尺寸 | 归一化尺寸 | orientationRaw | anomaly | 原始掩码覆盖 | 膨胀后覆盖 | debug |
|---|---:|---:|---:|:---:|---:|---:|---|
| `feature_1.png` | 268×127 | 268×127 | None | true | 0.0206% | 0.0970% | `debug/key/feature_1__orientation_mask.jpg` |
| `feature_2.png` | 189×261 | 189×261 | None | true | 2.2441% | 2.8280% | `debug/key/feature_2__orientation_mask.jpg` |
| `nut_1.png` | 376×136 | 376×136 | None | true | 1.4882% | 2.0377% | `debug/key/nut_1__orientation_mask.jpg` |
| `thread_1.png` | 91×84 | 91×84 | None | true | 0.0262% | 0.1570% | `debug/key/thread_1__orientation_mask.jpg` |
| `thread_2.png` | 203×177 | 203×177 | None | true | 5.0291% | 6.1785% | `debug/key/thread_2__orientation_mask.jpg` |

### DCIM 图片清点

| 文件 | 原始尺寸 | 归一化尺寸 | orientationRaw | anomaly | 原始掩码覆盖 | 膨胀后覆盖 | debug |
|---|---:|---:|---:|:---:|---:|---:|---|
| `IMG_20260828_103109.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.5434% | 0.6568% | `debug/dcim/IMG_20260828_103109__orientation_mask.jpg` |
| `IMG_20260828_103112.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.4041% | 0.4539% | `debug/dcim/IMG_20260828_103112__orientation_mask.jpg` |
| `IMG_20260828_103115.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.4080% | 0.4611% | `debug/dcim/IMG_20260828_103115__orientation_mask.jpg` |
| `IMG_20260828_103118.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.5255% | 0.6118% | `debug/dcim/IMG_20260828_103118__orientation_mask.jpg` |
| `IMG_20260828_103122.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.4449% | 0.5236% | `debug/dcim/IMG_20260828_103122__orientation_mask.jpg` |
| `IMG_20260828_103127.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.5320% | 0.6111% | `debug/dcim/IMG_20260828_103127__orientation_mask.jpg` |
| `IMG_20260828_103130.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.6789% | 0.8023% | `debug/dcim/IMG_20260828_103130__orientation_mask.jpg` |
| `IMG_20260828_103134.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.8108% | 0.9271% | `debug/dcim/IMG_20260828_103134__orientation_mask.jpg` |
| `IMG_20260828_103138.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.2925% | 0.3244% | `debug/dcim/IMG_20260828_103138__orientation_mask.jpg` |
| `IMG_20260828_103145.jpg` | 4096×3072 | 4096×3072 | 0 | true | 1.0195% | 1.0747% | `debug/dcim/IMG_20260828_103145__orientation_mask.jpg` |
| `IMG_20260828_103149.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.9301% | 1.0194% | `debug/dcim/IMG_20260828_103149__orientation_mask.jpg` |
| `IMG_20260828_103156.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.4766% | 0.5375% | `debug/dcim/IMG_20260828_103156__orientation_mask.jpg` |
| `IMG_20260828_103203.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.4509% | 0.5584% | `debug/dcim/IMG_20260828_103203__orientation_mask.jpg` |
| `IMG_20260828_103205.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.6284% | 0.6794% | `debug/dcim/IMG_20260828_103205__orientation_mask.jpg` |
| `IMG_20260828_103209.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.3094% | 0.3411% | `debug/dcim/IMG_20260828_103209__orientation_mask.jpg` |
| `IMG_20260828_103218.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.5264% | 0.6019% | `debug/dcim/IMG_20260828_103218__orientation_mask.jpg` |
| `IMG_20260828_103223.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.3689% | 0.4260% | `debug/dcim/IMG_20260828_103223__orientation_mask.jpg` |
| `IMG_20260828_103228.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.3397% | 0.4128% | `debug/dcim/IMG_20260828_103228__orientation_mask.jpg` |
| `IMG_20260828_103231.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.5976% | 0.7203% | `debug/dcim/IMG_20260828_103231__orientation_mask.jpg` |
| `IMG_20260828_103241.jpg` | 4096×3072 | 4096×3072 | 0 | true | 0.0993% | 0.1512% | `debug/dcim/IMG_20260828_103241__orientation_mask.jpg` |
| `IMG_20260828_103308.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.0081% | 0.0110% | `debug/dcim/IMG_20260828_103308__orientation_mask.jpg` |
| `IMG_20260828_103313.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.1232% | 0.1443% | `debug/dcim/IMG_20260828_103313__orientation_mask.jpg` |
| `IMG_20260828_103318.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.2287% | 0.2806% | `debug/dcim/IMG_20260828_103318__orientation_mask.jpg` |
| `IMG_20260828_103322.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.2589% | 0.3000% | `debug/dcim/IMG_20260828_103322__orientation_mask.jpg` |
| `IMG_20260828_103326.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.3889% | 0.4887% | `debug/dcim/IMG_20260828_103326__orientation_mask.jpg` |
| `IMG_20260828_103331.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.1670% | 0.1985% | `debug/dcim/IMG_20260828_103331__orientation_mask.jpg` |
| `IMG_20260828_103335.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.3036% | 0.3535% | `debug/dcim/IMG_20260828_103335__orientation_mask.jpg` |
| `IMG_20260828_103340.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.5376% | 0.6102% | `debug/dcim/IMG_20260828_103340__orientation_mask.jpg` |
| `IMG_20260828_103344.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.5667% | 0.6853% | `debug/dcim/IMG_20260828_103344__orientation_mask.jpg` |
| `IMG_20260828_103354.jpg` | 3072×4096 | 3072×4096 | 0 | true | 0.6771% | 0.8774% | `debug/dcim/IMG_20260828_103354__orientation_mask.jpg` |

## ROI 与 ground truth 政策

- Key 图片本身就是已裁剪的 ROI 小图；对应模板记录使用 `roi: [0.0, 0.0, 1.0, 1.0]`、`roiSource: KEY_CROPPED_IMAGE`，不标记为 `UNANNOTATED`。
- DCIM 大图没有人工确认的目标位置；对应目标使用 `roi: null`、`annotationStatus: UNANNOTATED`。
- `imageBounds` 只表示整张已归一化图片的边界，不能替代 DCIM 的真实 target ROI。
- `nut_1.png` 只记录 `observedTemplateCount: 2`；`expectedCount` 保持 null 且由后续配置提供。
- 30 张 DCIM 图片的全部目标标签均为 `unknown`，不能自动生成 `absent`。
- `ground_truth.json` 当前状态：`INSUFFICIENT_DATA`。需要人工确认目标 ROI、present/absent 标签后，才能进行 holdout 评估。

## 输出文件

- `dataset_manifest.json`：输入清点、尺寸、方向元数据、imageBounds、目标配置、掩码参数和覆盖率。
- `ground_truth.json`：30 张 DCIM × 4 类目标的 unknown-only 结构。
- `debug/key/`、`debug/dcim/`：方向与青绿色掩码可视化。

## 未完成项

- DCIM 未进行人工目标 ROI 标注；Key 模板使用裁剪图全幅 ROI 约定。
- 未获得人工确认的 absent 样本，不能报告检测准确率。
- 三个 detector 在 `presence_detectors.py` 中独立运行；本数据准备脚本本身不扫描 DCIM，也不接入 Android。
