# B2 模板拍摄、缩略图、重拍、排序 — 任务报告

**日期**：2026-09-03
**状态**：SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING

## 任务目标

在现有"我的 > 模板配置"基础上，完善模板管理能力：

1. 使用手机相机拍摄模板图片
2. 显示真实模板图片缩略图
3. 支持对指定 View 重拍并替换原图
4. 支持手动调整 View 顺序并持久化

## 实际修改文件

### 新增文件

| 文件 | 职责 |
|------|------|
| `app/src/main/java/.../template/TemplateCaptureViewModel.kt` | 拍摄 ViewModel：状态管理、新增/重拍、失败回滚 |
| `app/src/main/java/.../ui/screens/TemplateCaptureScreen.kt` | 拍摄页面：复用 CameraPreview TEMPLATE_CAPTURE 模式 |

### 修改文件

| 文件 | 变更内容 |
|------|----------|
| `app/src/main/java/.../data/image/MobileImageStore.kt` | 新增 `storeTemplateImage()` / `deleteTemplateImage()` / `getTemplateImagesPath()` |
| `app/src/main/java/.../data/dao/TemplateDao.kt` | 新增 `updateDisplayOrder()` / `reorderTemplates()` |
| `app/src/main/java/.../data/repository/InspectionRepository.kt` | 新增 `reorderTemplates()` / `storeTemplateImage()` / `deleteTemplateImage()` / `generateTempFile()` / `deleteTempFile()` |
| `app/src/main/java/.../ui/navigation/Screen.kt` | 新增 `TemplateCapture` 路由（支持新增和重拍两种模式） |
| `app/src/main/java/.../ui/navigation/AppNavigation.kt` | 注册 TemplateCapture 路由，传递 onCaptureNew / onRecapture 回调 |
| `app/src/main/java/.../ui/screens/TemplateConfigScreen.kt` | 缩略图列表、重拍按钮、上移/下移排序、拍摄新视角入口 |
| `app/src/main/java/.../ui/screens/TemplateDetailScreen.kt` | 重拍按钮 |
| `app/src/test/java/.../data/image/MobileImageStoreTest.kt` | 新增 7 项模板图片存储测试 |

## 功能完成情况

### 1. 模板拍摄 ✅

- 入口：模板配置 → 每 Part 组"拍摄新视角"按钮
- 复用唯一 CameraController 的 TEMPLATE_CAPTURE 模式（Preview + ImageCapture）
- 不创建第二个 ProcessCameraProvider
- 拍摄成功后写入 template_images/ 目录和数据库
- 每张图片关联明确的 Part / Template / View
- 取消、返回、权限拒绝时 CameraPreview DisposableEffect 自动释放资源

### 2. 缩略图 ✅

- TemplateConfigScreen 每行显示真实本地模板图片缩略图
- 图片加载失败时显示占位图标（Icons.Default.Photo）
- 无模板图片时显示"无图片"状态文字
- 不用固定占位图片冒充真实模板

### 3. 重拍 ✅

- 每行"重拍"按钮导航到 TemplateCaptureScreen 重拍模式
- TemplateDetailScreen 增加重拍按钮
- 重拍成功后替换该 View 的 mainImagePath
- 保留原 View 的稳定 ID、名称和 displayOrder
- 新图片写入成功后再删除旧图片
- 保存失败时旧图片和数据库记录保持可用

### 4. 排序 ✅

- 每行右侧上移/下移按钮
- 排序结果写入 displayOrder
- 排序保存后 Flow 自动刷新列表
- 不会产生相同顺序值（交换逻辑）

## 前序能力回归矩阵

| 能力 | 状态 | 说明 |
|------|------|------|
| 导航正常 | ✅ | TemplateConfig → TemplateDetail / TemplateCapture 路由正确 |
| CameraController 模式切换 | ✅ | TEMPLATE_CAPTURE 模式复用现有 CameraPreview |
| FIT_CENTER 和 contentRect | ✅ | CameraPreview 组件未修改核心画幅逻辑 |
| 模板透明叠加 | ✅ | CameraPreview overlay 逻辑未受影响 |
| ZIP/相册导入 | ✅ | TemplateImportService 未修改 |
| DPM/OCR 入口 | ✅ | 导航路由未受影响 |
| 图片资源释放 | ✅ | CameraPreview DisposableEffect 负责 disconnect |
| 错误态和取消态 | ✅ | TemplateCaptureScreen 完整错误处理 |

## JVM/测试结果

```
:app:compileDebugKotlin — BUILD SUCCESSFUL
:app:testDebugUnitTest — 325 项（320 passed / 0 failed / 5 skipped）
:app:assembleDebug — BUILD SUCCESSFUL
```

新增测试：
- MobileImageStoreTest 新增 7 项：模板图片存储成功、临时文件清理、空文件失败、无 part 残留、删除成功、安全检查、目录路径

## APK 信息

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 生成时间：2026-09-03 10:35
- 大小：221,475,126 bytes（~211 MB）
- SHA-256：`56e390a067ccd1a040ea05b86b9743bc185bf2c1215630e7fc0f4f35a9e7f495`
- 包名：`com.wearable.inspection.mobile`
- 启动组件：`com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`

## 未完成项和后续边界

### 待真机验收

1. 模板拍摄：从模板配置进入拍摄页面，拍摄 2+ View
2. 缩略图：模板配置页显示真实缩略图
3. 重拍：点击重拍按钮，替换指定 View 图片
4. 重拍失败：旧图片仍可用
5. 排序：上移/下移 View，重新进入页面顺序保持
6. 现场采集：模板叠加使用新顺序

### 后续边界（本轮不实现）

- V1-3 拍后比对（CaptureComparisonScreen）
- Template ROI 编辑器
- Detector / AlgorithmRegistry
- PASS/FAIL/REVIEW/ERROR
- InspectionSession 结果写入
- 结果详情、追溯记录、结果包导出
- 实时轮廓提取、Homography、SIFT、自动对齐
