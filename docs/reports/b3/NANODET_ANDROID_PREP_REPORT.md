# NanoDet Android 接入前置处理报告

**日期**：2026-09-14
**状态**：`ONNX→NCNN_CONVERTED / DESKTOP_PARITY_PASS / ANDROID_NCNN_RUNTIME_SMOKE_USER_ACCEPTED / TEMPLATE_ROI_STATIC_INFERENCE_USER_ACCEPTED / VIEW_CONFIRMATION_MODEL_RESULT_SOFTWARE_COMPLETE_AWAITING_USER_ACCEPTANCE`
**范围**：记录已有 ONNX 的官方 PNNX 转换、产物审计和桌面对照；Android NCNN FP32 runtime smoke 与已保存照片的 NanoDet ROI 静态推理均已由用户验收。本报告末尾新增当前任务“模型结果确认与人工终审”实现记录；未改变 Detector/预处理阈值/CameraX 预览推理、ZIP/DPM 导出或自动对齐。

## Android NCNN 运行时冒烟测试

**状态**：`USER_ACCEPTED`（2026-09-14）。本任务范围仅为 Android NCNN runtime 加载、推理及两张指定图片与桌面对照；当时未接入 ROI 页面、数据库或结果包。

### 工具链、ABI 与资产

- Android SDK：`D:\ProgramData\Android\SDK`。NDK：`D:\ProgramData\Android\SDK\android-ndk-r30`，版本 `30.0.16248370`（r30），含 Clang 和 `ndk-build.cmd`。SDK 未安装 CMake；本任务直接使用 NDK `ndk-build`，没有下载或安装工具。
- 工程使用 AGP `8.7.3`、Gradle wrapper `8.9`、Java 17、`minSdk=26`、`compileSdk=35`。Windows NDK `ndk-build` 不接受含空格的 native 项目路径；Gradle 将 androidTest native 输入暂存到系统临时目录 `C:\Users\ws\AppData\Local\Temp\mobileinspection-ncnn-smoke` 编译，再将生成库复制进测试 APK，临时目录在暂存后清理。
- 测试设备：`ERLDU20429005890`，HUAWEI `YAL-AL10`，Android 10；ABI 列表 `arm64-v8a, armeabi-v7a, armeabi`。本轮只构建和打包 `arm64-v8a` 测试 native 库；没有改主 APK 的 ABI 支持范围。
- NCNN shared 包：`D:\ProgramData\Android\SDK\ncnn-20260526-android-shared\arm64-v8a`，只使用该目录的 `libncnn.so` 和头文件。模型仅用 optlevel=2 产物：`D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp01_decoupled_retry\model_best\android_export\ncnn_20260526_opt2\nanodet.ncnn.param` 与 `nanodet.ncnn.bin`；未使用 optlevel=0 rejected 产物。
- 回归图片：`D:\study\Textile_defects\Wearable Inspection\DCIM\extracted_frames\frame_00106_f1060.jpg`、`D:\study\Textile_defects\Wearable Inspection\DCIM\extracted_frames\frame_00045_f450.jpg`。桌面对照：`D:\study\Textile_defects\nanodet-main\nanodet-main\reports\ncnn_android\parity_results.json`。

### 测试通路与执行

