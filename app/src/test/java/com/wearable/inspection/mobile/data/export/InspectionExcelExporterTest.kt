package com.wearable.inspection.mobile.data.export

import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * InspectionExcelExporter 单元测试
 *
 * 覆盖：
 * - CSV 字段、行数和 OK/NG 值正确
 * - ROI 为 NG 时仍然写入
 * - 总体为 NG 时仍然导出
 * - 特殊字符转义
 */
class InspectionExcelExporterTest {

    private fun createConfirm(
        roiId: String = "roi_001",
        roiName: String = "ROI 1",
        roiTargetType: String? = "THREAD",
        humanResult: String = "OK",
        overallResult: String = "OK",
        viewIndex: Int = 0,
        templateId: String = "tpl_001",
        templateName: String = "视角1",
        photoPath: String = "/captures/view_0_photo.jpg"
    ) = ViewRoiConfirmEntity(
        id = 1,
        batchId = "batch_001",
        photoId = 1,
        photoPath = photoPath,
        viewIndex = viewIndex,
        templateId = templateId,
        templateName = templateName,
        roiId = roiId,
        roiName = roiName,
        roiTargetType = roiTargetType,
        roiNormalizedRect = """{"left":0.1,"top":0.2,"right":0.3,"bottom":0.4}""",
        roiPixelRect = """{"left":100,"top":200,"right":300,"bottom":400}""",
        softwareResult = null,
        humanResult = humanResult,
        confirmTime = 1693824000000L,
        overallResult = overallResult,
        overallConfirmTime = 1693824005000L
    )

    @Test
    fun `header has all required fields`() {
        val header = InspectionExcelExporter.HEADER
        assertEquals(15, header.size)
        assertTrue(header.contains("图片名称"))
        assertTrue(header.contains("零件ID"))
        assertTrue(header.contains("模板ID"))
        assertTrue(header.contains("ViewID"))
        assertTrue(header.contains("View名称"))
        assertTrue(header.contains("ROI_ID"))
        assertTrue(header.contains("ROI属性"))
        assertTrue(header.contains("ROI坐标"))
        assertTrue(header.contains("ROI_normalizedRect"))
        assertTrue(header.contains("ROI_映射后像素坐标"))
        assertTrue(header.contains("软件检测结果"))
        assertTrue(header.contains("人工确认结果"))
        assertTrue(header.contains("人工确认时间"))
        assertTrue(header.contains("总体结果"))
        assertTrue(header.contains("总体确认时间"))
    }

    @Test
    fun `csv row has correct field count`() {
        val confirm = createConfirm()
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        assertEquals(15, row.size)
    }

    @Test
    fun `csv row has correct values`() {
        val confirm = createConfirm(
            roiId = "roi_123",
            roiName = "螺纹区域",
            roiTargetType = "THREAD",
            humanResult = "OK",
            overallResult = "OK"
        )
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")

        assertEquals("view_0_photo.jpg", row[0]) // 图片名称
        assertEquals("part_001", row[1])          // 零件ID
        assertEquals("tpl_001", row[2])           // 模板ID
        assertEquals("view_0", row[3])            // ViewID
        assertEquals("视角1", row[4])             // View名称
        assertEquals("roi_123", row[5])           // ROI_ID
        assertEquals("THREAD", row[6])            // ROI属性
        assertEquals("OK", row[11])               // 人工确认结果
        assertEquals("OK", row[13])               // 总体结果
    }

    @Test
    fun `ROI NG is still written to csv`() {
        val confirm = createConfirm(humanResult = "NG", overallResult = "OK")
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        assertEquals("NG", row[11]) // 人工确认结果 = NG
        assertEquals("OK", row[13]) // 总体结果 = OK
    }

    @Test
    fun `overall NG is still written to csv`() {
        val confirm = createConfirm(humanResult = "OK", overallResult = "NG")
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        assertEquals("OK", row[11]) // 人工确认结果 = OK
        assertEquals("NG", row[13]) // 总体结果 = NG
    }

