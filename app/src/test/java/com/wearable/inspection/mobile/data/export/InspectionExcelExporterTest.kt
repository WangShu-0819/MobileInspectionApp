package com.wearable.inspection.mobile.data.export

import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
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

    // ───────────────────────────────────────────────
    // __FULL_IMAGE__ 检测阈值过滤测试
    // ───────────────────────────────────────────────

    private fun createFullImagePhotoRow(
        batchId: String = "batch-fi",
        photoId: Long = 100,
        viewIndex: Int = 0,
        templateId: String = "tpl-fi",
    ): InspectionPhotoExportRow {
        val photo = CapturedPhotoEntity(
            photoId = photoId, batchId = batchId,
            filePath = "/captures/full_image_photo.jpg",
            viewIndex = viewIndex, templateId = templateId, templateName = "整图视角",
            capturedAt = 1693824000000L,
        )
        return InspectionPhotoExportRow(photo = photo, zipPath = "views/view_01/photo.jpg", status = "已导出")
    }

    private fun createFullImageRoiRow(
        batchId: String = "batch-fi",
        detectionsJson: String,
        threshold: Float = 0.37f,
    ): InspectionRoiExportRow {
        val photo = CapturedPhotoEntity(
            photoId = 100, batchId = batchId,
            filePath = "/captures/full_image_photo.jpg",
            viewIndex = 0, templateId = "tpl-fi", templateName = "整图视角",
            capturedAt = 1693824000000L,
        )
        val roi = RoiDefinitionEntity(
            id = "__FULL_IMAGE__", templateId = "tpl-fi", name = "整图检测",
            order = 0, normalizedRect = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}""",
            inspectionType = "VISUAL", targetType = null,
        )
        val confirm = ViewRoiConfirmEntity(
            id = 200, batchId = batchId, photoId = 100,
            photoPath = "/captures/full_image_photo.jpg",
            viewIndex = 0, templateId = "tpl-fi", templateName = "整图视角",
            roiId = "__FULL_IMAGE__", roiName = "整图检测", roiTargetType = null,
            roiNormalizedRect = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}""",
            roiPixelRect = """{"left":0,"top":0,"right":1920,"bottom":1080}""",
            softwareResult = null, humanResult = "OK",
            confirmTime = 1693824001000L,
            overallResult = "OK", overallConfirmTime = 1693824001000L,
            softwareThreshold = threshold,
            softwareDetectionsJson = detectionsJson,
            softwareStatus = "DETECTED",
        )
        return InspectionRoiExportRow(photo = photo, roi = roi, confirm = confirm)
    }

    @Test
    fun `__FULL_IMAGE__ CSV only includes detections above threshold`() {
        val detectionsJson = """[{"classIndex":1,"className":"thread","score":0.89,"roiBox":{"left":1},"imageBox":{"left":2}},{"classIndex":0,"className":"nut","score":0.20,"roiBox":{"left":3},"imageBox":{"left":4}},{"classIndex":2,"className":"bolt","score":0.45,"roiBox":{"left":5},"imageBox":{"left":6}}]"""
        val photoRows = listOf(createFullImagePhotoRow())
        val roiRows = listOf(createFullImageRoiRow(detectionsJson = detectionsJson, threshold = 0.37f))
        val os = ByteArrayOutputStream()

        InspectionExcelExporter.exportUnifiedToStream(
            batchId = "batch-fi", partId = "part-fi",
            photos = photoRows, roiRows = roiRows, dpmRows = emptyList(),
            outputStream = os,
        )

        val csv = os.toString("UTF-8")
        val lines = csv.removePrefix("﻿").trimEnd().lines()
        val dataLines = lines.drop(1) // skip header

        // 检测行：score=0.89 和 score=0.45 均 >=0.37，应保留；score=0.20 <0.37，应过滤
        val detectionLines = dataLines.filter { line ->
            val fields = parseCsvLine(line)
            fields.size > 34 && fields[0] == "ROI" && fields[32].isNotBlank()
        }
        assertEquals("应有 2 条达到阈值的检测行", 2, detectionLines.size)

        // 验证保留的检测分数
        val scores = detectionLines.map { line ->
            parseCsvLine(line)[34].toDouble()
        }
        assertTrue("0.89 应保留", scores.any { kotlin.math.abs(it - 0.89) < 0.01 })
        assertTrue("0.45 应保留", scores.any { kotlin.math.abs(it - 0.45) < 0.01 })
        assertFalse("0.20 不应出现", scores.any { kotlin.math.abs(it - 0.20) < 0.01 })
    }

    @Test
    fun `__FULL_IMAGE__ CSV with all detections below threshold writes single empty-detection row`() {
        val detectionsJson = """[{"classIndex":0,"className":"nut","score":0.10,"roiBox":{"left":1},"imageBox":{"left":2}},{"classIndex":1,"className":"thread","score":0.30,"roiBox":{"left":3},"imageBox":{"left":4}}]"""
        val photoRows = listOf(createFullImagePhotoRow())
        val roiRows = listOf(createFullImageRoiRow(detectionsJson = detectionsJson, threshold = 0.37f))
        val os = ByteArrayOutputStream()

        InspectionExcelExporter.exportUnifiedToStream(
            batchId = "batch-fi", partId = "part-fi",
            photos = photoRows, roiRows = roiRows, dpmRows = emptyList(),
            outputStream = os,
        )

        val csv = os.toString("UTF-8")
        val lines = csv.removePrefix("﻿").trimEnd().lines()
        val dataLines = lines.drop(1)

        // 所有检测低于阈值 → 只有 1 条 ROI 行（detectionIndex 为空）
        val roiLines = dataLines.filter { line ->
            val fields = parseCsvLine(line)
            fields.size > 6 && fields[0] == "ROI" && fields[6] == "__FULL_IMAGE__"
        }
        assertEquals("低于阈值时应只有 1 条 ROI 行", 1, roiLines.size)
        val fields = parseCsvLine(roiLines[0])
        assertEquals("detectionIndex 应为空", "", fields[32])
    }

    @Test
    fun `__FULL_IMAGE__ CSV preserves all detections when threshold is null`() {
        // threshold=null 时（旧数据兼容）：不做过滤，全部导出
        val detectionsJson = """[{"classIndex":1,"className":"thread","score":0.89,"roiBox":{"left":1},"imageBox":{"left":2}},{"classIndex":0,"className":"nut","score":0.20,"roiBox":{"left":3},"imageBox":{"left":4}}]"""
        val photoRows = listOf(createFullImagePhotoRow())
        val roi = RoiDefinitionEntity(
            id = "__FULL_IMAGE__", templateId = "tpl-fi", name = "整图检测",
            order = 0, normalizedRect = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}""",
            inspectionType = "VISUAL", targetType = null,
        )
        val photo = CapturedPhotoEntity(
            photoId = 100, batchId = "batch-fi",
            filePath = "/captures/full_image_photo.jpg",
            viewIndex = 0, templateId = "tpl-fi", templateName = "整图视角",
            capturedAt = 1693824000000L,
        )
        val confirm = ViewRoiConfirmEntity(
            id = 200, batchId = "batch-fi", photoId = 100,
            photoPath = "/captures/full_image_photo.jpg",
            viewIndex = 0, templateId = "tpl-fi", templateName = "整图视角",
            roiId = "__FULL_IMAGE__", roiName = "整图检测", roiTargetType = null,
            roiNormalizedRect = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}""",
            roiPixelRect = """{"left":0,"top":0,"right":1920,"bottom":1080}""",
            softwareResult = null, humanResult = "OK",
            confirmTime = 1693824001000L,
            overallResult = "OK", overallConfirmTime = 1693824001000L,
            softwareThreshold = null, // 旧数据无阈值
            softwareDetectionsJson = detectionsJson,
            softwareStatus = "DETECTED",
        )
        val roiRows = listOf(InspectionRoiExportRow(photo = photo, roi = roi, confirm = confirm))
        val os = ByteArrayOutputStream()

        InspectionExcelExporter.exportUnifiedToStream(
            batchId = "batch-fi", partId = "part-fi",
            photos = photoRows, roiRows = roiRows, dpmRows = emptyList(),
            outputStream = os,
        )

        val csv = os.toString("UTF-8")
        val lines = csv.removePrefix("﻿").trimEnd().lines()
        val dataLines = lines.drop(1)

        val detectionLines = dataLines.filter { line ->
            val fields = parseCsvLine(line)
            fields.size > 34 && fields[0] == "ROI" && fields[6] == "__FULL_IMAGE__" && fields[32].isNotBlank()
        }
        assertEquals("threshold=null 时应保留全部 2 条检测行", 2, detectionLines.size)
    }

    @Test
    fun `non-__FULL_IMAGE__ ROI detections are not filtered by threshold`() {
        // 普通 ROI 检测行不受阈值过滤影响
        val detectionsJson = """[{"classIndex":1,"className":"thread","score":0.20,"roiBox":{"left":1},"imageBox":{"left":2}}]"""
        val photo = CapturedPhotoEntity(
            photoId = 50, batchId = "batch-roi",
            filePath = "/captures/roi_photo.jpg",
            viewIndex = 0, templateId = "tpl-roi", templateName = "视角1",
            capturedAt = 1693824000000L,
        )
        val roi = RoiDefinitionEntity(
            id = "roi-thread", templateId = "tpl-roi", name = "螺纹 ROI",
            order = 0, normalizedRect = """{"left":0.1,"top":0.1,"right":0.9,"bottom":0.9}""",
            inspectionType = "VISUAL", targetType = "THREAD",
        )
        val confirm = ViewRoiConfirmEntity(
            id = 300, batchId = "batch-roi", photoId = 50,
            photoPath = "/captures/roi_photo.jpg",
            viewIndex = 0, templateId = "tpl-roi", templateName = "视角1",
            roiId = "roi-thread", roiName = "螺纹 ROI", roiTargetType = "THREAD",
            roiNormalizedRect = """{"left":0.1,"top":0.1,"right":0.9,"bottom":0.9}""",
            roiPixelRect = """{"left":100,"top":100,"right":900,"bottom":900}""",
            softwareResult = "OK", humanResult = "OK",
            confirmTime = 1693824001000L,
            overallResult = "OK", overallConfirmTime = 1693824001000L,
            softwareThreshold = 0.37f,
            softwareDetectionsJson = detectionsJson,
            softwareStatus = "DETECTED",
        )
        val photoRows = listOf(InspectionPhotoExportRow(photo = photo, zipPath = "views/view_01/photo.jpg", status = "已导出"))
        val roiRows = listOf(InspectionRoiExportRow(photo = photo, roi = roi, confirm = confirm))
        val os = ByteArrayOutputStream()

        InspectionExcelExporter.exportUnifiedToStream(
            batchId = "batch-roi", partId = "part-roi",
            photos = photoRows, roiRows = roiRows, dpmRows = emptyList(),
            outputStream = os,
        )

        val csv = os.toString("UTF-8")
        val lines = csv.removePrefix("﻿").trimEnd().lines()
        val dataLines = lines.drop(1)

        val detectionLines = dataLines.filter { line ->
            val fields = parseCsvLine(line)
            fields.size > 34 && fields[0] == "ROI" && fields[6] == "roi-thread" && fields[32].isNotBlank()
        }
        assertEquals("普通 ROI 应保留全部检测行（不受阈值过滤）", 1, detectionLines.size)
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"'); i++
                } else quoted = !quoted
                ',' -> if (quoted) current.append(c) else { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }
}
