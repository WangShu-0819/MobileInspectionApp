package com.wearable.inspection.mobile.template

import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 模板包导出失败。 */
class TemplatePackageExportException(message: String) : Exception(message)

data class TemplatePackageExportSummary(
    val file: File,
    val templateCount: Int,
    val roiCount: Int,
    val imageCount: Int,
)

/**
 * 与 [TemplatePackageImporter] 配套的纯 JVM 模板包导出器。
 *
 * manifest 使用一个 region 对应一个本地模板视角，并保存全部 ROI 配置；图片统一写入
 * images/，因此本类导出的包可以直接交给现有 ZIP 导入入口重新导入。
 */
object TemplatePackageExporter {
    const val FORMAT_VERSION = 2

    fun export(
        part: PartEntity,
        templates: List<InspectionTemplateEntity>,
        roisByTemplateId: Map<String, List<RoiDefinitionEntity>>,
        outputFile: File,
    ): TemplatePackageExportSummary {
        val orderedTemplates = templates.sortedWith(
            compareBy<InspectionTemplateEntity> { it.displayOrder }.thenBy { it.id }
        )
        if (orderedTemplates.isEmpty()) {
            throw TemplatePackageExportException("零件「${part.name}」没有可导出的模板视角")
        }

        val images = orderedTemplates.mapIndexed { index, template ->
            val source = File(template.mainImagePath)
            if (!source.isFile || source.length() <= 0L) {
                throw TemplatePackageExportException("视角「${template.name}」的参考图片不存在或为空")
            }
            val extension = source.extension
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: "jpg"
            template to "images/view_${String.format(Locale.ROOT, "%02d", index + 1)}.$extension"
        }

        outputFile.parentFile?.mkdirs()
        try {
            ZipOutputStream(FileOutputStream(outputFile, false).buffered()).use { zip ->
                val regions = JSONArray()
                images.forEachIndexed { index, (template, entryName) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    File(template.mainImagePath).inputStream().buffered().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()

                    val rois = roisByTemplateId[template.id].orEmpty()
                        .sortedWith(compareBy<RoiDefinitionEntity> { it.order }.thenBy { it.id })
                    regions.put(JSONObject().apply {
                        put("templateId", template.id)
                        put("regionName", template.name)
                        put("order", index)
                        put("enabled", template.enabled)
                        put("imageFiles", JSONArray().put(entryName))
                        putNullable("outlineData", template.outlineData)
                        putNullable("createdAt", template.createdAt)
                        putNullable("updatedAt", template.updatedAt)
                        put("rois", JSONArray().apply {
                            rois.forEach { roi -> put(roi.toManifestJson()) }
                        })
                    })
                }

                val manifest = JSONObject().apply {
                    put("formatVersion", FORMAT_VERSION)
                    put("partId", part.id)
                    put("partName", part.name)
                    putNullable("dpmCode", part.dpmCode)
                    put("regions", regions)
                }
                zip.putNextEntry(ZipEntry(TemplatePackageImporter.MANIFEST_ENTRY))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } catch (e: TemplatePackageExportException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            outputFile.delete()
            throw TemplatePackageExportException("模板包导出失败：${e.message ?: "未知错误"}")
        }

        return TemplatePackageExportSummary(
            file = outputFile,
            templateCount = orderedTemplates.size,
            roiCount = orderedTemplates.sumOf { roisByTemplateId[it.id].orEmpty().size },
            imageCount = images.size,
        )
    }

    private fun RoiDefinitionEntity.toManifestJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("order", order)
        put("shapeType", shapeType)
        put("normalizedRect", runCatching { JSONObject(normalizedRect) }.getOrElse {
            throw TemplatePackageExportException("ROI「$name」的 normalizedRect 无效")
        })
        putNullable("points", points)
        put("inspectionType", inspectionType)
        putNullable("expectedValue", expectedValue)
        putNullable("configJson", configJson)
        putNullable("preprocessJson", preprocessJson)
        put("enabled", enabled)
        putNullable("createdAt", createdAt)
        putNullable("targetType", targetType)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