- 当时的 `app/build.gradle.kts` 只为 `androidTest` 增加模型/图片/桌面对照 JSON 的生成资产暂存、NDK r30 native 编译和测试 `.so` 暂存任务；该冒烟测试阶段的主 APK 没有 NCNN native 库或模型资源。
- `app/src/androidTest/cpp/` 内的 `Android.mk`、`Application.mk` 和 JNI bridge 加载 `in0`/`out0`，输入 float32 `[1,3,416,416]`，输出验证为 `[3598,34]`。推理禁用 Vulkan、fp16 storage、fp16 packed 和 fp16 arithmetic，与桌面对照 CPU FP32 设置一致。
- `app/src/androidTest/java/.../NcnnSmokeNative.java` 与 `NcnnRuntimeSmokeInstrumentedTest.kt` 仅服务设备 smoke：OpenCV 按 BGR 解码、416×416 等比例缩放并左上放置、使用 mean `[103.53,116.28,123.675]` 和 std `[57.375,57.12,58.395]` 归一化，补边归零；逐候选做同一 DFL 解码、类别分组和 NMS，并核对类别、数量、候选点、置信度、框坐标及 0.05/0.25/0.37/0.50 四档阈值数量。
- 构建命令：`.\gradlew.bat :app:assembleDebug`、`.\gradlew.bat :app:assembleDebugAndroidTest`，均通过。设备命令：`.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.ncnn.NcnnRuntimeSmokeInstrumentedTest`，结果 **1/1 passed**；仪器测试结果 XML 为 `app/build/outputs/androidTest-results/connected/debug/TEST-YAL-AL10 - 10-_app-.xml`，测试 logcat 和两张图的 JSON 摘要位于 `app/build/outputs/androidTest-results/connected/debug/YAL-AL10 - 10/`。
- 测试前执行 `adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection`、停止 `com.wearable.inspection.mobile`、显式安装当前 `app-debug.apk`，并用完整组件 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` 启动；核对新包已安装、新包 PID 非空、旧包 PID 为空和前台 Activity。`connectedDebugAndroidTest` 后同样恢复主 APK；运行器重置了相机权限，按门禁授权后再次停止/安装/显式启动。最终核验新包前台 PID `6529`、旧包 PID 为空。

### Android 与桌面 NCNN 对照

两张输入源图均为 720×1280，缩放到 234×416，放置在 416×416 左上角，右侧补 182 列、底部补 0 行。输入 `[1,3,416,416]`，blob `in0`；输出 `[3598,34]`，blob `out0`。

| 图像 | 0.05 阈值类别/数量（Android；桌面对照） | 其他阈值数量（nut/thread） | 最大置信度绝对差 | 最大框坐标绝对差 |
|---|---|---|---:|---:|
| `frame_00106_f1060.jpg` | nut 1 / thread 2；与桌面 1 / 2 一致 | 0.25、0.37、0.50 均为 0 / 0，均与桌面对照一致 | `2.24e-7` | `3.53e-5 px` |
| `frame_00045_f450.jpg` | nut 0 / thread 4；与桌面 0 / 4 一致 | 0.25、0.37 均为 0 / 1；0.50 为 0 / 0，均与桌面对照一致 | `2.69e-7` | `2.35e-5 px` |

按同一输出候选点匹配的 Android NCNN 置信度（桌面对照值）：

- `frame_00106_f1060.jpg`：nut 点 333 `0.15253258`（`0.15253280`）；thread 点 1421 `0.05253360`（`0.05253368`）、点 1423 `0.05042307`（`0.05042311`）。3 个候选点均匹配；最大框坐标差 `3.53e-5 px`。
- `frame_00045_f450.jpg`：thread 点 2088 `0.37719038`（`0.37719011`）、点 3182 `0.07598109`（`0.07598110`）、点 380 `0.05458790`（`0.05458792`）、点 1840 `0.05015663`（`0.05015659`）。4 个候选点均匹配；最大框坐标差 `2.35e-5 px`。

Android 输出框坐标（原图像素，`[x1,y1,x2,y2]`）及逐框最大绝对坐标差：

| 图像 | 点/类别 | Android 框坐标 | Android vs 桌面最大坐标差 |
|---|---|---|---:|
| frame106 | 333 / nut | `[406.551009,68.423843,647.429739,285.594553]` | `2.35e-5 px` |
| frame106 | 1421 / thread | `[333.719060,574.121616,517.957458,754.208579]` | `3.53e-5 px` |
| frame106 | 1423 / thread | `[396.481100,577.574921,556.029816,755.557192]` | `1.76e-5 px` |
| frame45 | 2088 / thread | `[101.429872,867.645581,317.289217,1111.723551]` | `2.35e-5 px` |
| frame45 | 3182 / thread | `[305.245009,734.651618,632.680887,1030.732387]` | `2.35e-5 px` |
| frame45 | 380 / thread | `[310.559780,94.652211,461.364875,240.115339]` | `1.17e-5 px` |
| frame45 | 1840 / thread | `[394.753647,757.221375,610.175312,983.499979]` | `1.17e-5 px` |

`parity_results.json` 不含完整原始张量，因此本轮**没有**声称完成 Android 与桌面逐元素原始张量对照。报告中的分数和框差异来自 Android runtime 的输出解码，与 JSON 保存的桌面 NCNN 检测结果逐候选对照。

### APK、实际文件与后续边界

| APK | 路径 | 时间（Asia/Shanghai） | 大小 | SHA-256 |
|---|---|---|---:|---|
| 主 debug APK（用于门禁恢复） | `app/build/outputs/apk/debug/app-debug.apk` | 2026-09-14 16:36:04 | 221,545,526 bytes | `E2AC4A8B10924D27F38BF0107714079BBE151B26048E8733499089A080AC47CC` |
| instrumentation APK（含测试资产与 arm64-v8a NCNN/JNI 库） | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2026-09-14 16:45:39 | 11,839,158 bytes | `408D27BD5770B25277676A9A696F46083E3274566E3D73AF665C6CB85FE8D37C` |

本任务实际修改/新增：`app/build.gradle.kts`、`app/src/androidTest/cpp/Android.mk`、`app/src/androidTest/cpp/Application.mk`、`app/src/androidTest/cpp/ncnn_smoke_jni.cpp`、`app/src/androidTest/java/com/wearable/inspection/mobile/ncnn/NcnnSmokeNative.java`、`app/src/androidTest/java/com/wearable/inspection/mobile/ncnn/NcnnRuntimeSmokeInstrumentedTest.kt`、`tasks/todo.md` 和本报告。构建输出位于 `app/build/`，不作为源文件提交。

未完成项：ROI 或生产页面接入、数据库/结果包集成、代表性标注集上的阈值校准、设备性能/内存评估均不属于本任务。仅两张指定图通过本次 Android runtime smoke。该项随后由用户验收；当时 Git 工作区不干净，其他已有工作区改动保持未动。`tasks/todo.md` 中其他未验收任务状态未更改。

---

## 模型与现有 ONNX

指定权重：

- `.pth`：`D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp01_decoupled_retry\model_best\nanodet_model_best.pth`
- 大小 17,508,277 bytes；SHA-256 `71F7BCF03F0826934D778D81F3E14C213093AD7814CA5B3896AA32CDC50BADEE`
- 配置：`config/nanodet-plus-m_416_nut_thread_exp01_decoupled.yml`
- NanoDet-Plus / ShuffleNetV2 1.0x / GhostPAN / `decouple_reg=True`
- 两类：`0=nut`、`1=thread`；输入 416×416；strides `[8,16,32,64]`；`reg_max=7`

没有重新导出 ONNX。使用现有文件：

- 路径：`D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp01_decoupled_retry\model_best\android_export\nanodet_model_best.onnx`
- 大小 6,246,901 bytes；SHA-256 `490736DD75AE88591A4E7A5A570E31A78358695186F6903D1047635781FA565F`
- 输入名 `data`，float32 `[1,3,416,416]`；输出名 `output`，float32 `[1,3598,34]`，ONNX checker 通过。
- 3598 个位置等于 `52×52 + 26×26 + 13×13 + 7×7`；每个位置是 2 个 sigmoid 类别分数和 32 个 DFL 回归值。

## PNNX 转换与运行库

通过 NCNN 官方 PNNX 20260526 转换已有 ONNX。PNNX 转换器和预编译 Python 运行依赖保留在 NanoDet 工程外的隔离目录 `D:\study\Textile_defects\_ncnn_toolchain_20260526`；模型对照/预处理脚本已整理到 NanoDet 项目 `D:\study\Textile_defects\nanodet-main\nanodet-main\tools\ncnn_android`，对照结果保存到项目 `reports\ncnn_android\parity_results.json`。NCNN Android shared 运行库位于 `D:\ProgramData\Android\SDK\ncnn-20260526-android-shared`（arm64-v8a、armeabi-v7a、x86、x86_64、riscv64）。没有安装到 `yolov12` 环境，也没有改动用户级 NumPy。

- PNNX：官方 PyPI wheel `pnnx==20260526`，SHA-256 `66F49D67305528C8D7A0C8DF41C64A0B9DE8FC5254C87200199F481D9CAF5695`，已与 PyPI 发布元数据校验。
- NCNN Android shared 包：官方 release `20260526`，18,198,020 bytes，SHA-256 `123D5BD837C3AF570D64529402BF484817DED4837384D88ED711A0EAA83635FE`，校验通过。包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`、`riscv64` 的 `libncnn.so` 与头文件。
- 桌面推理：官方 `ncnn==1.0.20260526` Python wheel 解包到隔离目录；ONNX 对照使用隔离解包的 `onnxruntime==1.30.0`。二者均未安装到现有 Python 环境。

