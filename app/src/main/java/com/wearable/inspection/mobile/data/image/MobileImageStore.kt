package com.wearable.inspection.mobile.data.image

import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 拍照存储结果
 */
data class StoredImageResult(
    val finalPath: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val capturedAt: Long
)

/**
 * 移动端图片存储工具
 *
 * 职责：
 * 1. 临时 JPEG 文件生成
 * 2. JPEG 可解码和 EXIF 方向校验
 * 3. 原子移动到正式路径（使用 .part 中间文件）
 * 4. 失败清理临时文件和 .part 文件
 * 5. 路径合法性检查
 *
 * 文件事务流程：
 * 1. 生成唯一临时文件 → 2. 校验临时文件 → 3. 复制到 .part 文件 →
 * 4. 校验 .part 文件 → 5. 重命名为最终文件（原子操作）
 *
 * 任何失败都会清理 .part 和临时文件。
 */
class MobileImageStore(private val context: Context) {

    companion object {
        private const val CAPTURES_DIR = "captures"
        private const val TEMP_DIR = "capture_tmp"
        private const val TEMPLATE_IMAGES_DIR = "template_images"

        private const val TEMP_PREFIX = "capture_"
        private const val TEMP_SUFFIX = ".tmp.jpg"

        private const val PART_SUFFIX = ".part"

        private const val CAPTURE_FILE_PATTERN = "capture_%s_%s.jpg"
        private const val TEMPLATE_FILE_PATTERN = "tpl_%s_%s.jpg"
    }

    // ========== 临时文件 ==========

    private fun getTempDir(): File {
        return File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
    }

