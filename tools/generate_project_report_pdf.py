from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    BaseDocTemplate,
    Flowable,
    Frame,
    Image,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.utils import ImageReader


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "output" / "pdf" / "PROJECT_WORKLOAD_AND_ALGORITHM_REPORT_20260904.pdf"
FONT_REGULAR = Path(r"C:\Windows\Fonts\Deng.ttf")
FONT_BOLD = Path(r"C:\Windows\Fonts\Dengb.ttf")

PAGE_W, PAGE_H = A4
LEFT = 18 * mm
RIGHT = 18 * mm
TOP = 20 * mm
BOTTOM = 17 * mm
CONTENT_W = PAGE_W - LEFT - RIGHT

TEAL = colors.HexColor("#116B8C")
TEAL_DARK = colors.HexColor("#0A4B63")
PALE_BLUE = colors.HexColor("#EAF3F7")
PALE_GRAY = colors.HexColor("#F4F6F7")
TEXT = colors.HexColor("#26343D")
MUTED = colors.HexColor("#66747C")
GREEN = colors.HexColor("#287A54")
ORANGE = colors.HexColor("#B36B00")
RED = colors.HexColor("#A74444")


def register_fonts():
    if not FONT_REGULAR.exists() or not FONT_BOLD.exists():
        raise FileNotFoundError("Chinese fonts were not found in C:\\Windows\\Fonts")
    pdfmetrics.registerFont(TTFont("Deng", str(FONT_REGULAR)))
    pdfmetrics.registerFont(TTFont("DengBold", str(FONT_BOLD)))


class ReportDocTemplate(BaseDocTemplate):
    def __init__(self, filename, **kwargs):
        super().__init__(filename, **kwargs)
        frame = Frame(LEFT, BOTTOM, CONTENT_W, PAGE_H - TOP - BOTTOM, id="normal")
        self.addPageTemplates([PageTemplate(id="main", frames=[frame], onPage=draw_page)])


def draw_page(canvas, doc):
    canvas.saveState()
    if doc.page > 1:
        canvas.setStrokeColor(colors.HexColor("#D7E3E8"))
        canvas.setLineWidth(0.5)
        canvas.line(LEFT, PAGE_H - 13 * mm, PAGE_W - RIGHT, PAGE_H - 13 * mm)
        canvas.setFont("Deng", 8)
        canvas.setFillColor(MUTED)
        canvas.drawString(LEFT, PAGE_H - 10 * mm, "视觉质检 MobileInspectionApp")
        canvas.drawRightString(PAGE_W - RIGHT, PAGE_H - 10 * mm, "工程工作量与 ROI 算法结果")
    canvas.setStrokeColor(colors.HexColor("#D7E3E8"))
    canvas.setLineWidth(0.5)
    canvas.line(LEFT, 11 * mm, PAGE_W - RIGHT, 11 * mm)
    canvas.setFont("Deng", 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(LEFT, 7 * mm, "更新时间：2026-09-04")
    canvas.drawRightString(PAGE_W - RIGHT, 7 * mm, f"第 {doc.page} 页")
    canvas.restoreState()


styles = getSampleStyleSheet()
styles.add(ParagraphStyle(
    name="CoverTitle", fontName="DengBold", fontSize=25, leading=34,
    textColor=TEAL_DARK, alignment=TA_CENTER, spaceAfter=8 * mm,
))
styles.add(ParagraphStyle(
    name="CoverSub", fontName="Deng", fontSize=13, leading=21,
    textColor=MUTED, alignment=TA_CENTER, spaceAfter=5 * mm,
))
styles.add(ParagraphStyle(
    name="H1CN", fontName="DengBold", fontSize=17, leading=25,
    textColor=TEAL_DARK, spaceBefore=3 * mm, spaceAfter=4 * mm,
))
styles.add(ParagraphStyle(
    name="H2CN", fontName="DengBold", fontSize=12.5, leading=19,
    textColor=TEAL, spaceBefore=3 * mm, spaceAfter=2 * mm,
))
styles.add(ParagraphStyle(
    name="H3CN", fontName="DengBold", fontSize=10.5, leading=16,
    textColor=TEAL_DARK, spaceBefore=2 * mm, spaceAfter=1.5 * mm,
))
styles.add(ParagraphStyle(
    name="BodyCN", fontName="Deng", fontSize=9.3, leading=15,
    textColor=TEXT, wordWrap="CJK", spaceAfter=2.5 * mm,
))
styles.add(ParagraphStyle(
    name="SmallCN", fontName="Deng", fontSize=8, leading=12,
    textColor=MUTED, wordWrap="CJK", spaceAfter=1.5 * mm,
))
styles.add(ParagraphStyle(
    name="CaptionCN", fontName="Deng", fontSize=8.3, leading=12,
    textColor=MUTED, alignment=TA_CENTER, spaceBefore=1.5 * mm, spaceAfter=3 * mm,
))
styles.add(ParagraphStyle(
    name="TableCN", fontName="Deng", fontSize=7.9, leading=11,
    textColor=TEXT, wordWrap="CJK",
))
styles.add(ParagraphStyle(
    name="TableHeadCN", fontName="DengBold", fontSize=7.9, leading=11,
    textColor=colors.white, wordWrap="CJK",
))
styles.add(ParagraphStyle(
    name="CodeCN", fontName="Deng", fontSize=7.5, leading=11,
    textColor=TEXT, backColor=PALE_GRAY, leftIndent=3 * mm, rightIndent=3 * mm,
    borderPadding=2 * mm, wordWrap="CJK",
))


def p(text, style="BodyCN"):
    return Paragraph(text, styles[style])


def bullet(text):
    return Paragraph(f"<font color='{TEAL}'>•</font> {text}", styles["BodyCN"])


def h1(text):
    return p(text, "H1CN")


def h2(text):
    return p(text, "H2CN")


def h3(text):
    return p(text, "H3CN")


def table(rows, widths, header=True, font_size=7.9):
    data = []
    for row_index, row in enumerate(rows):
        converted = []
        for value in row:
            style = "TableHeadCN" if header and row_index == 0 else "TableCN"
            converted.append(p(str(value), style))
        data.append(converted)
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0, hAlign="LEFT")
    commands = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#C9D6DB")),
    ]
    if header:
        commands += [("BACKGROUND", (0, 0), (-1, 0), TEAL)]
        for i in range(1, len(rows)):
            if i % 2 == 0:
                commands.append(("BACKGROUND", (0, i), (-1, i), PALE_GRAY))
    t.setStyle(TableStyle(commands))
    return t


