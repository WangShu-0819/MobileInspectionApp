# V4/AKAZE 单张照片配准引擎实现报告

状态：**SOFTWARE_COMPLETE / AWAITING_USER_ACCEPTANCE**（2026-09-21）

## 概要

实现 V1-3"拍后模板与实拍比对 MVP"的底层配准引擎切片。当前只实现静态单张照片的 V4/AKAZE 配准、几何质量门禁、模板 ROI 四角投影和失败状态输出。

## 实际修改文件

### 生产代码（5 个）

| 文件 | 职责 |
|---|---|
| `registration/RegistrationResult.kt` | 数据类（RegistrationResult、ProjectedPoint、RegistrationStatus 枚举） |
| `registration/RegistrationConfig.kt` | 集中配置（AKAZE/Lowe/GMS/USAC/质量门禁阈值） |
| `registration/GmsGridFilter.kt` | 纯 Kotlin GMS 网格滤波（O(N) 分箱、3×3 邻域） |
| `registration/RegistrationQualityGates.kt` | 质量门禁（8 项检查） |
| `registration/PhotoRegistrationEngine.kt` | 主引擎（AKAZE→BFMatcher→GMS→Homography→门禁→投影） |

### 测试代码（3 个）

| 文件 | 项数 |
|---|---|
| `registration/RegistrationQualityGatesTest.kt` | 35 |
| `registration/GmsGridFilterTest.kt` | 7 |
| `registration/PhotoRegistrationEngineTest.kt` | 19 |
| **总计** | **61** |

## 既有链路审计结论

| 项目 | 结论 |
|---|---|
| 图片存储 | `MobileImageStore` 将现场照片保存到 `filesDir/captures/`，模板图保存到 `filesDir/template_images/`；拍照临时文件在 `cacheDir/capture_tmp/`，最终文件使用原子 rename 或 `.part` 写入，配准引擎无需新增存储路径。 |
| 照片/模板关联 | `CapturedPhotoEntity` 通过 `batchId`、`viewIndex`、`templateId` 和 `filePath` 关联现场照片与模板 View；当前没有配准矩阵或质量结果持久化字段，本轮保持不扩展 schema。 |
| EXIF | 文件未在存储时统一旋正；`MobileImageStore` 读取并返回 EXIF orientation。`RoiCoordinateMapper` 已覆盖 8 种 EXIF 方向并在裁剪时旋正，生产接入配准前必须沿用同一套 upright 坐标语义。OCR 路径仍存在 `android.media.ExifInterface` 与 AndroidX 两套实现，未在本轮改动。 |
| `contentRect` / ROI | `CameraPreview` 通过 `ContentRectCalculator` 计算 FIT_CENTER 的实际显示区域并在 `FrameInfo` 中上报；`RoiCoordinateMapper` 负责 normalized ROI、EXIF 和照片像素/裁剪坐标。其旧前提是现场照片已与模板对齐。本轮引擎只输出模板 ROI 四角的 Homography 投影，不替换 mapper，也不直接改变 UI overlay 坐标；后续接入需把投影结果接到现有显示/裁剪坐标链。 |
| OpenCV 依赖 | Android 使用 `org.opencv:opencv:4.10.0`；JVM 测试使用 `org.openpnp:opencv:4.9.0-0` 的桌面原生库。Android/JVM 均无可用 `xfeatures2d` GMS 实现，因此 GMS 使用本模块纯 Kotlin `GmsGridFilter`，AKAZE、BFMatcher、Calib3d Homography 和 Imgproc 复用现有 OpenCV。 |

## 算法实现

### 主路径

```
模板图 + 场景图 → 灰度化 → AKAZE detectAndCompute → 按 response 截断至 1000
→ BFMatcher knnMatch(K=2) → Lowe ratio(0.75) → GMS 网格滤波(20×20)
→ USAC_MAGSAC Homography(8px, 500 iter) → 读取 inlier mask
→ 质量门禁(8 项) → perspectiveTransform ROI 四角
```

### 关键参数

| 参数 | 值 | 来源 |
|---|---|---|
| AKAZE descriptor | MLDB, 3 channels | OpenCV 默认 |
| AKAZE threshold | 0.002 | 旧工程 V4 经验 |
| AKAZE max features | 1000 | 静态照片上限 |
| Lowe ratio | 0.75 | 经典值 |
| GMS grid | 20×20 | 旧工程 |
| GMS support threshold | 6 | 旧工程 |
| USAC threshold | 8.0px | 旧工程 |
| USAC max iterations | 500 | 旧工程 |
| USAC confidence | 0.995 | 旧工程 |

### 质量门禁

