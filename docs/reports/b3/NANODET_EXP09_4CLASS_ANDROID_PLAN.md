# NanoDet exp09 四分类 Android 现状审计报告

状态：**Task 2B Android 34→36 协议升级完成**（2026-09-20）

证据来源说明：
- Task 2A 桌面 parity 使用 yolov12 环境执行（Python 3.9.23），结果有效；
- Task 2A NCNN 转换使用 ncnn_py311 环境执行，结果有效；
- 这是用户授权的环境例外，不得写成 dinov2 复核通过。
- Task 2B Android 34→36 协议升级已完成（2026-09-20），JNI、decoder、contract 和测试均已更新。
- Android parity 2026-09-20 通过（NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4，设备 YAL-AL10）。

---

## 一、审计结论摘要

当前 Android NanoDet 实现完全基于**二分类协议**（output_width=34），所有源码、JNI、decoder、测试和 ROI 路由均硬编码为 `nut`(0) / `thread`(1)。升级到 exp09 四分类（output_width=36）需要**同步修改12个源码/测试文件 + 2个C++文件 + 替换模型资产 + 新增 exp09 ONNX/NCNN 转换产物**。

> **历史快照（Task 2B 实施前）**：上述审计结论描述的是 2026-09-20 Task 2B 执行前的代码状态。Task 2B 协议升级已完成，Android parity 已通过，34→36 升级不再阻塞。

**关键阻塞项（历史快照，已部分解除）**：exp09 Task 2A 开始前无 ONNX/NCNN 转换产物。截至 2026-09-20，ONNX 和 NCNN `.param/.bin` 已由 yolov12 环境生成并通过桌面 parity；dinov2 环境证据补正因缺少 torch/onnx/onnxruntime 被阻塞（见第十五节）。Android 34→36 协议升级和 Android parity 已于 2026-09-20 完成并通过。

> **历史快照注释**：本段描述 Task 2B 实施前的阻塞状态。34→36 升级和 Android parity 已完成；dinov2 环境补正仍被阻塞（用户已授权采用 yolov12 环境产物，不得写成 dinov2 复核通过）。

---

## 二、当前34列协议完整来源链

### 2.1 常量定义层