转换产物（本次唯一可用版本）：

`D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp01_decoupled_retry\model_best\android_export\ncnn_20260526_opt2\`

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `nanodet.ncnn.param` | 24,308 bytes | `AD45E2F3FCB6777A5E924AA9A3095C23B2C5F900632720C389D7816A1A390BDD` |
| `nanodet.ncnn.bin` | 5,002,704 bytes | `38F67190D669C9F047546F1B34DF541704E9A20429C4921182DDA8085E8F54E3` |

检查结果：

- PNNX 使用 `fp16=0 optlevel=2`，转换退出码 0。首轮 `optlevel=0` 产物含 37 个 NCNN runtime 未注册的 `prim::ListConstruct`，加载失败；该目录已标为 `ncnn_20260526_opt0_rejected`，不得用于集成。
- 最终 `.param` 为 239 层、283 个 blob，无 `prim::` 算子。`ncnn.Net.load_param()` 与 `load_model()` 均返回 0；两张图的 `Extractor.extract()` 均成功。
- NCNN 输入 blob 名是 **`in0`**，输出 blob 名是 **`out0`**，与 ONNX 的 `data`/`output` 不同。旧参考 C++ 当前仍使用 `data`/`output`，集成前必须改为实际 blob 名。
- 桌面 NCNN 输出为二维 Mat：`dims=2, w=34, h=3598`，即 `[3598,34]`，与 ONNX 去掉 batch 维后的输出对应。每行前 2 项已经是 sigmoid 后的 nut/thread 分数；后 32 项是四边各 8 个 DFL logits，NCNN/Android 解码不得对类别分数再次 sigmoid。
- 配置关键项逐一核对为 2 类、416×416、4 个 stride、`reg_max=7`、解耦回归 head；不是通过只改类别数套用旧模型。

转换参考：[NCNN 官方模型转换指南](https://github.com/tencent/ncnn/wiki/use-ncnn-with-pytorch-or-onnx)、[NCNN Android 构建指南](https://github.com/tencent/ncnn/wiki/how-to-build)、[NCNN Releases](https://github.com/Tencent/ncnn/releases)。

## 三方推理对照

运行命令：

```powershell
& 'D:\ProgramData\anaconda3\envs\yolov12\python.exe' -s `
  'D:\study\Textile_defects\nanodet-main\nanodet-main\tools\ncnn_android\compare_parity.py'
```