| 门禁 | 阈值 | 说明 |
|---|---|---|
| 最少内点数 | 10 | RANSAC inlier count |
| 最小内点比例 | 0.30 | inlier / good matches |
| 最大中位重投影误差 | 8.0px | median of inlier reprojection errors |
| 最小空间覆盖率 | 0.15 | inlier convex hull / bbox area |
| 最小投影面积比 | 0.005 | projected area / image area |
| 最大投影面积比 | 0.95 | projected area / image area |
| 图像边界容差 | 50px | projected corners margin |
| NaN/Inf 检查 | — | Homography 派生指标、空间覆盖率和投影点 |

### 失败语义

- 配准失败 → `RegistrationStatus.FAILED` 或 `FALLBACK_FULL_IMAGE`
- 失败时 `homography=null`、`projectedRoiCorners=null`
- 禁止返回看似有效的错误投影坐标
- `failureReason` 包含可读中文说明

## 测试结果

```
./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
```

完整 JVM 单元测试 XML：**1020 tests, 0 failures, 0 errors, 5 skipped**。
其中 V4/AKAZE：**61 tests, 0 failures, 0 errors, 0 skipped**。

### 测试覆盖

| 类别 | 测试 | 项数 |
|---|---|---|
| 1. 同图匹配 | same image matches successfully | 1 |
| 2a. 平移 | translation registration succeeds | 1 |
| 2b. 缩放 | scale registration succeeds | 1 |
| 2c. 旋转 | small rotation registration succeeds | 1 |
| 2d. 透视 | mild perspective registration succeeds | 1 |
| 3a. 弱纹理 | weak texture fails | 1 |
| 3b. 极小图 | tiny image fails | 1 |
| 3c. 无匹配 | completely different images fail | 1 |
| 4. 门禁-内点比例 | quality gate rejects low inlier ratio | 1 |
| 5. 投影方向 | successful registration has valid projected corners | 1 |
| 6. 失败语义 | failed registration returns null projected corners | 1 |
| 7. FALLBACK | fallback status has correct fields | 1 |
| 8. 稳定性 | repeated registration produces stable results | 1 |
| 9. 资源释放 | engine handles multiple sequential registrations | 1 |
| 9. 资源释放 | engine clear does not crash | 1 |
| 10. 纯色场景 | solid color scene fails | 1 |
| 10. 纹理差异 | different seeds produce different textures | 1 |
| 10. 结果完整性 | registration result has all required fields | 1 |
| 10. ROI 方向 | translated ROI projection preserves corner order | 1 |
| **集成测试合计** | **PhotoRegistrationEngineTest** | **19** |
| 门禁纯逻辑 | RegistrationQualityGatesTest | 35 |
| GMS 纯逻辑 | GmsGridFilterTest | 7 |
| **总计** | | **61** |

## 编译与构建

| 命令 | 结果 |
|---|---|
| `./gradlew.bat :app:compileDebugKotlin --no-daemon --rerun-tasks` | BUILD SUCCESSFUL |
| `./gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain` | BUILD SUCCESSFUL (1020 tests, 0 failures, 0 errors, 5 skipped) |
| `./gradlew.bat :app:assembleDebug --no-daemon` | BUILD SUCCESSFUL |

## JUnit XML 路径

```
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.registration.PhotoRegistrationEngineTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.registration.RegistrationQualityGatesTest.xml
app/build/test-results/testDebugUnitTest/TEST-com.wearable.inspection.mobile.registration.GmsGridFilterTest.xml
```

## APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 构建时间：2026-09-21 14:09:03 +08:00
- 大小：232151892 bytes
- SHA-256：`CC09EFC49096EA10C0F9FD7F89364A67B6F2ED2D57D0A98F7CBE541B80F315C1`

## 未完成项

无。

## 已知限制

1. **GMS xfeatures2d 不可用**：Android OpenCV SDK 和桌面 openpnp 均不包含 `xfeatures2d` 模块，已用纯 Kotlin `GmsGridFilter` 替代实现
2. **大角度旋转成功率下降**：旋转 >15° 时 AKAZE 特征匹配数量可能不足，属于后续优化范围
3. **桌面/Android OpenCV 版本差异**：桌面 4.9.0-0（openpnp）、Android 4.10.0，算法行为一致但版本号不同
4. **未运行 ADB、instrumented 或真机测试**：按任务边界禁止
5. **Git**：本报告 handback 时未提交；主协调完成独立审计后按当前任务路径选择性提交

## 不修改的组件

- CameraX、DPM、OCR、NanoDet 模型/阈值/解码器
- 旧 Wearable Inspection 工程
- 现有 ROI、确认、导出、批次清理功能
- 数据库 schema（无 migration）

## 后续顺序

1. 本任务验收通过后，实现 V1-3 CaptureComparisonScreen（切换、叠加、blink、缩放、平移和 Session ROI 人工微调）
2. 最后由独立任务把配准结果接入现有 NanoDet ROI 检测和结果包
