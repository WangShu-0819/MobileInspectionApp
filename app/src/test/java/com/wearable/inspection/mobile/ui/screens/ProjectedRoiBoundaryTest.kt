package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.export.InspectionExcelExporter
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V4 ROI 边界收口回归测试。
 *
 * 覆盖：拍照后无手动 ROI 调整入口、投影坐标是唯一数据源、
 * 模板坐标回退已移除、CSV 与确认实体坐标一致。
 */
class ProjectedRoiBoundaryTest {

    // ───────────────────────────────────────────────
    // 1. 拍照后无手动 ROI 调整入口
    // ───────────────────────────────────────────────

    @Test
    fun `CaptureComparisonScreen has no manual ROI adjustment entry points`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt")
            .readText()

        // SessionRoiOverlay 内部无 pointerInput（平移缩放的 pointerInput 在 ComparisonViewport 中，不影响 ROI）
        val overlaySection = source.substringAfter("private fun SessionRoiOverlay", "")
        assertFalse(
            overlaySection.contains("pointerInput"),
            "SessionRoiOverlay must not have pointerInput",
        )
        assertFalse(
            overlaySection.contains("detectDragGestures"),
            "SessionRoiOverlay must not have detectDragGestures",
        )

        // 无 onMove/onResize 回调传递给 SessionRoiOverlay
        assertFalse(
            source.contains("onMove ="),
            "ComparisonViewport must not pass onMove to SessionRoiOverlay",
        )
        assertFalse(
            source.contains("onResize ="),
            "ComparisonViewport must not pass onResize to SessionRoiOverlay",
        )

        // 无 viewModel.moveRoi / resizeRoiByDelta 调用
        assertFalse(
            source.contains("viewModel.moveRoi"),
            "Screen must not call viewModel.moveRoi",
        )
        assertFalse(
            source.contains("viewModel.resizeRoiByDelta"),
            "Screen must not call viewModel.resizeRoiByDelta",
        )