    @Test
    fun `both ROI and overall NG are written`() {
        val confirm = createConfirm(humanResult = "NG", overallResult = "NG")
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        assertEquals("NG", row[11])
        assertEquals("NG", row[13])
    }

    @Test
    fun `null software result exports empty string`() {
        val confirm = createConfirm()
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        assertEquals("", row[10]) // 软件检测结果 = 空
    }

    @Test
    fun `null targetType exports as 未选择`() {
        val confirm = createConfirm(roiTargetType = null)
        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        assertEquals("未选择", row[6])
    }

    @Test
    fun `exportToStream writes correct number of rows`() {
        val confirms = listOf(
            createConfirm(roiId = "roi_1", humanResult = "OK"),
            createConfirm(roiId = "roi_2", humanResult = "NG"),
            createConfirm(roiId = "roi_3", humanResult = "OK")
        )
        val os = ByteArrayOutputStream()
        InspectionExcelExporter.exportToStream(confirms, "part_001", os)

        val csv = os.toString("UTF-8")
        val lines = csv.trim().split("\n")
        // 1 header + 3 data rows
        assertEquals(4, lines.size)
    }

    @Test
    fun `exportToStream includes BOM for Excel compatibility`() {
        val confirms = listOf(createConfirm())
        val os = ByteArrayOutputStream()
        InspectionExcelExporter.exportToStream(confirms, "part_001", os)

        val bytes = os.toByteArray()
        // UTF-8 BOM: EF BB BF
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
    }

    @Test
    fun `exportToStream handles empty list`() {
        val os = ByteArrayOutputStream()
        InspectionExcelExporter.exportToStream(emptyList(), "part_001", os)

        val csv = os.toString("UTF-8")
        // Should have BOM + header + newline, no data rows
        val lines = csv.trim().split("\n")
        assertEquals(1, lines.size) // only header
    }

    @Test
    fun `escapeCsv wraps fields with commas`() {
        assertEquals("\"hello,world\"", InspectionExcelExporter.escapeCsv("hello,world"))
    }

    @Test
    fun `escapeCsv wraps fields with quotes`() {
        assertEquals("\"say \"\"hello\"\"\"", InspectionExcelExporter.escapeCsv("say \"hello\""))
    }

    @Test
    fun `escapeCsv does not wrap simple fields`() {
        assertEquals("hello", InspectionExcelExporter.escapeCsv("hello"))
    }

    @Test
    fun `multiple views generate correct rows`() {
        val confirms = listOf(
            createConfirm(viewIndex = 0, roiId = "roi_1", templateName = "视角1"),
            createConfirm(viewIndex = 0, roiId = "roi_2", templateName = "视角1"),
            createConfirm(viewIndex = 1, roiId = "roi_3", templateName = "视角2")
        )
        val os = ByteArrayOutputStream()
        InspectionExcelExporter.exportToStream(confirms, "part_001", os)

        val csv = os.toString("UTF-8")
        val lines = csv.trim().split("\n")
        assertEquals(4, lines.size) // header + 3 rows
    }

    @Test
    fun `combined export keeps photo and roi rows in one csv`() {
        val photo = CapturedPhotoEntity(
            photoId = 1,
            batchId = "batch_001",
            filePath = "/captures/view_0_photo.jpg",
            viewIndex = 0,
            templateId = "tpl_001",
            templateName = "视角1",
            capturedAt = 1693824000000L,
        )
        val os = ByteArrayOutputStream()
        InspectionExcelExporter.exportCombinedToStream(
            photos = listOf(
                InspectionPhotoExportRow(
                    photo = photo,
                    zipPath = "views/view_01/view_0_photo.jpg",
                    status = "已导出",
                )
            ),
            confirms = listOf(createConfirm()),
            partId = "part_001",
            outputStream = os,
        )

        val csv = os.toString("UTF-8")
        assertEquals(19, InspectionExcelExporter.COMBINED_HEADER.size)
        assertTrue(csv.contains("记录类型"))
        assertTrue(csv.contains("照片"))
        assertTrue(csv.contains("ROI确认"))
        assertTrue(csv.contains("views/view_01/view_0_photo.jpg"))
    }
}
