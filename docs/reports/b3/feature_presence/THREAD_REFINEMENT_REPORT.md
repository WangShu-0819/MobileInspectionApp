# ThreadPresenceDetector Key 全量圆孔精修报告

## 结论

离线 Thread detector 已完成 Key 全量回归。自动发现 `Key/thread_*.png`、`*.jpg`、`*.jpeg` 共 16 张样本（编号不连续，缺少 thread_13），16/16 均为 `PASS`。最终 `boxes` 和 debug 绿色圆使用同一个 refined 圆。

Key 目录没有人工像素级圆心/半径标注，因此本报告不伪造相对真实圆的误差数值；“圆心误差不超过短边 3%、半径误差不超过真实半径 8%”仍标记为“待人工复核”。contact sheet 和逐张 debug 图用于人工检查覆盖范围。

## 误差来源分析

旧流程可能直接使用 Hough 排序结果，导致低阈值 Hough 产生的背景圆、外部台阶圆或邻近结构抢占最终结果。thread_2 的当前 raw seed 仍是偏大的 `(89.50,84.50,r44.90)`，但精修后选择 `(80.57,91.49,r27.72)`，说明 raw Hough 只负责提供候选，不能作为最终定位。

thread_1 的当前 raw Hough 圆为 `(46.20,52.20,r19.72)`，精修为 `(46.20,50.20,r19.93)`；thread_2 的半径由外圈候选收缩到真实螺纹孔尺度。thread_5 由旧批处理中明显偏大的外圈方向收缩为 `(608.73,594.02,r104.72)`，thread_6 保持在中心暗孔附近 `(718.19,743.09,r105.33)`。

## 实现

- 保留 legacy、fine、fine-low 三路 `HoughCircles`，但对候选排序、去重和 seed 选择做稳定化；Hough 只生成候选。
- 对每个 seed 做局部 x/y 圆心搜索、半径搜索、暗孔中心 proposal 和 fine sweep；固定 `cv2` 线程数以避免候选相同分数时的非确定性。
- 几何项包含圆周真实 Canny/梯度支持、角度覆盖、角度均匀性、径向一致性、径向离散度、环带梯度和背景惩罚。
- 暗孔项包含中心暗度、对比度、均匀性、面积、暗孔圆心偏移和 inner/outer 共心度。
- 纹理项包含径向强度周期性、变化次数、边缘/梯度峰、内圈角度覆盖、径向环覆盖、角度一致性、中心暗孔尺度和周期纹理证据门。
- 对圆心偏离暗孔、边缘角度集中、径向边缘离散、圆周穿过背景、非共心暗孔、plain hole、亮圆和模糊纹理分别保留或增加惩罚/状态门禁。
- 针对负样本鲁棒性增加集中配置门禁：背景穿越 `backgroundPenaltyMax=0.30`；缺少中心暗孔过渡时，要求足够的纹理振幅或明确的中心暗孔置信度/极性，否则只能 `REVIEW`。这两项用于拦截线性结构和随机噪声伪造的纹理证据，不依赖样本坐标。
- 所有新增搜索范围和阈值集中在 `THREAD_DEFAULTS`；`DetectionResult`、`boxes`、`metrics`、`debug_path` 和原配置参数接口保留。
- debug 图同时显示橙色 raw Hough 候选、绿色 refined 圆、蓝色圆心/内圈，以及 raw/refined 坐标、shift、`G/D/T/S/E/C` 分数。

## Thread Key 逐张结果

坐标、半径和 box 均为原图像素。`shift` 是 raw Hough 圆心到 refined 圆心的距离；`dr` 是 refined 半径减 raw 半径，不是相对真实标注误差。