`-s` 禁止加载用户级 Python 包。比较脚本位于 NanoDet 项目的 `tools/ncnn_android/`，机器可读结果写入项目 `reports/ncnn_android/parity_results.json`；运行依赖仍从 `_ncnn_toolchain_20260526` 加载。测试图片目录可通过 `NANODET_VALIDATION_FRAMES` 环境变量覆盖，依赖目录可通过 `NCNN_TOOLCHAIN_ROOT` 覆盖。

两张图都由同一个预处理函数生成一份 float32 输入张量，再分别喂给 PyTorch、ONNX Runtime、NCNN CPU FP32：BGR，等比例缩放到 416 内，当前参考 C++ 的左上放置方式；使用 mean `[103.53,116.28,123.675]`、std `[57.375,57.12,58.395]`，归一化后补边清零。三方使用同一 DFL 解码、候选过滤和 NMS，框映射回原图。原图为高 1280、宽 720，缩放后为宽 234、高 416。

| 图像 | PyTorch/ONNX 原始张量 max abs | NCNN/ONNX max abs | NMS 后候选（阈值 0.05） | NCNN/ONNX 框匹配 |
|---|---:|---:|---|---|
| `frame_00106_f1060.jpg` | `1.276e-5` | `3.994e-6` | nut 1，thread 2；三方类别和数量一致 | 3/3 匹配；平均 IoU `0.9999997`；最大框坐标差 `0.000036 px`，最大分差 `1.72e-7` |
| `frame_00045_f450.jpg` | `1.276e-5` | `3.971e-6` | nut 0，thread 4；三方类别和数量一致 | 4/4 匹配；平均 IoU `0.9999997`；最大框坐标差 `0.000076 px`，最大分差 `4.77e-7` |

在本次固定预处理下，不同阈值经同一解码/NMS 后的数量如下（PyTorch、ONNX、NCNN 三方完全相同）：

| 图像 | 阈值 | nut | thread | 影响 |
|---|---:|---:|---:|---|
| `frame_00106_f1060.jpg` | 0.05 | 1 | 2 | 能保留低分 thread（`0.052534`、`0.050423`） |
| 同上 | 0.25 / 0.37 / 0.50 | 0 | 0 | 低分 thread 被过滤；该图最高 nut 为 `0.152533` |
| `frame_00045_f450.jpg` | 0.05 | 0 | 4 | 包含已知误报；最高 thread 为 `0.377191` |
| 同上 | 0.25 / 0.37 | 0 | 1 | 最高 thread 仍通过 0.37，故 0.37 未消除该误报 |
| 同上 | 0.50 | 0 | 0 | 误报消失，但 frame106 的低分 thread 也无法保留 |

