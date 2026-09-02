package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * DPM 离线解码基线测试
 *
 * 使用 Robolectric BitmapFactory 加载真实 JPEG + ZxingDataMatrixDecoder 解码。
 * 测试阶段（与旧版生产顺序一致）：
 * 1. 全图 ZXing（阶段2）
 * 2. 中心 50% ROI ZXing（阶段1）
 * 3. 中心 50% ROI + 缩放到400px ZXing（阶段1 完整流程）
 *
 * ML Kit 和网格兜底需 instrumented 环境，此处标注但不测试。
 * 此测试为算法证据，不能替代实时相机真机验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DpmOfflineDecodeTest {

    companion object {
        private val DPM_DATA_DIR = File(System.getProperty("user.dir")).parentFile?.let {
            File(it, "DPM_data")
        } ?: File("../DPM_data")

        private val SAMPLES = listOf(
            "45eb098523e21fa461e135dac8f7b678_720.jpg",
            "5ba81f191dc78bb60cf267eb9af10a54_720.jpg",
            "5fd53ffd01341e9dc10e4e977b804fad_720.jpg",
            "87879f06a1081dabfc34836fd92760ab_720.jpg",
            "a0a3e4f3ca567188aec2020ecacbc160.jpg",
            "c7f8366cd8452f313279c2b88a77eccf_720.jpg",
        )
    }

    private fun loadBitmap(filename: String): Bitmap? {
        val file = File(DPM_DATA_DIR, filename)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * 裁切中心 ratio 区域（与旧版 cropCenter(bitmap, CENTER_ROI_RATIO) 一致）
     */
    private fun cropCenter(src: Bitmap, ratio: Float): Bitmap {
        val cropW = (src.width * ratio).toInt().coerceIn(1, src.width)
        val cropH = (src.height * ratio).toInt().coerceIn(1, src.height)
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        return Bitmap.createBitmap(src, left, top, cropW, cropH)
    }

    /**
     * 缩放到目标宽度（保持宽高比）
     */
    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width <= targetW) return src
        val scale = targetW.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, targetW, (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    @Test
    fun `ZXing full image decode baseline`() {
        val decoder = ZxingDataMatrixDecoder()
        val results = mutableListOf<String>()
        var successCount = 0

        for (filename in SAMPLES) {
            val bitmap = loadBitmap(filename)
            if (bitmap == null) {
                results.add("$filename: FILE_NOT_FOUND_OR_UNREADABLE")
                continue
            }

            val startTime = System.currentTimeMillis()
            val result = kotlinx.coroutines.runBlocking { decoder.decode(bitmap) }
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                successCount++
                results.add("$filename: OK text='${result.rawValue.take(50)}' source=${result.source} ${bitmap.width}x${bitmap.height} time=${elapsed}ms")
            } else {
                results.add("$filename: NO_RESULT ${bitmap.width}x${bitmap.height} time=${elapsed}ms")
            }
        }

        println("=== ZXing Full Image Decode Baseline ===")
        println("DPM_DATA_DIR: ${DPM_DATA_DIR.absolutePath}")
        println("Success: $successCount / ${SAMPLES.size}")
        results.forEach { println(it) }
        println("=== End ===")
    }

    @Test
    fun `ZXing center 50 percent ROI decode baseline`() {
        val decoder = ZxingDataMatrixDecoder()
        val results = mutableListOf<String>()
        var successCount = 0

        for (filename in SAMPLES) {
            val bitmap = loadBitmap(filename)
            if (bitmap == null) {
                results.add("$filename: FILE_NOT_FOUND_OR_UNREADABLE")
                continue
            }

            // 旧版阶段1：中心 50% ROI
            val roi = cropCenter(bitmap, 0.5f)
            // 缩放到 400px（旧版 DPM_ROI_TARGET_WIDTH=400）
            val roiScaled = scaleToWidth(roi, 400)

            val startTime = System.currentTimeMillis()
            val result = kotlinx.coroutines.runBlocking { decoder.decode(roiScaled) }
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                successCount++
                results.add("$filename: OK text='${result.rawValue.take(50)}' source=${result.source} roi=${roi.width}x${roi.height}->${roiScaled.width}x${roiScaled.height} time=${elapsed}ms")
            } else {
                results.add("$filename: NO_RESULT roi=${roi.width}x${roi.height}->${roiScaled.width}x${roiScaled.height} time=${elapsed}ms")
            }
        }

        println("=== ZXing Center 50% ROI + Scale 400px Decode Baseline ===")
        println("Success: $successCount / ${SAMPLES.size}")
        results.forEach { println(it) }
        println("=== End ===")
        println("NOTE: ML Kit DATA_MATRIX and grid fallback require instrumented environment.")
    }

    @Test
    fun `sample file metadata`() {
        println("=== DPM Sample Metadata ===")
        println("DPM_DATA_DIR: ${DPM_DATA_DIR.absolutePath}")
        for (filename in SAMPLES) {
            val file = File(DPM_DATA_DIR, filename)
            if (!file.exists()) {
                println("$filename: NOT_FOUND")
                continue
            }
            val size = file.length()
            val bitmap = loadBitmap(filename)
            val dims = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "UNREADABLE"
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            println("$filename  size=$size  dims=$dims  sha256=$sha")
        }
        println("=== End ===")
    }
}