| 文件 | 行号 | 常量/值 | 说明 |
|---|---|---|---|
| [NanoDetInferenceModels.kt:10](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt#L10) | 10 | `OUTPUT_WIDTH = 34` | 全局输出宽度常量 |
| [NanoDetInferenceModels.kt:11](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt#L11) | 11 | `OUTPUT_HEIGHT = 3598` | anchor 数 = Σ(⌈416/s⌉²) for s∈{8,16,32,64} |
| [NanoDetInferenceModels.kt:77](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt#L77) | 77 | `outputShape: String = "[3598,34]"` | 结果数据类默认值 |
| [nanodet_ncnn_jni.cpp:11](app/src/main/cpp/nanodet_ncnn_jni.cpp#L11) | 11 | `kOutputWidth = 34` | 生产 JNI 输出宽度 |
| [nanodet_ncnn_jni.cpp:12](app/src/main/cpp/nanodet_ncnn_jni.cpp#L12) | 12 | `kOutputHeight = 3598` | 生产 JNI 输出高度 |
| [ncnn_smoke_jni.cpp:11](app/src/androidTest/cpp/ncnn_smoke_jni.cpp#L11) | 11 | `kOutputWidth = 34` | 冒烟测试 JNI |
| [ncnn_smoke_jni.cpp:12](app/src/androidTest/cpp/ncnn_smoke_jni.cpp#L12) | 12 | `kOutputHeight = 3598` | 冒烟测试 JNI |
| [NcnnRuntimeSmokeInstrumentedTest.kt:282](app/src/androidTest/java/com/wearable/inspection/mobile/ncnn/NcnnRuntimeSmokeInstrumentedTest.kt#L282) | 282 | `OUTPUT_WIDTH = 34` | 冒烟测试 Kotlin |

### 2.2 列布局含义

34 = **2 分类列** + **4×8 DFL 回归列**

| 列范围 | 含义 | 当前处理 |
|---|---|---|
| `[0..1]` | nut / thread sigmoid 分数 | 二选一 argmax |
| `[2..9]` | 左距离 8-bin DFL logits | softmax → 期望值 |
| `[10..17]` | 上距离 8-bin DFL logits | softmax → 期望值 |
| `[18..25]` | 右距离 8-bin DFL logits | softmax → 期望值 |
| `[26..33]` | 下距离 8-bin DFL logits | softmax → 期望值 |

### 2.3 Decoder 层

| 文件 | 行号 | 硬编码 | 说明 |
|---|---|---|---|
| [NanoDetOutputDecoder.kt:10](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt#L10) | 10 | `classNames = arrayOf("nut", "thread")` | 2元素类别名 |
| [NanoDetOutputDecoder.kt:20](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt#L20) | 20 | `require(output.size == OUTPUT_WIDTH * OUTPUT_HEIGHT)` | 校验 `34×3598` |
| [NanoDetOutputDecoder.kt:26](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt#L26) | 26 | `Array(2) { mutableListOf() }` | NMS 按2类分组 |
| [NanoDetOutputDecoder.kt:38](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt#L38) | 38 | `if (output[row + 1] > output[row]) 1 else 0` | **二值硬编码 argmax** |
| [NanoDetOutputDecoder.kt:44](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt#L44) | 44 | `base = row + 2 + side * (REG_MAX + 1)` | DFL 起始于第2列 |

### 2.4 JNI 输出校验层

**生产 JNI** ([nanodet_ncnn_jni.cpp:96-99](app/src/main/cpp/nanodet_ncnn_jni.cpp#L96-L99))：
```cpp
if (output.dims != 2 || output.w != kOutputWidth || output.h != kOutputHeight ||
    output.elemsize != sizeof(float)) {
    throw_illegal_state(env, "Unexpected NCNN output shape or element size");
```
**冒烟 JNI** ([ncnn_smoke_jni.cpp:83-86](app/src/androidTest/cpp/ncnn_smoke_jni.cpp#L83-L86))：相同校验。

四分类模型输出 `[3598,36]` 时会**直接被 JNI 拒绝**。

### 2.5 类别路由层

| 文件 | 行号 | 路由 | 说明 |
|---|---|---|---|
| [NanoDetInferenceModels.kt:102-106](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt#L102-L106) | 102-106 | `NUT→0, THREAD→1, FEATURE→null, null→null` | `classIndex()` 映射 |
| [NanoDetInferenceModels.kt:117-119](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt#L117-L119) | 117-119 | `FEATURE → FEATURE_UNSUPPORTED` | 不执行检测 |
| [NanoDetRoiInferenceService.kt:42-61](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetRoiInferenceService.kt#L42-L61) | 42-61 | `null→ROI_NOT_CONFIGURED, FEATURE→FEATURE_UNSUPPORTED, else→推理` | 按 targetType 路由 |

### 2.6 模型资产

| 资产 | 路径 | 大小 | SHA-256 |
|---|---|---|---|
| param | `app/build/generated/nanodet/main/assets/nanodet/nanodet.ncnn.param` | 24,308 bytes | `AD45E2F3FCB6777A5E924AA9A3095C23B2C5F900632720C389D7816A1A390BDD` |
| bin | `app/build/generated/nanodet/main/assets/nanodet/nanodet.ncnn.bin` | 5,002,704 bytes | `38F67190D669C9F047546F1B34DF541704E9A20429C4921182DDA8085E8F54E3` |

SHA-256 与 [NanoDetInferenceModels.kt:14-15](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt#L14-L15) 中的 `PARAM_SHA256` / `MODEL_SHA256` 完全一致。

模型 VERSION：`nanodet-ncnn-20260526-opt2`（PNNX 20260526，FP32，optlevel=2）。

---

## 三、exp09 四分类目标协议核对

### 3.1 训练配置

来源：`preflight_4class.json`

| 项目 | 值 |
|---|---|
| class_names | `["nut", "thread", "bolt", "nutsert"]` |
| num_classes | 4 |
| strides | `[8, 16, 32, 64]` |
| decouple_reg | true |
| forward_input_shape | `[1,3,160,416]`（非标准，preflight 测试用） |
| forward_output_shape | `[1,1386,36]`（160×416输入时的anchor数） |

### 3.2 四分类输出 shape 推导

| 项目 | 当前二分类 | exp09 目标 |
|---|---|---|
| ONNX 输出 | `[1,3598,34]` | `[1,3598,36]` |
| NCNN 输出 | `[3598,34]`（dims=2, w=34, h=3598） | `[3598,36]`（dims=2, w=36, h=3598） |
| 分类列数 | 2（nut, thread） | 4（nut, thread, bolt, nutsert） |
| DFL 列数 | 4×8=32 | 4×8=32（不变） |
| 总列数 | 2+32=34 | 4+32=36 |

### 3.3 四分类类别顺序

| 索引 | 类别 | 中文 | RoiTargetType 映射 |
|---:|---|---|---|
| 0 | nut | 螺母 | `NUT` |
| 1 | thread | 螺纹 | `THREAD` |
| 2 | bolt | 螺栓 | `BOLT`（新增） |
| 3 | nutsert | 铆螺母 | `NUTSERT`（新增） |

### 3.4 exp09 转换产物状态

| 产物 | 状态 | 说明 |
|---|---|---|
| model_best.ckpt | ✅ 存在 | 69,765,750 bytes，SHA-256 `256f1561...` |
| nanodet_model_best.pth | ✅ 存在 | 17,525,173 bytes，SHA-256 `92c2a0d6...` |
| ONNX 转换 | ❌ 不存在 | 需从 .pth 用 torch.onnx.export 生成 |
| NCNN .param/.bin | ❌ 不存在 | 需从 ONNX 用 PNNX 转换 |

### 3.5 exp09 训练证据（外部集 @ 阈值 0.20）

| 类别 | Recall | Precision | 漏检 | 误检 | 备注 |
|---|---|---|---|---|---|
| nut | 1.00 | 1.00 | 0 | 0 | 2 张图 |
| thread | 1.00 | 1.00 | 0 | 0 | 3 张图 |
| bolt | NA | NA | NA | NA | 无真实标注 |
| nutsert | 1.00 | 1.00 | 0 | 0 | 3 张图 |

⚠️ 仅 6 张外部图，bolt 无 GT，source group 泄漏风险存在。`0.20` 仅为候选基线。

---

## 四、发现的全部问题

### 4.1 需要修改的源码文件（生产代码）

| # | 文件 | 修改内容 | 严重性 |
|---|---|---|---|
| 1 | [NanoDetInferenceModels.kt](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt) | `OUTPUT_WIDTH=34→36`，`VERSION` 更新，`PARAM_SHA256`/`MODEL_SHA256` 更新，`outputShape` 默认值 `"[3598,36]"`，`classIndex()` 增加 `BOLT→2, NUTSERT→3` | **阻塞** |
| 2 | [NanoDetOutputDecoder.kt](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt) | `classNames` 扩展为4元素，`Array(2)` → `Array(classNames.size)`，二值 argmax → 通用 argmax over N 列 | **阻塞** |
| 3 | [nanodet_ncnn_jni.cpp](app/src/main/cpp/nanodet_ncnn_jni.cpp) | `kOutputWidth=34→36` | **阻塞** |
| 4 | [RoiTargetType.kt](app/src/main/java/com/wearable/inspection/mobile/data/entity/RoiTargetType.kt) | 新增 `BOLT("螺栓")` 和 `NUTSERT("铆螺母")` | **阻塞** |
| 5 | [ViewConfirmationViewModel.kt](app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt) | 行529-533 硬编码 `when(index) { 0->"NUT", 1->"THREAD" }` 需增加 `2->"BOLT", 3->"NUTSERT"` | 中 |
| 6 | [NanoDetRoiInferenceService.kt](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetRoiInferenceService.kt) | `when(targetType)` 的 else 分支已覆盖 BOLT/NUTSERT，但需确认 `classIndex()` 返回值不为 null（修复1后自动解决） | 低 |

### 4.2 需要修改的测试文件

| # | 文件 | 修改内容 |
|---|---|---|
| 7 | [NanoDetInferenceContractTest.kt](app/src/test/java/com/wearable/inspection/mobile/detection/NanoDetInferenceContractTest.kt) | `classIndex` 断言增加 BOLT→2, NUTSERT→3；candidate helper 扩展4类；decoder 测试适配36列 |
| 8 | [NanoDetRoiInferenceServiceTest.kt](app/src/test/java/com/wearable/inspection/mobile/detection/NanoDetRoiInferenceServiceTest.kt) | 增加 BOLT/NUTSERT 的路由测试 |
| 9 | [ncnn_smoke_jni.cpp](app/src/androidTest/cpp/ncnn_smoke_jni.cpp) | `kOutputWidth=34→36` |
| 10 | [NcnnRuntimeSmokeInstrumentedTest.kt](app/src/androidTest/java/com/wearable/inspection/mobile/ncnn/NcnnRuntimeSmokeInstrumentedTest.kt) | `OUTPUT_WIDTH=34→36`，`CLASS_NAMES` 扩展4元素，`3598*34` → `3598*36`，argmax 逻辑扩展，阈值计数扩展 bolt/nutsert |
| 11 | [NanoDetRoiRuntimeInstrumentedTest.kt](app/src/androidTest/java/com/wearable/inspection/mobile/detection/NanoDetRoiRuntimeInstrumentedTest.kt) | `CLASS_NAMES` 扩展，`outputShape` 断言更新，threshold 计数扩展 bolt/nutsert |
| 12 | [RoiTargetTypeTest.kt](app/src/test/java/com/wearable/inspection/mobile/data/entity/RoiTargetTypeTest.kt) | 枚举数量 3→5，新增 BOLT/NUTSERT 的 displayName/fromName 测试 |
| 13 | [ViewConfirmationModelResultTest.kt](app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationModelResultTest.kt) | `outputShape` 断言 `"[3598,34]"` → `"[3598,36]"` |

### 4.3 ROI 属性与路由问题

| 问题 | 当前状态 | 影响 |
|---|---|---|
| `RoiTargetType` 仅3个值 | `THREAD, NUT, FEATURE` | BOLT/NUTSERT 无法选择、无法路由 |
| targetType 存储为 TEXT | Room `TEXT` 列，`valueOf()` 解析 | **不需要 DB migration**，新枚举值自动兼容 |
| 模板导入导出 | 透传原始字符串，无校验 | 旧模板含 BOLT/NUTSERT 可正确导入导出 |
| UI 显示 | `fromName()?.displayName ?: "未选择"` | 新枚举加 displayName 后自动生效 |
| `ViewConfirmationScreen` | `fromName(roi.targetType)?.displayName ?: "未配置"` | 同上 |
| 默认 fallback | 无默认映射到 NUT/THREAD 或 NG | ✅ 安全，不会误判 |
| FEATURE 状态 | `FEATURE_UNSUPPORTED`，不执行检测 | 保持不变 |
| 按展示文本判断 | 未发现 | ✅ 安全 |

### 4.4 模型资产问题

| 问题 | 说明 |
|---|---|
| exp09 无 ONNX | 需从 `nanodet_model_best.pth` 导出 `[1,3,416,416]` → `[1,3598,36]` |
| exp09 无 NCNN | 需从 ONNX 用 PNNX 20260526 转换，optlevel=2 |
| 当前二分类模型 | SHA-256 与代码中的常量一致，运行正常 |
| 模型 asset 路径 | `nanodet/nanodet.ncnn.param` 和 `nanodet/nanodet.ncnn.bin`，通过 `ensureModel()` 从 assets 复制到 `filesDir/models/{VERSION}/` |

---

## 五、后续最小实现文件清单

### Phase 1：模型转换与 parity（不修改 MobileInspectionApp）

| 步骤 | 输入 | 输出 | 证据 |
|---|---|---|---|
| 1.1 PyTorch → ONNX | `nanodet_model_best.pth` | `exp09_4class.onnx` | 输入/输出名称、shape、SHA-256 |
| 1.2 ONNX → NCNN | `exp09_4class.onnx` | `exp09_4class.ncnn.param/.bin` | PNNX 版本、optlevel、SHA-256 |
| 1.3 桌面 parity | 相同输入张量 | PyTorch/ONNX/NCNN 输出 JSON | 最大绝对差、类别/框一致性 |

### Phase 2：Android 协议升级

| # | 文件 | 类型 |
|---|---|---|
| 1 | `detection/NanoDetInferenceModels.kt` | 生产 |
| 2 | `detection/NanoDetOutputDecoder.kt` | 生产 |
| 3 | `main/cpp/nanodet_ncnn_jni.cpp` | 生产 C++ |
| 4 | `data/entity/RoiTargetType.kt` | 生产 |
| 5 | `ui/screens/ViewConfirmationViewModel.kt` | 生产 |
| 6 | `test/.../NanoDetInferenceContractTest.kt` | 测试 |
| 7 | `test/.../NanoDetRoiInferenceServiceTest.kt` | 测试 |
| 8 | `test/.../RoiTargetTypeTest.kt` | 测试 |
| 9 | `test/.../ViewConfirmationModelResultTest.kt` | 测试 |
| 10 | `androidTest/cpp/ncnn_smoke_jni.cpp` | 测试 C++ |
| 11 | `androidTest/.../NcnnRuntimeSmokeInstrumentedTest.kt` | 测试 |
| 12 | `androidTest/.../NanoDetRoiRuntimeInstrumentedTest.kt` | 测试 |

### Phase 3：模型资产替换

| 资产 | 操作 |
|---|---|
| `assets/nanodet/nanodet.ncnn.param` | 替换为 exp09 产物 |
| `assets/nanodet/nanodet.ncnn.bin` | 替换为 exp09 产物 |
| `NanoDetModelContract.VERSION` | 更新版本字符串 |
| `NanoDetModelContract.PARAM_SHA256` | 更新为新 param 的 SHA-256 |
| `NanoDetModelContract.MODEL_SHA256` | 更新为新 bin 的 SHA-256 |

### Phase 4：验证与回归

- JVM 测试：contract/decoder/路由/兼容
- Android parity：同输入比较桌面 NCNN 与 Android NCNN
- Instrumented 测试：加载、推理、4类检测
- compile/assemble/APK 构建
- 前序功能回归矩阵

---

## 六、测试命令和真实结果

- JVM 测试：**本阶段未运行**
- Instrumented 测试：**本阶段未运行**
- APK：**本阶段未构建**

---

## 七、模型文件、NCNN 文件和已有 APK

### 当前二分类模型资产

| 文件 | 路径 | 大小 | 时间 | SHA-256 |
|---|---|---|---|---|
| param | `app/build/generated/nanodet/main/assets/nanodet/nanodet.ncnn.param` | 24,308 B | 2026-09-18 18:01 | `AD45E2F3FCB6777A5E924AA9A3095C23B2C5F900632720C389D7816A1A390BDD` |
| bin | `app/build/generated/nanodet/main/assets/nanodet/nanodet.ncnn.bin` | 5,002,704 B | 2026-09-18 18:01 | `38F67190D669C9F047546F1B34DF541704E9A20429C4921182DDA8085E8F54E3` |

### exp09 训练产物

| 文件 | 路径 | 大小 | SHA-256 |
|---|---|---|---|
| checkpoint | `exp09_.../model_best/model_best.ckpt` | 69,765,750 B | `256f15619f60ae6d1c13bede29854ce5dd0ff0e6fddc5d59776b7fd3df8f2639` |
| pth | `exp09_.../model_best/nanodet_model_best.pth` | 17,525,173 B | `92c2a0d66a022724cfcb5ace9398cda5f47747c5ccfd92424bc4703fde7dc3bd` |

### 已有 APK

最后已知 APK（2026-09-18 ROI 收口）：

| 项目 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 232,123,666 bytes |
| 时间 | 2026-09-18 17:56:08 +08:00 |
| SHA-256 | `2736b661fde7a170b7cdadb0e84d89c5fc45f182c608726b316577d5028d44fb` |

---

## 八、结构化证据路径

| 证据类型 | 路径 |
|---|---|
| exp09 训练报告 | `D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp09_retrain_nutsert_4class_baseline\experiment_report_4class.md` |
| exp09 preflight | `.../exp09_.../preflight_4class.json` |
| exp09 数据 manifest | `.../exp09_.../data_manifest_4class.json` |
| exp09 checkpoint 审计 | `.../exp09_.../checkpoint_compatibility_audit_4class.json` |
| 当前 NanoDet 准备报告 | `docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md` |
| B3 规划文档 | `docs/reports/b3/NANODET_EXP09_4CLASS_ANDROID_PLAN.md`（本文） |

---

## 九、未完成项和阻塞项

### 阻塞项（必须在实施前解决）

| # | 阻塞项 | 说明 | 状态 |
|---|---|---|---|
| B1 | exp09 ONNX 转换 | 无 `.onnx` 文件，需从 `.pth` 导出 | ✅ 已完成（2026-09-20） |
| B2 | exp09 NCNN 转换 | 无 `.param/.bin` 文件，需从 ONNX 转换 | ✅ 已完成（2026-09-20） |
| B3 | exp09 桌面 parity | 无法确认转换后输出与 PyTorch 一致 | ✅ 已完成（2026-09-20） |
| B4 | exp09 Android parity | Android NCNN 与桌面 NCNN 一致 | ✅ 通过（2026-09-20；NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4，YAL-AL10） |

### 非阻塞项（可在实施过程中完成）

| # | 项目 | 说明 |
|---|---|---|
| N1 | 阈值校准 | `0.20` 仅为候选，需更多独立数据 |
| N2 | bolt 类真实标注 | 外部集无 bolt GT |
| N3 | 独立外部数据补充 | 控制 source group 泄漏 |
| N4 | 全量设备回归 | 多 ABI/设备覆盖 |

---

## 十、Git 状态

```
 M tasks/plan.md
 M tasks/todo.md
?? docs/reports/b3/NANODET_EXP09_4CLASS_ANDROID_PLAN.md
```

本阶段未提交 Git。工作区其他改动保留。

---

## 十一、审计表

| 项目 | 当前 Android | exp09 目标 | 影响文件 | 后续动作 |
|---|---|---|---|---|
| 输出 shape | `[3598,34]` | `[3598,36]` | InferenceModels, JNI×2, SmokeTest | 常量修改 |
| 分类列数 | 2 | 4 | OutputDecoder, SmokeTest | argmax + NMS 扩展 |
| DFL 列数 | 4×8=32 | 4×8=32 | 无 | 不变 |
| 类别映射 | nut=0, thread=1 | nut=0, thread=1, bolt=2, nutsert=3 | DecisionPolicy, classIndex() | 新增映射 |
| blob 名称 | in0/out0 | in0/out0 | 无 | 不变 |
| 输入尺寸 | 416×416 | 416×416 | 无 | 不变 |
| 预处理 | BGR, mean/std, letterbox | 同左 | 无 | 不变 |
| 模型资产 | nanodet-ncnn-20260526-opt2 | exp09 产物已转换（见13.2） | assets + contract SHA-256 | 替换（待 Android 协议升级后） |
| ROI 属性 | THREAD/NUT/FEATURE | +BOLT/NUTSERT | RoiTargetType, Editor, Tests | 枚举扩展 |
| 检测路由 | NUT→0, THREAD→1 | +BOLT→2, NUTSERT→3 | DecisionPolicy, Service | 路由扩展 |
| FEATURE | 不执行检测 | 不执行检测 | 无 | 不变 |
| 阈值 | 0.37（起始） | 0.20（候选） | 后续校准阶段 | 非本阶段 |
| 测试覆盖 | 二分类全覆盖 | 需新增四分类测试 | 6个测试文件 | 扩展 |
| DB migration | 不需要 | 不需要 | 无 | targetType 为 TEXT |

---

## 十二、更新后的文件路径

- 本文档：`docs/reports/b3/NANODET_EXP09_4CLASS_ANDROID_PLAN.md`
- 任务状态：`tasks/todo.md`（Task 2A 已更新）
- 实施计划：`tasks/plan.md`（Task 2A 已更新）

---

## 十三、Task 2A：exp09 ONNX/NCNN 转换 + 桌面 parity（2026-09-20 完成）

### 13.1 执行工具

| 项目 | 版本/路径 |
|------|-----------|
| Python | 3.9.23（yolov12 env, `D:\ProgramData\anaconda3\envs\yolov12\python.exe`） |
| PyTorch | yolov12 环境内置（见 parity 脚本日志） |
| onnx | yolov12 环境内置（见 parity 脚本日志） |
| onnxruntime | yolov12 环境内置（见 parity 脚本日志） |
| ncnn Python | `D:\study\Textile_defects\_ncnn_toolchain_20260526\ncnn_py311\`（cp311 wheel，sys.path 注入） |
| PNNX | `D:\study\Textile_defects\_ncnn_toolchain_20260526\pnnx\pnnx\pnnx.exe`，PNNX 20260526 |
| 转换脚本 | `tools/ncnn_android/exp09_convert_and_parity.py` |

**dinov2 环境证据补正状态**：`D:\ProgramData\anaconda3\envs\dinov2\python.exe`（Python 3.9.23）缺少 torch、onnx、onnxruntime；工具链 ncnn/onnxruntime wheel 为 cp311 无法加载。**阻塞**。见第十五节。

### 13.2 输出产物 SHA-256 / 形状 / 大小

#### 正式 NCNN 接入候选（Android assets 来源）

| 文件 | 路径 | SHA-256 | 大小（bytes） | 说明 |
|------|------|---------|--------------|------|
| exp09.ncnn.param | `android_export\ncnn_20260526_opt2_4class\exp09.ncnn.param` | `B81B824FEA9FF949F72F3715A8F20C6EBE96C2792370DD80B7A7A69A65335960` | 24,308 | 239 layers, 283 blobs; input=`in0`, output=`out0` |
| exp09.ncnn.bin | `android_export\ncnn_20260526_opt2_4class\exp09.ncnn.bin` | `A9C6792BC13B926BF1BAA4F73C001E6EB7E08A3668682B9755E6796D8556C137` | 5,005,808 | FP32, optlevel=2, PNNX 20260526 |

上述两个文件是 Android assets 替换的正式候选，位于 `android_export\ncnn_20260526_opt2_4class\` 子目录。

#### ONNX 转换产物

| 文件 | 路径 | SHA-256 | 大小（bytes） | 说明 |
|------|------|---------|--------------|------|
| exp09_4class_nanodet.onnx | `android_export\exp09_4class_nanodet.onnx` | `8A16B51558C457A072053E724F4A904BB6B65BC43B810C08E133F1A850FA90FC` | 5,125,590 | input=`data` [1,3,416,416] output=`output` [1,3598,36] |

#### PNNX 中间产物（**不得作为 Android assets**）

| 文件 | 路径 | 说明 |
|------|------|------|
| exp09_4class_nanodet.pnnx.param | `android_export\exp09_4class_nanodet.pnnx.param` | PNNX 格式中间 param（含 `nn.Conv2d` 等 PNNX 层名），ncnn runtime 不可加载 |
| exp09_4class_nanodet.pnnx.bin | `android_export\exp09_4class_nanodet.pnnx.bin` | PNNX 格式中间 bin |

#### 源权重

| 文件 | SHA-256 | 大小（bytes） | 说明 |
|------|---------|--------------|------|
| nanodet_model_best.pth | `92C2A0D66A022724CFCB5ACE9398CDA5F47747C5CCFD92424BC4703FDE7DC3BD` | 17,525,173 | 训练产出，exp09 四分类 |

#### 证据文件

| 文件 | 路径 | 说明 |
|------|------|------|
| exp09_parity_results.json | `android_export\exp09_parity_results.json` | 21,633 bytes，parity 张量差异/检测计数/匹配 |
| exp09_model_metadata.json | `android_export\exp09_model_metadata.json` | 2,470 bytes，转换工具版本/SHA-256/协议规格 |
| BLOCKED_log.txt | `android_export\dinov2_recheck_20260920\BLOCKED_log.txt` | dinov2 环境依赖阻塞日志 |

产物根目录：`D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp09_retrain_nutsert_4class_baseline\android_export\`

### 13.3 协议规格确认

| 项目 | 值 |
|------|-----|
| num_classes | 4 |
| class_names（固定顺序） | `["nut","thread","bolt","nutsert"]` |
| class_index | nut=0, thread=1, bolt=2, nutsert=3 |
| strides | [8, 16, 32, 64] |
| anchor count | Σ(⌈416/s⌉²) = 2704+676+169+49 = 3598 |
| reg_max | 7 |
| decouple_reg | True |
| output_width | num_classes + 4×(reg_max+1) = 4 + 32 = **36** |
| output_height | 3598 |
| 输入 blob | `in0` |
| 输出 blob | `out0` |
| 预处理 | BGR, letterbox 416×416, mean=[103.53,116.28,123.675], std=[57.375,57.12,58.395] |
| 输入布局 | NCHW |

### 13.4 Parity 结果

#### frame_00106_f1060.jpg

**张量差异：**

| 对比 | max abs diff | mean abs diff | RMSE | p99 |
|------|-------------|--------------|------|-----|
| PyTorch vs ONNX | 1.07e-05 | 2.26e-07 | 4.08e-07 | — |
| NCNN vs ONNX | 5.14e-06 | 2.57e-07 | 4.46e-07 | — |
| PyTorch vs NCNN | 7.27e-06 | 2.72e-07 | 5.16e-07 | — |

**NMS 后检测数：**

| 阈值 | nut | thread | bolt | nutsert |
|------|-----|--------|------|---------|
| 0.05 | 0 | 1 | 0 | 1 |
| 0.10 | 0 | 0 | 0 | 1 |
| 0.15 | 0 | 0 | 0 | 0 |
| 0.20 | 0 | 0 | 0 | 0 |
| 0.37 | 0 | 0 | 0 | 0 |
| 0.50 | 0 | 0 | 0 | 0 |

**top detections @0.05（PyTorch）：**
- thread: score=0.055878, box=[49.3,387.9,209.0,568.8]
- nutsert: score=0.122244, box=[399.3,605.0,481.4,689.3]

**匹配（PyTorch/NCNN vs ONNX）：**

| 对比 | 匹配数(IoU≥0.5) | mean IoU | max score diff | max box diff (px) |
|------|----------------|----------|----------------|-------------------|
| PyTorch vs ONNX | 2 | 1.000000 | 2.24e-08 | 2.35e-05 |
| NCNN vs ONNX | 2 | 0.999999 | 1.94e-07 | 3.52e-05 |

#### frame_00045_f450.jpg

**张量差异：**

| 对比 | max abs diff | mean abs diff | RMSE | p99 |
|------|-------------|--------------|------|-----|
| PyTorch vs ONNX | 1.22e-05 | 2.06e-07 | 3.88e-07 | — |
| NCNN vs ONNX | 5.80e-06 | 2.34e-07 | 4.07e-07 | — |
| PyTorch vs NCNN | 6.44e-06 | 2.19e-07 | 4.00e-07 | — |

**NMS 后检测数：**

| 阈值 | nut | thread | bolt | nutsert |
|------|-----|--------|------|---------|
| 0.05 | 4 | 2 | 0 | 0 |
| 0.10 | 2 | 1 | 0 | 0 |
| 0.15 | 2 | 1 | 0 | 0 |
| 0.20 | 2 | 0 | 0 | 0 |
| 0.37 | 1 | 0 | 0 | 0 |
| 0.50 | 1 | 0 | 0 | 0 |

**top detections @0.05（PyTorch）：**
- nut: score=0.648162, box=[106.7,869.9,317.1,1115.8]
- nut: score=0.292874, box=[435.3,776.5,608.4,961.4]
- nut: score=0.092758, box=[404.7,451.6,644.8,676.7]
- nut: score=0.053317, box=[384.7,723.7,607.4,952.6]
- thread: score=0.163836, box=[270.3,55.4,449.5,194.2]
- thread: score=0.069424, box=[0.0,261.9,720.0,920.8]

**匹配（PyTorch/NCNN vs ONNX）：**

| 对比 | 匹配数(IoU≥0.5) | mean IoU | max score diff | max box diff (px) |
|------|----------------|----------|----------------|-------------------|
| PyTorch vs ONNX | 6 | 1.000000 | 5.07e-07 | 7.04e-05 |
| NCNN vs ONNX | 6 | 0.999999 | 5.96e-07 | 5.87e-05 |

### 13.5 Parity 结论

- **PyTorch ↔ ONNX ↔ NCNN 三路一致**，所有张量差异 ≤ 1.22e-05（FP32 量级，正常）
- 检测数量在所有阈值下**完全相同**
- 所有匹配检测框 IoU ≥ 0.999（亚像素级一致）
- **4-class 36列协议已通过桌面 parity 验证**
- **Android parity 已通过**（2026-09-20）：Task 2B 34→36 协议升级完成，NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4 通过（设备 YAL-AL10）

### 13.6 阈值观察（回归图像）

| 图像 | 内容 | 0.05 检测 | 0.20 检测 | 备注 |
|------|------|----------|----------|------|
| frame_00106 | nutsert 区域 | thread=1(0.056), nutsert=1(0.122) | 0 | 弱检测，无背景误触发 |
| frame_00045 | nut 区域 | nut=4, thread=2 | nut=2 | 强 nut 检测，0.648 最高 |

- frame_00106 的 nutsert score 仅0.122，0.20 阈值下不触发 → 需阈值校准阶段进一步评估
- **bolt 类在两幅回归图中均无检测样本**（两图均无 bolt GT），**不能从这两张图推出 bolt 的召回、精度或泛化结论**
- **`0.20` 仅为阶段性候选阈值**，不是最终现场校准结论；最终阈值必须由更多独立数据和分类型指标支持

---

## 十四、NCNN 模型文件分类（正式候选 vs PNNX 中间产物）

| 分类 | 文件 | 目录 | 可用于 Android assets |
|------|------|------|----------------------|
| **正式 NCNN 接入候选** | `exp09.ncnn.param` | `ncnn_20260526_opt2_4class\` | ✅ |
| **正式 NCNN 接入候选** | `exp09.ncnn.bin` | `ncnn_20260526_opt2_4class\` | ✅ |
| ONNX 转换产物 | `exp09_4class_nanodet.onnx` | `android_export\` | N/A（ONNX 不直接用于 Android） |
| PNNX 中间产物 | `exp09_4class_nanodet.pnnx.param` | `android_export\` | ❌ PNNX 格式，ncnn runtime 不可加载 |
| PNNX 中间产物 | `exp09_4class_nanodet.pnnx.bin` | `android_export\` | ❌ PNNX 格式，ncnn runtime 不可加载 |

正式接入 Android 时，必须使用 `ncnn_20260526_opt2_4class\` 子目录下的 `.param/.bin`，不得使用根目录下的 PNNX 中间产物。

---

## 十五、dinov2 环境证据补正阻塞记录（2026-09-20）

**状态**：**BLOCKED** — 等待主协调决策

**指定 Python**：`D:\ProgramData\anaconda3\envs\dinov2\python.exe`（Python 3.9.23）

**依赖检查结果**：

| 包 | 状态 | 说明 |
|----|------|------|
| numpy | 2.0.2 ✅ | |
| cv2 | 4.13.0 ✅ | |
| torch | ❌ MISSING | `No module named 'torch'` |
| onnx | ❌ MISSING | `No module named 'onnx'` |
| onnxruntime | ❌ MISSING | `No module named 'onnxruntime'` |

**工具链 fallback 尝试**：

| 工具 | 结果 |
|------|------|
| ncnn wheel `ncnn-1.0.20260526-cp311-cp311-win_amd64.whl` | ❌ cp311 wheel 无法在 Python 3.9 加载（`No module named 'ncnn.ncnn'`） |
| onnxruntime wheel `onnxruntime-1.30.0-cp311-cp311-win_amd64.whl` | ❌ cp311 DLL 无法在 Python 3.9 加载（`DLL load failed`） |

**阻塞原因**：dinov2 环境未安装 torch、onnx、onnxruntime，且工具链 wheel 为 cp311（Python 3.11 ABI），与 dinov2 的 Python 3.9 不兼容。

**处置**：
- 未安装任何依赖
- 未退回使用 yolov12 环境
- 保留失败日志：`android_export\dinov2_recheck_20260920\BLOCKED_log.txt`
- Task 2A 标记为"证据补正阻塞"

**解决选项（需主协调决策）**：
1. 在 dinov2 环境安装 torch/onnx/onnxruntime
2. 提供 Python 3.11 环境（与工具链 wheel ABI 匹配）
3. 接受 yolov12 环境的已有证据作为 Task 2A 有效结论

---

本轮仅执行只读审计、模型转换和 parity 验证及证据补正尝试，未修改 Android 生产代码、测试代码、数据库 schema 或 APK。等待主协调复核后，下一阶段再实施四分类 decoder、模型资产替换和 BOLT/NUTSERT 路由修改。

---

## 十六、Task 2B：Android 34→36 四分类协议升级（2026-09-20 完成）

### 16.1 任务范围

完成 Android NanoDet 二分类协议到 exp09 四分类协议的最小升级：
- 输出宽度 `34 → 36`
- 类别顺序固定：`0=nut`、`1=thread`、`2=bolt`、`3=nutsert`
- DFL 保持 4 边 × 8 bins = 32 列
- 不修改 CameraX、DPM、OCR、导航、数据库或旧工程
- 本轮不扩展 ROITargetType、模板 UI 或 BOLT/NUTSERT 持久化路由（属于后续 Task 3）

### 16.2 修改文件清单

#### 生产代码

| 文件 | 修改内容 |
|---|---|
| [NanoDetInferenceModels.kt](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetInferenceModels.kt) | `VERSION`→`nanodet-ncnn-exp09-4class`，`OUTPUT_WIDTH`→36，SHA-256 更新，`outputShape`→`[3598,36]` |
| [NanoDetOutputDecoder.kt](app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetOutputDecoder.kt) | `classNames`→4元素，`Array(2)`→`Array(classNames.size)`，二值 argmax→4类 argmax，DFL 起始列→`classNames.size` |
| [nanodet_ncnn_jni.cpp](app/src/main/cpp/nanodet_ncnn_jni.cpp) | `kOutputWidth`→36 |

#### 测试代码

| 文件 | 修改内容 |
|---|---|
| [NanoDetInferenceContractTest.kt](app/src/test/java/com/wearable/inspection/mobile/detection/NanoDetInferenceContractTest.kt) | 新增4类测试：shape 校验、34列拒绝、4类 argmax、DFL 偏移、4类 NMS、空检测、低分候选 |
| [NcnnRuntimeSmokeInstrumentedTest.kt](app/src/androidTest/java/com/wearable/inspection/mobile/ncnn/NcnnRuntimeSmokeInstrumentedTest.kt) | `OUTPUT_WIDTH`→36，`CLASS_NAMES`→4元素，argmax/DFL 更新，阈值计数扩展 |
| [NanoDetRoiRuntimeInstrumentedTest.kt](app/src/androidTest/java/com/wearable/inspection/mobile/detection/NanoDetRoiRuntimeInstrumentedTest.kt) | `CLASS_NAMES`→4元素，`outputShape`→`[3598,36]` |
| [ViewConfirmationModelResultTest.kt](app/src/test/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationModelResultTest.kt) | `outputShape`→`[3598,36]`，`modelVersion`→新版本 |
| [ncnn_smoke_jni.cpp](app/src/androidTest/cpp/ncnn_smoke_jni.cpp) | `kOutputWidth`→36 |

#### 构建配置

| 文件 | 修改内容 |
|---|---|
| [build.gradle.kts](app/build.gradle.kts) | `ncnnSmokeModelRoot` 指向 exp09 产物目录 |

#### 模型资产

| 资产 | 源路径 | 目标路径 |
|---|---|---|
| param | `exp09_retrain_nutsert_4class_baseline/android_export/ncnn_20260526_opt2_4class/exp09.ncnn.param` | `app/src/main/assets/nanodet/nanodet.ncnn.param` |
| bin | `exp09_retrain_nutsert_4class_baseline/android_export/ncnn_20260526_opt2_4class/exp09.ncnn.bin` | `app/src/main/assets/nanodet/nanodet.ncnn.bin` |

### 16.3 新模型资产详情

| 文件 | 大小 | SHA-256 |
|---|---|---|
| nanodet.ncnn.param | 24,308 bytes | `B81B824FEA9FF949F72F3715A8F20C6EBE96C2792370DD80B7A7A69A65335960` |
| nanodet.ncnn.bin | 5,005,808 bytes | `A9C6792BC13B926BF1BAA4F73C001E6EB7E08A3668682B9755E6796D8556C137` |

模型 VERSION：`nanodet-ncnn-exp09-4class`

### 16.4 验证结果

#### JVM 单元测试

```bash
./gradlew :app:testDebugUnitTest --tests "com.wearable.inspection.mobile.detection.*" --tests "com.wearable.inspection.mobile.ui.screens.ViewConfirmationModelResultTest" --no-daemon
```

**结果**：BUILD SUCCESSFUL，所有 NanoDet 相关测试通过。

#### Kotlin 编译

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

**结果**：BUILD SUCCESSFUL

#### AndroidTest Kotlin 编译

```bash
./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
```

**结果**：BUILD SUCCESSFUL

#### Debug APK 构建

```bash
./gradlew :app:assembleDebug --no-daemon
```

**结果**：BUILD SUCCESSFUL

| 项目 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 232,126,458 bytes |
| 时间 | 2026-09-20 |
| SHA-256 | `B41D0D9532A7D258CFBED73B9F65096C84A4D154EBBCC5EA406D1987295A5F68` |

### 16.5 Android Parity 设备验证（2026-09-20）

#### Connected AndroidTest

| 测试类 | 命令 | 结果 | 设备 |
|--------|------|------|------|
| NcnnRuntimeSmokeInstrumentedTest | `connectedDebugAndroidTest --rerun-tasks -P...class=...NcnnRuntimeSmokeInstrumentedTest` | **1/1 PASS**（1.831s） | YAL-AL10 |
| NanoDetRoiRuntimeInstrumentedTest | `connectedDebugAndroidTest --rerun-tasks -P...class=...NanoDetRoiRuntimeInstrumentedTest` | **4/4 PASS**（1.976s） | YAL-AL10 |

#### 测试 XML 证据

- `docs/reports/b3/exp09_smoke_test_result.xml` — tests=1, failures=0, errors=0, time=1.831s
- `docs/reports/b3/exp09_roi_test_result.xml` — tests=4, failures=0, errors=0, time=1.976s

#### androidTest APK

| 项目 | 值 |
|------|-----|
| 路径 | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| 大小 | 7,166,571 bytes |
| SHA-256 | `AC82ACD473BFB52C4927669331A02B635A192F2BE74B57444879355276F2ECDD` |

#### 真机安装验证

| 项目 | 值 |
|------|-----|
| 设备 | YAL-AL10（ERLDU20429005890） |
| 安装 | `adb install -r` Success |
| 启动 | `am start -W` COLD, TotalTime=1507ms |
| 新包 PID | 18855 |
| 前台 Activity | `com.wearable.inspection.mobile/.MainActivity` |

#### 关键验证项

| 项目 | 值 |
|------|-----|
| NCNN 输出 shape | `[3598,36]`（smoke test 断言 `3598*36` 元素） |
| 4-class argmax | nut/thread/bolt/nutsert 全部参与 argmax 和阈值计数 |
| Android vs parity 分数差异 | ≤ 1e-5（MAX_SCORE_DIFF） |
| Android vs parity 框坐标差异 | ≤ 0.01 px（MAX_BOX_DIFF_PX） |
| 阈值对照 | 8 档（0.05/0.10/0.15/0.20/0.25/0.30/0.37/0.50）×4类×2图 |

#### 修复的 ROI 测试缺口

`NanoDetRoiRuntimeInstrumentedTest` 首次运行时因4处未适配 exp09 parity 而失败：
1. parity 文件名 `parity_results.json` → `exp09_parity_results.json`
2. 总检测数断言硬编码 `nut + thread` → `CLASS_NAMES.sumOf { ... }`
3. 阈值计数只含 nut/thread → 扩展为4类
4. THRESHOLDS 列表 4档 → 8档

修复后重跑 4/4 PASS。修复仅限 androidTest 文件，未修改生产代码。

### 16.6 未完成项

| 项目 | 说明 |
|---|---|
| Android parity | ✅ 2026-09-20 通过（NcnnRuntimeSmoke 1/1 + NanoDetRoiRuntime 4/4，YAL-AL10） |
| RoiTargetType 扩展 | 不在本轮范围（Task 3） |
| BOLT/NUTSERT 持久化路由 | 不在本轮范围（Task 3） |
| 阈值校准 | 需更多独立数据 |
| connectedAndroidTest | ✅ 通过（2026-09-20；设备 YAL-AL10） |
| 真机安装验证 | ✅ 通过（2026-09-20；新包 PID 18855，前台 Activity com.wearable.inspection.mobile/.MainActivity） |

### 16.6 Git 状态

未提交 Git。工作区改动保留等待主协调审计。