| 文件 | 状态 | raw Hough `(x,y,r)` | refined `(x,y,r)` | shift / dr | box | score | G / D / T / S / A / AC |
|---|---|---|---|---:|---|---:|---:|
| `thread_1.png` | PASS | `(46.20,52.20,19.72)` | `(46.20,50.20,19.93)` | `2.00 / +0.21` | `[26,30,40,40]` | `0.648` | `0.58 / 0.57 / 0.82 / 1.00 / 1.00 / 0.06` |
| `thread_2.png` | PASS | `(89.50,84.50,44.90)` | `(80.57,91.49,27.72)` | `11.34 / -17.18` | `[53,64,55,55]` | `0.774` | `0.60 / 0.73 / 1.00 / 1.00 / 0.87 / 0.69` |
| `thread_3.jpg` | PASS | `(404.25,462.35,70.89)` | `(407.57,462.35,68.00)` | `3.32 / -2.89` | `[340,394,136,136]` | `0.597` | `0.63 / 0.60 / 0.85 / 0.24 / 1.00 / 0.00` |
| `thread_4.jpg` | PASS | `(1081.18,1110.23,104.59)` | `(1081.18,1110.23,125.51)` | `0.00 / +20.92` | `[956,985,251,251]` | `0.614` | `0.70 / 0.62 / 0.97 / 0.28 / 1.00 / 0.00` |
| `thread_5.jpg` | PASS | `(570.89,623.95,261.80)` | `(608.73,594.02,104.72)` | `48.24 / -157.08` | `[504,489,209,209]` | `0.673` | `0.54 / 0.80 / 0.87 / 0.79 / 0.74 / 0.54` |
| `thread_6.jpg` | PASS | `(723.72,743.09,81.09)` | `(718.19,743.09,105.33)` | `5.54 / +24.24` | `[613,638,211,211]` | `0.556` | `0.30 / 0.34 / 1.00 / 0.39 / 0.62 / 0.58` |
| `thread_7.jpg` | PASS | `(518.12,529.70,123.31)` | `(512.49,490.91,114.69)` | `39.20 / -8.62` | `[398,376,229,229]` | `0.649` | `0.61 / 0.63 / 0.93 / 0.17 / 0.65 / 0.63` |
| `thread_8.jpg` | PASS | `(678.59,603.88,89.65)` | `(686.72,599.53,101.60)` | `9.23 / +11.95` | `[585,498,203,203]` | `0.612` | `0.80 / 0.48 / 1.00 / 0.42 / 0.00 / 0.56` |
| `thread_9.jpg` | PASS | `(547.07,489.18,89.34)` | `(542.57,496.16,79.04)` | `8.30 / -10.30` | `[464,417,158,158]` | `0.657` | `0.66 / 0.61 / 1.00 / 0.50 / 0.51 / 0.36` |
| `thread_10.jpg` | PASS | `(690.51,751.40,268.46)` | `(691.99,686.79,215.00)` | `64.62 / -53.46` | `[477,472,430,430]` | `0.708` | `0.41 / 0.76 / 1.00 / 0.21 / 0.95 / 0.71` |
| `thread_11.jpg` | PASS | `(690.84,566.28,58.13)` | `(686.23,566.28,88.30)` | `4.61 / +30.17` | `[598,478,177,177]` | `0.561` | `0.51 / 0.46 / 0.94 / 0.13 / 0.28 / 0.71` |
| `thread_12.jpg` | PASS | `(790.14,624.09,69.74)` | `(739.28,629.62,113.36)` | `51.17 / +43.62` | `[626,516,227,227]` | `0.669` | `0.48 / 0.53 / 1.00 / 1.00 / 0.63 / 0.55` |
| `thread_14.jpg` | PASS | `(826.58,804.78,61.02)` | `(793.14,844.45,127.52)` | `51.88 / +66.50` | `[666,717,255,255]` | `0.640` | `0.75 / 0.62 / 1.00 / 0.39 / 1.00 / 0.00` |
| `thread_15.jpg` | PASS | `(963.56,786.10,54.17)` | `(922.12,744.67,86.73)` | `58.60 / +32.56` | `[835,658,173,173]` | `0.606` | `0.61 / 0.70 / 0.86 / 0.26 / 1.00 / 0.00` |
| `thread_16.jpg` | PASS | `(761.20,786.10,85.62)` | `(816.85,847.98,108.93)` | `83.22 / +23.32` | `[708,739,218,218]` | `0.638` | `0.71 / 0.55 / 0.95 / 0.72 / 0.34 / 0.35` |
| `thread_17.jpg` | PASS | `(486.86,513.85,142.01)` | `(491.01,610.31,147.13)` | `96.55 / +5.12` | `[344,463,294,294]` | `0.657` | `0.57 / 0.50 / 1.00 / 0.43 / 1.00 / 0.32` |

