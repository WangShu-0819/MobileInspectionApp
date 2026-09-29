# Black Thread 相似度遮挡压力实验

生成时间：2026-09-27。实验种子：`20260927`。

## 用途与试点候选

本轮对已有 Black Thread ROI 做同源几何配准和人工遮挡压力测试，选出一个供**人工监督现场试点**观察的灰度 SSIM 候选阈值：

> **Black Thread 灰度 SSIM ≥ 0.95：显示相似度辅助 OK。**

候选口径是模板映射后 ROI 核心有效像素的灰度 SSIM 均值；梯度 SSIM 同步记录作诊断。该候选只用于 Black Thread 的监督试点，不能视为已校准的通用业务阈值，也不能直接套用到白件、Nut、Nutsert、Bolt 或其他 ROI。NanoDet 的候选过滤阈值 `0.05`、模型建议阈值 `0.50` 和 NMS IoU `0.60` 均保持原值；`0.95` 是相似度灰度 SSIM 候选。

现场逻辑按以下规则试验：V4 配准门禁失败时不计算相似度，沿用全图检测；门禁通过且 NanoDet 判 NG（低于模型阈值或无检测）时才计算相似度。SSIM 达到 `0.95` 时显示相似度辅助 OK，低于该值时保留 NanoDet NG；最终仍由人工确认，不自动放行。`Key_role` 中的孔位图暂不能提供有标签的缺件对照，因此未用于阈值选择。

## 数据与方法

- 输入目录：`D:\study\Textile_defects\Wearable Inspection\Key_update_black`；当前清点为 33 张 JPG、33 个对应 YOLO 标签、45 个框，其中 16 个 `Black Thread` ROI 用于本实验。
- 每个 ROI 使用 `identity`、`perspective_moderate`、`combined_moderate` 三种查询图。清洁几何组共 48 个配准通过并评分的案例。
- 对查询图 ROI 分别覆盖 10%、25%、50%、75%、100% 的水平带；每个覆盖率测 top/center/bottom 三个位置。遮挡区域以 ROI 周边像素中位色填充，原始照片不修改。
- 所有样例先走离线 Python 配准质量门禁；门禁通过后才在模板映射 ROI 核心有效区域评分。遮挡后有 13 个案例未通过门禁，未计算相似度，符合回退逻辑。Python 复现未包含 App 自定义 GMS。
- 遮挡组共 720 个条件，707 个门禁通过并评分、13 个门禁拒绝。重复扰动来自相同的 16 个标注 ROI，不是独立现场样本。

## 分数响应

| ROI 遮挡面积 | 案例数 | 有效评分 | 门禁拒绝 | 灰度 SSIM 中位数 | SSIM 最大值 | ≥0.95 辅助 OK | ≥0.90 辅助 OK |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10% | 144 | 144 | 0 | 0.9036 | 0.9598 | 12 | 78 |
| 25% | 144 | 144 | 0 | 0.7755 | 0.8388 | 0 | 0 |
| 50% | 144 | 143 | 1 | 0.5516 | 0.6240 | 0 | 0 |
| 75% | 144 | 141 | 3 | 0.3314 | 0.4373 | 0 | 0 |
| 100% | 144 | 135 | 9 | 0.1639 | 0.2989 | 0 | 0 |

清洁几何组 SSIM 中位数为 `0.9651`、第 5 百分位为 `0.9519`；`0.95` 候选通过 46/48 个清洁组案例。≥75% 遮挡且门禁通过的 276 个案例中，最高 SSIM 为 `0.4373`，没有案例达到 `0.95`。10% 遮挡仍有 12/144 个案例达到候选值，说明轻微局部遮挡可能仍显示辅助 OK。

以上计数只表示本组人工遮挡和同源几何变化下的评分响应。它们不代表现场误报率、错误 OK 风险或真实缺件召回率。`Key_role` 现有 42 张 JPG，没有标签或可核验的同工位在位配对映射；抽查可见空孔近景和整件照片，但无法判断每个孔位是否本来就应安装零件。用户确认这些孔位的预期状态不确定，因此仅登记为后续探索素材，不计作负样本真值。

## 试点边界与后续

- `0.95` 只为 Black Thread 现场监督试点的起始候选。试点需记录同一物理零件/ROI 的 NanoDet 原结果、相似度分数、辅助显示结果、人工最终判断、V4 门禁结果、视角和批次；不得因本实验自动放行。
- 收集真实现场配对及人工真值后，按物理零件/批次/会话拆分校准集与独立留出集，再评估候选值并报告混淆矩阵和错误 OK 风险。若现场清洁样本低于 `0.95` 或真空位样本高于它，按留出验证结果调整，不用合成案例声称校准完成。
- exp23 白件、白件不支持的 BOLT/NUTSERT，以及 Black Nut/Nutsert/Bolt 均需独立样本与评分验证；本轮数值不可迁移。
- 离线实现的 Python OpenCV 版本为 4.13.0，App 目标版本为 OpenCV 4.10.0，且 Python 没有 App 自定义 GMS；Android V4 parity 尚未验证。

## 产物

- [`scores.csv`](scores.csv)：清洁组、遮挡比例/位置、门禁结果、灰度 SSIM 和梯度 SSIM。
- [`coverage_summary.csv`](coverage_summary.csv)：按遮挡面积汇总的评分、门禁和 `0.95`/`0.90` 通过数。
- [`threshold_sweep.csv`](threshold_sweep.csv)：阈值扫描结果。
- [`summary.json`](summary.json)：数据清点、方法口径和主要统计。
- 执行脚本：[`synthetic_occlusion_study.py`](../../../../../tools/roi_similarity/synthetic_occlusion_study.py)。

复现命令：

```powershell
& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\roi_similarity\synthetic_occlusion_study.py --dataset "D:\study\Textile_defects\Wearable Inspection\Key_update_black" --output "docs/reports/b3/roi_similarity/synthetic_occlusion_20260927" --seed 20260927
```