**阈值结论**：`0.37` 只能保留为可调起始值，不能称为已校准阈值。本轮当前左上放置预处理下，frame45 的 `0.377191` 高于 0.37；frame106 的 thread 分数约 `0.05`，在 0.37 下会漏掉。另测相同缩放图像但居中放置后，frame45 的最高 thread 降至 `0.209691`，显示 padding/内容位置会显著影响得分。先前记录的 `0.3573` 与本轮固定预处理结果不一致，目前尚未在同一输入变换下复现；必须先固定训练/Android 预处理契约，再用有人工框标注的代表性验证集校准阈值。

## NanoDet 模板 ROI 静态照片推理接入

**状态**：USER_ACCEPTED（2026-09-14）。本项复用已验收的 optlevel=2 模型、NCNN Android shared 库和 runtime；没有重新转换模型或重做 NCNN 冒烟测试。实际生产调用只对已保存照片和当前模板启用的 ROI 执行，运行于 IO dispatcher，不绑定 CameraX 分析流。

### 行为与结果契约

- 复用 RoiDefinitionEntity.targetType 和 RoiCoordinateMapper。NUT 映射 NCNN 类别 0，THREAD 映射类别 1；未选属性产生 ROI_NOT_CONFIGURED，FEATURE 产生 FEATURE_UNSUPPORTED，均不生成 OK/NG 检测建议。
- 读取 JPEG EXIF 方向并在原始像素栅格上解码，再应用 EXIF 1–8 的方向映射；根据模板图像和照片的真实宽高映射 normalized ROI，按半开像素边界裁切，拒绝无效或越界 ROI。检测框同时给出 ROI 裁剪坐标和完整正向照片坐标，并裁切到真实照片范围。
- 输入与已验证路径一致：BGR，416×416 等比例缩放，左上放置图像并在右侧/底部补零，mean [103.53,116.28,123.675]，std [57.375,57.12,58.395]，blob in0 / out0，CPU FP32。输出 shape [3598,34]。
- 解码保留置信度不低于 0.05 的候选、每类别最多 100 个框，并按类别以 0.6 IoU 阈值做 NMS；不会在候选阶段用 0.37 丢掉低分框。业务阈值 0.37 仅是未校准起始值。结果对象记录所有保留框、类别、分数、ROI/整图像素坐标、阈值、模型版本和哈希、耗时、图像尺寸、EXIF 和状态。
- 对应类别至少有一个框达到业务阈值时为 DETECTED / 建议 OK；存在该类框但都低于阈值为 DETECTED_BELOW_THRESHOLD / 建议 NG，并保留最高分；没有该类框为 NO_DETECTION / 建议 NG / 分数为空。模型、runtime、照片关联、照片或模板图读取错误均保留非成功状态，不伪装成 OK/NG。ViewModel 仅把真实结果放入内存状态供后续页面使用；本项不持久化推理或人工确认结果。

### 模型、设备与输出差异

- 模型版本 nanodet-ncnn-20260526-opt2。模型目录：D:\study\Textile_defects\nanodet-main\nanodet-main\workspace\key_nut_thread_experiments\exp01_decoupled_retry\model_best\android_export\ncnn_20260526_opt2。param SHA-256 AD45E2F3FCB6777A5E924AA9A3095C23B2C5F900632720C389D7816A1A390BDD；bin SHA-256 38F67190D669C9F047546F1B34DF541704E9A20429C4921182DDA8085E8F54E3。NCNN shared 库取自 D:\ProgramData\Android\SDK\ncnn-20260526-android-shared\arm64-v8a。
- 测试设备为 YAL-AL10，Android 10，实际 ABI arm64-v8a。NCNN/JNI 库只打入 arm64-v8a，未新增 abiFilters 或扩大 App ABI。两张回归照片均为 720×1280、EXIF orientation 1；预处理缩为 234×416，放在 416×416 左上角，右侧补 182 像素、底部补 0。
- 回归图片：D:\study\Textile_defects\Wearable Inspection\DCIM\extracted_frames\frame_00106_f1060.jpg、D:\study\Textile_defects\Wearable Inspection\DCIM\extracted_frames\frame_00045_f450.jpg。桌面对照文件：D:\study\Textile_defects\nanodet-main\nanodet-main\reports\ncnn_android\parity_results.json。
- Android instrumentation 使用整张照片作为全图 ROI、THREAD 目标类别，以便直接与桌面对照 JSON 的同一图像基线比较；正常服务按当前模板 ROI 裁切，ROI 到整图坐标另有边界与 EXIF 测试。每张图 Android 与桌面对照都有相同候选类别和数量，runtime 输出 shape 为 [3598,34]：

