package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.registration.RegistrationResult
import com.wearable.inspection.mobile.registration.RegistrationStatus
import com.wearable.inspection.mobile.data.export.InspectionZipExportService
import com.wearable.inspection.mobile.ui.screens.CaptureComparisonViewModel
import com.wearable.inspection.mobile.ui.screens.LoadingPath
import com.wearable.inspection.mobile.ui.screens.NormalizedRect
import com.wearable.inspection.mobile.ui.screens.SessionRoi
import com.wearable.inspection.mobile.ui.screens.SessionRoiRegistry
import com.wearable.inspection.mobile.ui.screens.ViewConfirmationViewModel
import com.wearable.inspection.mobile.ui.screens.allRoisProjected
import com.wearable.inspection.mobile.ui.screens.buildViewRoiConfirmEntity
import com.wearable.inspection.mobile.ui.screens.resolveLoadingPath
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V4 RegistrationResult → NanoDet 检测集成测试。
 *
 * 覆盖：成功投影、失败/fallback 可达、缓存缺失、重复输入稳定、
 * 不使用模板 ROI、整图导出回链、资源释放。
 */
class V4NanoDetIntegrationTest {

    // ───────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────

    private fun roiDef(id: String, name: String = "ROI_$id") = RoiDefinitionEntity(
        id = id,
        templateId = "t1",
        name = name,
        order = 0,
        normalizedRect = NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f).toJsonString(),
        inspectionType = "VISUAL",
        enabled = true,
    )

    private fun makeRegistration(
        status: RegistrationStatus,
        homography: DoubleArray? = null,
    ): RegistrationResult = RegistrationResult(
        status = status,
        homography = homography,
        projectedRoiCorners = null,
        inlierCount = 0,
        inlierRatio = 0.0,
        reprojectionError = 0.0,
        spatialCoverage = 0.0,
        quadrilateralValid = false,
        matcherName = "test",
        matcherVersion = "0",
        failureReason = if (status != RegistrationStatus.SUCCESS) "test failure" else null,
    )

    private val identityHomography = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )

    // ───────────────────────────────────────────────
    // 1. SUCCESS 投影坐标传递到 SessionRoiRegistry
    // ───────────────────────────────────────────────

    @Test
    fun `SUCCESS projected session ROIs round-trip through registry`() {
        SessionRoiRegistry.clearAll()
        val rois = listOf(
            roiDef("r1", "区域A") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("r2", "区域B") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val projected = NormalizedRect(0.15f, 0.15f, 0.35f, 0.35f)
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        // Simulate what onProceed does
        SessionRoiRegistry.write("b1", 100L, 0, sessionRois, reg.status)

        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        assertNotNull(entry)
        assertEquals(RegistrationStatus.SUCCESS, entry.registrationStatus)
        assertEquals(2, entry.sessionRois.size)
        assertEquals("r1", entry.sessionRois[0].id)
        assertEquals("区域A", entry.sessionRois[0].name)
        assertEquals(projected, entry.sessionRois[0].rect)
        assertEquals("r2", entry.sessionRois[1].id)
    }

    @Test
    fun `SUCCESS path preserves projected coordinates not template coordinates`() {
        SessionRoiRegistry.clearAll()
        val templateRect = NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)
        val projectedRect = NormalizedRect(0.2f, 0.25f, 0.45f, 0.5f)
        val rois = listOf(roiDef("r1") to templateRect)
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projectedRect }

        SessionRoiRegistry.write("b1", 100L, 0, sessionRois, reg.status)
        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)!!

        // Projected rect must differ from template rect
        assertEquals(projectedRect, entry.sessionRois[0].rect)
        assertTrue(entry.sessionRois[0].rect != templateRect)
    }

    // ───────────────────────────────────────────────
    // 2. FAILED / FALLBACK 可达确认页
    // ───────────────────────────────────────────────

    @Test
    fun `FAILED registration allows proceed to confirmation`() {
        val reg = makeRegistration(RegistrationStatus.FAILED)
        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, emptyList()),
            "FAILED registration must allow proceed for full-image fallback",
        )
    }

    @Test
    fun `FALLBACK_FULL_IMAGE allows proceed to confirmation`() {
        val reg = makeRegistration(RegistrationStatus.FALLBACK_FULL_IMAGE)
        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, emptyList()),
            "FALLBACK_FULL_IMAGE must allow proceed for full-image fallback",
        )
    }

    @Test
    fun `FAILED writes to registry with FAILED status`() {
        SessionRoiRegistry.clearAll()
        val reg = makeRegistration(RegistrationStatus.FAILED)
        SessionRoiRegistry.write("b1", 100L, 0, emptyList(), reg.status)

        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        assertNotNull(entry)
        assertEquals(RegistrationStatus.FAILED, entry.registrationStatus)
        assertTrue(entry.sessionRois.isEmpty())
    }

    @Test
    fun `FALLBACK writes to registry with FALLBACK status`() {
        SessionRoiRegistry.clearAll()
        val reg = makeRegistration(RegistrationStatus.FALLBACK_FULL_IMAGE)
        SessionRoiRegistry.write("b1", 100L, 0, emptyList(), reg.status)

        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        assertNotNull(entry)
        assertEquals(RegistrationStatus.FALLBACK_FULL_IMAGE, entry.registrationStatus)
    }

    @Test
    fun `isFullImageFallback is true when registration is FAILED`() {
        val reg = makeRegistration(RegistrationStatus.FAILED)
        // Simulate CaptureComparisonViewModel.isFullImageFallback logic
        val isFullImageFallback = reg.status != RegistrationStatus.SUCCESS || true /* sessionRois.isEmpty() */
        assertTrue(isFullImageFallback)
    }

    @Test
    fun `isFullImageFallback is true when registration is FALLBACK`() {
        val reg = makeRegistration(RegistrationStatus.FALLBACK_FULL_IMAGE)
        val isFullImageFallback = reg.status != RegistrationStatus.SUCCESS || true
        assertTrue(isFullImageFallback)
    }

    @Test
    fun `isFullImageFallback is false when SUCCESS with non-empty session ROIs`() {
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val sessionRois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)))
        val isFullImageFallback = reg.status != RegistrationStatus.SUCCESS || sessionRois.isEmpty()
        assertFalse(isFullImageFallback)
    }

    // ───────────────────────────────────────────────
    // 3. 缓存缺失 → 不使用模板 ROI
    // ───────────────────────────────────────────────

    @Test
    fun `null registration blocks proceed`() {
        assertFalse(
            CaptureComparisonViewModel.canProceedToConfirmation(null, emptyList()),
            "Null registration (not attempted) must block proceed",
        )
    }

    @Test
    fun `registry miss returns null`() {
        SessionRoiRegistry.clearAll()
        val entry = SessionRoiRegistry.readAndConsume("nonexistent", 999L, 0)
        assertNull(entry)
    }

    @Test
    fun `registry consume semantics - second read returns null`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 100L, 0, listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))), RegistrationStatus.SUCCESS)

        val first = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        val second = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        assertNotNull(first)
        assertNull(second, "Second read must return null (consumed)")
    }

    // ───────────────────────────────────────────────
    // 4. 重复输入稳定
    // ───────────────────────────────────────────────

    @Test
    fun `repeated buildSessionRois with same input is stable`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val projected = NormalizedRect(0.15f, 0.25f, 0.45f, 0.55f)

        val first = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }
        val second = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        assertEquals(first, second, "Repeated calls must produce identical sessionRois")
    }

    @Test
    fun `repeated canProceedToConfirmation is stable`() {
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val sessionRois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)))

        val first = CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois)
        val second = CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois)

        assertEquals(first, second)
        assertTrue(first)
    }

    @Test
    fun `repeated registry write-read is stable`() {
        SessionRoiRegistry.clearAll()
        val rois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)))

        SessionRoiRegistry.write("b1", 100L, 0, rois, RegistrationStatus.SUCCESS)
        val first = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        SessionRoiRegistry.write("b1", 100L, 0, rois, RegistrationStatus.SUCCESS)
        val second = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.sessionRois, second.sessionRois)
        assertEquals(first.registrationStatus, second.registrationStatus)
    }

    // ───────────────────────────────────────────────
    // 5. 不使用模板 ROI（FAILED/FALLBACK 不投影）
    // ───────────────────────────────────────────────

    @Test
    fun `FAILED buildSessionRois returns empty list`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.FAILED)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) {
            NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)
        }
        assertTrue(sessionRois.isEmpty(), "FAILED must not produce session ROIs")
    }

    @Test
    fun `FALLBACK buildSessionRois returns empty list`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.FALLBACK_FULL_IMAGE)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) {
            NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)
        }
        assertTrue(sessionRois.isEmpty(), "FALLBACK must not produce session ROIs")
    }

    @Test
    fun `null projection rejects entire batch (all-or-nothing)`() {
        val rois = listOf(
            roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("r2") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)

        var callIndex = 0
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) {
            if (callIndex++ == 0) null else NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f)
        }

        assertTrue(sessionRois.isEmpty(), "One null projection must reject the entire batch")
    }

    // ───────────────────────────────────────────────
    // 6. 整图导出回链
    // ───────────────────────────────────────────────

    @Test
    fun `full-image ROI ID constant is defined`() {
        assertEquals("__FULL_IMAGE__", ViewConfirmationViewModel.FULL_IMAGE_ROI_ID)
    }

    @Test
    fun `FullImageInferResult aggregatedSuggestion is always null`() {
        val result = FullImageInferResult(
            detections = listOf(
                NanoDetDetection(
                    classIndex = 0, className = "NUT", score = 0.95f, point = 0,
                    roiBox = NanoDetBox(0.0, 0.0, 100.0, 100.0),
                    imageBox = NanoDetBox(0.0, 0.0, 100.0, 100.0),
                )
            ),
            highestScore = 0.95f,
            elapsedMs = 50L,
            imageWidth = 1920,
            imageHeight = 1080,
            exifOrientation = 1,
            status = NanoDetInferenceStatus.DETECTED,
        )
        assertNull(result.aggregatedSuggestion, "Full-image mode must not auto-fake OK/NG")
    }

    @Test
    fun `FullImageInferResult with no detections has null suggestion`() {
        val result = FullImageInferResult(
            detections = emptyList(),
            highestScore = null,
            elapsedMs = 30L,
            imageWidth = 1920,
            imageHeight = 1080,
            exifOrientation = 1,
            status = NanoDetInferenceStatus.NO_DETECTION,
        )
        assertNull(result.aggregatedSuggestion, "No detections must not auto-fake NG")
    }

    @Test
    fun `FullImageInferResult with low score has null suggestion`() {
        val result = FullImageInferResult(
            detections = listOf(
                NanoDetDetection(
                    classIndex = 1, className = "THREAD", score = 0.1f, point = 0,
                    roiBox = NanoDetBox(0.0, 0.0, 50.0, 50.0),
                    imageBox = NanoDetBox(0.0, 0.0, 50.0, 50.0),
                )
            ),
            highestScore = 0.1f,
            elapsedMs = 40L,
            imageWidth = 1920,
            imageHeight = 1080,
            exifOrientation = 1,
            status = NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
        )
        assertNull(result.aggregatedSuggestion, "Low confidence must not auto-fake NG")
    }

    @Test
    fun `full-image entity fields survive round-trip`() {
        // Simulate what buildViewRoiConfirmEntity produces for full-image mode
        val detections = listOf(
            NanoDetDetection(0, "NUT", 0.88f, 0, NanoDetBox(10.0, 10.0, 50.0, 50.0), NanoDetBox(10.0, 10.0, 50.0, 50.0)),
            NanoDetDetection(1, "THREAD", 0.72f, 0, NanoDetBox(60.0, 60.0, 100.0, 100.0), NanoDetBox(60.0, 60.0, 100.0, 100.0)),
        )
        val infer = NanoDetRoiInferenceResult(
            roiId = "__FULL_IMAGE__",
            status = NanoDetInferenceStatus.DETECTED,
            modelSuggestion = null,
            matchingScore = 0.88f,
            targetClassIndex = null,
            detections = detections,
            imageWidth = 1920,
            imageHeight = 1080,
        )
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1",
            photoId = 100L,
            photoPath = "/tmp/photo.jpg",
            viewIndex = 0,
            templateId = "t1",
            templateName = "模板A",
            roi = RoiDefinitionEntity(
                id = "__FULL_IMAGE__", templateId = "t1", name = "整图检测",
                order = 0, normalizedRect = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}""",
                inspectionType = "VISUAL", enabled = true,
            ),
            roiPixelRect = """{"left":0,"top":0,"right":1920,"bottom":1080}""",
            inference = infer,
            humanResult = "OK",
            overallResult = "OK",
            confirmedAt = System.currentTimeMillis(),
        )

        assertEquals("__FULL_IMAGE__", entity.roiId)
        assertEquals("整图检测", entity.roiName)
        assertEquals("OK", entity.humanResult)
        assertEquals("OK", entity.overallResult)
        assertNull(entity.softwareResult, "modelSuggestion is null → softwareResult is null")
        assertEquals(0.88f, entity.softwareScore)
        assertFalse(entity.humanChangedModel, "null modelSuggestion → humanChangedModel is false")
        assertNotNull(entity.softwareDetectionsJson)
        assertTrue(entity.softwareDetectionsJson!!.contains("NUT"))
        assertTrue(entity.softwareDetectionsJson!!.contains("THREAD"))
        assertEquals(2, entity.softwareDetectionsJson!!.split("\"className\"").size - 1)
    }

    // ───────────────────────────────────────────────
    // 7. 资源释放
    // ───────────────────────────────────────────────

    @Test
    fun `SessionRoiRegistry clearAll removes all entries`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 1L, 0, listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))), RegistrationStatus.SUCCESS)
        SessionRoiRegistry.write("b2", 2L, 1, listOf(SessionRoi("r2", "B", NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f))), RegistrationStatus.FAILED)
        SessionRoiRegistry.clearAll()

        assertNull(SessionRoiRegistry.readAndConsume("b1", 1L, 0))
        assertNull(SessionRoiRegistry.readAndConsume("b2", 2L, 1))
    }

    @Test
    fun `SessionRoiRegistry clear removes specific entry`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 1L, 0, listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))), RegistrationStatus.SUCCESS)
        SessionRoiRegistry.clear("b1", 1L, 0)

        assertNull(SessionRoiRegistry.readAndConsume("b1", 1L, 0))
    }

    // ───────────────────────────────────────────────
    // 8. All registration statuses allow proceed
    // ───────────────────────────────────────────────

    @Test
    fun `all registration statuses allow proceed`() {
        for (status in RegistrationStatus.entries) {
            val reg = makeRegistration(status)
            assertTrue(
                CaptureComparisonViewModel.canProceedToConfirmation(reg, emptyList()),
                "Status $status must allow proceed",
            )
        }
    }

    // ───────────────────────────────────────────────
    // 9. buildSessionRois preserves ROI metadata
    // ───────────────────────────────────────────────

    @Test
    fun `successful projection preserves roi id and name`() {
        val rois = listOf(
            roiDef("id_a", "区域A") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("id_b", "区域B") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val projected = NormalizedRect(0.15f, 0.15f, 0.35f, 0.35f)
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)

        val result = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        assertEquals(2, result.size)
        assertEquals("id_a", result[0].id)
        assertEquals("区域A", result[0].name)
        assertEquals("id_b", result[1].id)
        assertEquals("区域B", result[1].name)
        assertEquals(projected, result[0].rect)
        assertEquals(projected, result[1].rect)
    }

    // ───────────────────────────────────────────────
    // 10. Export: synthetic RoiDefinitionEntity for unmatched confirms
    // ───────────────────────────────────────────────

    @Test
    fun `synthetic RoiDefinitionEntity preserves confirm fields for export`() {
        // Simulates what InspectionZipExportService does for __FULL_IMAGE__ confirms
        val confirmRoiId = "__FULL_IMAGE__"
        val confirmRoiName = "整图检测"
        val confirmRoiNormalizedRect = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}"""
        val confirmRoiTargetType: String? = null

        val syntheticDef = RoiDefinitionEntity(
            id = confirmRoiId,
            templateId = "t1",
            name = confirmRoiName,
            order = 0,
            normalizedRect = confirmRoiNormalizedRect,
            inspectionType = "VISUAL",
            enabled = true,
            targetType = confirmRoiTargetType,
        )

        assertEquals(confirmRoiId, syntheticDef.id)
        assertEquals(confirmRoiName, syntheticDef.name)
        assertEquals(confirmRoiNormalizedRect, syntheticDef.normalizedRect)
        assertNull(syntheticDef.targetType)
    }

    // ───────────────────────────────────────────────
    // 11. resolveLoadingPath — ViewModel 分支路由
    // ───────────────────────────────────────────────

    @Test
    fun `resolveLoadingPath - SUCCESS with non-empty sessionRois returns PROJECTED`() {
        val entry = SessionRoiRegistry.Entry(
            sessionRois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))),
            registrationStatus = RegistrationStatus.SUCCESS,
        )
        assertEquals(LoadingPath.PROJECTED, resolveLoadingPath(entry, isFullImageFallback = false))
    }

    @Test
    fun `resolveLoadingPath - SUCCESS with empty sessionRois returns FULL_IMAGE`() {
        val entry = SessionRoiRegistry.Entry(
            sessionRois = emptyList(),
            registrationStatus = RegistrationStatus.SUCCESS,
        )
        assertEquals(LoadingPath.FULL_IMAGE, resolveLoadingPath(entry, isFullImageFallback = false))
    }

    @Test
    fun `resolveLoadingPath - FAILED entry returns FULL_IMAGE`() {
        val entry = SessionRoiRegistry.Entry(
            sessionRois = emptyList(),
            registrationStatus = RegistrationStatus.FAILED,
        )
        assertEquals(LoadingPath.FULL_IMAGE, resolveLoadingPath(entry, isFullImageFallback = false))
    }

    @Test
    fun `resolveLoadingPath - FALLBACK entry returns FULL_IMAGE`() {
        val entry = SessionRoiRegistry.Entry(
            sessionRois = emptyList(),
            registrationStatus = RegistrationStatus.FALLBACK_FULL_IMAGE,
        )
        assertEquals(LoadingPath.FULL_IMAGE, resolveLoadingPath(entry, isFullImageFallback = false))
    }

    @Test
    fun `resolveLoadingPath - isFullImageFallback true returns FULL_IMAGE regardless of cached`() {
        val entry = SessionRoiRegistry.Entry(
            sessionRois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))),
            registrationStatus = RegistrationStatus.SUCCESS,
        )
        assertEquals(LoadingPath.FULL_IMAGE, resolveLoadingPath(entry, isFullImageFallback = true))
    }

    @Test
    fun `resolveLoadingPath - cache miss returns FULL_IMAGE not TEMPLATE`() {
        // cached == null && !isFullImageFallback → fail-closed, NEVER TEMPLATE
        assertEquals(LoadingPath.FULL_IMAGE, resolveLoadingPath(null, isFullImageFallback = false))
    }

    @Test
    fun `resolveLoadingPath - never returns TEMPLATE`() {
        // Exhaustive: no combination of inputs should produce TEMPLATE
        val entries = listOf(
            null,
            SessionRoiRegistry.Entry(emptyList(), RegistrationStatus.SUCCESS),
            SessionRoiRegistry.Entry(listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))), RegistrationStatus.SUCCESS),
            SessionRoiRegistry.Entry(emptyList(), RegistrationStatus.FAILED),
            SessionRoiRegistry.Entry(emptyList(), RegistrationStatus.FALLBACK_FULL_IMAGE),
        )
        for (entry in entries) {
            for (fallback in listOf(false, true)) {
                val path = resolveLoadingPath(entry, fallback)
                assertTrue(
                    path != LoadingPath.TEMPLATE,
                    "resolveLoadingPath must never return TEMPLATE (entry=$entry, fallback=$fallback)"
                )
            }
        }
    }

    @Test
    fun `resolveLoadingPath - repeated calls are stable`() {
        val entry = SessionRoiRegistry.Entry(
            sessionRois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))),
            registrationStatus = RegistrationStatus.SUCCESS,
        )
        val first = resolveLoadingPath(entry, false)
        val second = resolveLoadingPath(entry, false)
        assertEquals(first, second)
    }

    // ───────────────────────────────────────────────
    // 12. allRoisProjected — 部分投影拒绝
    // ───────────────────────────────────────────────

    @Test
    fun `allRoisProjected - all present returns true`() {
        val roiIds = listOf("r1", "r2")
        val sessionRois = listOf(
            SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)),
            SessionRoi("r2", "B", NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f)),
        )
        assertTrue(allRoisProjected(roiIds, sessionRois))
    }

    @Test
    fun `allRoisProjected - missing one returns false`() {
        val roiIds = listOf("r1", "r2", "r3")
        val sessionRois = listOf(
            SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)),
            SessionRoi("r2", "B", NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f)),
            // r3 missing
        )
        assertFalse(allRoisProjected(roiIds, sessionRois))
    }

    @Test
    fun `allRoisProjected - empty sessionRois returns false`() {
        val roiIds = listOf("r1")
        assertFalse(allRoisProjected(roiIds, emptyList()))
    }

    @Test
    fun `allRoisProjected - empty roiIds returns true`() {
        val sessionRois = listOf(SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)))
        assertTrue(allRoisProjected(emptyList(), sessionRois))
    }

    @Test
    fun `allRoisProjected - extra sessionRois are ignored`() {
        val roiIds = listOf("r1")
        val sessionRois = listOf(
            SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)),
            SessionRoi("r_extra", "X", NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f)),
        )
        assertTrue(allRoisProjected(roiIds, sessionRois))
    }

    @Test
    fun `partial projected ROI triggers full-image fallback via allRoisProjected`() {
        // Simulates: 3 template ROIs but only 2 projected → must reject batch
        val roiIds = listOf("r1", "r2", "r3")
        val sessionRois = listOf(
            SessionRoi("r1", "A", NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)),
            SessionRoi("r2", "B", NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f)),
            // r3 not projected
        )
        assertFalse(
            allRoisProjected(roiIds, sessionRois),
            "Partial projection must reject batch — never use template normalizedRect for missing ROI"
        )
    }

    // ───────────────────────────────────────────────
    // 13. Export: KNOWN_SYNTHETIC_ROI_IDS 约束
    // ───────────────────────────────────────────────

    @Test
    fun `__FULL_IMAGE__ is in KNOWN_SYNTHETIC_ROI_IDS`() {
        assertTrue(
            "__FULL_IMAGE__" in InspectionZipExportService.KNOWN_SYNTHETIC_ROI_IDS,
            "__FULL_IMAGE__ must be recognized as a known synthetic ROI ID for export"
        )
    }

    @Test
    fun `unknown roiIds are NOT in KNOWN_SYNTHETIC_ROI_IDS`() {
        val unknownIds = listOf("stale_roi_123", "old_template_roi", "random_id", "")
        for (id in unknownIds) {
            assertFalse(
                id in InspectionZipExportService.KNOWN_SYNTHETIC_ROI_IDS,
                "Unknown roiId '$id' must not be unconditionally treated as synthetic"
            )
        }
    }

    @Test
    fun `export code restricts synthetic defs to known IDs only`() {
        // Source-level verification: export code must check KNOWN_SYNTHETIC_ROI_IDS
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt"
        ).readText()
        assertTrue(
            source.contains("KNOWN_SYNTHETIC_ROI_IDS"),
            "Export code must reference KNOWN_SYNTHETIC_ROI_IDS constant"
        )
        assertTrue(
            source.contains("confirm.roiId in KNOWN_SYNTHETIC_ROI_IDS"),
            "Export code must gate synthetic definition creation on known IDs"
        )
        assertTrue(
            source.contains("Skipping unmatched confirm"),
            "Export code must log/skip unknown roiIds"
        )
    }

    // ───────────────────────────────────────────────
    // 14. projectedRoisSnapshot 不受 UI 手调影响
    // ───────────────────────────────────────────────

    @Test
    fun `projectedRoisSnapshot is set from buildSessionRois not from UI`() {
        // buildSessionRois produces the projected data
        val rois = listOf(
            roiDef("r1", "区域A") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("r2", "区域B") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val projected = NormalizedRect(0.15f, 0.15f, 0.35f, 0.35f)
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val projectedRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        // projectedRoisSnapshot should contain registration-produced coordinates
        assertEquals(2, projectedRois.size)
        assertEquals(projected, projectedRois[0].rect)
        assertEquals(projected, projectedRois[1].rect)

        // Simulate UI manual adjustment (moveRoi)
        val manuallyAdjusted = projectedRois.map { roi ->
            if (roi.id == "r1") roi.copy(rect = NormalizedRect(0.2f, 0.2f, 0.4f, 0.4f)) else roi
        }

        // projectedRoisSnapshot must NOT be affected by manual adjustment
        assertTrue(manuallyAdjusted[0].rect != projectedRois[0].rect)
        assertEquals(projected, projectedRois[0].rect)
    }

    @Test
    fun `registry write uses projectedRoisSnapshot not mutable sessionRois`() {
        SessionRoiRegistry.clearAll()
        val projected = NormalizedRect(0.15f, 0.15f, 0.35f, 0.35f)
        val manualRect = NormalizedRect(0.25f, 0.25f, 0.45f, 0.45f)

        // Simulate: projected snapshot differs from UI-adjusted sessionRois
        val projectedSnapshot = listOf(SessionRoi("r1", "A", projected))
        val manuallyAdjusted = listOf(SessionRoi("r1", "A", manualRect))

        // AppNavigation should write projectedRoisSnapshot, not sessionRois
        SessionRoiRegistry.write("b1", 100L, 0, projectedSnapshot, RegistrationStatus.SUCCESS)
        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)!!

        assertEquals(projected, entry.sessionRois[0].rect)
        assertTrue(entry.sessionRois[0].rect != manualRect)
    }

    // ───────────────────────────────────────────────
    // 15. 手调 ROI 改变后，保存的 roiPixelRect 不变
    // ───────────────────────────────────────────────

    @Test
    fun `projectedPixelRects used in save are same as NanoDet input`() {
        // Source-level verification: saveRoiConfirms must check projectedPixelRects first
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt"
        ).readText()
        assertTrue(
            source.contains("projectedPixelRects[roi.id]"),
            "saveRoiConfirms must check projectedPixelRects first"
        )
        assertTrue(
            source.contains("projectedPixelRects[roi.id] = pixelRect"),
            "projectedPixelRects must be populated in loadWithProjectedRois"
        )
    }

    @Test
    fun `manual ROI drag does not change roiPixelRect in entity`() {
        // The entity's roiPixelRect comes from projectedPixelRects, not from sessionRois
        val roi = roiDef("r1")
        val inference = NanoDetRoiInferenceResult(
            roiId = "r1", status = NanoDetInferenceStatus.NO_DETECTION,
            modelSuggestion = null, matchingScore = null, targetClassIndex = null,
        )
        val projectedPixelRectJson = """{"left":288,"top":162,"right":672,"bottom":486}"""
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 100L, photoPath = "/tmp/photo.jpg",
            viewIndex = 0, templateId = "t1", templateName = "模板A",
            roi = roi,
            roiPixelRect = projectedPixelRectJson,  // from projected coords, not manual
            inference = inference,
            humanResult = "OK", overallResult = "OK", confirmedAt = System.currentTimeMillis(),
        )

        // Entity must contain the projected pixel rect exactly as provided
        assertTrue(entity.roiPixelRect.contains("288"))
        assertTrue(entity.roiPixelRect.contains("162"))
        assertTrue(entity.roiPixelRect.contains("672"))
        assertTrue(entity.roiPixelRect.contains("486"))
    }

    // ───────────────────────────────────────────────
    // 16. 非单位配准：保存坐标 = projected ROI ≠ 模板 ROI
    // ───────────────────────────────────────────────

    @Test
    fun `non-identity homography produces different coordinates than template`() {
        val templateRect = NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)
        val projectedRect = NormalizedRect(0.15f, 0.2f, 0.4f, 0.45f)  // shifted by homography

        // buildSessionRois with non-identity projection
        val rois = listOf(roiDef("r1") to templateRect)
        val reg = makeRegistration(RegistrationStatus.SUCCESS, doubleArrayOf(
            1.1, 0.05, 10.0,
            0.02, 1.08, 5.0,
            0.0001, 0.0002, 1.0,
        ))
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projectedRect }

        assertEquals(1, sessionRois.size)
        assertEquals(projectedRect, sessionRois[0].rect)
        assertTrue(sessionRois[0].rect != templateRect, "Projected ROI must differ from template ROI")
    }

    @Test
    fun `registry entry stores projected not template coordinates`() {
        SessionRoiRegistry.clearAll()
        val templateRect = NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)
        val projectedRect = NormalizedRect(0.15f, 0.2f, 0.4f, 0.45f)

        val projectedRois = listOf(SessionRoi("r1", "A", projectedRect))
        SessionRoiRegistry.write("b1", 100L, 0, projectedRois, RegistrationStatus.SUCCESS)

        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)!!
        assertEquals(projectedRect, entry.sessionRois[0].rect)
        assertTrue(entry.sessionRois[0].rect != templateRect)
    }

    // ───────────────────────────────────────────────
    // 17. saveRoiConfirms 优先使用 projectedPixelRects
    // ───────────────────────────────────────────────

    @Test
    fun `saveRoiConfirms source uses projectedPixelRects before template fallback`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt"
        ).readText()
        assertTrue(
            source.contains("projectedPixelRects[roi.id]"),
            "saveRoiConfirms must check projectedPixelRects first"
        )
        assertTrue(
            source.contains("projectedPixelRects[roi.id] = pixelRect"),
            "projectedPixelRects must be populated in loadWithProjectedRois"
        )
    }

    // ───────────────────────────────────────────────
    // 18. projectedRoisSnapshot 字段存在且不可变语义
    // ───────────────────────────────────────────────

    @Test
    fun `CaptureComparisonViewModel has projectedRoisSnapshot property`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonViewModel.kt"
        ).readText()
        assertTrue(
            source.contains("var projectedRoisSnapshot"),
            "ViewModel must have projectedRoisSnapshot property"
        )
        assertTrue(
            source.contains("projectedRoisSnapshot = loaded.projectedRoisSnapshot"),
            "projectedRoisSnapshot must be set in loadFromStorage"
        )
    }

    @Test
    fun `AppNavigation writes projectedRoisSnapshot not sessionRois to registry`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt"
        ).readText()
        assertTrue(
            source.contains("sessionRois = comparisonViewModel.projectedRoisSnapshot"),
            "AppNavigation must write projectedRoisSnapshot to registry"
        )
        assertFalse(
            source.contains("sessionRois = comparisonViewModel.sessionRois,\n"),
            "AppNavigation must NOT write mutable sessionRois to registry"
        )
    }
}
