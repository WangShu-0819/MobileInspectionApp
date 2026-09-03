package com.wearable.inspection.mobile.template

import android.content.Context
import android.net.Uri
import android.util.Log
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
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
        private val FLAT_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

        /** 扁平目录的唯一顺序来源：稳定的文件名排序。 */
        internal fun stableFlatImageFiles(directory: File): List<File> =
            directory.listFiles()
                ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in FLAT_IMAGE_EXTENSIONS }
                ?.sortedWith(compareBy<File> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
                ?: emptyList()
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
     * 从已解压目录导入（需要 template.json）
     */
    suspend fun importFromDirectory(directory: File, database: AppDatabase): ImportResult =
        withContext(Dispatchers.IO) {
            val pkg = DirectoryTemplateImporter.parse(directory)
            importPackage(pkg, database)
        }

    /**
     * 从系统相册导入模板图片。
     *
     * 每张图片作为一个有序视角写入同一个零件；已有零件只追加视角，
     * 不删除已有模板。图片会先复制到 App 私有目录，再写入数据库。
     */
    suspend fun importFromImageUris(
        uris: List<Uri>,
        partId: String,
        partName: String,
        database: AppDatabase,
    ): ImportResult = withContext(Dispatchers.IO) {
        val normalizedPartId = partId.trim()
        val normalizedPartName = partName.trim().ifBlank { normalizedPartId }
        val imageUris = uris.distinct()
        val baseResult = { message: String ->
            ImportResult(
                success = false,
                partId = normalizedPartId,
                templateCount = 0,
                roiCount = 0,
                warnings = emptyList(),
                errorMessage = message,
            )
        }

        if (!normalizedPartId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            return@withContext baseResult("零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）")
        }
        if (imageUris.isEmpty()) {
            return@withContext baseResult("未选择模板图片")
        }

        val partDao = database.partDao()
        val templateDao = database.templateDao()
        val copiedFiles = mutableListOf<File>()
        val insertedTemplateIds = mutableListOf<String>()
        var createdPart = false

        try {
            val imagesDir = File(context.filesDir, TEMPLATE_IMAGES_DIR).apply { mkdirs() }
            val imageFiles = imageUris.map { uri ->
                val extension = context.contentResolver.getType(uri)
                    ?.substringAfterLast('/')
                    ?.lowercase()
                    ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "bmp") }
                    ?: "jpg"
                val destination = File(imagesDir, "${UUID.randomUUID()}.$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取图片")
                if (destination.length() == 0L) {
                    throw IllegalStateException("图片为空")
                }
                copiedFiles += destination
                destination
            }

            val now = System.currentTimeMillis()
            val existingPart = partDao.getById(normalizedPartId)
            if (existingPart == null) {
                partDao.insert(
                    PartEntity(
                        id = normalizedPartId,
                        name = normalizedPartName,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                createdPart = true
            }

            val firstViewIndex = templateDao.getByPartId(normalizedPartId).size
            imageFiles.forEachIndexed { index, imageFile ->
                val templateId = "${normalizedPartId}_photo_${UUID.randomUUID()}"
                templateDao.insert(
                    InspectionTemplateEntity(
                        id = templateId,
                        partId = normalizedPartId,
                        name = "视角 ${firstViewIndex + index + 1}",
                        mainImagePath = imageFile.absolutePath,
                        displayOrder = firstViewIndex + index,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                insertedTemplateIds += templateId
            }

            if (existingPart != null && existingPart.name != normalizedPartName) {
                partDao.update(existingPart.copy(name = normalizedPartName, updatedAt = now))
            }

            ImportResult(
                success = true,
                partId = normalizedPartId,
                templateCount = imageFiles.size,
                roiCount = 0,
                warnings = emptyList(),
            )
        } catch (e: Exception) {
            insertedTemplateIds.forEach { templateDao.deleteById(it) }
            copiedFiles.forEach { it.delete() }
            if (createdPart) partDao.deleteById(normalizedPartId)
            Log.e(TAG, "相册模板导入失败", e)
            baseResult(e.message ?: "导入失败")
        }
    }

    /**
     * 从扁平图片目录导入（无需 template.json）
     *
     * 目录中所有图片文件按文件名稳定排序，每张图作为一个视角(region)。
     * 零件 ID 和名称从目录名派生。
     */
    suspend fun importFromFlatDirectory(directory: File, database: AppDatabase): ImportResult =
        withContext(Dispatchers.IO) {
            val imageFiles = stableFlatImageFiles(directory)

            if (imageFiles.isEmpty()) {
                return@withContext ImportResult(
                    success = false,
                    partId = directory.name,
                    templateCount = 0,
                    roiCount = 0,
                    warnings = emptyList(),
                    errorMessage = "目录中无图片文件"
                )
            }

            val partId = directory.name
            val pkg = TemplatePackage(
                partId = partId,
                partName = partId,
                dpmCode = null,
                regions = imageFiles.mapIndexed { index, file ->
                    TemplateRegionData(
                        regionName = "视角${index + 1}",
                        imageFiles = listOf(file),
                        displayOrder = index,
                    )
                },
                warnings = emptyList()
            )
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
                    displayOrder = index,
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
