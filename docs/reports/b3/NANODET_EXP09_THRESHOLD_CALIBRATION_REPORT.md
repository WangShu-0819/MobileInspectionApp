# NanoDet exp09 四分类阈值校准与数据证据报告

日期：2026-09-20
状态：**THRESHOLD_ANALYSIS_COMPLETE**（离线分析，非现场校准）

---

## 1. 目标

对 exp09 四分类模型（nut=0, thread=1, bolt=2, nutsert=3）在冻结外部测试集上，按 5 个候选阈值（0.10, 0.15, 0.20, 0.25, 0.30）分别统计 nut/thread/bolt/nutsert 的 TP、FP、FN、precision 和 recall。结果用于评估 `0.20` 候选基线的合理性，不用于宣称最终现场阈值或泛化完成。

## 2. 数据来源

### 2.1 外部测试集

| 属性 | 值 |
|---|---|
| 图片数 | 6 |
| GT 对象总数 | 8 |
| GT 标注格式 | YOLO（class_id cx cy w h, normalized） |
| 图片目录 | `derived_data/external_test/` |
| 评估产出目录 | `external_test_eval_4class/` |

### 2.2 外部测试集图片与 GT 标注

| 图片 | GT 类别 | GT 数量 | 来源 |
|---|---|---|---|
| nut_26.jpg | nut (0) | 1 | Black Nut |
| nut_29.jpg | nut (0) | 1 | Black Nut |
| thread_21.jpg | thread (1) | 1 | Thread |
| thread_26.jpg | thread (1) | 1 | Thread |
| thread_34.jpg | thread (1) | 1 | Thread |
| Nutsert_6.jpg | nutsert (3) | 3 | Nutsert |
| **bolt** | — | **0** | **无真实标注** |

