# 视觉质检 App 使用说明文档报告

日期：2026-09-04

状态：**DOCUMENTATION_COMPLETE / RENDER_QA_UNAVAILABLE**

## 交付物

- [视觉质检 MobileInspectionApp 使用说明](../../user-guide/视觉质检MobileInspectionApp_使用说明.docx)

文档面向现场质检员、模板管理员和项目维护人员，覆盖：

- 三个一级入口：现场采集、追溯记录、我的
- 零件创建、DPM 绑定、模板视角拍摄/导入
- 模板详情、ROI 新增/移动/缩放/删除和目标属性选择
- 透明模板叠加、多视角拍照、ROI/总体 OK/NG 人工确认
- 检测报告、ZIP、按视角照片目录和 Excel 兼容 CSV
- 追溯批次筛选、选择、导出和删除
- 模板包导入/导出/删除、零件左滑删除和删除确认
- 应用设置、权限、存储、DPM、ROI、ZIP 常见问题

## 图片编排

源图片目录：`D:\study\刘老师\项目\AR眼镜\9.4 v1\图片`

共 20 张图片，全部嵌入 DOCX。正文按实际业务顺序编排：

1. 进入现场采集并发现尚未配置模板
2. 模板配置、创建零件、拍摄/导入视角、DPM 绑定
3. 视角详情和 ROI 属性配置
4. 现场采集、切换视角、人工确认
5. 检测报告和 ZIP 内容
6. 追溯记录、模板包和零件管理

图片源文件中 3 张 ZIP 内容截图的文件写入时间早于手机操作截图；报告在文档中将它们放到检测报告之后的逻辑输出环节，并在封面明确说明。

## 代码依据与口径

重点核对的源码入口：

- `AppNavigation.kt`：一级 Tab 和子流程导航
- `LiveInspectionScreen.kt`：零件选择、DPM/OCR 入口、模板叠加、拍照和批次关联
- `PartListScreen.kt` / `PartDetailScreen.kt`：零件和视角配置
- `TemplateDetailScreen.kt` / `RoiEditorScreen.kt`：模板详情和 ROI 编辑
- `ViewConfirmationScreen.kt` / `ViewConfirmationViewModel.kt`：ROI 与总体人工确认
- `ExportResultScreen.kt` / `InspectionZipExportService.kt`：检测报告、ZIP 和 CSV
- `TraceRecordsScreen.kt`：批次筛选、导出和清理
- `TemplatePackageScreen.kt` / `PartManagementScreen.kt`：模板包和零件维护
- `AppSettingsScreen.kt`：DPM 网格尺寸、预览显示比例和版本信息

文档明确保留以下当前版本行为：

- DPM 入口只支持手机相机实时扫码，不提供相册码图导入。
- ROI 和总体结果必须由人工选择；未选择时不默认 OK/NG，NG 仍保存。
- 软件检测结果保留为 null/未执行，人工 OK 不等于算法 PASS。
- 无 ROI 视角仍保存原始照片，但不生成 ROI 确认行。
- 所有视角完成且照片索引完整后才允许导出检测结果 ZIP。
- `inspection_result.csv` 是 ZIP 内的 Excel 兼容 CSV，不虚构为独立 `.xlsx`。
- 应用以离线、本机数据保存为当前页面口径。

## 验证记录

已执行：

- `images_audit.py`：DOCX 内嵌图片 20 张，尺寸和目标关系可解析。
- `a11y_audit.py`：高/中/低级问题均为 0；图片均设置了替代文本，表格首行已标记为表头。
- `section_audit.py`：1 个 Letter 纵向 section，四边 1 英寸页边距，页眉/页脚距离 0.492 英寸。
- 结构检查：20 个 inline shapes、20 个 `word/media` 图片、20 个图片文件名图注、22 个显式分页符；正文标题和当前版本边界文本存在。
- `git status --short`：保留工作区原有改动；本轮未提交 Git。

未执行：

- DOCX→PNG/PDF 渲染：当前环境没有 LibreOffice/`soffice`，标准 `render_docx.py` 无法启动，因此未宣称通过逐页视觉渲染验收。
- Gradle、ADB、APK 构建/安装、启动/停止和真机验收：本轮只制作文档，未执行。

## 本轮实际修改文件

- `docs/user-guide/视觉质检MobileInspectionApp_使用说明.docx`
- `tools/generate_user_manual.py`
- `tasks/todo.md`
- `docs/reports/b2/USER_GUIDE_20260904.md`

未修改旧工程；未提交 Git。
