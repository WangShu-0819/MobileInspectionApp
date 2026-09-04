package com.wearable.inspection.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 照片落库和确认关联的源码契约测试。
 * 真实 Room 写入由现有数据库测试覆盖；这里锁定拍照流程不能丢弃自增 ID 或混用 View。
 */
class CapturedPhotoPersistenceContractTest {

    private fun read(path: String): String = File(path).readText()

    @Test
    fun `captured photo dao returns generated id and supports readback`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/dao/CapturedPhotoDao.kt")
        assertTrue(source.contains("suspend fun insert(photo: CapturedPhotoEntity): Long"))
        assertTrue(source.contains("suspend fun getById(photoId: Long): CapturedPhotoEntity?"))
    }

    @Test
    fun `repository exposes generated photo id and readback`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
        assertTrue(source.contains("suspend fun insertCapturedPhoto(photo: CapturedPhotoEntity): Long"))
        assertTrue(source.contains("suspend fun getCapturedPhoto(photoId: Long): CapturedPhotoEntity?"))
        assertTrue(source.contains("return capturedPhotoDao.insert(photo)"))
    }

    @Test
    fun `confirmation validates photo batch view and template before loading rois`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt")
        assertTrue(source.contains("photo.batchId == batchId"))
        assertTrue(source.contains("photo.viewIndex == viewIndex"))
        assertTrue(source.contains("photo.templateId == templateId"))
        assertTrue(source.indexOf("photoMatchesView") < source.indexOf("repository.getRois(templateId)"))
    }

    @Test
    fun `confirmation never invents roi result and rejects empty roi confirmation`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt")
        assertTrue(source.contains("if (rois.isEmpty())"))
        assertTrue(source.contains("humanResult = roiResults.getValue(roi.id)"))
        assertFalse(source.contains("humanResult = roiResults[roi.id] ?: \"OK\""))
    }

    @Test
    fun `live inspection does not create confirmation rows for no roi path`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val noRoi = source.indexOf("if (rois.isEmpty())")
        val confirm = source.indexOf("onNavigateToConfirm(")
        assertTrue(noRoi > 0)
        assertTrue(confirm > noRoi)
        assertFalse(source.substring(noRoi, confirm).contains("insertViewRoiConfirms"))
    }
}
