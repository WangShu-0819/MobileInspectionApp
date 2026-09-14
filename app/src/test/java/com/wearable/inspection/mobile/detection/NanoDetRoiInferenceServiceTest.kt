package com.wearable.inspection.mobile.detection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NanoDetRoiInferenceServiceTest {
    @Test
    fun `unset feature malformed ROI and unreadable image return explicit non-success states`() {
        val service = NanoDetRoiInferenceService(ApplicationProvider.getApplicationContext<Application>())
        try {
            val results = service.inferSavedPhoto(
                photoPath = "${ApplicationProvider.getApplicationContext<Application>().cacheDir}/missing.jpg",
                rois = listOf(
                    roi("unset", null),
                    roi("feature", "FEATURE"),
                    roi("malformed", "NUT", "not-json"),
                    roi("missing-photo", "THREAD")
                ),
                templateExifOrientation = 1
            )
            assertEquals(NanoDetInferenceStatus.ROI_NOT_CONFIGURED, results.getValue("unset").status)
            assertEquals(NanoDetInferenceStatus.FEATURE_UNSUPPORTED, results.getValue("feature").status)
            assertEquals(NanoDetInferenceStatus.INVALID_ROI, results.getValue("malformed").status)
            assertEquals(NanoDetInferenceStatus.IMAGE_UNREADABLE, results.getValue("missing-photo").status)
            results.values.forEach {
                assertNull(it.modelSuggestion)
                assertNull(it.matchingScore)
                assertTrue(it.detections.isEmpty())
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `missing template geometry prevents a success suggestion`() {
        val service = NanoDetRoiInferenceService(ApplicationProvider.getApplicationContext<Application>())
        try {
            val result = service.inferSavedPhoto(
                photoPath = "missing.jpg",
                rois = listOf(roi("roi", "NUT")),
                templateExifOrientation = null
            ).getValue("roi")
            assertEquals(NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE, result.status)
            assertNull(result.modelSuggestion)
            assertNull(result.matchingScore)
        } finally {
            service.close()
        }
    }

    private fun roi(id: String, targetType: String?, rect: String = """{"left":0,"top":0,"right":1,"bottom":1}""") =
        RoiDefinitionEntity(
            id = id,
            templateId = "template",
            name = id,
            order = 0,
            normalizedRect = rect,
            inspectionType = "PRESENCE",
            targetType = targetType
        )
}
