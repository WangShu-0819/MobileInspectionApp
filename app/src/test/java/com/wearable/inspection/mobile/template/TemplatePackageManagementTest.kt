package com.wearable.inspection.mobile.template

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 模板包管理入口的源码契约测试，避免只实现解析器而遗漏页面和删除语义。 */
class TemplatePackageManagementTest {

    private fun readMain(path: String): String =
        File("src/main/java/com/wearable/inspection/mobile/$path").readText()

    @Test
    fun `template package screen exposes import export and delete actions`() {
        val source = readMain("ui/screens/TemplatePackageScreen.kt")

        assertTrue(source.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(source.contains("ActivityResultContracts.CreateDocument(\"application/zip\")"))
        assertTrue(source.contains("TemplatePackageExporter.export"))
        assertTrue(source.contains("repository.deleteTemplatePackage(partId)"))
        assertTrue(source.contains("repository.observeParts()"))
        assertTrue(source.contains("repository.observeAllTemplates()"))
        assertTrue(source.contains("AlertDialog"))
    }

    @Test
    fun `template package deletion keeps capture history and removes managed images`() {
        val source = readMain("data/repository/InspectionRepository.kt")
        val deleteBlockStart = source.indexOf("suspend fun deleteTemplatePackage")
        assertTrue("模板包删除方法应存在", deleteBlockStart >= 0)
        val deleteBlock = source.substring(deleteBlockStart, (deleteBlockStart + 700).coerceAtMost(source.length))

        assertTrue(deleteBlock.contains("templateDao.getByPartId(partId)"))
        assertTrue(deleteBlock.contains("partDao.deleteById(partId)"))
        assertTrue(deleteBlock.contains("deleteTemplateImage(it)"))
        assertFalse("模板包删除不能调用批次删除", deleteBlock.contains("deleteCaptureBatchCompletely"))
    }

    @Test
    fun `import service restores exported template and roi fields`() {
        val source = readMain("template/TemplateImportService.kt")

        assertTrue(source.contains("outlineData = region.outlineData"))
        assertTrue(source.contains("createdAt = region.createdAt ?: now"))
        assertTrue(source.contains("updatedAt = region.updatedAt ?: now"))
        assertTrue(source.contains("region.rois.forEachIndexed"))
        assertTrue(source.contains("normalizedRect = roi.normalizedRect"))
        assertTrue(source.contains("targetType = roi.targetType"))
        assertTrue(source.contains("configJson = roi.configJson"))
    }
}
