package com.wearable.inspection.mobile.template

import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplatePackageExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `导出包可被现有导入解析器完整读取`() {
        val imageOne = byteArrayOf(1, 2, 3)
        val imageTwo = byteArrayOf(4, 5, 6, 7)
        val imageFileOne = tmp.newFile("one.jpg").apply { writeBytes(imageOne) }
        val imageFileTwo = tmp.newFile("two.png").apply { writeBytes(imageTwo) }
        val part = PartEntity(id = "PART_1", name = "测试零件", dpmCode = "DPM-1")
        val templateOne = InspectionTemplateEntity(
            id = "template-one",
            partId = part.id,
            name = "正面",
            mainImagePath = imageFileOne.absolutePath,
            displayOrder = 1,
        )
        val templateTwo = InspectionTemplateEntity(
            id = "template-two",
            partId = part.id,
            name = "侧面",
            mainImagePath = imageFileTwo.absolutePath,
            displayOrder = 0,
        )
        val roi = RoiDefinitionEntity(
            id = "roi-thread",
            templateId = templateOne.id,
            name = "螺纹区域",
            order = 0,
            normalizedRect = "{\"left\":0.1,\"top\":0.2,\"right\":0.8,\"bottom\":0.9}",
            inspectionType = "THREAD_PRESENCE",
            targetType = "THREAD",
            configJson = "{\"threshold\":7}",
        )
        val output = tmp.newFile("template.zip")

        val summary = TemplatePackageExporter.export(
            part = part,
            templates = listOf(templateOne, templateTwo),
            roisByTemplateId = mapOf(templateOne.id to listOf(roi)),
            outputFile = output,
        )

        val parsed = TemplatePackageImporter.parse(output, tmp.newFolder("unpacked"))

        assertTrue(summary.file.exists())
        assertEquals(2, summary.templateCount)
        assertEquals(1, summary.roiCount)
        assertEquals("DPM-1", parsed.dpmCode)
        assertEquals(listOf("侧面", "正面"), parsed.regions.map { it.regionName })
        assertEquals("template-two", parsed.regions[0].templateId)
        assertEquals("template-one", parsed.regions[1].templateId)
        assertEquals(0, parsed.regions[0].displayOrder)
        assertEquals(1, parsed.regions[1].displayOrder)
        assertEquals("THREAD", parsed.regions[1].rois.single().targetType)
        assertEquals("螺纹区域", parsed.regions[1].rois.single().name)
        assertEquals(
            "{\"left\":0.1,\"top\":0.2,\"right\":0.8,\"bottom\":0.9}",
            parsed.regions[1].rois.single().normalizedRect,
        )
        assertArrayEquals(imageTwo, parsed.regions[0].imageFiles.single().readBytes())
        assertArrayEquals(imageOne, parsed.regions[1].imageFiles.single().readBytes())
    }

    @Test
    fun `缺失参考图片时导出失败且不留下不完整 zip`() {
        val output = tmp.newFile("missing.zip")
        val template = InspectionTemplateEntity(
            id = "template-missing",
            partId = "PART_1",
            name = "正面",
            mainImagePath = tmp.root.resolve("missing.jpg").absolutePath,
        )

        try {
            TemplatePackageExporter.export(
                part = PartEntity(id = "PART_1", name = "测试零件"),
                templates = listOf(template),
                roisByTemplateId = emptyMap(),
                outputFile = output,
            )
            fail("缺图不应导出成功")
        } catch (e: TemplatePackageExportException) {
            assertTrue(e.message.orEmpty().contains("图片"))
        }
        assertFalse("失败后不能留下不完整 ZIP", output.exists() && output.length() > 0L)
    }

    @Test
    fun `旧包没有 rois 字段时仍可解析`() {
        val image = tmp.newFile("legacy.jpg").apply { writeBytes(byteArrayOf(9)) }
        val zip = tmp.newFile("legacy.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(java.util.zip.ZipEntry("images/legacy.jpg"))
            output.write(image.readBytes())
            output.closeEntry()
            output.putNextEntry(java.util.zip.ZipEntry("template.json"))
            output.write(
                JSONObject()
                    .put("partId", "PART_1")
                    .put("partName", "旧包")
                    .put("regions", org.json.JSONArray().put(
                        JSONObject()
                            .put("regionName", "正面")
                            .put("imageFiles", org.json.JSONArray().put("images/legacy.jpg"))
                    ))
                    .toString()
                    .toByteArray()
            )
            output.closeEntry()
        }

        val parsed = TemplatePackageImporter.parse(zip, tmp.newFolder("legacy-unpacked"))

        assertTrue(parsed.regions.single().rois.isEmpty())
        assertTrue(parsed.warnings.isEmpty())
    }
}
