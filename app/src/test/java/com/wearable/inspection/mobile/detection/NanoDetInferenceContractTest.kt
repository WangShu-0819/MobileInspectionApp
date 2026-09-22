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
        assertEquals(2, NanoDetDecisionPolicy.classIndex(RoiTargetType.BOLT))
        assertEquals(3, NanoDetDecisionPolicy.classIndex(RoiTargetType.NUTSERT))
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
            listOf(candidate(classIndex = 0, score = 0.50f)),
            threshold = 0.50f
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(NanoDetSuggestion.OK, decision.suggestion)
        assertEquals(0.50f, decision.matchingScore)
    }

    @Test
    fun `matching candidate below threshold suggests NG and preserves its score`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.THREAD,
            listOf(candidate(classIndex = 1, score = 0.49f)),
            threshold = 0.50f
        )
        assertEquals(NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
        assertEquals(0.49f, decision.matchingScore)
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
    fun `bolt routing selects class 2 and decides correctly`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.BOLT,
            listOf(
                candidate(classIndex = 0, score = 0.95f),
                candidate(classIndex = 2, score = 0.80f)
            ),
            threshold = 0.50f
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(NanoDetSuggestion.OK, decision.suggestion)
        assertEquals(0.80f, decision.matchingScore)
        assertEquals(2, decision.targetClassIndex)
    }

    @Test
    fun `nutsert routing selects class 3 and decides correctly`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.NUTSERT,
            listOf(
                candidate(classIndex = 0, score = 0.95f),
                candidate(classIndex = 3, score = 0.30f)
            ),
            threshold = 0.50f
        )
        assertEquals(NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
        assertEquals(0.30f, decision.matchingScore)
        assertEquals(3, decision.targetClassIndex)
    }

    @Test
    fun `bolt with no bolt candidate returns NO_DETECTION`() {
        val decision = NanoDetDecisionPolicy.decide(
            RoiTargetType.BOLT,
            listOf(candidate(classIndex = 0, score = 0.95f))
        )
        assertEquals(NanoDetInferenceStatus.NO_DETECTION, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
        assertEquals(2, decision.targetClassIndex)
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
    fun `output width is 36 for 4-class protocol`() {
        assertEquals(36, NanoDetModelContract.OUTPUT_WIDTH)
        assertEquals(3598, NanoDetModelContract.OUTPUT_HEIGHT)
    }

    @Test
    fun `34-column output is rejected by decoder`() {
        val wrongSize = FloatArray(3598 * 34)
        val transform = NanoDetImagePreprocessor.letterboxTransform(416, 416)
        try {
            NanoDetOutputDecoder.decode(wrongSize, transform)
            throw AssertionError("expected 34-column output to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: decoder rejects old 34-column protocol
        }
    }

    @Test
    fun `4-class argmax selects correct class`() {
        val width = NanoDetModelContract.OUTPUT_WIDTH
        val output = FloatArray(width * NanoDetModelContract.OUTPUT_HEIGHT)
        // Row 0: class 3 (nutsert) has highest score
        output[0 * width + 3] = 0.8f
        output[0 * width + 0] = 0.1f
        output[0 * width + 1] = 0.1f
        output[0 * width + 2] = 0.1f
        // Row 1: class 2 (bolt) has highest score
        output[1 * width + 2] = 0.7f
        output[1 * width + 0] = 0.1f
        output[1 * width + 1] = 0.1f
        output[1 * width + 3] = 0.1f
        val transform = NanoDetImagePreprocessor.letterboxTransform(416, 416)
        val detections = NanoDetOutputDecoder.decode(output, transform, scoreThreshold = 0.01f)

        val nutsert = detections.filter { it.classIndex == 3 }
        val bolt = detections.filter { it.classIndex == 2 }
        assertEquals("nutsert detection count", 1, nutsert.size)
        assertEquals("bolt detection count", 1, bolt.size)
        assertEquals("nutsert score", 0.8f, nutsert.single().score)
        assertEquals("bolt score", 0.7f, bolt.single().score)
        assertEquals("nutsert className", "nutsert", nutsert.single().className)
        assertEquals("bolt className", "bolt", bolt.single().className)
    }

    @Test
    fun `DFL starts at column 4 not column 2 with numeric box verification`() {
        // Place detection at stride-8 grid point (10, 5) — a non-boundary interior point.
        // Decoder uses centerX = x * stride (no half-stride offset).
        // Point index = 5 * 52 + 10 = 270. Row base = 270 * 36 = 9720.
        // Center = (10*8, 5*8) = (80, 40) in input space.
        val width = NanoDetModelContract.OUTPUT_WIDTH
        val output = FloatArray(width * NanoDetModelContract.OUTPUT_HEIGHT)
        val row = (5 * 52 + 10) * width  // 9720

        // Class 1 (thread) wins
        output[row + 1] = 0.9f
        // DFL starts at column 4. Peak at bins [2, 3, 1, 4]:
        //   left  dist = 2*8 = 16 → box.left  = 80 - 16 = 64
        //   top   dist = 3*8 = 24 → box.top   = 40 - 24 = 16
        //   right dist = 1*8 =  8 → box.right = 80 +  8 = 88
        //   bottomdist = 4*8 = 32 → box.bottom= 40 + 32 = 72
        val peakBins = intArrayOf(2, 3, 1, 4)
        for (side in 0 until 4) {
            for (i in 0 until 8) {
                output[row + 4 + side * 8 + i] = if (i == peakBins[side]) 10.0f else 0.0f
            }
        }

        val transform = NanoDetImagePreprocessor.letterboxTransform(416, 416)
        val detections = NanoDetOutputDecoder.decode(output, transform, scoreThreshold = 0.01f)
        assertEquals("exactly one detection", 1, detections.size)
        val det = detections.single()
        assertEquals("thread", det.className)
        // If DFL were offset at row+2 (old 2-col protocol), columns 2..3 would be
        // read as DFL left/top logits, producing entirely wrong box coordinates.
        // With correct offset at row+4, the box matches the expected geometry.
        val tol = 1.0
        assertEquals("box.left",  64.0, det.box.left,   tol)
        assertEquals("box.top",   16.0, det.box.top,    tol)
        assertEquals("box.right", 88.0, det.box.right,  tol)
        assertEquals("box.bottom",72.0, det.box.bottom, tol)
    }

    @Test
    fun `4-class NMS groups detections by class independently`() {
        // NanoDet argmax: one grid point → one class. Place bolt and nutsert
        // at ADJACENT stride-8 points with identical DFL → high IoU overlap.
        // NMS groups by class: both must survive despite the overlap.
        //
        // stride-8 layout: point = y*52+x. (x,y)=(10,5) → 270, (x,y)=(11,5) → 271.
        // center(10,5) = (80,40). center(11,5) = (88,40). DFL peak bin3 → distance=24.
        // box1 = (56, 16, 104, 64). box2 = (64, 16, 112, 64).
        // Overlap horizontally: [64, 104] = 40px. Vertically full: 48px.
        // IoU = 40*48 / (48*48 + 48*48 - 40*48) = 1920/2688 ≈ 0.71 > NMS_THRESHOLD(0.6).
        // Same-class would suppress. Different classes → both survive.
        val width = NanoDetModelContract.OUTPUT_WIDTH
        val output = FloatArray(width * NanoDetModelContract.OUTPUT_HEIGHT)

        // Point (10,5): bolt wins argmax
        val row1 = (5 * 52 + 10) * width  // 270*36
        output[row1 + 2] = 0.8f   // bolt
        output[row1 + 3] = 0.5f   // nutsert
        for (side in 0 until 4) for (i in 0 until 8)
            output[row1 + 4 + side * 8 + i] = if (i == 3) 10.0f else 0.0f

        // Point (11,5): nutsert wins argmax
        val row2 = (5 * 52 + 11) * width  // 271*36
        output[row2 + 2] = 0.3f   // bolt
        output[row2 + 3] = 0.7f   // nutsert
        for (side in 0 until 4) for (i in 0 until 8)
            output[row2 + 4 + side * 8 + i] = if (i == 3) 10.0f else 0.0f

        val transform = NanoDetImagePreprocessor.letterboxTransform(416, 416)
        val detections = NanoDetOutputDecoder.decode(output, transform, scoreThreshold = 0.01f)

        val boltDetections = detections.filter { it.classIndex == 2 }
        val nutsertDetections = detections.filter { it.classIndex == 3 }
        // NMS is per-class: both survive despite IoU ≈ 0.71 > 0.6 threshold.
        assertEquals("bolt count", 1, boltDetections.size)
        assertEquals("nutsert count", 1, nutsertDetections.size)
        assertEquals("bolt score", 0.8f, boltDetections.single().score)
        assertEquals("nutsert score", 0.7f, nutsertDetections.single().score)
        assertEquals("bolt className", "bolt", boltDetections.single().className)
        assertEquals("nutsert className", "nutsert", nutsertDetections.single().className)
    }

    @Test
    fun `empty output for new classes produces no bolt or nutsert detections`() {
        val output = FloatArray(NanoDetModelContract.OUTPUT_WIDTH * NanoDetModelContract.OUTPUT_HEIGHT)
        val transform = NanoDetImagePreprocessor.letterboxTransform(720, 1280)
        val detections = NanoDetOutputDecoder.decode(output, transform)
        assertTrue("no bolt detections", detections.none { it.classIndex == 2 })
        assertTrue("no nutsert detections", detections.none { it.classIndex == 3 })
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

    private val classNames = arrayOf("nut", "thread", "bolt", "nutsert")

    private fun candidate(classIndex: Int, score: Float) = NanoDetCandidate(
        classIndex = classIndex,
        className = classNames[classIndex],
        score = score,
        box = NanoDetBox(0.0, 0.0, 20.0, 20.0),
        point = 0
    )
}
