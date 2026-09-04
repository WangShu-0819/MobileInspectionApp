package com.wearable.inspection.mobile.template

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateCaptureConcurrencyTest {

    @Test
    fun `template capture locks before launching async work`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/template/TemplateCaptureViewModel.kt")
            .readText()
        val captureBody = source
            .substringAfter("fun onCapture(")
            .substringBefore("    /**\n     * 保存到数据库")
        val guard = captureBody.indexOf("if (current is CaptureState.Capturing) return")
        val lock = captureBody.indexOf("_state.value = CaptureState.Capturing")
        val launch = captureBody.indexOf("viewModelScope.launch")

        assertTrue("应先检查重复点击", guard >= 0)
        assertTrue("应在启动协程前锁定 Capturing 状态", lock >= 0 && lock < launch)
    }
}
