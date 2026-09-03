package com.wearable.inspection.mobile.template

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.db.ALL_MIGRATIONS
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 模板视角顺序的 Room、重导入和扁平目录真机回归。 */
@RunWith(AndroidJUnit4::class)
class TemplateViewOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        testRoot = File(context.cacheDir, "template_order_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun dao_returnsViewOrder_withStableSecondaryKey() = runBlocking {
        db.partDao().insert(PartEntity(id = "order_part", name = "顺序测试"))
        db.templateDao().insert(template("z", displayOrder = 2))
        db.templateDao().insert(template("b", displayOrder = 0))
        db.templateDao().insert(template("a", displayOrder = 0))

        val names = db.templateDao().getByPartId("order_part").map { it.name }

        assertEquals(listOf("a", "b", "z"), names)
    }

    @Test
    fun directoryReimport_keepsManifestOrder() = runBlocking {
        val directory = createManifestDirectory(
            partId = "reimport_part",
            regions = listOf(
                "第二" to 20,
                "第三" to 30,
                "第一" to 10,
            ),
        )
        val service = TemplateImportService(ApplicationProvider.getApplicationContext())

        assertTrue(service.importFromDirectory(directory, db).success)
        val first = db.templateDao().getByPartId("reimport_part").map { it.name }
        assertTrue(service.importFromDirectory(directory, db).success)
        val second = db.templateDao().getByPartId("reimport_part").map { it.name }

        assertEquals(listOf("第一", "第二", "第三"), first)
        assertEquals(first, second)
    }

    @Test
    fun flatDirectory_usesStableFilenameOrder() = runBlocking {
        val directory = File(testRoot, "flat_part").apply { mkdirs() }
        File(directory, "z.jpg").writeBytes(byteArrayOf(3))
        File(directory, "a.jpg").writeBytes(byteArrayOf(1))
        File(directory, "m.jpg").writeBytes(byteArrayOf(2))
        File(directory, "ignore.txt").writeText("ignore")
        val service = TemplateImportService(ApplicationProvider.getApplicationContext())

        assertTrue(service.importFromFlatDirectory(directory, db).success)

        assertEquals(
            listOf("a.jpg", "m.jpg", "z.jpg"),
            db.templateDao().getByPartId("flat_part")
                .map { File(it.mainImagePath).name.substringAfterLast('_') },
        )
    }

    private fun template(idSuffix: String, displayOrder: Int) = InspectionTemplateEntity(
        id = "template_$idSuffix",
        partId = "order_part",
        name = idSuffix,
        mainImagePath = "/tmp/$idSuffix.jpg",
        displayOrder = displayOrder,
    )

    private fun createManifestDirectory(
        partId: String,
        regions: List<Pair<String, Int>>,
    ): File {
        val directory = File(testRoot, partId).apply { mkdirs() }
        val images = File(directory, "images").apply { mkdirs() }
        val regionObjects = JSONArray()
        regions.forEachIndexed { index, (name, order) ->
            val fileName = "view_${index + 1}.jpg"
            File(images, fileName).writeBytes(byteArrayOf((index + 1).toByte()))
            regionObjects.put(JSONObject().apply {
                put("regionName", name)
                put("order", order)
                put("imageFiles", JSONArray(listOf("images/$fileName")))
            })
        }
        File(directory, "template.json").writeText(
            JSONObject().apply {
                put("partId", partId)
                put("partName", partId)
                put("regions", regionObjects)
            }.toString()
        )
        return directory
    }
}
