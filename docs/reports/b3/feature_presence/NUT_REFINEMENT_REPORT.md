# NutPresenceDetector Nut Key 批量优化报告

## 状态

Nut Key 离线优化及主体六边形偏移微调已完成，当前实现保留 `NutPresenceDetector`、`DetectionResult`、`boxes`、`metrics`、`debug_path`、`expectedCount` 和现有配置接口。未运行 Gradle、adb、APK、真机或 DCIM 准确率评估；未提交 Git。

本轮自动发现规则为 `nut_*.png`、`nut_*.jpg`、`nut_*.jpeg`，没有依赖编号连续性。当前发现 5 个文件：`nut_1.png`、`nut_3.jpg`、`nut_4.jpg`、`nut_5.jpg`、`nut_6.jpg`。

## 根因与实现

审计确认原流程使用 CLAHE、Canny、Otsu/亮度阈值、轮廓近六边形判断、面积/aspect/fill/solidity、中心孔 Hough 证据、IoU NMS 和综合评分。Key 图中的外层高亮轮廓经常是垫圈，不是螺母主体；单一阈值还会把主体拆成亮面、侧面和局部轮廓，造成候选重复或只框局部。

本轮仅在 Nut 分支增加通用规则：

- 保留 Otsu，并使用集中配置的多亮度候选分支 `[180, 200, 220, 230, 240]`；Canny、CLAHE 和原有几何门禁继续参与评分；
- 使用 `RETR_TREE` 的嵌套轮廓和跨阈值包容关系寻找内部近六边形/侧面证据；用凸包和稳定顶点数恢复主体候选；
- 主体 box 使用外层组件的尺度约束和内部结构的尺度/中心，不使用 Key 文件坐标；轮廓 box 覆盖被接受的完整轮廓，而不是仅使用简化多边形顶点；
- 中心孔仍需要真实暗孔、环边缘和中心位置证据；没有中心孔的候选在默认 `holeReviewMin` 下被拒绝；
- IoU 与 containment NMS 仍同时保留，但强内部主体证据可以解释性地替换垫圈级轮廓；普通同类候选仍按分数保留较优者；
- 针对 Key 复核发现的偏移，默认六边形绘制角度恢复为稳定的 `0°` 主体几何先验；保留 `bodyHexAngleCandidates` 配置接口供明确姿态的调用方覆盖，不再让整幅边缘图把垫圈/背景边缘误选成 `-20°/20°` 主体边；主体 box 同时被限制在提供证据的组件内；
- debug 图绘制橙色原始候选、绿色 NMS 最终 box，并在下方面板逐候选输出 `C/G/H/box/source`，其中 source 明确记录阈值分支和 nested 类型。

没有通过降低 `geometryMin`、降低中心孔门槛或无条件提高 score 来掩盖误检；没有对任何 Nut 样本写固定坐标、固定矩形或样本特判。

## Nut Key 批量结果

`candidateCount` 是 IoU/containment NMS 后的最终数量；`candidateCountBeforeNms` 是全部候选分支合并后的数量。用户已确认五张 Nut Key 样本均包含 2 个螺母，因此批量回归统一使用 `expectedCount=2`；该配置只属于离线回归标签，不是 detector 的样本特判。

| 文件 | 状态 | score | before NMS | candidateCount | 最终 boxes（x,y,w,h） | 每框 score | 每框 G / H |
|---|---:|---:|---:|---:|---|---|---|
| `nut_1.png` | `PASS` | `0.6945` | 28 | 2 | `[[221,54,64,57],[65,57,69,62]]` | `0.7539 / 0.6351` | `0.6957/0.7930 ; 0.5798/0.7265` |
| `nut_3.jpg` | `PASS` | `0.5929` | 21 | 2 | `[[831,362,173,202],[302,394,227,192]]` | `0.5670 / 0.6187` | `0.5624/0.5556 ; 0.6639/0.5649` |
| `nut_4.jpg` | `PASS` | `0.5876` | 18 | 2 | `[[426,1034,298,323],[549,446,250,228]]` | `0.6591 / 0.5162` | `0.6227/0.6020 ; 0.5306/0.4829` |
| `nut_5.jpg` | `REVIEW` | `0.4785` | 19 | 2 | `[[900,396,156,153],[429,400,154,150]]` | `0.4998 / 0.4572` | `0.5356/0.4173 ; 0.4450/0.4486` |
| `nut_6.jpg` | `REVIEW` | `0.4598` | 24 | 2 | `[[312,406,171,166],[793,424,187,160]]` | `0.4744 / 0.4452` | `0.4622/0.5623 ; 0.4183/0.4870` |

