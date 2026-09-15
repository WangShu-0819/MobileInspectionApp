package com.wearable.inspection.mobile.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
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
 * 3. 原子移动到正式路径（同目录优先 rename，跨目录时使用 .part 中间文件）
 * 4. 失败清理临时文件和 .part 文件
 * 5. 路径合法性检查
 *
 * 文件事务流程：
 * 1. 生成唯一临时文件 → 2. 校验临时文件 → 3. 同目录重命名到最终文件；
 * 跨目录时回退为复制到 .part 文件 → 4. 重命名为最终文件（原子操作）
 *
 * 任何失败都会清理 .part 和临时文件。
 */
class MobileImageStore(private val context: Context) {

    companion object {
        private const val CAPTURES_DIR = "captures"
        private const val TEMP_DIR = "capture_tmp"
        private const val TEMPLATE_IMAGES_DIR = "template_images"
        private const val DPM_EVIDENCE_DIR = "dpm_evidence"

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
     * 生成现场照片的受管理临时路径。
     *
     * CameraX 直接写入受管理的 captures 临时路径，避免拍照完成后再把大 JPEG
     * 从 cache 复制到 captures；调用方仍必须在 IO 调度器上完成落盘。
     */
    fun generateCaptureFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uuid = UUID.randomUUID().toString().take(8)
        return File(getCapturesDir(), "$TEMP_PREFIX${ts}_$uuid$TEMP_SUFFIX")
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

    private fun generateFinalFile(): File {
        return File(getCapturesDir(), generateFinalFileName())
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

        val finalFile = generateFinalFile()
        val partFile = File(finalFile.absolutePath + PART_SUFFIX)

        try {
            // 检查最终文件是否已存在（不覆盖）
            if (finalFile.exists()) {
                tempFile.delete()
                return null
            }

            // 同一应用数据分区通常可以直接重命名，避免复制大 JPEG。
            // 如果底层文件系统不允许跨目录 rename，再回退到 .part 复制流程。
            if (tempFile.renameTo(finalFile)) {
                return finalFile
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
     * 2. 原子移动到正式路径（同目录优先 rename）
     * 3. 返回结果
     */
    fun storeCapturedImage(tempFile: File): StoredImageResult? {
        val validation = validateJpeg(tempFile) ?: run {
            tempFile.delete()
            return null
        }
        val finalFile = atomicMoveToFinal(tempFile) ?: return null
        // 文件内容已经在移动前校验过；不要再次解码整张大图。
        return validation.copy(finalPath = finalFile.absolutePath)
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

    // ========== DPM 扫码证据存储 ==========

    private fun getDpmEvidenceDir(): File {
        return File(context.filesDir, DPM_EVIDENCE_DIR).apply { mkdirs() }
    }

    /**
     * 保存 Bitmap 到 DPM 证据目录（JPEG 格式，质量 90）
     *
     * 调用方负责在 Bitmap 回收前调用此方法。
     *
     * @return 保存的文件路径，失败返回 null
     */
    fun saveDpmEvidenceFrame(bitmap: Bitmap, fileName: String): String? {
        val file = File(getDpmEvidenceDir(), fileName)
        return try {
            val compressed = FileOutputStream(file).use { fos ->
                val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
                success
            }
            if (compressed && file.exists() && file.length() > 0L) {
                file.absolutePath
            } else {
                file.delete()
                null
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    /**
     * 从原图裁切 ROI 区域并保存为 JPEG
     *
     * @param source 原图 Bitmap
     * @param roiRect 裁切区域（像素坐标，已 clamp 到 source 边界内）
     * @param fileName 保存文件名
     * @return 保存的文件路径，失败返回 null
     */
    fun saveDpmEvidenceRoi(source: Bitmap, roiRect: Rect, fileName: String): String? {
        return try {
            val clamped = clampRect(roiRect, source.width, source.height)
            val cropped = Bitmap.createBitmap(
                source, clamped.left, clamped.top, clamped.width(), clamped.height()
            )
            val path = saveDpmEvidenceFrame(cropped, fileName)
            if (cropped !== source) cropped.recycle()
            path
        } catch (_: Exception) {
            File(getDpmEvidenceDir(), fileName).delete()
            null
        }
    }

    /** 删除 DPM 证据文件，仅允许删除受管理 dpm_evidence 目录内的文件。 */
    fun deleteDpmEvidenceFile(path: String?) {
        if (path == null) return
        runCatching {
            val file = File(path)
            val base = getDpmEvidenceDir().canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == base) target.delete()
        }
    }

    private fun clampRect(rect: Rect, w: Int, h: Int): Rect {
        val l = rect.left.coerceIn(0, w - 1)
        val t = rect.top.coerceIn(0, h - 1)
        val r = rect.right.coerceIn(l + 1, w)
        val b = rect.bottom.coerceIn(t + 1, h)
        return Rect(l, t, r, b)
    }
}
