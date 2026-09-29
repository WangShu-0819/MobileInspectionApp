package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.vision.OpenCvTestSupport
import com.wearable.inspection.mobile.registration.RegistrationConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoiSimilarityFallbackTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = OpenCvTestSupport.loadNative()
    }

    @Test
    fun `all widget target types share the uniform 0_75 candidate threshold`() {
        listOf(RoiTargetType.THREAD, RoiTargetType.NUT, RoiTargetType.BOLT, RoiTargetType.NUTSERT).forEach { type ->
            assertEquals(0.75f, RoiSimilarityThresholdPolicy.threshold("Black_Part_001", type)!!, 0.0001f)
            assertEquals(0.75f, RoiSimilarityThresholdPolicy.threshold("White_Part_001", type)!!, 0.0001f)
        }
        assertNull(RoiSimilarityThresholdPolicy.threshold("Black_Part_001", RoiTargetType.FEATURE))
        assertNull(RoiSimilarityThresholdPolicy.threshold("legacy_part", null))
        assertEquals("Lowe registration ratio remains an independent parameter", 0.75f, RegistrationConfig.LOWE_RATIO, 0.0001f)
        assertEquals(0.75f, RoiSimilarityThresholdPolicy.WIDGET_SIMILARITY_CANDIDATE_THRESHOLD, 0.0001f)
    }

    @Test
    fun `fallback entry includes every widget NG and unsupported target without rewriting NanoDet`() {
        listOf(RoiTargetType.THREAD, RoiTargetType.NUT, RoiTargetType.BOLT, RoiTargetType.NUTSERT).forEach { type ->
            val roiResult = NanoDetRoiInferenceResult(
                roiId = "roi-${type.name}",
                status = NanoDetInferenceStatus.NO_DETECTION,
                modelSuggestion = NanoDetSuggestion.NG,
                matchingScore = null,
                targetClassIndex = null,
            )
            assertTrue("NG $type should enter fallback", RoiSimilarityFallbackPolicy.shouldRun(roiResult, type))
            val unsupported = roiResult.copy(
                status = NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED,
                modelSuggestion = null,
            )
            assertTrue("unsupported $type should enter independent fallback", RoiSimilarityFallbackPolicy.shouldRun(unsupported, type))
            assertEquals(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, unsupported.status)
            assertNull(unsupported.modelSuggestion)
        }
        val modelUnavailable = NanoDetRoiInferenceResult(
            roiId = "white-bolt",
            status = NanoDetInferenceStatus.MODEL_UNAVAILABLE,
            modelSuggestion = null,
            matchingScore = null,
            targetClassIndex = null,
        )
        assertTrue(!RoiSimilarityFallbackPolicy.shouldRun(modelUnavailable, RoiTargetType.BOLT))
        assertTrue(!RoiSimilarityFallbackPolicy.shouldRun(modelUnavailable, RoiTargetType.FEATURE))
    }

    @Test
    fun `missing ROI evidence blocks comparison without a score or candidate`() {
        val unavailableImage = RoiSimilarityResult(
            status = RoiSimilarityStatus.ROI_IMAGE_UNAVAILABLE,
            detail = "ROI image unavailable",
        )
        val failedSave = RoiSimilarityResult(
            status = RoiSimilarityStatus.ROI_EVIDENCE_SAVE_FAILED,
            detail = "ROI evidence save failed",
        )
        assertTrue(!unavailableImage.wasRun)
        assertTrue(!failedSave.wasRun)
        assertNull(unavailableImage.score)
        assertNull(unavailableImage.candidate)
        assertNull(failedSave.score)
        assertNull(failedSave.candidate)
    }

    @Test
    fun `registered identical ROI returns the uniform threshold and OK candidate`() {
        val directory = Files.createTempDirectory("roi-similarity").toFile()
        val source = texturedImage()
        val templatePath = directory.resolve("template.jpg").absolutePath
        val photoPath = directory.resolve("photo.jpg").absolutePath
        try {
            assertTrue(Imgcodecs.imwrite(templatePath, source))
            assertTrue(Imgcodecs.imwrite(photoPath, source))
            val result = RoiSimilarityFallback().evaluate("Black_Thread_001", templatePath, photoPath, threadRoi())
            assertEquals(RoiSimilarityStatus.SCORED_WITH_CANDIDATE_THRESHOLD, result.status)
            assertNotNull(result.score)
            assertEquals(0.75f, result.threshold!!, 0.0001f)
            assertEquals(NanoDetSuggestion.OK, result.candidate)
        } finally {
            source.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `registration failure has no score or candidate`() {
        val directory = Files.createTempDirectory("roi-similarity-fail").toFile()
        val flat = Mat(320, 320, CvType.CV_8UC3, Scalar(160.0, 160.0, 160.0))
        val templatePath = directory.resolve("template.jpg").absolutePath
        val photoPath = directory.resolve("photo.jpg").absolutePath
        try {
            assertTrue(Imgcodecs.imwrite(templatePath, flat))
            assertTrue(Imgcodecs.imwrite(photoPath, flat))
            val result = RoiSimilarityFallback().evaluate("Black_Thread_001", templatePath, photoPath, threadRoi())
            assertEquals(RoiSimilarityStatus.REGISTRATION_FAILED, result.status)
            assertNull(result.score)
            assertNull(result.candidate)
        } finally {
            flat.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `other widget target types also classify at 0_75 while FEATURE has no candidate`() {
        val directory = Files.createTempDirectory("roi-similarity-no-threshold").toFile()
        val source = texturedImage()
        val templatePath = directory.resolve("template.jpg").absolutePath
        val photoPath = directory.resolve("photo.jpg").absolutePath
        try {
            assertTrue(Imgcodecs.imwrite(templatePath, source))
            assertTrue(Imgcodecs.imwrite(photoPath, source))
            val result = RoiSimilarityFallback().evaluate("Black_Nut_001", templatePath, photoPath, threadRoi(RoiTargetType.NUT))
            assertEquals(RoiSimilarityStatus.SCORED_WITH_CANDIDATE_THRESHOLD, result.status)
            assertNotNull(result.score)
            assertEquals(0.75f, result.threshold!!, 0.0001f)
            assertEquals(NanoDetSuggestion.OK, result.candidate)
            val feature = RoiSimilarityFallback().evaluate("Black_Part_001", templatePath, photoPath, threadRoi(RoiTargetType.FEATURE))
            assertEquals(RoiSimilarityStatus.SCORED_NO_THRESHOLD, feature.status)
            assertNull(feature.threshold)
            assertNull(feature.candidate)
        } finally {
            source.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `ROI evidence is saved before comparison and saved evidence survives compare error`() = runTest {
        val events = mutableListOf<String>()
        val execution = runRoiSimilarityAfterSavingEvidence(
            bitmapAvailable = true,
            saveEvidence = { events += "save"; "/roi/sim.jpg" },
            compare = { path -> events += "compare:$path"; RoiSimilarityResult(RoiSimilarityStatus.COMPARISON_ERROR, detail = "test") },
        )

        assertEquals(listOf("save", "compare:/roi/sim.jpg"), events)
        assertEquals("/roi/sim.jpg", execution.evidencePath)
        assertEquals("SAVED", execution.evidenceStatus)
        assertEquals(RoiSimilarityStatus.COMPARISON_ERROR, execution.result.status)
        assertTrue(execution.result.wasRun)
        assertNull(execution.result.candidate)
    }

    @Test
    fun `unavailable or unsaved ROI image skips comparison with explicit states`() = runTest {
        var compareCalls = 0
        val unavailable = runRoiSimilarityAfterSavingEvidence(
            bitmapAvailable = false,
            saveEvidence = { error("must not save without a bitmap") },
            compare = { compareCalls++; error("must not compare") },
        )
        assertEquals(RoiSimilarityStatus.ROI_IMAGE_UNAVAILABLE, unavailable.result.status)
        assertEquals("ROI_BITMAP_UNAVAILABLE", unavailable.evidenceStatus)
        assertNull(unavailable.result.score)
        assertNull(unavailable.result.candidate)

        val saveFailed = runRoiSimilarityAfterSavingEvidence(
            bitmapAvailable = true,
            saveEvidence = { null },
            compare = { compareCalls++; error("must not compare") },
        )
        assertEquals(RoiSimilarityStatus.ROI_EVIDENCE_SAVE_FAILED, saveFailed.result.status)
        assertEquals("SAVE_FAILED_NOT_RUN", saveFailed.evidenceStatus)
        assertNull(saveFailed.evidencePath)
        assertNull(saveFailed.result.score)
        assertNull(saveFailed.result.candidate)
        assertEquals(0, compareCalls)
    }

    private fun threadRoi(type: RoiTargetType = RoiTargetType.THREAD) = RoiDefinitionEntity(
        id = "roi-1",
        templateId = "template-1",
        name = "widget",
        order = 0,
        normalizedRect = """{"left":0.1,"top":0.1,"right":0.9,"bottom":0.9}""",
        inspectionType = "VISUAL",
        targetType = type.name,
    )

    private fun texturedImage(): Mat {
        val image = Mat(320, 320, CvType.CV_8UC3, Scalar(170.0, 170.0, 170.0))
        val random = Random(441)
        repeat(100) {
            val x = random.nextInt(320)
            val y = random.nextInt(320)
            Imgproc.circle(image, Point(x.toDouble(), y.toDouble()), random.nextInt(7) + 2,
                Scalar(random.nextInt(220).toDouble(), random.nextInt(220).toDouble(), random.nextInt(220).toDouble()), -1)
        }
        repeat(24) {
            Imgproc.line(image, Point(random.nextInt(320).toDouble(), random.nextInt(320).toDouble()),
                Point(random.nextInt(320).toDouble(), random.nextInt(320).toDouble()), Scalar(20.0, 40.0, 210.0), 2)
        }
        return image
    }
}