所有 5 张图的最终框均为 2 个主体候选，`selectedBodyCount=2`，没有嵌套重复框。`nut_1.png` 的两个框分别位于图像左右两侧并覆盖两个完整螺母主体；框不再只覆盖外层垫圈或中心亮斑。当前 debug 的绿色主体六边形均使用 `A=0°`，避免 `nut_3/4/6` 被垫圈或背景边缘旋转拉偏。

### 优化前后

| 文件 | 优化前最终数 / before | 优化前主要问题 | 优化后最终数 / before | 结果 |
|---|---:|---|---:|---|
| `nut_1.png` | 2 / 15 | 框接近垫圈外轮廓 | 2 / 28 | 2 个主体框，稳定 0° 六边形，PASS |
| `nut_3.jpg` | 2 / 13 | 外层组件偏大 | 2 / 21 | 2 个主体框，稳定 0° 六边形，PASS |
| `nut_4.jpg` | 2 / 9 | 外层/局部轮廓竞争 | 2 / 18 | 2 个主体框，稳定 0° 六边形，PASS |
| `nut_5.jpg` | 2 / 11 | 亮面局部框，侧面不完整 | 2 / 19 | 主体恢复为 2 框并稳定角度，数量正确但质量 REVIEW |
| `nut_6.jpg` | 2 / 13 | 轮廓与背景候选竞争 | 2 / 24 | 2 个主体框并稳定角度、限制组件边界，数量正确但质量 REVIEW |

优化后 raw 候选数增加是因为显式保留了阈值分支和嵌套主体证据；NMS 将这些同一螺母的候选去重为 2 个最终框。此次偏移微调没有改变候选数量或 box 坐标，只取消了会追随垫圈/背景边缘的默认角度搜索；为防止回归，测试同时覆盖了稳定默认角度、嵌套重复、圆形、亮斑、垫圈、非六边形背景和无中心孔。

## 可视化产物

每张 debug 图的上部是原始 ROI 和候选框：橙色 `R` 为 NMS 前候选，绿色 `F` 为最终框；下部表格显示候选/最终分数、几何分数、中心孔分数、box 和阈值/来源分支。

