package com.wearable.inspection.mobile.ui.screens

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.image.MobileImageStore
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceService
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * ViewConfirmationViewModel 保存生命周期测试。
 *
 * 使用 MockitoSuspendStubber（Java）解决 Kotlin null-safety 对 Mockito matcher
 * 返回 null 的 IncompatibleClassChangeError / NPE 问题。
 * 所有 suspend fun 的 stub/verify 和涉及 Mockito.any() 的非空参数 stub 均通过 Java 调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ViewModelSaveLifecycleTest {

    private val batchId = "b-lifecycle"
    private val photoId = 100L
    private val viewIndex = 0
    private val templateId = "tpl-1"
    private val templateName = "View1"
    private val partId = "part-1"
    private val totalViews = 1

    private val threadRoi = RoiDefinitionEntity(
        id = "roi-thread", templateId = "tpl-1", name = "螺纹",
        order = 0, normalizedRect = """{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8}""",
        inspectionType = "NONE", targetType = "THREAD"
    )

    private val featureRoi = RoiDefinitionEntity(
        id = "roi-feature", templateId = "tpl-1", name = "部件",
        order = 1, normalizedRect = """{"left":0.1,"top":0.1,"right":0.5,"bottom":0.5}""",
        inspectionType = "NONE", targetType = "FEATURE"
    )

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: InspectionRepository
    private lateinit var imageStore: MobileImageStore
    private lateinit var inferenceService: NanoDetRoiInferenceService

    /** 每个测试在自己的临时目录中创建的真实照片路径。 */
    private lateinit var photoPath: String

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repo = Mockito.mock(InspectionRepository::class.java)
        imageStore = Mockito.mock(MobileImageStore::class.java)
        inferenceService = Mockito.mock(NanoDetRoiInferenceService::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 在临时目录中创建一个有效的 JPEG 文件供 BitmapFactory 解码，
     * 使 ViewModel 初始化时 RoiCoordinateMapper.getImageGeometry() 返回非 null。
     */
    private fun createTestPhoto(dir: File): String {
        val photoFile = File(dir, "test_photo.jpg")
        // 最小合法 JPEG: SOI + APP0 + SOF0 (1×1 白色像素) + EOI
        photoFile.writeBytes(byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                         // SOI
            0xFF.toByte(), 0xE0.toByte(),                         // APP0
            0x00.toByte(), 0x10.toByte(),                         // length = 16
            0x4A, 0x46, 0x49, 0x46, 0x00,                        // "JFIF\0"
            0x01, 0x01,                                           // version 1.1
            0x00,                                                 // aspect ratio units = none
            0x00.toByte(), 0x01.toByte(),                         // X density = 1
            0x00.toByte(), 0x01.toByte(),                         // Y density = 1
            0x00, 0x00,                                           // no thumbnail
            0xFF.toByte(), 0xC0.toByte(),                         // SOF0
            0x00.toByte(), 0x0B.toByte(),                         // length = 11
            0x08,                                                 // precision = 8
            0x00.toByte(), 0x01.toByte(),                         // height = 1
            0x00.toByte(), 0x01.toByte(),                         // width = 1
            0x01,                                                 // 1 component (grayscale)
            0x01, 0x11, 0x00,                                     // component spec
            0xFF.toByte(), 0xC4.toByte(),                         // DHT
            0x00.toByte(), 0x1F.toByte(),                         // length = 31
            0x00,                                                 // DC table 0
            0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01,     // counts
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,     // symbols
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B,
            0xFF.toByte(), 0xDA.toByte(),                         // SOS
            0x00.toByte(), 0x08.toByte(),                         // length = 8
            0x01,                                                 // 1 component
            0x01, 0x00,                                           // component + DC/AC table
            0x00, 0x3F, 0x00,                                     // spectral selection
            0x79.toByte(), 0x18.toByte(), 0xE3.toByte(), 0x00.toByte(), // compressed data (1×1 px)
            0xFF.toByte(), 0xD9.toByte()                          // EOI
        ))
        return photoFile.absolutePath
    }

    private fun setVmField(vm: ViewConfirmationViewModel, name: String, value: Any?) {
        var clazz: Class<*>? = vm.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.set(vm, value)
                return
            } catch (_: NoSuchFieldException) {
                // Kotlin delegated properties (mutableStateOf) compile to "$delegate" fields.
                // Try matching by prefix — e.g. "overallResult" → "overallResult$delegate".
                val delegateField = clazz.declaredFields.firstOrNull { it.name.startsWith("$name\$") }
                if (delegateField != null) {
                    delegateField.isAccessible = true
                    val delegate = delegateField.get(vm)
                    // Compose MutableState has setValue — use it to trigger snapshot updates.
                    if (delegate is androidx.compose.runtime.MutableState<*>) {
                        @Suppress("UNCHECKED_CAST")
                        (delegate as androidx.compose.runtime.MutableState<Any?>).value = value
                        return
                    }
                    // Fallback: direct field write (non-Compose delegates).
                    delegateField.set(vm, value)
                    return
                }
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("$name not found in hierarchy")
    }

    private fun createVm(): ViewConfirmationViewModel {
        return ViewConfirmationViewModel(
            repository = repo, batchId = batchId, photoId = photoId, photoPath = photoPath,
            viewIndex = viewIndex, templateId = templateId, templateName = templateName,
            partId = partId, totalViews = totalViews, inferenceService = inferenceService,
            imageStore = imageStore
        )
    }

    private fun injectSavedOverrideEvidence(vm: ViewConfirmationViewModel, roiId: String, path: String, time: Long) {
        val evidenceRefClass = Class.forName(
            "com.wearable.inspection.mobile.ui.screens.ViewConfirmationViewModel\$EvidenceRef"
        )
        val evidenceRef = evidenceRefClass.constructors.first().newInstance(path, time)
        val savedEvidenceField = Class.forName(
            "com.wearable.inspection.mobile.ui.screens.ViewConfirmationViewModel"
        ).declaredFields.first { it.name == "savedOverrideEvidence" }
        savedEvidenceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val evidenceMap = savedEvidenceField.get(vm) as MutableMap<String, Any?>
        evidenceMap[roiId] = evidenceRef
    }

    private suspend fun setupRepo(
        savedConfirmsRef: MutableList<List<ViewRoiConfirmEntity>>,
        replaceAnswer: org.mockito.stubbing.Answer<*>? = null
    ) {
        `when`(repo.getCapturedPhoto(photoId)).thenReturn(
            CapturedPhotoEntity(photoId, batchId, photoPath, viewIndex, templateId, templateName, photoId)
        )
        `when`(repo.getRois(templateId)).thenReturn(listOf(threadRoi, featureRoi))
        `when`(repo.getTemplate(templateId)).thenReturn(null)

        // stub replaceViewRoiConfirmsForPhoto via Java helper (suspend, final class)
        if (replaceAnswer != null) {
            MockitoSuspendStubber.stubReplace(repo, replaceAnswer)
        } else {
            MockitoSuspendStubber.stubReplace(repo, org.mockito.stubbing.Answer { inv ->
                @Suppress("UNCHECKED_CAST")
                savedConfirmsRef[0] = inv.arguments[2] as List<ViewRoiConfirmEntity>
                null
            })
        }

        // stub getViewRoiConfirmsByPhoto via Java helper (suspend, final class)
        MockitoSuspendStubber.stubGetConfirms(repo, org.mockito.stubbing.Answer {
            savedConfirmsRef[0].toList()
        })
    }

    // ===== 1. 重复确认保留原值 =====

    @Test
    fun duplicateOverridePreservesOriginalOverrideTime() = runTest {
        val dir = Files.createTempDirectory("vm-lifecycle").toFile()
        try {
            photoPath = createTestPhoto(dir)
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val oldEvidencePath = File(evidenceDir, "old_override.jpg").apply { writeText("OLD_DATA") }.absolutePath
            val originalTime = 1_700_000_000_000L

            `when`(imageStore.roiEvidenceFileValid(oldEvidencePath)).thenReturn(true)

            val savedConfirmsRef = mutableListOf<List<ViewRoiConfirmEntity>>(emptyList())
            setupRepo(savedConfirmsRef)

            val vm = createVm()
            advanceUntilIdle()

            setVmField(vm, "roiResults", mutableStateMapOf<String, String>().also {
                it["roi-thread"] = "NG"; it["roi-feature"] = "OK"
            })
            setVmField(vm, "overallResult", "OK")
            setVmField(vm, "isLoaded", true)
            setVmField(vm, "photoAssociationValid", true)
            injectSavedOverrideEvidence(vm, "roi-thread", oldEvidencePath, originalTime)

            // 需要推理结果才能让 ViewModel 判定 humanChangedModel=true 并保留 overrideTime
            @Suppress("UNCHECKED_CAST")
            val infResults1 = vm.javaClass.getDeclaredField("inferenceResults").let {
                it.isAccessible = true
                it.get(vm) as MutableMap<String, NanoDetRoiInferenceResult>
            }
            infResults1["roi-thread"] = NanoDetRoiInferenceResult(
                roiId = "roi-thread", status = NanoDetInferenceStatus.DETECTED,
                modelSuggestion = NanoDetSuggestion.OK, matchingScore = 0.91f,
                targetClassIndex = 1, threshold = 0.37f
            )

            vm.saveConfirmation()
            advanceUntilIdle()

            assertTrue("saveCompleted 应为 true", vm.saveCompleted)
            assertNull("errorMessage 应为 null", vm.errorMessage)
            val threadConfirm = savedConfirmsRef[0].first { it.roiId == "roi-thread" }
            assertEquals("overrideTime 应保留原值", originalTime, threadConfirm.overrideTime)
            assertEquals("roiEvidencePath 应保留原值", oldEvidencePath, threadConfirm.roiEvidencePath)
            assertTrue("旧证据文件应仍存在", File(oldEvidencePath).exists())
            Mockito.verify(imageStore, Mockito.never()).deleteRoiEvidence(Mockito.anyString())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ===== 2. 恢复模型结果后成功保存 =====

    @Test
    fun saveSuccessDeletesOldEvidenceWhenNoLongerOverride() = runTest {
        val dir = Files.createTempDirectory("vm-lifecycle").toFile()
        try {
            photoPath = createTestPhoto(dir)
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val oldPath = File(evidenceDir, "old_evidence.jpg").apply { writeText("OLD") }.absolutePath
            val newPath = File(evidenceDir, "new_evidence.jpg").absolutePath

            MockitoSuspendStubber.stubSaveRoiEvidence(imageStore) {
                val target = File(newPath)
                target.parentFile?.mkdirs()
                target.writeText("NEW_EVIDENCE_DATA")
                newPath
            }

            `when`(imageStore.roiEvidenceFileValid(oldPath)).thenReturn(false)

            val savedConfirmsRef = mutableListOf<List<ViewRoiConfirmEntity>>(emptyList())
            setupRepo(savedConfirmsRef)

            val vm = createVm()
            advanceUntilIdle()

            setVmField(vm, "roiResults", mutableStateMapOf<String, String>().also {
                it["roi-thread"] = "OK"; it["roi-feature"] = "OK"
            })
            setVmField(vm, "overallResult", "OK")
            setVmField(vm, "isLoaded", true)
            setVmField(vm, "photoAssociationValid", true)
            injectSavedOverrideEvidence(vm, "roi-thread", oldPath, 1_700_000_000_000L)

            @Suppress("UNCHECKED_CAST")
            val infResults = vm.javaClass.getDeclaredField("inferenceResults").let {
                it.isAccessible = true
                it.get(vm) as MutableMap<String, NanoDetRoiInferenceResult>
            }
            infResults["roi-thread"] = NanoDetRoiInferenceResult(
                roiId = "roi-thread", status = NanoDetInferenceStatus.DETECTED,
                modelSuggestion = NanoDetSuggestion.OK, matchingScore = 0.91f,
                targetClassIndex = 1, threshold = 0.37f
            )
            infResults["roi-feature"] = NanoDetRoiInferenceResult(
                roiId = "roi-feature", status = NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
                modelSuggestion = null, matchingScore = null,
                targetClassIndex = null, threshold = 0.37f
            )

            vm.saveConfirmation()
            advanceUntilIdle()

            assertTrue("saveCompleted 应为 true", vm.saveCompleted)

            // DB 保存确实发生（via Java helper, suspend final class）
            MockitoSuspendStubber.verifyReplaceCalled(repo, Mockito.times(1), batchId, photoId)
            // 旧文件被删除
            Mockito.verify(imageStore).deleteRoiEvidence(oldPath)

            val threadConfirm = savedConfirmsRef[0].first { it.roiId == "roi-thread" }
            assertFalse("humanChangedModel 应为 false", threadConfirm.humanChangedModel)
            assertNull("overrideTime 应为 null", threadConfirm.overrideTime)
            assertNull("roiEvidencePath 应为 null", threadConfirm.roiEvidencePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ===== 3. DB 保存失败，旧记录保持 =====

    @Test
    fun dbSaveFailurePreservesOldConfirmAndEvidence() = runTest {
        val dir = Files.createTempDirectory("vm-lifecycle").toFile()
        try {
            photoPath = createTestPhoto(dir)
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val oldPath = File(evidenceDir, "old_evidence.jpg").apply { writeText("OLD_DATA") }.absolutePath

            `when`(imageStore.roiEvidenceFileValid(oldPath)).thenReturn(true)

            val oldConfirmsSnapshot = listOf(
                ViewRoiConfirmEntity(
                    id = 99, batchId = batchId, photoId = photoId, photoPath = photoPath,
                    viewIndex = viewIndex, templateId = templateId, templateName = templateName,
                    roiId = "roi-thread", roiName = "螺纹", roiTargetType = "THREAD",
                    roiNormalizedRect = "{}", roiPixelRect = "{}",
                    humanResult = "NG", confirmTime = 9000, overallResult = "OK",
                    overallConfirmTime = 9001, humanChangedModel = true,
                    overrideTime = 1_700_000_000_000L, roiEvidencePath = oldPath
                )
            )
            val savedConfirmsRef = mutableListOf<List<ViewRoiConfirmEntity>>(oldConfirmsSnapshot)
            setupRepo(savedConfirmsRef, replaceAnswer = org.mockito.stubbing.Answer {
                throw RuntimeException("DB write failed")
            })

            val vm = createVm()
            advanceUntilIdle()

            setVmField(vm, "roiResults", mutableStateMapOf<String, String>().also {
                it["roi-thread"] = "NG"; it["roi-feature"] = "OK"
            })
            setVmField(vm, "overallResult", "OK")
            setVmField(vm, "isLoaded", true)
            setVmField(vm, "photoAssociationValid", true)
            injectSavedOverrideEvidence(vm, "roi-thread", oldPath, 1_700_000_000_000L)

            vm.saveConfirmation()
            advanceUntilIdle()

            assertFalse("saveCompleted 应为 false", vm.saveCompleted)
            assertTrue("errorMessage 应包含 DB write failed",
                vm.errorMessage?.contains("DB write failed") == true)

            // replaceViewRoiConfirmsForPhoto 确实被调用
            MockitoSuspendStubber.verifyReplaceCalled(repo, Mockito.times(1), batchId, photoId)

            // 旧数据库内容完全不变
            val currentConfirms = repo.getViewRoiConfirmsByPhoto(batchId, photoId)
            assertEquals("旧确认行数量不变", oldConfirmsSnapshot.size, currentConfirms.size)
            assertEquals("旧确认行 roiId 不变", oldConfirmsSnapshot[0].roiId, currentConfirms[0].roiId)
            assertEquals("旧确认行 humanResult 不变", oldConfirmsSnapshot[0].humanResult, currentConfirms[0].humanResult)
            assertEquals("旧确认行 overrideTime 不变", oldConfirmsSnapshot[0].overrideTime, currentConfirms[0].overrideTime)
            assertEquals("旧确认行 roiEvidencePath 不变", oldConfirmsSnapshot[0].roiEvidencePath, currentConfirms[0].roiEvidencePath)

            assertTrue("旧证据文件应仍存在", File(oldPath).exists())
            Mockito.verify(imageStore, Mockito.never()).deleteRoiEvidence(Mockito.anyString())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ===== 4. DB 保存失败，清理新文件 =====

    @Test
    fun dbSaveFailureCleansUpNewEvidenceFiles() = runTest {
        val dir = Files.createTempDirectory("vm-lifecycle").toFile()
        try {
            photoPath = createTestPhoto(dir)

            // 使用真实 MobileImageStore，使 saveRoiEvidence 真实创建临时文件
            val ctx = org.robolectric.RuntimeEnvironment.getApplication()
            val realImageStore = MobileImageStore(ctx)

            val savedConfirmsRef = mutableListOf<List<ViewRoiConfirmEntity>>(emptyList())
            setupRepo(savedConfirmsRef, replaceAnswer = org.mockito.stubbing.Answer {
                throw RuntimeException("DB constraint violation")
            })

            // 使用真实 imageStore 构造 ViewModel
            val vm = ViewConfirmationViewModel(
                repository = repo, batchId = batchId, photoId = photoId, photoPath = photoPath,
                viewIndex = viewIndex, templateId = templateId, templateName = templateName,
                partId = partId, totalViews = totalViews, inferenceService = inferenceService,
                imageStore = realImageStore
            )
            advanceUntilIdle()

            setVmField(vm, "roiResults", mutableStateMapOf<String, String>().also {
                it["roi-thread"] = "NG"; it["roi-feature"] = "OK"
            })
            setVmField(vm, "overallResult", "OK")
            setVmField(vm, "isLoaded", true)
            setVmField(vm, "photoAssociationValid", true)

            @Suppress("UNCHECKED_CAST")
            val infResults = vm.javaClass.getDeclaredField("inferenceResults").let {
                it.isAccessible = true
                it.get(vm) as MutableMap<String, NanoDetRoiInferenceResult>
            }
            infResults["roi-thread"] = NanoDetRoiInferenceResult(
                roiId = "roi-thread", status = NanoDetInferenceStatus.DETECTED,
                modelSuggestion = NanoDetSuggestion.OK, matchingScore = 0.91f,
                targetClassIndex = 1, threshold = 0.37f
            )

            // 注入 ROI 裁剪图（真实 Bitmap），使 saveRoiEvidence 能被 ViewModel 调用
            @Suppress("UNCHECKED_CAST")
            val roiBitmaps = vm.javaClass.getDeclaredField("roiBitmaps").let {
                it.isAccessible = true
                it.get(vm) as MutableMap<String, android.graphics.Bitmap>
            }
            roiBitmaps["roi-thread"] = android.graphics.Bitmap.createBitmap(
                100, 100, android.graphics.Bitmap.Config.ARGB_8888
            )

            // 记录 saveRoiEvidence 调用前 ROI 证据目录中已有的文件
            val evidenceDir = File(realImageStore.getRoiEvidencePath())
            evidenceDir.mkdirs()
            val filesBefore = evidenceDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

            vm.saveConfirmation()
            advanceUntilIdle()

            assertFalse("saveCompleted 应为 false", vm.saveCompleted)
            assertTrue("errorMessage 应包含 DB constraint violation，实际: ${vm.errorMessage}",
                vm.errorMessage?.contains("DB constraint violation") == true)

            // saveRoiEvidence 创建了文件，DB 失败后被清理（新创建的文件应被删除）
            val filesAfter = evidenceDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
            val newFiles = filesAfter - filesBefore
            assertTrue("本轮新创建的证据文件应已被清理", newFiles.isEmpty())

            MockitoSuspendStubber.verifyReplaceCalled(repo, Mockito.times(1), batchId, photoId)
            val currentConfirms = repo.getViewRoiConfirmsByPhoto(batchId, photoId)
            assertTrue("旧数据库确认行为空（无旧行）", currentConfirms.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
