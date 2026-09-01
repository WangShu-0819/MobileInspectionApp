package com.wearable.inspection.mobile.camera

import androidx.camera.core.ImageProxy

/**
 * 帧分析器接口
 *
 * 实现者可以用于检查分析、缺陷检测、模板采集等。
 *
 * **重要**：FrameAnalyzer 不负责关闭 ImageProxy。
 * CameraController 拥有 ImageProxy 的生命周期，在 analyze() 返回后（包括异常时）关闭。
 */
interface FrameAnalyzer {
    /**
     * 分析一帧图像
     *
     * 注意：不要调用 image.close()，由 CameraController 管理关闭。
     */
    fun analyze(image: ImageProxy)

    /**
     * 停止分析器，释放资源
     */
    fun stop()
}

/**
 * 累加计数分析器（用于测试）
 *
 * 不关闭 ImageProxy，由 CameraController 在 finally 中关闭。
 */
class TestCountingAnalyzer : FrameAnalyzer {

    private val _analyzeCount = java.util.concurrent.atomic.AtomicInteger(0)
    val analyzeCount: Int get() = _analyzeCount.get()

    @Volatile
    var throwOnAnalyze = false

    override fun analyze(image: ImageProxy) {
        if (throwOnAnalyze) {
            throw RuntimeException("测试异常")
        }
        _analyzeCount.incrementAndGet()
    }

    override fun stop() {
        _analyzeCount.set(0)
    }
}
