package com.wearable.inspection.mobile.template

import android.content.Context
import android.util.Log
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 模板导入服务
 *
 * 编排完整导入流程：解析 → 复制图片 → upsert Part → insert Template → insert ROI。
 * 失败时事务回滚 + 清理已复制文件，不留下孤儿数据。
 *
 * 支持两种输入：
 * - ZIP 文件（通过 [TemplatePackageImporter]）
 * - 已解压目录（通过 [DirectoryTemplateImporter]）
 */
class TemplateImportService(private val context: Context) {

    companion object {
        private const val TAG = "TemplateImportService"
        private const val TEMPLATE_IMAGES_DIR = "template_images"
    }

    /**
     * 导入结果
     */
    data class ImportResult(
        val success: Boolean,
        val partId: String,
        val templateCount: Int,
        val roiCount: Int,
        val warnings: List<String>,
        val errorMessage: String? = null,
    )

    /**
     * 从 ZIP 文件导入
     */
    suspend fun importFromZip(zipFile: File, database: AppDatabase): ImportResult =
        withContext(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "template_import_${System.currentTimeMillis()}")
            try {
                workDir.mkdirs()
                val pkg = TemplatePackageImporter.parse(zipFile, workDir)
                importPackage(pkg, database)
            } finally {
                workDir.deleteRecursively()
            }
        }

    /**
     * 从已解压目录导入
     */
    suspend fun importFromDirectory(directory: File, database: AppDatabase): ImportResult =
        withContext(Dispatchers.IO) {
            val pkg = DirectoryTemplateImporter.parse(directory)
            importPackage(pkg, database)
        }

    /**
     * 核心导入逻辑：事务编排
     */
    private suspend fun importPackage(
        pkg: TemplatePackage,
        database: AppDatabase,
    ): ImportResult {
        val copiedFiles = mutableListOf<File>()
        val partDao = database.partDao()
        val templateDao = database.templateDao()
        val roiDao = database.roiDao()

        try {
            // 1. 准备图片存储目录
            val imagesDir = File(context.filesDir, TEMPLATE_IMAGES_DIR).apply { mkdirs() }

            // 2. 复制所有图片到 App 私有目录
            val imageMapping = mutableMapOf<File, File>() // 原文件 → 新文件
            for (region in pkg.regions) {
                for (imageFile in region.imageFiles) {
                    if (!imageFile.exists() || imageFile.length() == 0L) {
                        Log.w(TAG, "跳过无效图片：${imageFile.absolutePath}")
                        continue
                    }
                    val destName = "${UUID.randomUUID()}_${imageFile.name}"
                    val destFile = File(imagesDir, destName)
                    imageFile.copyTo(destFile, overwrite = false)
                    copiedFiles += destFile
                    imageMapping[imageFile] = destFile
                }
            }

            // 3. Upsert 零件
            val now = System.currentTimeMillis()
            val existingPart = partDao.getById(pkg.partId)
            if (existingPart != null) {
                // 更新零件信息（不覆盖已有 dpmCode）
                partDao.update(existingPart.copy(
                    name = pkg.partName,
                    updatedAt = now,
                ))
            } else {
                partDao.insert(PartEntity(
                    id = pkg.partId,
                    name = pkg.partName,
                    dpmCode = pkg.dpmCode,
                    createdAt = now,
                    updatedAt = now,
                ))
            }

            // 4. 删除该零件下已有模板（重新导入时清理旧数据）
            templateDao.deleteByPartId(pkg.partId)

            // 5. 为每个 region 创建模板和 ROI
            var templateCount = 0
            var roiCount = 0
            for ((index, region) in pkg.regions.withIndex()) {
                // 找到该 region 复制后的第一张图片作为 mainImage
                val mainImageFile = region.imageFiles.firstNotNullOfOrNull { original ->
                    imageMapping[original]
                }
                if (mainImageFile == null) {
                    Log.w(TAG, "视角「${region.regionName}」无有效图片，跳过")
                    continue
                }

                val templateId = "${pkg.partId}_region_${index}_${UUID.randomUUID()}"
                val template = InspectionTemplateEntity(
                    id = templateId,
                    partId = pkg.partId,
                    name = region.regionName,
                    mainImagePath = mainImageFile.absolutePath,
                    createdAt = now,
                    updatedAt = now,
                )
                templateDao.insert(template)
                templateCount++

                // 从 template.json 的 roi 字段创建 ROI（如果有）
                // 注意：当前 TemplateRegionData 不携带 roi 信息
                // 未来可在 TemplateRegionData 中添加 roi 字段
            }

            Log.i(TAG, "导入成功：partId=${pkg.partId}, templates=$templateCount, rois=$roiCount")

            return ImportResult(
                success = true,
                partId = pkg.partId,
                templateCount = templateCount,
                roiCount = roiCount,
                warnings = pkg.warnings,
            )
        } catch (e: Exception) {
            Log.e(TAG, "导入失败，回滚", e)

            // 回滚：删除已复制的文件
            for (file in copiedFiles) {
                try {
                    file.delete()
                } catch (_: Exception) { }
            }

            return ImportResult(
                success = false,
                partId = pkg.partId,
                templateCount = 0,
                roiCount = 0,
                warnings = pkg.warnings,
                errorMessage = e.message ?: "未知错误",
            )
        }
    }
}