| 图像 | 0.05 候选（nut / thread） | 0.25 / 0.37 / 0.50 数量（nut / thread） | 0.37 结果（THREAD 全图 ROI） | Android 耗时 | 最大分数差 | 最大框坐标差 |
|---|---:|---|---|---:|---:|---:|
| frame_00106_f1060.jpg | 1 / 2 | 0/0、0/0、0/0 | DETECTED_BELOW_THRESHOLD，建议 NG，最高分 0.05253365 | 128 ms | 1.34e-7 | 2.85e-5 px |
| frame_00045_f450.jpg | 0 / 4 | 0/1、0/1、0/0 | DETECTED，建议 OK，最高分 0.37719017 | 70 ms | 5.96e-8 | 2.09e-5 px |

每个保留候选与桌面对照逐项比较如下。框坐标为完整正向照片像素 [left,top,right,bottom]；全图 ROI 情况下 ROI 框与照片框相同。

| 图像 | 类别 / 候选点 | 分数 | 照片框 | 最大坐标差 |
|---|---|---:|---|---:|
| frame_00106_f1060.jpg | nut / 333 | 0.15253267 | [406.551008,68.423849,647.429767,285.594564] | 1.09e-5 px |
| 同上 | thread / 1421 | 0.05253365 | [333.719050,574.121606,517.957475,754.208580] | 2.85e-5 px |
| 同上 | thread / 1423 | 0.05042312 | [396.481083,577.574914,556.029808,755.557204] | 9.47e-6 px |
| frame_00045_f450.jpg | thread / 2088 | 0.37719017 | [101.429867,867.645583,317.289225,1111.723550] | 2.09e-5 px |
| 同上 | thread / 3182 | 0.07598106 | [305.245024,734.651632,632.680904,1030.732395] | 1.56e-5 px |
| 同上 | thread / 380 | 0.05458792 | [310.559783,94.652222,461.364865,240.115322] | 1.00e-5 px |
| 同上 | thread / 1840 | 0.05015665 | [394.753640,757.221367,610.175308,983.499982] | 8.97e-6 px |

桌面对照 parity_results.json 不含完整原始张量；这里只比较运行时解码出的类别、候选数量、分数和框坐标，没有声称完成逐元素原始张量比较。

### 实际修改文件

- 构建/Native：app/build.gradle.kts；app/src/main/cpp/Android.mk、Application.mk、nanodet_ncnn_jni.cpp。
- 推理实现：app/src/main/java/com/wearable/inspection/mobile/detection/NanoDetCoordinateMapper.kt、NanoDetImagePreprocessor.kt、NanoDetInferenceModels.kt、NanoDetNcnnNative.java、NanoDetOutputDecoder.kt、NanoDetRoiInferenceService.kt；接线修改 app/src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt、app/src/main/java/com/wearable/inspection/mobile/ui/screens/RoiCoordinateMapper.kt、app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt。
- 测试：新增 app/src/test/java/com/wearable/inspection/mobile/detection/NanoDetInferenceContractTest.kt、app/src/test/java/com/wearable/inspection/mobile/detection/NanoDetRoiInferenceServiceTest.kt、app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiExifMapperTest.kt、app/src/androidTest/java/com/wearable/inspection/mobile/detection/NanoDetRoiRuntimeInstrumentedTest.kt；扩展 app/src/test/java/com/wearable/inspection/mobile/ui/screens/RoiCoordinateMapperTest.kt。
- 任务记录：本文件和 docs/reports/b3/NANODET_ANDROID_PREP_REPORT.md。旧 NCNN smoke 的 app/src/androidTest/cpp/ 与 .../ncnn/ 文件未改动。

### 构建、测试、APK 与设备恢复