**关键限制**：
- bolt 在外部测试集中有 **0 个** GT 对象，无法计算 precision/recall
- 总共仅 6 张图片、8 个 GT 对象，样本量极小
- 所有外部测试图片来自 `D:\study\Textile_defects\Wearable Inspection\Key_update_black\`

### 2.3 Source Mapping

- `source_mapping_4class.json` 记录 8 个原始类别到 4 个目标类别的映射
- 6 个冻结外部样本 stems: `Nutsert_6`, `nut_26`, `nut_29`, `thread_21`, `thread_26`, `thread_34`
- 26 个 adaptation 训练样本 stems 分布在 nut/thread/nutsert 子组

### 2.4 Source Group 泄漏风险

`fixed_val_eval_4class`（固定验证集）的图片来自训练数据同源增强组。训练数据中存在 **16 个增强组** 跨越 train/val 边界（如 `img_bright__*` 同时出现在训练和验证子集中）。这意味着验证集上的 mAP=100% 可能过于乐观，不代表独立泛化能力。

外部测试集的 6 张图片来自不同拍摄批次（`Key_update_black`），与训练数据无直接源组重叠，因此外部集指标相对更可信，但样本量不足。

### 2.5 固定验证集（补充参考）

固定验证集 25 张图片在阈值 0.20 下：
- nut: GT=3, Pred=3, TP=3, FP=0, FN=0, P=1.000, R=1.000
- thread: GT=17, Pred=18, TP=17, FP=1, FN=0, P=0.944, R=1.000
- bolt: GT=10, Pred=10, TP=10, FP=0, FN=0, P=1.000, R=1.000
- nutsert: GT=0, Pred=0, TP=0, FP=0, FN=0, N/A

注意：固定验证集 nutsert 有 0 个 GT，而 bolt 有 10 个 GT（外部集则相反）。两个集互补但各自样本有限。

## 3. 外部测试集多阈值指标对比

### 3.1 nut（类别 0）— GT=2

| 阈值 | Pred | TP | FP | FN | Precision | Recall |
|---|---|---|---|---|---|---|
| 0.05 | 10 | 2 | 8 | 0 | 0.200 | 1.000 |
| **0.10** | 3 | 2 | 1 | 0 | 0.667 | 1.000 |
| 0.15 | 2 | 2 | 0 | 0 | **1.000** | **1.000** |
| **0.20** | 2 | 2 | 0 | 0 | **1.000** | **1.000** |
| 0.25 | 2 | 2 | 0 | 0 | **1.000** | **1.000** |
| **0.30** | 2 | 2 | 0 | 0 | **1.000** | **1.000** |

**阈值 0.10 误检**：thread_21.jpg 上出现 1 个 nut FP（score=0.129），IoU=0（非同位置）。
**阈值 0.05 额外误检**：nut_29.jpg（1 FP, score=0.093）、thread_26.jpg（3 FP, 最高 score=0.066）。
**阈值 ≥0.15**：所有误检均被消除，recall 保持 1.0。

### 3.2 thread（类别 1）— GT=3

| 阈值 | Pred | TP | FP | FN | Precision | Recall |
|---|---|---|---|---|---|---|
| 0.05 | 10 | 3 | 7 | 0 | 0.300 | 1.000 |
| **0.10** | 4 | 3 | 1 | 0 | 0.750 | 1.000 |
| 0.15 | 4 | 3 | 1 | 0 | 0.750 | 1.000 |
| **0.20** | 3 | 3 | 0 | 0 | **1.000** | **1.000** |
| 0.25 | 3 | 3 | 0 | 0 | **1.000** | **1.000** |
| **0.30** | 3 | 3 | 0 | 0 | **1.000** | **1.000** |

**阈值 0.10/0.15 误检**：nut_26.jpg 上出现 1 个 thread FP（score=0.177），该框位于图片顶部（可能为背景纹理）。IoU=0。
**阈值 ≥0.20**：该 FP 被消除，recall 保持 1.0。

### 3.3 bolt（类别 2）— GT=0

| 阈值 | Pred | TP | FP | FN | Precision | Recall |
|---|---|---|---|---|---|---|
| 0.05 | 4 | 0 | 4 | 0 | 0.000 | N/A |
| 0.10 | 0 | 0 | 0 | 0 | N/A | N/A |
| 0.15 | 0 | 0 | 0 | 0 | N/A | N/A |
| 0.20 | 0 | 0 | 0 | 0 | N/A | N/A |
| 0.25 | 0 | 0 | 0 | 0 | N/A | N/A |
| 0.30 | 0 | 0 | 0 | 0 | N/A | N/A |

**bolt 外部集无 GT 对象，无法评估 recall。** 阈值 0.05 有 4 个 bolt FP（nut_26×1, nut_29×1, Nutsert_6×2），均为同区域其他类别的跨类误报。阈值 ≥0.10 后 bolt FP 归零。

### 3.4 nutsert（类别 3）— GT=3

| 阈值 | Pred | TP | FP | FN | Precision | Recall |
|---|---|---|---|---|---|---|
| 0.05 | 10 | 3 | 7 | 0 | 0.300 | 1.000 |
| **0.10** | 4 | 3 | 1 | 0 | 0.750 | 1.000 |
| 0.15 | 3 | 3 | 0 | 0 | **1.000** | **1.000** |
| **0.20** | 3 | 3 | 0 | 0 | **1.000** | **1.000** |
| 0.25 | 3 | 3 | 0 | 0 | **1.000** | **1.000** |
| **0.30** | 3 | 3 | 0 | 0 | **1.000** | **1.000** |

**阈值 0.10 误检**：Nutsert_6.jpg 上出现 1 个 nutsert FP（score=0.108），IoU=0。
**阈值 ≥0.15**：所有误检被消除，recall 保持 1.0。

### 3.5 综合对比（排除 bolt）

| 阈值 | nut P | nut R | thread P | thread R | nutsert P | nutsert R | 总 FP |
|---|---|---|---|---|---|---|---|
| 0.05 | 0.200 | 1.000 | 0.300 | 1.000 | 0.300 | 1.000 | 26 |
| 0.10 | 0.667 | 1.000 | 0.750 | 1.000 | 0.750 | 1.000 | 3 |
| 0.15 | 1.000 | 1.000 | 0.750 | 1.000 | 1.000 | 1.000 | 1 |
| **0.20** | **1.000** | **1.000** | **1.000** | **1.000** | **1.000** | **1.000** | **0** |
| 0.25 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 0 |
| 0.30 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 0 |

**0.20 是能实现零 FP + 零 FN（对有 GT 的三类）的最低阈值。**

## 4. 漏检与误检分析

### 4.1 漏检（FN）

所有 6 个阈值下，nut、thread、nutsert 的 FN 均为 **0**。该模型在外部测试集上对已知 GT 对象的检出能力非常强（最低置信度的 TP 约 0.705 为 nut_26.jpg 上的 nut 检测）。

### 4.2 误检（FP）来源

| 阈值 | 类别 | 图片 | Score | IoU | 性质 |
|---|---|---|---|---|---|
| 0.05 | nut | nut_29.jpg | 0.093 | 0 | 同图低分噪声 |
| 0.05 | nut | thread_21.jpg | 0.129 | 0 | 跨类别误报 |
| 0.05 | nut | thread_26.jpg | 0.066 | 0 | 跨类别误报 |
| 0.05 | thread | nut_26.jpg | 0.177 | 0 | 背景纹理误报 |
| 0.05 | thread | nut_29.jpg | 0.053-0.061 | 0 | 低分噪声 |
| 0.05 | bolt | nut_26/nut_29/Nutsert_6 | 0.053-0.064 | 0 | 跨类别误报 |
| 0.05 | nutsert | nut_29.jpg | 0.057 | 0 | 跨类别误报 |
| 0.05 | nutsert | thread_34.jpg | 0.063 | 0 | 跨类别误报 |
| 0.05 | nutsert | Nutsert_6.jpg | 0.065-0.108 | 0 | 同图低分重复框 |
| 0.10 | nut | thread_21.jpg | 0.129 | 0 | 跨类别误报 |
| 0.10 | thread | nut_26.jpg | 0.177 | 0 | 背景纹理误报 |
| 0.10 | nutsert | Nutsert_6.jpg | 0.108 | 0 | 同图低分重复框 |

**特点**：所有 FP 的 IoU=0（不与任何 GT 重叠），score 均 <0.18，主要为低分跨类别噪声。阈值 ≥0.20 后全部消除。

## 5. Android NCNN Parity 证据

### 5.1 Parity 验证

`exp09_parity_results.json` 记录 2 张回归图的 PyTorch/ONNX/NCNN 三方对照：

| 比较 | max_abs | mean_abs | rmse |
|---|---|---|---|
| PT vs ONNX | 1.07e-05 | 2.26e-07 | 4.08e-07 |
| NCNN vs ONNX | 5.14e-06 | 2.57e-07 | 4.46e-07 |
| PT vs NCNN | 7.27e-06 | 2.72e-07 | 5.16e-07 |

NCNN 与 ONNX 输出高度一致（差异 <1e-5），匹配框 IoU ≥0.999。

### 5.2 回归图检测计数（NCNN，逐阈值）

**frame_00106_f1060.jpg**：

| 阈值 | nut | thread | bolt | nutsert |
|---|---|---|---|---|
| 0.05 | 0 | 1 | 0 | 1 |
| 0.10 | 0 | 0 | 0 | 1 |
| 0.15 | 0 | 0 | 0 | 0 |
| 0.20 | 0 | 0 | 0 | 0 |
| 0.25 | 0 | 0 | 0 | 0 |
| 0.30 | 0 | 0 | 0 | 0 |

**frame_00045_f450.jpg**：

| 阈值 | nut | thread | bolt | nutsert |
|---|---|---|---|---|
| 0.05 | 4 | 2 | 0 | 0 |
| 0.10 | 2 | 1 | 0 | 0 |
| 0.15 | 2 | 1 | 0 | 0 |
| 0.20 | 2 | 0 | 0 | 0 |
| 0.25 | 2 | 0 | 0 | 0 |
| 0.30 | 1 | 0 | 0 | 0 |

**观察**：
- frame_00106：thread 最高分约 0.056、nutsert 最高分约 0.122，≥0.15 后全部归零
- frame_00045：nut 最高分约 0.648（稳定保留），thread 最高分约 0.164（≥0.20 消失），第 2 个 nut 约 0.293（≥0.30 消失）
- PyTorch/ONNX/NCNN 在所有阈值下检测数量完全一致

## 6. 0.20 候选基线评估

### 6.1 优势

1. **零 FP + 零 FN**（对有 GT 的三类）：nut/thread/nutsert 在外部集上 precision=recall=1.000
2. **是最低零误检阈值**：0.15 仍有 1 个 thread FP，0.10 有 3 个 FP
3. **消除低分噪声**：所有 FP 的 score <0.18，0.20 阈值有足够安全裕度
4. **Android parity 一致**：NCNN 与 PyTorch 检测计数完全匹配

### 6.2 局限

1. **样本量极小**：仅 6 张外部测试图、8 个 GT 对象，统计置信度极低
2. **bolt 无外部 GT**：无法评估 bolt 的 recall 和 precision
3. **固定验证集存在 source group 泄漏**：16 个增强组跨 train/val 边界
4. **只有 1 类有多个 GT 对象**（nutsert=3），其余 2 类各仅 1-2 个 GT
5. **回归图检测稀疏**：2 张回归图在 0.20 下均无低分边界检测（frame_00045 的 thread 0.164 被排除）
6. **未覆盖现场条件变化**：光照、距离、遮挡、新零件类型等

### 6.3 结论

**0.20 只能作为阶段性候选基线**，不能宣称：
- 最终现场阈值
- 泛化能力确认
- bolt 检测可靠性
- 在更大/更多样数据集上的等效表现

## 7. 实际数据路径

| 路径 | 说明 |
|---|---|
| `exp09_retrain_nutsert_4class_baseline/` | 实验根目录 |
| `external_test_eval_4class/diagnostics.json` | 全量诊断数据（含所有阈值） |
| `external_test_eval_4class/score_01/threshold_report.json` | 阈值 0.10 报告 |
| `external_test_eval_4class/score_015/threshold_report.json` | 阈值 0.15 报告 |
| `external_test_eval_4class/score_02/threshold_report.json` | 阈值 0.20 报告 |
| `external_test_eval_4class/score_025/threshold_report.json` | 阈值 0.25 报告 |
| `external_test_eval_4class/score_005/threshold_report.json` | 阈值 0.05 报告（补充） |
| `derived_data/external_test/*.txt` | YOLO GT 标注 |
| `source_mapping_4class.json` | 四分类 source-to-target 映射（位于实验根目录） |
| `fixed_val_eval_4class/score_02/threshold_report.json` | 固定验证集 0.20 报告 |
| `android_export/exp09_parity_results.json` | Android parity 结果 |
| `experiment_report_4class.md` | 完整实验报告 |

阈值 0.30 的指标从 `diagnostics.json` 中 0.05 阈值的原始预测分数计算得出：保留每个类别 `prediction_details` 中 `score >= 0.30` 的预测，并沿用诊断数据的 `matched_iou50` 标记统计 TP/FP/FN。由于本数据集所有真实 TP 分数都高于 0.30，这一推导与 0.25 报告的三类结果一致。

## 8. 实际命令

```powershell
# 只读复算命令；不修改实验产物或 MobileInspectionApp。
$python = 'D:\ProgramData\anaconda3\envs\dinov2\python.exe'
$script = @'
import json
from pathlib import Path

root = Path(r"D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp09_retrain_nutsert_4class_baseline")
data = json.loads((root / "external_test_eval_4class" / "diagnostics.json").read_text(encoding="utf-8"))
source = data["thresholds_results"]["0.05"]["per_image"]
for name in ("nut", "thread", "bolt", "nutsert"):
    gt = sum(item["classes"][name]["gt"] for item in source)
    details = [
        pred
        for item in source
        for pred in item["classes"][name]["prediction_details"]
        if pred["score"] >= 0.30
    ]
    tp = sum(pred["matched_iou50"] for pred in details)
    fp = len(details) - tp
    fn = gt - tp
    precision = tp / len(details) if details else None
    recall = tp / gt if gt else None
    print(name, {"gt": gt, "pred": len(details), "tp": tp, "fp": fp, "fn": fn,
                 "precision": precision, "recall": recall})
'@
& $python -c $script
```

## 9. 未完成项

- [ ] bolt 独立外部测试集标注与评估（当前 bolt GT=0）
- [ ] 更大规模外部测试集（≥50 张/类）用于统计显著性
- [ ] Source group 泄漏修复后重跑验证集
- [ ] 现场条件变化（光照、距离、新零件）下的阈值鲁棒性测试
- [ ] 不同置信度区间的 ROC/PR 曲线分析
- [ ] Android 设备上实际推理延迟与阈值对帧率影响
- [ ] 0.20 作为生产阈值前的 A/B 现场对照
- [x] 数据与报告已由主协调审计；只提交本任务的三份文档，不纳入其他工作区改动

## 10. Git 收口范围

- 当前分支：`main`
- 审计基线：`1440dfe4`
- 本任务只涉及三份文档：本报告、`tasks/todo.md`、`tasks/plan.md`
- 不纳入生产代码、测试代码、模型资产或其他工作区改动；最终提交状态以根目录 Git 历史为准
