package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.ui.screens.ContentRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoDetInferenceContractTest {
    @Test
    fun `target types map only to their trained classes`() {
        assertEquals(0, NanoDetDecisionPolicy.classIndex(RoiTargetType.NUT))
        assertEquals(1, NanoDetDecisionPolicy.classIndex(RoiTargetType.THREAD))
        assertNull(NanoDetDecisionPolicy.classIndex(RoiTargetType.FEATURE))
        assertNull(NanoDetDecisionPolicy.classIndex(null))
    }

    @Test
    fun `feature and unset target never become model suggestions`() {
        val feature = NanoDetDecisionPolicy.decide(RoiTargetType.FEATURE, emptyList())
        val unset = NanoDetDecisionPolicy.decide(null, emptyList())
        assertEquals(NanoDetInferenceStatus.FEATURE_UNSUPPORTED, feature.status)
        assertEquals(NanoDetInferenceStatus.ROI_NOT_CONFIGURED, unset.status)
        assertNull(feature.suggestion)
        assertNull(unset.suggestion)
    }

    @Test
    fun `score exactly at business threshold suggests OK`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.NUT,
            listOf(candidate(classIndex = 0, score = 0.37f)),
            threshold = 0.37f
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(NanoDetSuggestion.OK, decision.suggestion)
        assertEquals(0.37f, decision.matchingScore)
    }

    @Test
    fun `matching candidate below threshold suggests NG and preserves its score`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.THREAD,
            listOf(candidate(classIndex = 1, score = 0.369f)),
            threshold = 0.37f
        )
        assertEquals(NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
        assertEquals(0.369f, decision.matchingScore)
    }

    @Test
    fun `no matching box suggests NG with no fabricated score`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.NUT,
            listOf(candidate(classIndex = 1, score = 0.91f))
        )
        assertEquals(NanoDetInferenceStatus.NO_DETECTION, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
        assertNull(decision.matchingScore)
    }

    @Test
    fun `letterbox transform matches verified 720 by 1280 preprocessing`() {
        val transform = NanoDetImagePreprocessor.letterboxTransform(720, 1280)
        assertEquals(234, transform.resizedWidth)
        assertEquals(416, transform.resizedHeight)
        assertEquals(234.0 / 720, transform.scaleX, 0.0)
        assertEquals(416.0 / 1280, transform.scaleY, 0.0)
    }

    @Test
    fun `BGR pack uses per channel mean and std and zeros letterbox padding`() {
        val transform = NanoDetInputTransform(720, 1280, 234, 416, 234.0 / 720, 416.0 / 1280)
        val size = NanoDetModelContract.INPUT_SIZE
        val planeSize = size * size
        val bgr = ByteArray(planeSize * 3)
        bgr[0] = 10
        bgr[1] = 20
        bgr[2] = 30
        val contentLast = (233 * 3)
        bgr[contentLast] = 255.toByte()
        val tensor = NanoDetImagePreprocessor.packBgrToNchw(bgr, transform)

        assertEquals((10 - 103.53) / 57.375, tensor[0].toDouble(), 1e-6)
        assertEquals((20 - 116.28) / 57.12, tensor[planeSize].toDouble(), 1e-6)
        assertEquals((30 - 123.675) / 58.395, tensor[2 * planeSize].toDouble(), 1e-6)
        assertEquals((255 - 103.53) / 57.375, tensor[233].toDouble(), 1e-6)
        assertEquals(0f, tensor[234]) // right padding remains zero after normalization
        val verticalPadding = NanoDetInputTransform(720, 720, 416, 234, 416.0 / 720, 234.0 / 720)
        val verticalTensor = NanoDetImagePreprocessor.packBgrToNchw(bgr, verticalPadding)
        assertEquals(0f, verticalTensor[234 * size]) // rows below resized content remain zero
    }

    @Test
    fun `candidate at low filter boundary is retained`() {
        val output = FloatArray(NanoDetModelContract.OUTPUT_WIDTH * NanoDetModelContract.OUTPUT_HEIGHT)
        output[0] = NanoDetModelContract.CANDIDATE_THRESHOLD
        val transform = NanoDetImagePreprocessor.letterboxTransform(416, 416)

        val detections = NanoDetOutputDecoder.decode(output, transform)
        assertEquals(1, detections.size)
        assertEquals(0, detections.single().classIndex)
        assertEquals(NanoDetModelContract.CANDIDATE_THRESHOLD, detections.single().score)
    }

    @Test
    fun `all zero model output has no candidates and malformed output fails explicitly`() {
        val output = FloatArray(NanoDetModelContract.OUTPUT_WIDTH * NanoDetModelContract.OUTPUT_HEIGHT)
        val transform = NanoDetImagePreprocessor.letterboxTransform(720, 1280)
        assertTrue(NanoDetOutputDecoder.decode(output, transform).isEmpty())
        assertFalse(output.any { !it.isFinite() })
        try {
            NanoDetOutputDecoder.decode(floatArrayOf(0f), transform)
            throw AssertionError("expected invalid output to throw")
        } catch (_: IllegalArgumentException) {
            // The service converts this output-contract failure to INFERENCE_ERROR.
        }
    }

    @Test
    fun `ROI box is clipped then offset to full photo coordinates`() {
        val mapped = NanoDetCoordinateMapper.mapRoiBoxToPhoto(
            NanoDetBox(-5.0, 2.0, 150.0, 200.0),
            ContentRectBounds(100, 200, 200, 300),
            imageWidth = 400,
            imageHeight = 500
        )!!
        assertEquals(NanoDetBox(0.0, 2.0, 100.0, 100.0), mapped.roiBox)
        assertEquals(NanoDetBox(100.0, 202.0, 200.0, 300.0), mapped.imageBox)
        assertNull(
            NanoDetCoordinateMapper.mapRoiBoxToPhoto(
                NanoDetBox(-4.0, 0.0, -1.0, 4.0),
                ContentRectBounds(0, 0, 20, 20),
                imageWidth = 20,
                imageHeight = 20
            )
        )
    }

    private fun candidate(classIndex: Int, score: Float) = NanoDetCandidate(
        classIndex = classIndex,
        className = if (classIndex == 0) "nut" else "thread",
        score = score,
        box = NanoDetBox(0.0, 0.0, 20.0, 20.0),
        point = 0
    )
}