- JVM 和构建命令：.\gradlew.bat --console=plain -q :app:testDebugUnitTest --tests com.wearable.inspection.mobile.detection.NanoDetInferenceContractTest --tests com.wearable.inspection.mobile.detection.NanoDetRoiInferenceServiceTest --tests com.wearable.inspection.mobile.ui.screens.RoiCoordinateMapperTest --tests com.wearable.inspection.mobile.ui.screens.RoiExifMapperTest :app:assembleDebug :app:assembleDebugAndroidTest。33 项通过、0 失败；主 APK 和 instrumentation APK 均构建成功。
- 真机命令：.\gradlew.bat --console=plain :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.wearable.inspection.mobile.detection.NanoDetRoiRuntimeInstrumentedTest"。YAL-AL10 上 4/4 通过，覆盖两图 Android/桌面对照、EXIF JPEG 原始栅格读取与方向像素坐标、以及 runtime/model/inference 错误状态。结果 XML：app/build/outputs/androidTest-results/connected/debug/TEST-YAL-AL10 - 10-_app-.xml；逐测试 logcat 位于同目录的 YAL-AL10 - 10 子目录。
- 测试前后均按门禁执行。测试后命令依次为 adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection、adb -s ERLDU20429005890 shell am force-stop com.wearable.inspection.mobile、adb -s ERLDU20429005890 install -r app\build\outputs\apk\debug\app-debug.apk、adb -s ERLDU20429005890 shell am start -W -n com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity，然后检查 pm list packages、两个包的 pidof 和 dumpsys activity activities。instrumentation 弹出相机权限请求后，使用 adb -s ERLDU20429005890 shell pm grant com.wearable.inspection.mobile android.permission.CAMERA，并再次停止新旧包、显式启动和核验：两个包均已安装，新包 PID 19157、旧包 PID 为空、前台 Activity 是 com.wearable.inspection.mobile/.MainActivity。没有启动旧包。

| APK | 路径 | 构建时间（Asia/Shanghai） | 字节数 | SHA-256 |
|---|---|---|---:|---|
| 主 debug APK（门禁恢复包） | app/build/outputs/apk/debug/app-debug.apk | 2026-09-14 17:39:07 +08:00 | 276579040 | 9AD4ED5B841C14FABEAC24ABF829938194CC98A7CA737A0087374A3D569C49D1 |
| Android test APK | app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | 2026-09-14 17:39:18 +08:00 | 11839158 | EB565E917EB7A5982DF8A1353F00842D789C09E51E48B4FAA50B341958AEAD28 |

### 限制与 Git 状态

- 0.37 尚未用具备人工真值的代表性照片集校准；本项只验证两张既定回归图，frame45 的 THREAD 最高分 0.37719 略高于该起始阈值。
- 未接确认页 UI、人工改判、数据库持久化、结果 ZIP、自动对齐或 CameraX 预览推理。没有新增截图/视觉验收；本任务只核对模型输出与 Android 包门禁。
- NDK r30 构建输出 -lncnn 非系统 linker flag 警告；库在 arm64 APK 中成功链接，且设备 runtime 测试通过。
- 当前任务已获用户验收。NanoDet 实现、测试、任务状态和本报告单独提交；DPM、现场采集、其他计划/文档改动保留在工作区并排除在提交之外。

## Android 后续集成边界

生产主 APK 现已包含 arm64-v8a 的 NCNN 模型资产、libncnn.so 与 ROI 静态照片推理 JNI；Android NCNN runtime smoke 和本项 ROI 推理均已由用户验收。仍未完成的后续工作包括代表性标注集上的阈值校准、更多照片/设备覆盖、性能与内存评估、确认页 UI、人工确认、结果持久化及 ZIP 集成。推理只在已保存照片上按当前模板 ROI 执行，不使用 CameraX 预览流；0.37 仍是未校准起始值。其他既有 Git 工作区改动保留并排除在本轮提交之外。

## NanoDet ROI 结果确认与人工终审（2026-09-14）

**状态：`SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE`。** 前序 NCNN Android smoke 与 ROI 静态照片推理均已验收。本项让既有确认页消费 `ViewModel.inferenceResults`，并将模型快照和人工终审写入同一既有 `view_roi_confirms` 记录；没有重做转换、NCNN smoke 或两张图的推理对照。

### 接入与结果持久化

