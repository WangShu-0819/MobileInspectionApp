package com.wearable.inspection.mobile.ui.screens

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.wearable.inspection.mobile.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 模板图片加载结果，用于分类错误而非统一显示"模板图片加载失败"。
 */
sealed class TemplateLoadResult {
    /** 加载成功 */
    data class Success(val bitmap: Bitmap) : TemplateLoadResult()

    /** 加载失败，[userMessage] 用于 UI 显示（不含敏感路径或堆栈） */
    data class Failure(val userMessage: String, val cause: Throwable? = null) : TemplateLoadResult()
}

/**
 * 模板图片路径类型。
 */
internal enum class PathScheme {
    CONTENT,  // content://
    FILE_URI, // file://
    PLAIN,    // 绝对路径 /data/...
}

/**
 * 统一的模板图片加载器。
 *
 * - 支持 content://、file:// 和绝对文件路径；
 * - 两次打开输入流：第一次读取 bounds，第二次用受控 inSampleSize 解码；
 * - 正确区分 CancellationException 与真实加载异常；
 * - 所有 InputStream 使用 use {} 正确关闭。
 *
 * @param imageSource   模板图片路径（可以是 content:// URI、file:// URI 或绝对文件路径）
 * @param contentResolver 用于解析 content:// URI；传 null 时遇到 content:// 会返回 Failure
 * @param maxTargetSize 解码后最大边长（像素），默认 2048；<=0 时回退为 2048
 */
suspend fun loadTemplateBitmap(
    imageSource: String?,
    contentResolver: ContentResolver? = null,
    maxTargetSize: Int = 2048,
): TemplateLoadResult {
    return loadTemplateBitmapInternal(imageSource, contentResolver, maxTargetSize)
}

/**
 * 内部实现，[decodeFn] 可注入用于测试 CancellationException 传播。
 */
@VisibleForTesting
internal suspend fun loadTemplateBitmapInternal(
    imageSource: String?,
    contentResolver: ContentResolver? = null,
    maxTargetSize: Int = 2048,
    decodeFn: ((InputStream, BitmapFactory.Options) -> Bitmap?)? = null,
): TemplateLoadResult {
    // ── 1. 路径为空 ──
    if (imageSource.isNullOrBlank()) {
        return TemplateLoadResult.Failure("模板路径为空")
    }

    val scheme = classifyPath(imageSource)
    if (BuildConfig.DEBUG) {
        android.util.Log.d("TemplateImageLoader", "load: scheme=$scheme, source=$imageSource")
    }

    // ── 2. content:// 但没有 ContentResolver ──
    if (scheme == PathScheme.CONTENT && contentResolver == null) {
        return TemplateLoadResult.Failure("无法访问模板图片（缺少 ContentResolver）")
    }

    // ── 3. 文件预检（仅 file/plain） ──
    if (scheme != PathScheme.CONTENT) {
        val path = resolveFilePath(imageSource, scheme)
            ?: return TemplateLoadResult.Failure("模板路径无效")
        val file = File(path)
        checkFileReadable(file)?.let { return it }
    }

    // ── 4. 第一次打开：读取 bounds ──
    val (width, height) = try {
        openStream(imageSource, scheme, contentResolver).use { stream ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            opts.outWidth to opts.outHeight
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        logError("bounds", imageSource, scheme, e)
        return TemplateLoadResult.Failure("模板图片打开失败", e)
    }

    if (width <= 0 || height <= 0) {
        if (BuildConfig.DEBUG) {
            android.util.Log.w("TemplateImageLoader", "Invalid image dimensions: ${width}x${height}")
        }
        return TemplateLoadResult.Failure("模板图片格式无法识别")
    }

    // ── 5. 计算降采样率 ──
    val safeMaxTarget = if (maxTargetSize <= 0) 2048 else maxTargetSize
    val inSampleSize = calculateInSampleSize(width, height, safeMaxTarget)

    // ── 6. 第二次打开：解码 Bitmap ──
    return try {
        openStream(imageSource, scheme, contentResolver).use { stream ->
            val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val bitmap = if (decodeFn != null) {
                decodeFn(stream, opts)
            } else {
                BitmapFactory.decodeStream(stream, null, opts)
            }
            if (bitmap == null) {
                TemplateLoadResult.Failure("模板图片解码失败")
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "TemplateImageLoader",
                        "decoded: ${bitmap.width}x${bitmap.height}, sampleSize=$inSampleSize"
                    )
                }
                TemplateLoadResult.Success(bitmap)
            }
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        logError("decode", imageSource, scheme, e)
        TemplateLoadResult.Failure("模板图片解码失败", e)
    }
}

// ─── 内部实现 ────────────────────────────────────────────────────────────────────

/**
 * 解析文件路径：file:// 提取 path，plain 直接返回。
 */
private fun resolveFilePath(source: String, scheme: PathScheme): String? {
    return when (scheme) {
        PathScheme.FILE_URI -> Uri.parse(source).path
        PathScheme.PLAIN -> source
        PathScheme.CONTENT -> null
    }
}

/**
 * 打开输入流，支持 content://、file:// 和绝对路径。
 */
private fun openStream(
    source: String,
    scheme: PathScheme,
    contentResolver: ContentResolver?,
): InputStream {
    return when (scheme) {
        PathScheme.CONTENT -> {
            val uri = Uri.parse(source)
            contentResolver!!.openInputStream(uri)
                ?: throw IllegalStateException("无法打开模板图片")
        }
        PathScheme.FILE_URI -> {
            val path = Uri.parse(source).path
                ?: throw IllegalStateException("模板路径无效")
            FileInputStream(File(path))
        }
        PathScheme.PLAIN -> {
            FileInputStream(File(source))
        }
    }
}

/**
 * 检查文件是否可读，返回 null 表示通过，否则返回 Failure。
 */
private fun checkFileReadable(file: File): TemplateLoadResult.Failure? {
    if (!file.exists()) {
        return TemplateLoadResult.Failure("模板图片不存在")
    }
    if (file.length() == 0L) {
        return TemplateLoadResult.Failure("模板图片文件为空")
    }
    if (!file.canRead()) {
        return TemplateLoadResult.Failure("模板图片无法读取")
    }
    return null
}

/**
 * 判断路径类型。
 */
internal fun classifyPath(source: String): PathScheme {
    return when {
        source.startsWith("content://") -> PathScheme.CONTENT
        source.startsWith("file://") -> PathScheme.FILE_URI
        else -> PathScheme.PLAIN
    }
}

/**
 * 计算 inSampleSize（2 的幂次），确保解码后最大边不超过 [maxTarget]。
 * [maxTarget] <= 0 时回退为 2048，避免死循环。
 */
@VisibleForTesting
internal fun calculateInSampleSize(
    imageWidth: Int,
    imageHeight: Int,
    maxTarget: Int = 2048,
): Int {
    if (imageWidth <= 0 || imageHeight <= 0) return 1
    val safeTarget = if (maxTarget <= 0) 2048 else maxTarget
    var sample = 1
    val maxDimension = maxOf(imageWidth, imageHeight)
    while (maxDimension / sample > safeTarget) {
        sample *= 2
    }
    return sample
}

private fun logError(stage: String, source: String, scheme: PathScheme, e: Exception) {
    if (BuildConfig.DEBUG) {
        android.util.Log.e(
            "TemplateImageLoader",
            "Template load failed at stage=$stage, scheme=$scheme, source=$source",
            e
        )
    }
}