缩写：`G` 几何，`D` 暗孔，`T` 周期纹理，`S` 清晰度，`A` 中心暗孔尺度，`AC` 纹理角度一致性。完整 metrics 位于 `thread_key_results.json`。

## 漏检、误检和偏移审计

- 漏检：未发现。自动发现的 16 张 Thread Key 全部返回 `PASS`。
- 误检：未发现自动状态级误检；这些 Key 均为用户确认的 Thread 正样本，且当前 contact sheet 中绿色圆均覆盖对应螺纹孔。
- 明显偏移：contact sheet 中未发现绿色圆完全落到邻近结构的样本。`thread_10`、`thread_12`、`thread_14`、`thread_15`、`thread_16`、`thread_17` 的 raw→refined shift 较大或存在较大尺度变化，列为人工重点复核项；没有人工像素级标注，不能将这些审计项写成真实圆心/半径误差。

## 负样本鲁棒性

使用指定 Python 生成并检测 12 张通用合成负样本，覆盖普通圆孔、亮圆、非同心环、单段圆弧、径向边缘离散、背景圆环、模糊螺纹、普通垫圈、线性纹理、断裂圆环、随机噪声和径向辐条。负样本不使用 Key 坐标、固定 ROI 或固定半径。

| 文件 | 状态 | score | 判定原因 |
|---|---:|---:|---|
| `thread_negative_background_rings.png` | REVIEW | `0.250` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_blurred_thread.png` | REVIEW | `0.321` | circular evidence exists but image quality is low |
| `thread_negative_bright_circle.png` | REVIEW | `0.106` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_broken_ring.png` | REVIEW | `0.247` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_linear_texture.png` | REVIEW | `0.708` | candidate circle crosses unsupported background structure |
| `thread_negative_noise.png` | REVIEW | `0.639` | central dark aperture evidence is insufficient for thread texture |
| `thread_negative_nonconcentric_ring.png` | REVIEW | `0.113` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_plain_hole.png` | REVIEW | `0.179` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_plain_washer.png` | REVIEW | `0.404` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_radial_spokes.png` | REVIEW | `0.263` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_radial_spread.png` | REVIEW | `0.209` | plain circular aperture lacks repeated thread texture evidence |
| `thread_negative_single_arc.png` | REVIEW | `0.168` | plain circular aperture lacks repeated thread texture evidence |

结果为 `12/12` 未 `PASS`，没有负样本误 PASS。首轮误 PASS 的线性纹理和随机噪声已分别被背景穿越门禁、中心暗孔/纹理振幅门禁拦截。该结论只代表这组可复现合成负样本，不能替代更大规模真实现场负样本评估。

负样本逐张 debug 图片绝对路径目录：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\debug\`

负样本 contact sheet：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\negative_thread_contact_sheet.jpg`