        // 工具栏提示不含"拖动 ROI"
        assertFalse(
            source.contains("拖动 ROI"),
            "Toolbar hint must not mention dragging ROI",
        )
    }

    // ───────────────────────────────────────────────
    // 2. ViewModel 无 moveRoi/resizeRoiByDelta
    // ───────────────────────────────────────────────

    @Test
    fun `CaptureComparisonViewModel has no moveRoi or resizeRoiByDelta methods`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonViewModel.kt")
            .readText()

        assertFalse(
            source.contains("fun moveRoi("),
            "ViewModel must not have moveRoi method",
        )
        assertFalse(
            source.contains("fun resizeRoiByDelta("),
            "ViewModel must not have resizeRoiByDelta method",
        )

        // canProceed 和 isFullImageFallback 必须使用 projectedRoisSnapshot
        assertTrue(
            source.contains("canProceedToConfirmation(registrationResult, projectedRoisSnapshot)"),
            "canProceed must use projectedRoisSnapshot",
        )
        assertTrue(
            source.contains("projectedRoisSnapshot.isEmpty()"),
            "isFullImageFallback must use projectedRoisSnapshot",
        )
    }

    // ───────────────────────────────────────────────
    // 3. 非单位配准时保存的 roiPixelRect 等于 projected ROI
    // ───────────────────────────────────────────────

    @Test
    fun `non-identity registration produces entity with projected pixel rect`() {
        val roi = RoiDefinitionEntity(
            id = "r1",
            templateId = "t1",
            name = "ROI_r1",
            order = 0,
            normalizedRect = NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f).toJsonString(),
            inspectionType = "VISUAL",
            enabled = true,
        )
        val inference = NanoDetRoiInferenceResult(
            roiId = "r1",
            status = NanoDetInferenceStatus.NO_DETECTION,
            modelSuggestion = null,
            matchingScore = null,
            targetClassIndex = null,
        )

        // projected 像素坐标（来自 registration homography，与模板坐标不同）
        val projectedPixelRect = """{"left":288,"top":162,"right":672,"bottom":486}"""

        val entity = buildViewRoiConfirmEntity(
            batchId = "b1",
            photoId = 100L,
            photoPath = "/tmp/photo.jpg",
            viewIndex = 0,
            templateId = "t1",
            templateName = "模板A",
            roi = roi,
            roiPixelRect = projectedPixelRect,
            inference = inference,
            humanResult = "OK",
            overallResult = "OK",
            confirmedAt = System.currentTimeMillis(),
        )

        // entity 的 roiPixelRect 必须等于 projected 坐标
        val obj = JSONObject(entity.roiPixelRect)
        assertEquals(288, obj.getInt("left"))
        assertEquals(162, obj.getInt("top"))
        assertEquals(672, obj.getInt("right"))
        assertEquals(486, obj.getInt("bottom"))
    }

    // ───────────────────────────────────────────────
    // 4. projected 坐标缺失时不回退模板坐标
    // ───────────────────────────────────────────────

    @Test
    fun `saveRoiConfirms has no template-coordinate fallback`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt")
            .readText()

        // 找到 saveRoiConfirms 方法体
        val methodStart = source.indexOf("private suspend fun saveRoiConfirms(")
        assertTrue(methodStart > 0, "saveRoiConfirms must exist")

        // 提取方法体（约 90 行）
        val methodBody = source.substring(methodStart, (methodStart + 4000).coerceAtMost(source.length))

        // 不得包含 mapTemplateRoiToPhotoPixels（模板回退已移除）
        assertFalse(
            methodBody.contains("mapTemplateRoiToPhotoPixels"),
            "saveRoiConfirms must not call mapTemplateRoiToPhotoPixels",
        )

        // 必须检查 projectedPixelRects[roi.id]
        assertTrue(
            methodBody.contains("projectedPixelRects[roi.id]"),
            "saveRoiConfirms must check projectedPixelRects[roi.id]",
        )

        // 必须在 geometry 为 null 时 throw（fail-closed）
        assertTrue(
            methodBody.contains("throw IllegalStateException") || methodBody.contains("throw"),
            "saveRoiConfirms must throw for missing projected coords + null geometry",
        )
    }

    // ───────────────────────────────────────────────
    // 5. CSV 与确认实体坐标一致
    // ───────────────────────────────────────────────

    @Test
    fun `CSV export uses roiPixelRect from entity which comes from projected coordinates`() {
        val confirm = ViewRoiConfirmEntity(
            id = 0,
            batchId = "b1",
            photoId = 100L,
            photoPath = "/tmp/photo.jpg",
            viewIndex = 0,
            templateId = "t1",
            templateName = "模板A",
            roiId = "r1",
            roiName = "ROI",
            roiTargetType = "THREAD",
            roiNormalizedRect = """{"left":0.1,"top":0.1,"right":0.3,"bottom":0.3}""",
            roiPixelRect = """{"left":288,"top":162,"right":672,"bottom":486}""",
            humanResult = "OK",
            confirmTime = System.currentTimeMillis(),
            overallResult = "OK",
            overallConfirmTime = System.currentTimeMillis(),
        )

        val row = InspectionExcelExporter.toCsvRow(confirm, "part_001")
        // toCsvRow 第 9 列（0-based）为 roiPixelRect
        val pixelRectJson = row[9]
        val obj = JSONObject(pixelRectJson)
        assertEquals(288, obj.getInt("left"))
        assertEquals(162, obj.getInt("top"))
        assertEquals(672, obj.getInt("right"))
        assertEquals(486, obj.getInt("bottom"))
    }

    // ───────────────────────────────────────────────
    // 6. RegistrationSummary 文案已更新
    // ───────────────────────────────────────────────

    @Test
    fun `RegistrationSummary shows concise success text not verbose text`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt")
            .readText()
        assertTrue(
            source.contains("\"配准成功\""),
            "RegistrationSummary should say '配准成功' for success",
        )
        assertFalse(
            source.contains("已自动投影 Session ROI"),
            "RegistrationSummary should NOT contain old verbose '已自动投影' text",
        )
        assertFalse(
            source.contains("可调整 Session ROI"),
            "RegistrationSummary should NOT say '可调整' for success",
        )
    }
}
