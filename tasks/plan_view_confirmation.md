# Implementation Plan: 单零件多 View 人工确认与 ZIP 导出

## 概述

在现有拍照采集流程（LiveInspectionScreen → capture → advanceToNextView）中，插入人工确认环节。每拍完一张 View，弹出确认界面，展示所有 ROI 裁剪子图，工人逐个选择 OK/NG 并确认总体结果，再进入下一 View。全部 View 完成后生成包含照片+CSV 的 ZIP。

## 修改文件清单

### 新建文件 (10)

| 文件 | 用途 |
|------|------|
| `data/entity/ViewRoiConfirmEntity.kt` | 每条 ROI 人工确认记录实体 |
| `data/dao/ViewRoiConfirmDao.kt` | 新实体 DAO |
| `data/export/InspectionExcelExporter.kt` | CSV/Excel 检测结果生成 |
| `ui/screens/ViewConfirmationScreen.kt` | 人工确认 UI（ROI 列表 + 总体 OK/NG） |
| `ui/screens/ViewConfirmationViewModel.kt` | 确认流程 ViewModel |
| `test/.../RoiCoordinateMapperTest.kt` | 坐标映射 + ROI 子图裁剪测试 |
| `test/.../ViewRoiConfirmEntityTest.kt` | 实体序列化测试 |
| `test/.../InspectionExcelExporterTest.kt` | CSV 字段/行数/OK/NG 测试 |
| `test/.../InspectionZipExportServiceTest.kt` | ZIP 结构/隔离测试 |
| `test/.../ViewConfirmationFlowTest.kt` | 端到端流程回归测试 |

### 修改文件 (7)

| 文件 | 修改内容 |
|------|---------|
| `data/db/AppDatabase.kt` | 添加 ViewRoiConfirmEntity, 升级到 version=6, 注册新 DAO |
| `data/db/Migrations.kt` | 新增 MIGRATION_5_6 |
| `data/repository/InspectionRepository.kt` | 新增 ViewRoiConfirm 相关方法 |
| `MobileInspectionApp.kt` | 初始化 InspectionZipExportService |
| `ui/navigation/Screen.kt` | 新增 ViewConfirmation 路由 |
| `ui/navigation/AppNavigation.kt` | 注册 ViewConfirmation composable |
| `ui/screens/LiveInspectionScreen.kt` | 拍照成功后导航到确认页而非自动 advanceToNextView |

## 架构设计

### 数据模型

```
ViewRoiConfirmEntity (
    id: Long (autoGenerate PK),
    batchId: String,           -- 关联 CaptureBatchEntity
    photoId: Long,             -- 关联 CapturedPhotoEntity
    viewIndex: Int,
    templateId: String,
    templateName: String,
    roiId: String,
    roiName: String,
    roiTargetType: String?,    -- THREAD/NUT/FEATURE
    roiNormalizedRect: String, -- JSON normalizedRect
    roiPixelRect: String,      -- JSON 映射后像素坐标
    softwareResult: String?,   -- 预留，null
    humanResult: String,       -- OK / NG
    confirmTime: Long,
    overallResult: String,     -- OK / NG（冗余存储，方便查询）
    overallConfirmTime: Long
)
```

每条 ROI 一行。总体结果同时写在每行上（冗余，方便导出）。

### 坐标映射

复用现有纯函数（已在 LiveInspectionOverlayTest 中验证）：
- `parseNormalizedRect(json)` → `NormalizedRect`
- `mapNormalizedRectToContentRect(rect, contentRectBounds)` → `OverlayRect`

对于照片（非 PreviewView），contentRect = 整张照片的 (0, 0, width, height)。
即 normalizedRect 直接映射到照片像素坐标：

```kotlin
fun mapNormalizedRectToImagePixels(
    normalizedRect: NormalizedRect,
    imageWidth: Int,
    imageHeight: Int
): android.graphics.Rect {
    return android.graphics.Rect(
        (normalizedRect.left * imageWidth).toInt(),
        (normalizedRect.top * imageHeight).toInt(),
        (normalizedRect.right * imageWidth).toInt(),
        (normalizedRect.bottom * imageHeight).toInt()
    )
}
```

ROI 子图裁剪：使用 `BitmapFactory.decodeFile` + `Bitmap.createBitmap(bitmap, left, top, w, h)`。

### 流程

```
LiveInspectionScreen 拍照成功
    → 保存 CapturedPhotoEntity
    → 导航到 ViewConfirmationScreen(batchId, photoId, viewIndex, partId)
    → ViewConfirmationViewModel 加载照片 + ROI 列表
    → 裁剪 ROI 子图显示
    → 工人逐个选择 OK/NG + 总体 OK/NG
    → 确认 → 保存 ViewRoiConfirmEntity 列表
    → 判断是否还有下一 View
        → 有: 导航回 LiveInspectionScreen（自动 advanceToNextView）
        → 无: 导航到 ExportResultScreen(batchId, partId)
    → ExportResultScreen:
        → InspectionZipExportService.generateZip()
        → 显示 ZIP 信息 + 下载/分享按钮
```

### 中断恢复

- 已确认的 ViewRoiConfirmEntity 已持久化到 DB
- 重新进入时，ViewModel 检查 batchId 下已有 confirm 记录
- 跳过已确认的 View，从第一个未确认的 View 开始
- CaptureBatchEntity.endTime 不设置（保持 null 表示未完成）

### Excel (CSV) 字段

```csv
图片名称,零件ID,模板ID,ViewID,View名称,ROI_ID,ROI属性,ROI坐标,ROI_normalizedRect,ROI_像素坐标,软件检测结果,人工确认结果,人工确认时间,总体结果,总体确认时间
capture_001.jpg,part_001,tpl_001,view_1,视角1,roi_001,螺纹,"{left:0.1,top:0.2,right:0.3,bottom:0.4}","{left:0.1,top:0.2,right:0.3,bottom:0.4}","{left:100,top:200,right:300,bottom:400}",,OK,2026-09-04 10:30:00,OK,2026-09-04 10:30:05
```

### ZIP 结构

```
inspection_part001_batch123_20260904_103000.zip
├── captures/
│   ├── view_0_capture_xxx.jpg
│   ├── view_1_capture_yxx.jpg
│   └── ...
└── inspection_result.csv
```

### ZIP 文件名

`inspection_{partId}_{batchId_short}_{timestamp}.zip`

## 实现顺序

1. **Entity + DAO + Migration** — 数据层基础
2. **Repository 扩展** — 新增 CRUD 方法
3. **坐标映射工具** — 从 LiveInspectionScreen 提取为独立函数
4. **InspectionExcelExporter** — CSV 生成
5. **InspectionZipExportService** — ZIP 打包（照片+CSV）
6. **ViewConfirmationViewModel** — 确认流程逻辑
7. **ViewConfirmationScreen** — UI
8. **ExportResultScreen** — 导出结果展示+分享
9. **Navigation + Wiring** — 路由注册 + LiveInspectionScreen 修改
10. **测试** — 全部 12 项测试覆盖

## 测试策略

- JVM 单测：坐标映射、CSV 生成、ZIP 结构、实体序列化
- 不运行 adb / 不安装 APK / 不提交 Git
- 测试命令：`./gradlew testDebugUnitTest`