def image_flowable(path, max_width=CONTENT_W, max_height=205 * mm):
    path = Path(path)
    if not path.exists():
        return p(f"图片缺失：{path}", "SmallCN")
    iw, ih = ImageReader(str(path)).getSize()
    scale = min(max_width / iw, max_height / ih)
    img = Image(str(path), width=iw * scale, height=ih * scale)
    img.hAlign = "CENTER"
    return img


def add_image(story, path, caption, max_width=CONTENT_W, max_height=205 * mm):
    story.append(image_flowable(path, max_width=max_width, max_height=max_height))
    story.append(p(caption, "CaptionCN"))


def add_callout(story, title, text, color=TEAL):
    block = Table([[p(f"<b>{title}</b><br/>{text}", "BodyCN")]], colWidths=[CONTENT_W])
    block.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), PALE_BLUE),
        ("BOX", (0, 0), (-1, -1), 0.8, color),
        ("LINEBEFORE", (0, 0), (0, -1), 4, color),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    story.append(block)
    story.append(Spacer(1, 4 * mm))


def build_story():
    story = []
    assets = ROOT / "docs" / "online" / "assets"
    alg = ROOT / "docs" / "reports" / "b3" / "feature_presence"

    story.append(Spacer(1, 20 * mm))
    story.append(p("视觉质检 MobileInspectionApp", "CoverTitle"))
    story.append(p("工程工作量、功能交付与 ROI 算法结果", "CoverSub"))
    story.append(p("A4 带图项目总结版 | 2026-09-04", "CoverSub"))
    story.append(Spacer(1, 8 * mm))
    add_callout(
        story,
        "当前结论",
        "Android 主链路已形成；Thread/Nut ROI 检测器已完成离线 Key 回归，但尚未接入 Android 现场检测。DCIM 尚无人工 ROI 和 present/absent 标签，因此不报告现场准确率。",
    )
    add_image(story, assets / "01_live_capture_template.jpg", "项目流程示意：现场采集、模板配置和检测结果形成闭环。", max_width=95 * mm, max_height=145 * mm)
    story.append(PageBreak())

    story.append(h1("目录与阅读口径"))
    story.append(p("本文将项目交付拆成四部分：已经完成的工程能力、按现场顺序的使用流程、Thread/Nut/Feature ROI 算法结果，以及当前数据和验收边界。工作量按真实源码、测试、报告、图片和构建产物统计，不把离线原型结果写成 APK 的现场准确率。"))
    story.append(table([
        ["章节", "内容"],
        ["1", "工程工作量总览、阶段成果和当前交付物"],
        ["2", "现场配置、采集、人工确认、报告导出的 20 步图片流程"],
        ["3", "Thread 螺纹、Nut 螺母、Feature 部件 ROI 算法与结果"],
        ["4", "数据集、自动化验证、限制和下一步"],
    ], [18 * mm, CONTENT_W - 18 * mm]))
    story.append(PageBreak())

    story.append(h1("1. 工程工作量总览"))
    story.append(p("项目没有提供按人天统计的工时记录，以下采用可核验的交付量统计。"))
    story.append(table([
        ["交付维度", "当前规模或结果", "说明"],
        ["Android 主源码", "106 个 Kotlin 文件", "相机、数据、模板、采集、追溯、导出、DPM、OCR 和页面导航"],
        ["JVM 测试", "56 个 Kotlin 测试文件", "覆盖持久化、坐标映射、导出、导航、相机状态和 UI 状态契约"],
        ["Instrumented 测试", "7 个 Kotlin 测试文件", "覆盖相机生命周期、DPM 设置等设备侧契约"],
        ["离线算法", "4 个 Python 文件", "数据准备、Thread/Nut/Feature 检测、评估和测试"],
        ["项目报告", "34 个 Markdown 报告", "B1/B2/B3 阶段报告、算法报告和验收记录"],
        ["项目方流程图片", "20 张", "已复制到仓库 docs/online/assets/ 并按业务顺序引用"],
        ["最新 Debug APK", "221,529,142 字节", "当前代码构建成功，未执行真机安装"],
    ], [35 * mm, 40 * mm, CONTENT_W - 75 * mm]))
    story.append(h2("阶段成果"))
    story.append(table([
        ["阶段", "主要工作", "状态"],
        ["A / B0", "新工程边界、旧工程迁移审计、源码结构整理", "已完成"],
        ["B1", "CameraX 控制器、状态/生命周期、画幅/contentRect、真实拍照与存储、完整回归", "已完成并通过真机验收"],
        ["B2", "DPM 迁移、模板包、透明叠加、ROI 属性、模板拍摄/排序、人工确认和 ZIP 导出", "主要软件链路完成，部分真机验收待执行"],
        ["B3 OCR", "钢印 OCR 核心算法和 CameraX/UI 集成", "软件完成；JVM 308 项中 303 通过、0 失败、5 跳过"],
        ["B3 ROI 算法", "Thread、Nut、Feature 三个轻量离线检测器和评估链路", "离线原型完成，Android 集成延期"],
    ], [22 * mm, 103 * mm, CONTENT_W - 125 * mm]))
    story.append(PageBreak())

    story.append(h1("2. 当前版本交付物"))
    story.append(h2("APK"))
    story.append(p("构建命令：<font name='DengBold'>.\\gradlew.bat :app:assembleDebug --no-daemon</font>。结果为 BUILD SUCCESSFUL，38 个 actionable tasks。"))
    story.append(table([
        ["项目", "值"],
        ["APK 路径", r"D:\study\Textile_defects\Wearable Inspection\MobileInspectionApp\app\build\outputs\apk\debug\app-debug.apk"],
        ["构建时间", "2026-09-04 18:17:46"],
        ["大小", "221,529,142 字节"],
        ["SHA-256", "6BFEF6CC61D826439C3F9A7B25041E14C554FC5C750813810B6B13F56D7F9179"],
        ["应用包名", "com.wearable.inspection.mobile"],
        ["启动组件", "com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity"],
    ], [32 * mm, CONTENT_W - 32 * mm]))
    story.append(h2("Git 交付"))
    story.append(table([
        ["提交", "内容"],
        ["1a5aaea9", "模板导入、视图顺序、采集流程及测试"],
        ["3a38ae4b", "B2 验证报告与任务清单"],
        ["c986ed58", "带图片的使用说明文档及生成脚本"],
        ["b5c68d78", "工程工作量与 ROI 算法结果在线总结及 20 张业务图片"],
    ], [30 * mm, CONTENT_W - 30 * mm]))
    story.append(PageBreak())

    story.append(h1("3. 现场使用流程（按业务顺序）"))
    story.append(p("现场流程为：创建模板 -> 创建零件 -> 绑定 DPM -> 添加视角和 ROI -> 现场按顺序拍照 -> 有 ROI 时人工确认 -> 全部完成后导出 ZIP -> 追溯记录查看和维护。"))

    steps = [
        ("3.1 进入现场采集并配置模板", "首次进入现场采集时，如果当前零件没有可用模板，应先进入模板配置。", "01_live_capture_template.jpg", "图 1：现场采集入口发现当前零件尚未完成模板配置。"),
        ("", "新建模板，后续模板视角、图片和 ROI 都归属于该模板。", "02_create_template.jpg", "图 2：新建模板。"),
        ("", "创建模板所属零件，建议使用稳定的零件 ID，便于追溯和导出。", "03_create_part.jpg", "图 3：填写零件 ID 和名称。"),
        ("", "零件创建完成后，可以绑定 DPM 码，并进入模板视角拍摄或导入图片。", "04_part_dpm_and_import.jpg", "图 4：配置 DPM 并拍摄或导入模板图片。"),
        ("3.2 绑定 DPM 并准备模板视角", "DPM 入口只支持手机相机实时扫码，不提供相册码图导入。", "05_template_ready_capture.jpg", "图 5：模板配置完成后开始采集。"),
        ("", "为零件绑定 DPM 码；冲突绑定应被拒绝，未知码只提示先完成模板绑定。", "06_bind_dpm.jpg", "图 6：配对 DPM 码。"),
        ("3.3 设置每个视角的 ROI 和目标属性", "模板视角中可以新增、选中、移动、缩放、删除 ROI，并保存 normalizedRect。", "07_view_set_roi.jpg", "图 7：单个视角设置 ROI。"),
        ("", "每个 ROI 选择 THREAD 螺纹、NUT 螺母或 FEATURE 部件；历史空值显示未选择。", "08_roi_property.jpg", "图 8：ROI 编辑和目标属性选择。"),
        ("", "确认 ROI 位置和属性后保存模板配置。", "09_roi_save.jpg", "图 9：保存 ROI 编辑结果。"),
        ("3.4 多视角现场拍照与人工确认", "现场采集按模板中的 View 顺序进行；照片先真实写入当前采集批次。", "10_view_capture_done.jpg", "图 10：当前视角完成拍照并保存现场照片。"),
        ("", "按模板顺序切换到下一个采集视角。", "11_switch_view.jpg", "图 11：切换采集视角。"),
        ("", "有 ROI 的 View 进入确认页，逐个选择 ROI OK/NG，并单独选择整张照片总体 OK/NG。", "12_manual_roi_confirmation.jpg", "图 12：拍照后人工确认 ROI 和总体结果。"),
        ("3.5 生成报告和导出 ZIP", "所有 View 都完成且照片索引完整后，才允许生成检测报告。", "13_report_download_share.jpg", "图 13：生成检测报告、下载和分享。"),
        ("", "ZIP 包按采集批次关联，按 View 分目录保存原始现场照片。", "14_zip_overview.png", "图 14：ZIP 包整体结构。"),
        ("", "ZIP 包中的照片按采集视角组织。", "15_zip_photos.png", "图 15：ZIP 包图片目录。"),
        ("", "结果文件为 UTF-8 BOM 的 Excel 兼容 CSV，不伪造为独立 XLSX。", "16_zip_excel.png", "图 16：ZIP 包中的结果文件。"),
        ("3.6 追溯记录、模板包和零件管理", "追溯记录可以按零件、批次查看和导出；已完成批次才允许导出 ZIP。", "17_trace_records.jpg", "图 17：追溯采集记录的删除和下载。"),
        ("", "模板包用于迁移模板、视角、图片、顺序、DPM 和 ROI 属性。", "18_template_package.jpg", "图 18：模板包导入和导出。"),
        ("", "零件管理提供左滑删除入口。", "19_part_swipe_delete.jpg", "图 19：零件管理左滑删除。"),
        ("", "删除前显示零件信息并二次确认，避免误删。", "20_part_delete_confirm.jpg", "图 20：零件删除确认。"),
    ]
    for title, text, filename, caption in steps:
        if title:
            story.append(h2(title))
        story.append(p(text))
        add_image(story, assets / filename, caption, max_width=100 * mm, max_height=150 * mm)
        story.append(Spacer(1, 2 * mm))
    story.append(PageBreak())

    story.append(h1("4. ROI 算法当前结果"))
    story.append(h2("4.1 算法定位"))
    story.append(p("当前 B3 算法是 presence-offline.3 离线原型，源码位于 tools/feature_presence/presence_detectors.py。三个检测器共享 DetectionResult、ROI 校验和 debug 输出接口，但尚未接入 Android App。"))
    story.append(table([
        ["检测器", "主要方法", "输出"],
        ["ThreadPresenceDetector", "Hough 圆候选 -> 局部圆心/半径精修 -> 几何、中心暗孔、周期纹理和清晰度评分", "圆、box、score、metrics、PASS/FAIL/REVIEW"],
        ["NutPresenceDetector", "CLAHE/Canny/Otsu 多阈值 -> 嵌套轮廓和近六边形主体 -> 中心孔证据 -> NMS", "主体 box、每框评分、数量和 PASS/REVIEW"],
        ["FeaturePresenceDetector", "pHash 粗筛 -> 多尺度模板匹配 -> AKAZE/BFMatcher -> affine RANSAC", "匹配框、相似度、特征点和覆盖度证据"],
    ], [38 * mm, 75 * mm, CONTENT_W - 113 * mm]))
    story.append(h2("4.2 Thread（螺纹）"))
    add_image(story, alg / "evaluation" / "debug" / "thread_key_contact_sheet.jpg", "图 21：Thread Key 批量结果；橙色为 raw Hough 候选，绿色为 refined 圆。", max_width=CONTENT_W, max_height=105 * mm)
    story.append(table([
        ["数据集", "数量", "结果", "结论"],
        ["Thread Key 正样本", "16", "16/16 PASS", "未发现状态级漏检；thread_13 缺失，不补造样本"],
        ["通用合成负样本", "12", "12/12 未 PASS", "普通圆孔、亮圆、断裂环、线性纹理、随机噪声等被拦截"],
        ["原图派生无螺纹孔负样本", "16", "14 PASS、2 REVIEW", "负样本鲁棒性未通过，暴露全图搜索误选邻近圆形结构的风险"],
    ], [37 * mm, 18 * mm, 38 * mm, CONTENT_W - 93 * mm]))
    story.append(p("实现重点：Hough 只生成候选，随后执行局部圆心/半径搜索、暗孔中心 proposal、几何支持、圆周角度覆盖、周期纹理、共心度和背景惩罚。Key 没有像素级真值标注，因此不能把这些结果写成真实定位误差。"))
    story.append(h2("4.3 Nut（螺母）"))
    add_image(story, alg / "evaluation" / "debug" / "key" / "nut_key_batch_contact_sheet.jpg", "图 22：Nut Key 批量结果；绿色框为 NMS 后的主体框，默认六边形几何角度为 0°。", max_width=CONTENT_W, max_height=112 * mm)
    story.append(p("五张 Nut Key 图片均由项目方确认包含 2 个螺母，回归时通过运行时 expectedCount=2 验证；该值没有写死到检测器中。"))
    story.append(table([
        ["文件", "状态", "score", "最终数量", "最终框"],
        ["nut_1.png", "PASS", "0.6945", "2", "[[221,54,64,57],[65,57,69,62]]"],
        ["nut_3.jpg", "PASS", "0.5929", "2", "[[831,362,173,202],[302,394,227,192]]"],
        ["nut_4.jpg", "PASS", "0.5876", "2", "[[426,1034,298,323],[549,446,250,228]]"],
        ["nut_5.jpg", "REVIEW", "0.4785", "2", "[[900,396,156,153],[429,400,154,150]]"],
        ["nut_6.jpg", "REVIEW", "0.4598", "2", "[[312,406,171,166],[793,424,187,160]]"],
    ], [30 * mm, 25 * mm, 22 * mm, 24 * mm, CONTENT_W - 101 * mm]))
    story.append(p("Nut 负样本：通用合成负样本 8/8 无候选、无最终框；原图派生无螺母零件 5/5 无候选、无最终框。当前 expectedCount=0 的空结果语义为 REVIEW，不应误解为误检 PASS。"))
    story.append(h2("4.4 Feature（部件）"))
    story.append(p("Feature 检测器使用 pHash、模板匹配和 AKAZE/affine RANSAC 的轻量证据链。Key 自检中的 feature_1.png 和 feature_2.png 均为 PASS，但这同样不等于现场准确率。当前不实现 Homography、SIFT、自动姿态对齐或自动 ROI。"))
    story.append(PageBreak())

    story.append(h1("5. 算法可视化与数据边界"))
    add_image(story, alg / "evaluation" / "negative_thread" / "negative_thread_contact_sheet.jpg", "图 23：Thread 通用合成负样本；12/12 未 PASS。", max_width=CONTENT_W, max_height=90 * mm)
    add_image(story, alg / "evaluation" / "negative_thread" / "original_based" / "thread_original_based_negative_contact_sheet.jpg", "图 24：Thread 原图派生负样本；14/16 仍 PASS，提示全图搜索误检风险。", max_width=CONTENT_W, max_height=90 * mm)
    add_image(story, alg / "evaluation" / "negative_nut" / "nut_negative_contact_sheet.jpg", "图 25：Nut 通用合成负样本；8/8 无候选。", max_width=CONTENT_W, max_height=90 * mm)
    add_image(story, alg / "evaluation" / "negative_nut" / "original_based" / "nut_original_based_negative_contact_sheet.jpg", "图 26：Nut 原图派生无螺母负样本；5/5 无候选。", max_width=CONTENT_W, max_height=90 * mm)
    story.append(h2("数据集现状"))
    for item in [
        "当前 Key 目录包含 23 张可读图片：Thread 16 张、Nut 5 张、Feature 2 张。Key 图片本身是已裁剪 ROI 小图，使用 [0,0,1,1] 作为模板 ROI 约定。",
        "DCIM 有 30 张 JPG，全部可读；120 个目标记录的标签全部为 unknown，ROI 全部为 null，状态为 UNANNOTATED。",
        "由于 DCIM 没有人工确认的目标 ROI 和 present/absent 标签，评估状态为 INSUFFICIENT_DATA，不计算 accuracy、recall 或 confusion matrix。",
    ]:
        story.append(bullet(item))
    story.append(PageBreak())

    story.append(h1("6. 测试、限制与下一步"))
    story.append(h2("已执行验证"))
    story.append(table([
        ["验证项", "结果"],
        ["Thread 专项回归", "16 tests，OK，16 张 Thread Key 全部 PASS"],
        ["Nut/Feature/Thread 综合离线验收", "17 tests，OK"],
        ["数据准备 self-test", "SELF_TEST_PASS"],
        ["Key/DCIM 解码检查", "Key 23 张、DCIM 30 张均可读；初始 Task 1 快照曾记录 5 张基准图"],
        ["DCIM 检测评估", "120/120 SKIPPED，未伪造准确率"],
        ["Android Debug APK", "assembleDebug 成功"],
    ], [55 * mm, CONTENT_W - 55 * mm]))
    story.append(h2("当前限制"))
    for item in [
        "ROI 算法尚未接入 Android：离线回归不能表示 APK 已经在现场实时检测。",
        "Thread 原图派生负样本出现 14/16 PASS，说明全图搜索可能把邻近圆形结构当成螺纹，应优先使用模板 ROI 或更严格的空间约束。",
        "Nut 的 nut_5 和 nut_6 数量正确但质量为 REVIEW，不应抬高 score 强制 PASS。",
        "现场准确率需要真实 ROI、present/absent 标签以及训练/验证划分；在此之前只报告样本回归，不报告准确率。",
        "软件检测结果仍可为 null/未执行；人工 OK/NG 是人工确认，不应伪装成算法 PASS/FAIL。",
        "B2 的人工确认、多 View 批次复用、无 ROI View、ZIP 导出和 DPM 部分真机/物理验收仍需在指定设备上继续验证。",
    ]:
        story.append(bullet(item))
    story.append(h2("主要代码入口"))
    story.append(table([
        ["区域", "入口"],
        ["Android 采集", "app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt"],
        ["人工确认", "app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt"],
        ["ZIP 导出", "app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt"],
        ["离线检测器", "tools/feature_presence/presence_detectors.py"],
        ["离线评估", "tools/feature_presence/evaluate_presence.py"],
        ["完整报告", "docs/reports/b3/feature_presence/OFFLINE_PROTOTYPE_REPORT.md"],
    ], [32 * mm, CONTENT_W - 32 * mm]))
    add_callout(story, "最终状态", "工程总结和带图 PDF 已生成。算法结果按当前真实证据保留边界：Thread/Nut/Feature 为离线原型，DCIM 评估数据不足，Android 集成和部分物理验收待后续任务。", color=GREEN)
    return story


def main():
    register_fonts()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    doc = ReportDocTemplate(
        str(OUTPUT), pagesize=A4, leftMargin=LEFT, rightMargin=RIGHT,
        topMargin=TOP, bottomMargin=BOTTOM, title="视觉质检 MobileInspectionApp 工程工作量与 ROI 算法结果",
        author="OpenAI Codex",
    )
    doc.build(build_story())
    print(OUTPUT)


if __name__ == "__main__":
    main()
