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
 * 3. 原子移动到正式路径
 * 4. 失败清理临时文件
 * 5. 路径合法性检查
 */
class MobileImageStore(private val context: Context) {

    companion object {
        private const val CAPTURES_DIR = "captures"
        private const val TEMP_DIR = "capture_tmp"

        private const val TEMP_PREFIX = "capture_"
        private const val TEMP_SUFFIX = ".tmp.jpg"

        private const val CAPTURE_FILE_PATTERN = "capture_%s_%s.jpg"
    }

    // ========== 临时文件 ==========

    private fun getTempDir(): File {
        return File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
    }

    /**
     * 生成唯一临时文件路径
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

        return StoredImageResult(
            finalPath = file.absolutePath,
            sizeBytes = file.length(),
            width = options.outWidth,
            height = options.outHeight,
            orientation = orientation,
            capturedAt = System.currentTimeMillis()
        )
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
     * 使用 renameTo；如果失败（跨文件系统），回退到复制+删除。
     */
    fun atomicMoveToFinal(tempFile: File): File? {
        if (!tempFile.exists()) return null

        val finalFile = File(getCapturesDir(), generateFinalFileName())

        return if (tempFile.renameTo(finalFile)) {
            finalFile
        } else {
            try {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
                finalFile
            } catch (_: Exception) {
                finalFile.delete()
                null
            }
        }
    }

    /**
     * 完整的拍照存储流程
     *
     * 1. 校验临时文件
     * 2. 原子移动到正式路径
     * 3. 返回结果
     */
    fun storeCapturedImage(tempFile: File): StoredImageResult? {
        val validation = validateJpeg(tempFile) ?: return null
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
}
