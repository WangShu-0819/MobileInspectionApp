package com.wearable.inspection.mobile.camera

import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * 帧分析器接口
 *
 * 所有业务分析器（DPM、OCR、模板匹配等）实现此接口。
 * CameraController 在模式切换时调用 stop() 清理分析器内部状态。
 *
 * 实现者必须在 analyze() 的所有路径关闭 ImageProxy（包括异常路径）。
 */
interface FrameAnalyzer {
    /**
     * 分析一帧图像
     *
     * 实现者必须在所有路径（包括异常路径）关闭 image.close()。
     * CameraController 不负责关闭传入的 ImageProxy。
     *
     * @param image 待分析的图像帧，使用后必须关闭
     */
    fun analyze(image: ImageProxy)

    /**
     * 停止分析，清理内部资源
     *
     * CameraController 在以下时机调用：
     * - switchMode() 切换到新模式前
     * - disconnect() 页面离开时
     * - release() 永久释放时
     *
     * 调用后 analyze() 不应再被调用。
     */
    fun stop()
}

/**
 * 测试用计数分析器
 *
 * 记录 analyze/stop 调用次数，可配置抛异常测试错误路径。
 * 所有路径保证 ImageProxy 关闭。
 */
class TestCountingAnalyzer : FrameAnalyzer {

    private val _analyzeCount = AtomicInteger(0)
    val analyzeCount: Int get() = _analyzeCount.get()

    private val _stopCount = AtomicInteger(0)
    val stopCount: Int get() = _stopCount.get()

    private val _errorCount = AtomicInteger(0)
    val errorCount: Int get() = _errorCount.get()

    /** 设为 true 时 analyze() 抛出 RuntimeException，但仍关闭 ImageProxy */
    @Volatile
    var throwOnAnalyze = false

    override fun analyze(image: ImageProxy) {
        try {
            if (throwOnAnalyze) {
                _errorCount.incrementAndGet()
                throw RuntimeException("TestCountingAnalyzer: 故意抛出的测试异常")
            }
            _analyzeCount.incrementAndGet()
        } finally {
            image.close()
        }
    }

    override fun stop() {
        _stopCount.incrementAndGet()
    }
}