- `D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_batch__nut_1.png.jpg`
- `D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_batch__nut_3.jpg.jpg`
- `D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_batch__nut_4.jpg.jpg`
- `D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_batch__nut_5.jpg.jpg`
- `D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_batch__nut_6.jpg.jpg`
- 已按本轮结果刷新用户点名的 `nut__nut_1.png.jpg`：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut__nut_1.png.jpg`
- 批量 contact sheet：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_key_batch_contact_sheet.jpg`
- 批量机器可读结果：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\debug\key\nut_key_batch_results.json`

## 负样本鲁棒性

在同一 detector 配置下生成 8 张通用负样本，覆盖普通圆形、单纯亮斑、金属高光、嵌套高光、非六边形背景、偏心孔、仅垫圈和无孔六边形。负样本没有写入任何 Nut Key 坐标或样本特判。

| 负样本 | candidateCountBeforeNms | candidateCount | boxes | 状态 |
|---|---:|---:|---|---|
| `nut_negative_bright_spot.png` | 0 | 0 | `[]` | `REVIEW`（expectedCount=0 的空结果不判 PASS） |
| `nut_negative_hex_no_hole.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_negative_metal_glare.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_negative_nested_glare.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_negative_nonhex_background.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_negative_offcenter_hole.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_negative_plain_circle.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_negative_washer_only.png` | 0 | 0 | `[]` | `REVIEW` |

因此负样本为 8/8 无候选、无最终框；`REVIEW` 是当前 `expectedCount=0` 空检测结果的既有状态语义，不代表误检通过。批量可视化和结果文件为：

- 负样本 contact sheet：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_nut\nut_negative_contact_sheet.jpg`
- 负样本机器可读结果：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_nut\nut_negative_results.json`
- 负样本 debug 图目录：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_nut\debug\`

### 原图派生无螺母零件负样本

另外从自动发现的 5 张 Nut Key 原图派生了 5 张无螺母零件图。每张图按正样本检测到的主体框自动推导完整可见螺母/垫圈组件移除区域，使用原图邻域修复；移除区域以外的原始像素逐张校验保持不变，没有加入固定坐标或样本特判。

| 来源 | 原图派生负样本 | candidateCountBeforeNms | candidateCount | boxes | 状态 |
|---|---|---:|---:|---|---|
| `nut_1.png` | `nut_no_nuts__nut_1.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_3.jpg` | `nut_no_nuts__nut_3.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_4.jpg` | `nut_no_nuts__nut_4.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_5.jpg` | `nut_no_nuts__nut_5.png` | 0 | 0 | `[]` | `REVIEW` |
| `nut_6.jpg` | `nut_no_nuts__nut_6.png` | 0 | 0 | `[]` | `REVIEW` |

5/5 原图派生负样本均无候选、无最终框。`expectedCount=0` 下的空结果保持 detector 既有 `REVIEW` 语义，未伪造为 PASS。

- 原图派生负样本目录：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_nut\original_based\`
- 原图派生负样本 contact sheet：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_nut\original_based\nut_original_based_negative_contact_sheet.jpg`
- 原图派生负样本结果：`D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\docs\reports\b3\feature_presence\evaluation\negative_nut\original_based\nut_original_based_negative_results.json`
- 每张原图派生负样本的 detector debug 图位于上述目录的 `debug\` 子目录。

## 测试与门禁

使用唯一指定解释器：`D:\ProgramData\anaconda3\envs\dinov2\python.exe`。

- Nut 专项新增原图派生负样本回归：`4/4 PASS`；包含 5 张原图派生负样本、8 张生成负样本、圆形/亮斑/垫圈/非六边形/无中心孔误检集合，以及 Key 数量、稳定默认角度、NMS 和 debug 审计回归。此前 Nut 专项 `7/7 PASS`、完整验收 `17/17 PASS`；Key 的 `nut_5/6` 保持真实质量状态 `REVIEW`，没有通过抬高 score 掩盖低置信度；
- 完整验收命令已执行：`Ran 17 tests in 1188.689s — OK`。Nut、Feature 和 Thread 全部通过；本轮没有修改 Thread；
- 未创建虚拟环境、`site-packages` 或依赖副本；未运行 Gradle、adb、APK、真机测试；未提交 Git。

## DCIM 边界

官方 DCIM 标签和评估边界保持不变：无 ROI 的 DCIM 图片仍为 `unknown`、`ROI=null`、`UNANNOTATED`、`SKIPPED`，没有用于 Nut 准确率计算，也没有改写官方标签。

## 实际修改文件

- `tools/feature_presence/presence_detectors.py`
- `tools/feature_presence/test_presence_detectors.py`
- `tasks/todo.md` — 回填本轮 Nut 子任务状态及完整门禁阻塞项
- `docs/reports/b3/feature_presence/OFFLINE_PROTOTYPE_REPORT.md` — 更新 Nut 结果和报告索引
- `docs/reports/b3/feature_presence/NUT_REFINEMENT_REPORT.md`
- 由本轮离线批量生成的 5 张 Nut debug 图、Nut Key contact sheet 和 `nut_key_batch_results.json`
- `docs/reports/b3/feature_presence/evaluation/negative_nut/` — 8 张负样本、8 张 debug 图、负样本 contact sheet 和 `nut_negative_results.json`

既有无关脏改动均保留；本轮未提交 Git。