负样本结构化结果（包含 raw Hough、refined 圆、box、shift/dr、各项分数及完整 metrics）：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\negative_thread_results.json`

### 原图派生“无螺纹孔”负样本

另外从自动发现的 16 张 Thread Key 原图逐张生成了对应负样本。生成过程使用该图检测得到的 source circle 作为动态锚点，只在目标圆内移除内侧螺纹纹理并以暗孔颜色填充；圆外像素保持不变，原始 Key 文件未被覆盖。该过程不使用固定坐标、固定 ROI、固定半径或样本特判。

生成参数已写入结构化结果：`removalMode=original-based-inner-thread-flat-dark-hole`、`removalRadiusRatio=0.99`、`blendRadiusRatio=0.06`、`fillPercentile=20`。16/16 的 `outsideRemovalMaskUnchanged` 为 true，表示“其他不变”检查通过。

这组负样本的检测结果为 **14/16 PASS、2/16 REVIEW**，因此不能判定为负样本鲁棒性通过。`thread_3.jpg`（0.547）和 `thread_11.jpg`（0.147）为 REVIEW；其余 14 张仍被 PASS。contact sheet 显示，许多 PASS 的绿色 refined 圆已经跳到原图中保留下来的邻近圆形结构，另有少数候选仍覆盖被处理目标附近的残余外圈。这暴露的是“全图搜索会把邻近结构当成 Thread”的真实误检风险，不是生成失败。

原图派生负样本逐张 debug 图片目录：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\original_based\debug\`

原图派生负样本 contact sheet：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\original_based\thread_original_based_negative_contact_sheet.jpg`

原图派生负样本结构化结果（包含生成完整性、source circle、raw Hough、refined 圆、box、shift/dr、各项分数及完整 metrics）：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\original_based\thread_original_based_negative_results.json`

原图派生负样本文件目录：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_thread\original_based\`

## Debug 产物

逐张 debug 图片绝对路径目录：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\thread_key\`

文件为 `thread_1.jpg`、`thread_2.jpg`、`thread_3.jpg`、`thread_4.jpg`、`thread_5.jpg`、`thread_6.jpg`、`thread_7.jpg`、`thread_8.jpg`、`thread_9.jpg`、`thread_10.jpg`、`thread_11.jpg`、`thread_12.jpg`、`thread_14.jpg`、`thread_15.jpg`、`thread_16.jpg`、`thread_17.jpg`。

批量 contact sheet：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\thread_key_contact_sheet.jpg`

批量结构化结果：

`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\thread_key_results.json`

## 测试

解释器：`D:\ProgramData\anaconda3\envs\dinov2\python.exe`

命令：

`D:\ProgramData\anaconda3\envs\dinov2\python.exe -m unittest discover -s tools\feature_presence -p "test_*.py" -v`

结果：`Ran 16 tests in 1170.588s`，`OK`，退出码 `0`。

覆盖内容：

- 全部 16 张 Thread Key 自动发现、PASS、圆内边界、半径合理、暗孔共心、boxes 一致性和双次可复现；
- thread_1/thread_2 保持 PASS；
- 合成螺纹精修；
- 普通圆孔、模糊螺纹、亮圆、非同心环均未 PASS；
- 12 张生成 Thread 负样本均未 PASS，包含线性纹理和随机噪声对抗样本；
- 既有 Nut/Feature 测试未修改且全量通过。

## DCIM 与范围

DCIM 仍保持 `unknown / ROI=null / UNANNOTATED / SKIPPED`，没有把无 ROI 的 DCIM 图片用于准确率计算，也没有改变官方标签。

本轮实际修改：

- `tools/feature_presence/presence_detectors.py`
- `tools/feature_presence/test_presence_detectors.py`
- `docs/reports/b3/feature_presence/THREAD_REFINEMENT_REPORT.md`
- 新生成 `evaluation/debug/thread_key/` 下 16 张 debug 图、contact sheet 和 `thread_key_results.json`
- 新生成 `evaluation/negative_thread/` 下 12 张负样本、12 张 debug 图、contact sheet 和 `negative_thread_results.json`
- 新生成 `evaluation/negative_thread/original_based/` 下 16 张原图派生无螺纹孔负样本、16 张 debug 图、contact sheet 和 `thread_original_based_negative_results.json`

未运行 Gradle、adb、APK、真机测试；未创建虚拟环境、site-packages 或依赖副本；未修改 NutPresenceDetector、FeaturePresenceDetector、App/Gradle/Kotlin 工程；未提交 Git。工作区中其他既有脏改动保持不变。