- 每个 ROI 独立显示推理状态、目标类别、模型建议、最高匹配分数、阈值起始值、模型版本和检测框。全部保留框叠加在对应 ROI 照片裁切上。未配置属性、FEATURE 不支持、无框、照片/模板/模型/runtime 不可用、ROI 无效或推理错误均显示各自状态；没有将这些状态伪装成检测成功。0.37 显示为未校准起始阈值；不可执行模型的情况显示阈值未执行。
- 人工逐 ROI OK/NG 与模型建议分字段保存并保持人工独立选取，支持双向改判并记录 `humanChangedModel`。整张照片总体结果仍由人工单独选择，沿用 `overallResult/overallConfirmTime`。
- 继续复用 `ViewRoiConfirmEntity`。保存模型类别、全部保留检测框（类别、分数、ROI 框/照片框）、分数、阈值、状态、版本/模型摘要、推理耗时；复用 `confirmTime` 记录人工终审时间。DAO 按 `batchId + photoId` 读取和事务替换当前照片记录，再核对 photoPath、viewIndex、templateId 与 roiId。
- Room 从 v7 升至 v8，真实 migration 为已有确认行增加可空模型列和 `humanChangedModel DEFAULT 0`；对旧行清除既有 `softwareResult`，保留人工和稳定关联字段，旧模型结果继续为 null/未执行。Room schema v8 已导出。

### 验证、APK 与设备门禁

- 定向 JVM 共 44/44 通过：模型与人工分离、双向改判、总体照片结果独立、未执行/无框/错误态、每个推理状态标签、检测框坐标/模型摘要和确认时间、Compose 显示与人工选择、既有确认/照片关系契约。Compose UI 在 Robolectric 下 3/3 通过。
- YAL-AL10（Android 10）Room `AppDatabaseTest` instrumented 5/5 通过，含 v7→v8 migration 旧行兼容和按稳定批次/照片重载测试。最终 `connectedDebugAndroidTest` 只运行 `AppDatabaseTest`。
- 曾尝试 instrumented Compose UI 语义测试，但该宿主环境未建立 Compose hierarchy（另一次主 Activity 已设置 content）；这些尝试不记为通过。使用 Robolectric Compose 测试替代验证 UI 状态、改判和检测框覆盖。没有在真机完成确认页导航或截图视觉验收。
- 构建：`.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`，通过。主 APK `app/build/outputs/apk/debug/app-debug.apk`：2026-09-14 18:52:50 +08:00，276,579,040 bytes，SHA-256 `4F721E4244BD6D1FE133BBB2CBCB3453C8177234C12D56E84C32A58E4A243FDA`。Android test APK `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`：2026-09-14 18:40:29 +08:00，12,547,784 bytes，SHA-256 `F27578C350A60952DC43C42A0AA82E3B56F6BFAF0ECECC10E1AEE13642429476`。
- 测试前后均停止 `com.wearable.inspection` 和 `com.wearable.inspection.mobile`，安装主 APK 后用完整组件 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` 显式启动，并核对两个包安装状态、新包 PID、旧包 PID 与前台 Activity。instrumented runner 清除了相机权限，恢复时仅授予新包后重新启动和核验。最终新包 PID `4337`，旧包 PID 为空，前台是新包 `MainActivity`。未启动旧包。

完整文件列表、DB 列名、测试命令和异项工作区失败记录见 [`docs/reports/b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md`](../b2/VIEW_CONFIRMATION_ZIP_EXPORT_REPORT.md)。

### 限制与 Git

- 没有阈值校准、Detector 算法改动、CameraX 预览推理、ROI 汇总照片结果、ZIP/DPM 导出或新相机架构。

## 2026-09-15 批次 ZIP 显式关联扩展

NanoDet 已沿用现有 `ViewRoiConfirmEntity` 快照进入统一批次 CSV：全部检测框逐行保留，模型建议与人工最终结果/改判/确认时间分列，模型失败、无框、FEATURE、未配置和缺图均不伪造 OK/NG。真实归档测试覆盖多 View、多 ROI、双向改判与总体人工结果独立；最新样例目录为 `C:\Users\ws\AppData\Local\Temp\inspection-export7435539282332082454`。本轮同时修正照片行总体人工结果/时间、照片 ZIP 路径/状态的 manifest 列位，并将 DPM 批次证据限制为严格 batchId 相等且源帧真实非空。

本轮未重复 NCNN smoke、模型转换或 connected tests。结果相关 JVM 104/104 通过，Kotlin 编译和 Debug APK 构建通过；APK 为 2026-09-15 13:12:25 +08:00，SHA-256 `D2D7B57FF523EA82D48E7F1EEDCE4ED1FCAB7D32EC5CC192CAD2DEED06B6B35B`。全量 JVM 792 项完成、779 通过、13 失败、5 跳过；失败属于工作区既有并行改动/基线断言。
- 未运行 ADB、connectedDebugAndroidTest 或真机；结果包扩展实现已提交 Git：`f723da0e`，工作区其他改动保留，等待用户验收。
