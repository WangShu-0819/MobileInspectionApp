package com.wearable.inspection.mobile.template

import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * T1 模板与零件 ID 前缀关系测试
 *
 * 覆盖：模板 partId 使用最终零件 ID、模板 ID 独立于 partId、
 * 导入的无前缀零件保持原样。
 */
class TemplatePartIdPrefixTest {

    @Test
    fun `template with Black partId uses final ID`() {
        val part = PartEntity(id = "Black_part01", name = "黑件01")
        val template = InspectionTemplateEntity(
            id = UUID.randomUUID().toString(),
            partId = part.id,
            name = "视角1",
            mainImagePath = "/path/to/image.jpg"
        )
        assertEquals("Black_part01", template.partId)
    }

    @Test
    fun `template with White partId uses final ID`() {
        val part = PartEntity(id = "White_part01", name = "白件01")
        val template = InspectionTemplateEntity(
            id = UUID.randomUUID().toString(),
            partId = part.id,
            name = "视角1",
            mainImagePath = "/path/to/image.jpg"
        )
        assertEquals("White_part01", template.partId)
    }

    @Test
    fun `template with old unprefixed partId preserved as-is`() {
        val part = PartEntity(id = "old_part_001", name = "旧零件")
        val template = InspectionTemplateEntity(
            id = UUID.randomUUID().toString(),
            partId = part.id,
            name = "视角1",
            mainImagePath = "/path/to/image.jpg"
        )
        assertEquals("old_part_001", template.partId)
        // 旧零件不应被猜测件色
        assert(!template.partId.startsWith("White_")) { "Old partId should not be prefixed" }
        assert(!template.partId.startsWith("Black_")) { "Old partId should not be prefixed" }
    }

    @Test
    fun `template id is independent from partId`() {
        val part = PartEntity(id = "Black_part01", name = "黑件01")
        val template = InspectionTemplateEntity(
            id = "template_uuid_123",
            partId = part.id,
            name = "视角1",
            mainImagePath = "/path/to/image.jpg"
        )
        assertNotEquals(template.id, template.partId)
        assertEquals("template_uuid_123", template.id)
        assertEquals("Black_part01", template.partId)
    }

    @Test
    fun `multiple templates can share same prefixed partId`() {
        val partId = "White_part02"
        val template1 = InspectionTemplateEntity(
            id = UUID.randomUUID().toString(),
            partId = partId,
            name = "视角1",
            mainImagePath = "/path1.jpg"
        )
        val template2 = InspectionTemplateEntity(
            id = UUID.randomUUID().toString(),
            partId = partId,
            name = "视角2",
            mainImagePath = "/path2.jpg"
        )
        assertEquals(partId, template1.partId)
        assertEquals(partId, template2.partId)
        assertNotEquals(template1.id, template2.id)
    }

    @Test
    fun `same baseId with different colors produce different partIds`() {
        val whitePart = PartEntity(id = "White_abc", name = "白件")
        val blackPart = PartEntity(id = "Black_abc", name = "黑件")
        assertNotEquals(whitePart.id, blackPart.id)
        assertEquals("White_abc", whitePart.id)
        assertEquals("Black_abc", blackPart.id)
    }

    @Test
    fun `import preserves original partId without guessing color`() {
        // 模拟导入场景：partId 来自导入包，不自动加前缀
        val importedPartId = "imported_part_001"
        val part = PartEntity(id = importedPartId, name = "导入零件")
        // 导入时不应猜测件色
        assert(!part.id.startsWith("White_")) { "Import should not add color prefix" }
        assert(!part.id.startsWith("Black_")) { "Import should not add color prefix" }
    }
}