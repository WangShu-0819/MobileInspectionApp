# 执行 Agent 指令：V4/AKAZE 单张照片配准引擎

状态：**IMPLEMENTATION_AUTHORIZED / NOT_STARTED**（2026-09-21）

本任务是 V1-3“拍后模板与实拍比对 MVP”的底层配准引擎切片。用户已授权实现 V4/AKAZE；执行 Agent 负责源码、自动化测试和构建，主协调负责审计 handback、验证结果、更新文档和选择性 Git 收口。

## 一、开始前必须审计

先读取：

1. `tasks/todo.md` 顶部当前唯一任务；
2. `tasks/plan.md` 本任务指针；
3. `docs/reports/b3/PHOTO_REGISTRATION_ENGINE_OPTIONS.md`；
4. 当前工程中模板图片、现场照片、`MobileImageStore`、`CapturedPhotoEntity`、`RoiCoordinateMapper`、照片旋转/EXIF、`contentRect`、ROI 持久化和现有 OpenCV 依赖；
5. 旧工程 `OpenCvV4MatchEngine.kt` 及相关几何测试，仅作只读行为参考，不得修改旧工程，不得建立旧工程运行时依赖。

开始修改前必须在 handback 中列出实际复用路径、缺口和预计修改文件。不得先创建第二套 ROI、照片、检测结果或人工确认实体。

## 二、实现目标

输入：模板参考图、现场采集照片、模板 ROI 的规范坐标，以及实际图像区域/旋转所需信息。

实现一次性静态配准流程：

```text
AKAZE 特征
→ BFMatcher + Lowe ratio
→ GMS（当前 Android OpenCV 能力支持时）
→ Homography
→ 内点/覆盖率/重投影误差/投影四边形质量门禁
→ 模板 ROI 四角投影
```

输出一个稳定的配准领域结果对象，至少包含：

- 配准状态：成功、失败或 `FALLBACK_FULL_IMAGE` 建议；
- Homography 或投影后的模板四角；
- 内点数、内点比例、重投影误差、空间覆盖率；
- 投影四边形合法性结果；
- matcher 名称/版本；
- 可解释的失败原因。

质量门禁必须集中定义，不能把阈值散落在调用方。至少检查内点数量、内点比例、重投影误差、空间覆盖、凸四边形、面积、边界和异常数值。配准不可靠时，必须丢弃映射 ROI，不得返回看似有效的错误坐标。

`FALLBACK_FULL_IMAGE` 只表示后续业务可以考虑整图检测；本任务不在配准引擎内调用 NanoDet、不执行数量判定、不生成最终 OK/NG。

## 三、必须补充的 JVM 测试

至少覆盖：

1. 同图或可稳定匹配的基准样本；
2. 平移、缩放、旋转和轻微透视变化；
3. 特征不足、无匹配、错误模板和质量门禁拒绝；
4. 内点比例、重投影误差、空间覆盖和非法/越界投影四边形分别触发失败；
5. 成功时 ROI 四角投影方向和坐标范围正确；
6. 失败时不使用映射 ROI，只输出明确 fallback/失败状态；
7. 重复输入结果稳定，Bitmap/Mat/临时资源在成功和失败路径均释放。

优先使用可审计的固定测试夹具或确定性合成图；不得用单张真实图片硬编码通过，不得弱化断言，不得新增 `@Ignore` 或 skip。

## 四、明确不做

- 不实现完整 `CaptureComparisonScreen`、切换/透明度/blink/缩放/平移 UI；
- 不实现 Session ROI 拖动/缩放 UI；
- 不实现 ALIKED + LightGlue、双方案 fallback 或新的模型运行时；
- 不实现实时轮廓、实时姿态匹配、自动 `ALIGNED/LOST`、ROI 自动跟踪；
- 不修改 NanoDet 模型、算法、阈值、全图检测业务判定或人工最终结果语义；
- 不修改 CameraX、DPM、OCR、批次清理、ZIP/CSV 导出和旧工程。

## 五、验证与 handback

完成后至少运行并回填真实结果：

```text
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks
./gradlew.bat :app:assembleDebug --no-daemon
```

如全量测试发现前序回归，立即停止扩大范围并报告。当前任务不运行 ADB、instrumented 或真机，除非用户另行授权。handback 必须列出：实际修改文件、测试命令和结果、JUnit XML 路径、APK 路径/时间/大小/SHA-256（若构建成功）、未完成项和 Git 状态。执行 Agent 不提交 Git、不使用 `git add .`、reset、clean、stash 或回滚用户改动。