    /**
     * 生成唯一临时文件路径
     *
     * 使用时间戳 + UUID 确保不可冲突。
     */
    fun generateTempFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uuid = UUID.randomUUID().toString().take(8)
        return File(getTempDir(), "$TEMP_PREFIX${ts}_$uuid$TEMP_SUFFIX")
    }

    /**
     * 清理临时目录中的所有文件
     */
    fun cleanTempDir() {
        val dir = getTempDir()
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * 清理指定临时文件
     */
    fun deleteTempFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    // ========== 校验 ==========

    /**
     * 校验 JPEG 文件有效性
     *
     * 检查：文件存在且非空、可解码、宽高有效、EXIF 方向有效。
     */
    fun validateJpeg(file: File): StoredImageResult? {
        if (!file.exists() || file.length() == 0L) {
            return null
        }

        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null
            }

            val orientation = try {
                val exif = ExifInterface(file.absolutePath)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            StoredImageResult(
                finalPath = file.absolutePath,
                sizeBytes = file.length(),
                width = options.outWidth,
                height = options.outHeight,
                orientation = orientation,
                capturedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            // 解码失败（无效数据、损坏文件等）
            null
        }
    }

    // ========== 原子移动 ==========

    private fun getCapturesDir(): File {
        return File(context.filesDir, CAPTURES_DIR).apply { mkdirs() }
    }

    private fun generateFinalFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uuid = UUID.randomUUID().toString().take(8)
        return String.format(CAPTURE_FILE_PATTERN, ts, uuid)
    }

    /**
     * 原子移动临时文件到正式路径
     *
     * 流程：
     * 1. 生成唯一最终文件名
     * 2. 检查最终文件是否已存在（不覆盖）
     * 3. 复制到 .part 中间文件
     * 4. 校验 .part 文件有效性
     * 5. 重命名 .part → 最终文件（原子操作）
     * 6. 删除临时文件
     *
     * 失败时清理 .part 和临时文件。
     *
     * @return 最终文件，失败返回 null
     */
    fun atomicMoveToFinal(tempFile: File): File? {
        if (!tempFile.exists()) return null

        val finalFile = File(getCapturesDir(), generateFinalFileName())
        val partFile = File(finalFile.absolutePath + PART_SUFFIX)

        try {
            // 检查最终文件是否已存在（不覆盖）
            if (finalFile.exists()) {
                tempFile.delete()
                return null
            }

            // 复制到 .part 文件
            tempFile.copyTo(partFile, overwrite = false)

            // 校验 .part 文件
            if (!partFile.exists() || partFile.length() == 0L) {
                cleanupFiles(partFile, tempFile)
                return null
            }

            // 重命名 .part → 最终文件（原子操作）
            if (!partFile.renameTo(finalFile)) {
                cleanupFiles(partFile, tempFile)
                return null
            }

            // 删除临时文件
            tempFile.delete()

            return finalFile
        } catch (e: Exception) {
            // 任何失败都清理
            cleanupFiles(partFile, tempFile)
            return null
        }
    }

    /**
     * 清理文件（忽略删除失败）
     */
    private fun cleanupFiles(vararg files: File) {
        files.forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // 忽略删除失败
            }
        }
    }

    /**
     * 完整的拍照存储流程
     *
     * 1. 校验临时文件
     * 2. 原子移动到正式路径（使用 .part 中间文件）
     * 3. 返回结果
     */
    fun storeCapturedImage(tempFile: File): StoredImageResult? {
        val validation = validateJpeg(tempFile) ?: run {
            tempFile.delete()
            return null
        }
        val finalFile = atomicMoveToFinal(tempFile) ?: return null
        return validateJpeg(finalFile)
    }

    // ========== 删除 ==========

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    // ========== 路径合法性检查 ==========

    fun isPathSafe(path: String): Boolean {
        return try {
            val file = File(path)
            val canonical = file.canonicalPath
            val baseCanonical = context.filesDir.canonicalPath
            canonical.startsWith(baseCanonical)
        } catch (_: Exception) {
            false
        }
    }

    fun fileExistsAndNonEmpty(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.length() > 0
    }

    // ========== 清理捕获目录 ==========

    fun getCapturesPath(): String = getCapturesDir().absolutePath

    fun listCaptures(): List<File> {
        val dir = getCapturesDir()
        return if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 清理 .part 残留文件
     */
    fun cleanPartFiles() {
        val dir = getCapturesDir()
        if (dir.exists()) {
            dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
        }
    }

    // ========== 模板图片存储 ==========

    private fun getTemplateImagesDir(): File {
        return File(context.filesDir, TEMPLATE_IMAGES_DIR).apply { mkdirs() }
    }

    private fun generateTemplateFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uuid = UUID.randomUUID().toString().take(8)
        return String.format(TEMPLATE_FILE_PATTERN, ts, uuid)
    }

    /**
     * 存储模板图片到 template_images/ 目录
     *
     * 流程与 storeCapturedImage 相同：校验 → .part 中间文件 → 原子重命名。
     * 模板图片与采集图片使用独立目录，互不干扰。
     *
     * @param tempFile 临时文件（拍照输出或复制的临时文件）
     * @return 存储结果，失败返回 null
     */
    fun storeTemplateImage(tempFile: File): StoredImageResult? {
        val validation = validateJpeg(tempFile) ?: run {
            tempFile.delete()
            return null
        }
        val finalFile = File(getTemplateImagesDir(), generateTemplateFileName())
        val partFile = File(finalFile.absolutePath + PART_SUFFIX)
        try {
            if (finalFile.exists()) {
                tempFile.delete()
                return null
            }
            tempFile.copyTo(partFile, overwrite = false)
            if (!partFile.exists() || partFile.length() == 0L) {
                cleanupFiles(partFile, tempFile)
                return null
            }
            if (!partFile.renameTo(finalFile)) {
                cleanupFiles(partFile, tempFile)
                return null
            }
            tempFile.delete()
            return validateJpeg(finalFile)
        } catch (e: Exception) {
            cleanupFiles(partFile, tempFile)
            return null
        }
    }

    /**
     * 删除模板图片
     *
     * 路径必须在 template_images/ 目录内（安全检查）。
     */
    fun deleteTemplateImage(path: String) {
        val file = File(path)
        val templateDir = getTemplateImagesDir()
        try {
            val canonical = file.canonicalPath
            val baseCanonical = templateDir.canonicalPath
            if (canonical.startsWith(baseCanonical) && file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
            // 忽略路径检查或删除失败
        }
    }

    /**
     * 获取模板图片目录路径
     */
    fun getTemplateImagesPath(): String = getTemplateImagesDir().absolutePath
}
