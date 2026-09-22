package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.registration.RegistrationResult
import com.wearable.inspection.mobile.registration.RegistrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureComparisonGeometryTest {

    // ───────────────────────────────────────────────
    // NormalizedRect geometry (unchanged)
    // ───────────────────────────────────────────────

    @Test
    fun moveClampsSessionRoiInsideImage() {
        val moved = NormalizedRect(0.2f, 0.3f, 0.6f, 0.8f).move(0.7f, -0.6f)

        assertEquals(0.6f, moved.left, 0.0001f)
        assertEquals(0.0f, moved.top, 0.0001f)
        assertEquals(1.0f, moved.right, 0.0001f)
        assertEquals(0.5f, moved.bottom, 0.0001f)
    }

    @Test
    fun resizeTopLeftKeepsBottomRightFixed() {
        val original = NormalizedRect(0.2f, 0.3f, 0.7f, 0.8f)
        val resized = original.resize(0, 0.1f, 0.15f)

        assertEquals(0.1f, resized.left, 0.0001f)
        assertEquals(0.15f, resized.top, 0.0001f)
        assertEquals(original.right, resized.right, 0.0001f)
        assertEquals(original.bottom, resized.bottom, 0.0001f)
    }

    @Test
    fun resizeEnforcesMinimumSizeAtBoundary() {
        val resized = NormalizedRect(0.01f, 0.01f, 0.5f, 0.5f).resize(0, 0f, 0f)

        assertTrue(resized.right - resized.left >= 0.02f)
        assertTrue(resized.bottom - resized.top >= 0.02f)
        assertTrue(resized.left in 0f..1f && resized.right in 0f..1f)
        assertTrue(resized.top in 0f..1f && resized.bottom in 0f..1f)
    }

    @Test
    fun serializationPreservesSessionRoiCoordinates() {
        val source = NormalizedRect(0.12f, 0.23f, 0.67f, 0.89f)

        val restored = NormalizedRect.fromJsonString(source.toJsonString())

        requireNotNull(restored)
        assertEquals(source.left, restored.left, 0.0001f)
        assertEquals(source.top, restored.top, 0.0001f)
        assertEquals(source.right, restored.right, 0.0001f)
        assertEquals(source.bottom, restored.bottom, 0.0001f)
    }

    // ───────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────

    private fun roiDef(id: String, name: String = "ROI_$id"): RoiDefinitionEntity =
        RoiDefinitionEntity(
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
    // FAILED / FALLBACK → empty, canProceed = false
    // ───────────────────────────────────────────────

    @Test
    fun `FAILED registration yields empty sessionRois`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))

        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = rois,
            registration = makeRegistration(RegistrationStatus.FAILED),
            project = { NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f) },
        )

        assertTrue(result.isEmpty(), "FAILED registration must not produce session ROIs")
    }

    @Test
    fun `FAILED registration allows proceed for full-image fallback`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.FAILED)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) {
            NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)
        }

        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois),
            "FAILED registration must allow proceed for full-image fallback",
        )
    }

    @Test
    fun `FALLBACK_FULL_IMAGE yields empty sessionRois`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))

        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = rois,
            registration = makeRegistration(RegistrationStatus.FALLBACK_FULL_IMAGE),
            project = { NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f) },
        )

        assertTrue(result.isEmpty(), "FALLBACK_FULL_IMAGE must not produce session ROIs")
    }

    @Test
    fun `FALLBACK_FULL_IMAGE allows proceed for full-image fallback`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.FALLBACK_FULL_IMAGE)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) {
            NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)
        }

        assertTrue(CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois))
    }

    // ───────────────────────────────────────────────
    // All-or-nothing: any null projection → empty
    // ───────────────────────────────────────────────

    @Test
    fun `single null projection yields empty sessionRois`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f))

        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = rois,
            registration = makeRegistration(RegistrationStatus.SUCCESS, identityHomography),
            project = { null },
        )

        assertTrue(result.isEmpty(), "Null projection must be discarded, not replaced by template rect")
    }

    @Test
    fun `mixed projections rejects entire batch`() {
        val rois = listOf(
            roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("r2") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )

        var callIndex = 0
        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = rois,
            registration = makeRegistration(RegistrationStatus.SUCCESS, identityHomography),
            project = { if (callIndex++ == 0) null else NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f) },
        )

        assertTrue(
            result.isEmpty(),
            "One null projection must reject the entire batch (all-or-nothing)",
        )
    }

    @Test
    fun `mixed projection null still allows proceed via fallback`() {
        val rois = listOf(
            roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("r2") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)

        var callIndex = 0
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) {
            if (callIndex++ == 0) null else NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f)
        }

        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois),
            "Partial projection failure allows proceed (will use fallback path)",
        )
    }

    @Test
    fun `all projections null yields empty sessionRois`() {
        val rois = listOf(
            roiDef("r1") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("r2") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )

        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = rois,
            registration = makeRegistration(RegistrationStatus.SUCCESS, identityHomography),
            project = { null },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty validRois yields empty sessionRois on SUCCESS`() {
        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = emptyList(),
            registration = makeRegistration(RegistrationStatus.SUCCESS, identityHomography),
            project = { NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f) },
        )

        assertTrue(result.isEmpty())
    }

    // ───────────────────────────────────────────────
    // All projections succeed → allow proceed
    // ───────────────────────────────────────────────

    @Test
    fun `all projections succeed allows proceed`() {
        val rois = listOf(
            roiDef("id_a", "区域A") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("id_b", "区域B") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val projected = NormalizedRect(0.15f, 0.15f, 0.35f, 0.35f)
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)

        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        assertEquals(2, sessionRois.size)
        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois),
            "All projections succeed → must allow proceed",
        )
    }

    @Test
    fun `successful projection preserves roi id and name`() {
        val rois = listOf(
            roiDef("id_a", "区域A") to NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f),
            roiDef("id_b", "区域B") to NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f),
        )
        val projected = NormalizedRect(0.15f, 0.15f, 0.35f, 0.35f)

        val result = CaptureComparisonViewModel.buildSessionRois(
            validRois = rois,
            registration = makeRegistration(RegistrationStatus.SUCCESS, identityHomography),
            project = { projected },
        )

        assertEquals(2, result.size)
        assertEquals("id_a", result[0].id)
        assertEquals("区域A", result[0].name)
        assertEquals("id_b", result[1].id)
        assertEquals("区域B", result[1].name)
        assertEquals(projected, result[0].rect)
        assertEquals(projected, result[1].rect)
    }

    // ───────────────────────────────────────────────
    // Repeated input → stable
    // ───────────────────────────────────────────────

    @Test
    fun `repeated calls with same input produce identical output`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val projected = NormalizedRect(0.15f, 0.25f, 0.45f, 0.55f)

        val first = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }
        val second = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        assertEquals(first, second, "Repeated calls must produce identical sessionRois")
    }

    // ───────────────────────────────────────────────
    // canProceedToConfirmation — additional coverage
    // ───────────────────────────────────────────────

    @Test
    fun `single null projection allows proceed via fallback`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { null }

        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois),
            "Single null projection allows proceed (will use fallback path)",
        )
    }

    @Test
    fun `empty validRois allows proceed via fallback`() {
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(
            validRois = emptyList(),
            registration = reg,
            project = { NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f) },
        )

        assertTrue(
            CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois),
            "Empty validRois allows proceed (will use fallback path)",
        )
    }

    @Test
    fun `repeated canProceedToConfirmation calls are stable`() {
        val rois = listOf(roiDef("r1") to NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f))
        val reg = makeRegistration(RegistrationStatus.SUCCESS, identityHomography)
        val projected = NormalizedRect(0.15f, 0.25f, 0.45f, 0.55f)
        val sessionRois = CaptureComparisonViewModel.buildSessionRois(rois, reg) { projected }

        val first = CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois)
        val second = CaptureComparisonViewModel.canProceedToConfirmation(reg, sessionRois)

        assertEquals(first, second, "Repeated canProceedToConfirmation must be stable")
        assertTrue(first, "All projections succeed → must allow proceed")
    }

    // ───────────────────────────────────────────────
    // null registration → blocks proceed
    // ───────────────────────────────────────────────

    @Test
    fun `null registration blocks proceed`() {
        assertFalse(
            CaptureComparisonViewModel.canProceedToConfirmation(null, emptyList()),
            "Null registration (not attempted) must block proceed",
        )
    }

    // ───────────────────────────────────────────────
    // All status values allow proceed (registration != null)
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
}
