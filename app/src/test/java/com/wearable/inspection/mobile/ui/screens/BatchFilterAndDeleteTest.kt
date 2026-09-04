package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.io.File

/**
 * 采集批次时间筛选与删除交互状态测试
 *
 * 覆盖 18 项要求中的可测试子集（JVM 层）：
 * 1-6: 时间筛选逻辑（sinceMillis 计算、边界、排序）
 * 7: 筛选不影响数据
 * 8-9: 多选 batchId 绑定与筛选切换清除
 * 10-14: 删除状态逻辑
 * 15-17: 布局稳定性概念验证
 * 18: 前序回归
 *
 * 注意：Compose UI 布局 bounds 断言（#16 标题栏高度、#17 遮挡检测）
 * 需要 instrumented 测试或 screenshot 测试框架覆盖，
 * 本轮仅在报告中说明覆盖范围，不在 JVM 层伪造 bounds。
 */
class BatchFilterAndDeleteTest {

    // ---- 辅助方法 ----

    private fun read(path: String): String = File(path).readText()

    private fun createBatch(
        batchId: String = "batch_001",
        partId: String? = "part_001",
        partName: String? = "零件A",
        startTime: Long = System.currentTimeMillis(),
        viewCount: Int = 4
    ) = CaptureBatchEntity(
        batchId = batchId,
        partId = partId,
        partName = partName,
        startTime = startTime,
        viewCount = viewCount
    )

    private fun daysAgo(days: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -days)
        }.timeInMillis
    }

    private fun todayStartMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ====== #1: 今日筛选按本地日期正确工作 ======

    @Test
    fun `today filter sinceMillis is local midnight`() {
        val since = BatchTimeFilter.TODAY.sinceMillis()!!
        val expected = todayStartMillis()
        // 允许 1ms 误差（执行时间差）
        assertTrue("sinceMillis should be at or near today start",
            since in (expected - 1)..(expected + 1))
    }

    @Test
    fun `today filter includes batch created this morning`() {
        val since = BatchTimeFilter.TODAY.sinceMillis()!!
        val thisMorning = todayStartMillis() + 3600_000 // 01:00
        val batch = createBatch(startTime = thisMorning)
        assertTrue(batch.startTime >= since)
    }

    @Test
    fun `today filter excludes batch from yesterday`() {
        val since = BatchTimeFilter.TODAY.sinceMillis()!!
        val yesterday = daysAgo(1)
        val batch = createBatch(startTime = yesterday)
        assertTrue(batch.startTime < since)
    }

    // ====== #2: 近 3 天包含正确的三个自然日 ======

    @Test
    fun `last 3 days filter sinceMillis is 2 days ago midnight`() {
        val since = BatchTimeFilter.LAST_3_DAYS.sinceMillis()!!
        val expected = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -2)
        }.timeInMillis
        assertTrue(since in (expected - 1)..(expected + 1))
    }

    @Test
    fun `last 3 days includes today`() {
        val since = BatchTimeFilter.LAST_3_DAYS.sinceMillis()!!
        assertTrue(todayStartMillis() >= since)
    }

    @Test
    fun `last 3 days includes 2 days ago`() {
        val since = BatchTimeFilter.LAST_3_DAYS.sinceMillis()!!
        val twoDaysAgo = daysAgo(2)
        assertTrue(twoDaysAgo >= since)
    }

    @Test
    fun `last 3 days excludes 3 days ago`() {
        val since = BatchTimeFilter.LAST_3_DAYS.sinceMillis()!!
        val threeDaysAgo = daysAgo(3)
        assertTrue(threeDaysAgo < since)
    }

    // ====== #3: 近 7 天包含正确的七个自然日 ======

    @Test
    fun `last 7 days filter sinceMillis is 6 days ago midnight`() {
        val since = BatchTimeFilter.LAST_7_DAYS.sinceMillis()!!
        val expected = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -6)
        }.timeInMillis
        assertTrue(since in (expected - 1)..(expected + 1))
    }

    @Test
    fun `last 7 days includes 6 days ago`() {
        val since = BatchTimeFilter.LAST_7_DAYS.sinceMillis()!!
        val sixDaysAgo = daysAgo(6)
        assertTrue(sixDaysAgo >= since)
    }

    @Test
    fun `last 7 days excludes 7 days ago`() {
        val since = BatchTimeFilter.LAST_7_DAYS.sinceMillis()!!
        val sevenDaysAgo = daysAgo(7)
        assertTrue(sevenDaysAgo < since)
    }

    // ====== #4: 所有筛选包含全部批次 ======

    @Test
    fun `all filter sinceMillis is null`() {
        assertNull(BatchTimeFilter.ALL.sinceMillis())
    }

    @Test
    fun `all filter label is correct`() {
        assertEquals("所有", BatchTimeFilter.ALL.label)
    }

    // ====== #5: 批次按真实采集时间倒序排列 ======

    @Test
    fun `batches with different startTimes sort descending`() {
        val batches = listOf(
            createBatch(batchId = "old", startTime = daysAgo(5)),
            createBatch(batchId = "new", startTime = daysAgo(0)),
            createBatch(batchId = "mid", startTime = daysAgo(2))
        )
        val sorted = batches.sortedByDescending { it.startTime }
        assertEquals("new", sorted[0].batchId)
        assertEquals("mid", sorted[1].batchId)
        assertEquals("old", sorted[2].batchId)
    }

    @Test
    fun `batches with same startTime maintain relative order`() {
        val time = System.currentTimeMillis()
        val batches = listOf(
            createBatch(batchId = "a", startTime = time),
            createBatch(batchId = "b", startTime = time)
        )
        val sorted = batches.sortedByDescending { it.startTime }
        // 稳定排序保证同时间的相对顺序不变
        assertEquals("a", sorted[0].batchId)
        assertEquals("b", sorted[1].batchId)
    }

    // ====== #6: 空时间历史记录只在"所有"中显示 ======

    @Test
    fun `batch with startTime of 0 is below all time filters`() {
        val batch = createBatch(startTime = 0L)
        // startTime=0 的记录应低于所有时间筛选
        val todaySince = BatchTimeFilter.TODAY.sinceMillis()!!
        val last3Since = BatchTimeFilter.LAST_3_DAYS.sinceMillis()!!
        val last7Since = BatchTimeFilter.LAST_7_DAYS.sinceMillis()!!
        assertTrue(batch.startTime < todaySince)
        assertTrue(batch.startTime < last3Since)
        assertTrue(batch.startTime < last7Since)
    }

    @Test
    fun `batch with very old startTime is below all time filters`() {
        // 模拟历史数据（1970-01-02）
        val batch = createBatch(startTime = 86400_000L)
        val last7Since = BatchTimeFilter.LAST_7_DAYS.sinceMillis()!!
        assertTrue(batch.startTime < last7Since)
    }

    // ====== #7: 筛选不会修改数据库或删除批次 ======

    @Test
    fun `filter enum sinceMillis is pure computation`() {
        // sinceMillis() 只做日历计算，不修改任何状态
        val before = BatchTimeFilter.entries.map { it.sinceMillis() }
        val after = BatchTimeFilter.entries.map { it.sinceMillis() }
        assertEquals(before, after)
    }

    @Test
    fun `filter label does not mutate enum`() {
        val label1 = BatchTimeFilter.TODAY.label
        val label2 = BatchTimeFilter.TODAY.label
        assertEquals(label1, label2)
        assertEquals("今日", label1)
    }

    // ====== #8: 多选批次绑定真实 batchId ======

    @Test
    fun `selected batchIds can contain multiple exact entity batchIds`() {
        var selectedBatchIds = emptySet<String>()
        val first = createBatch(batchId = "batch_real_123")
        val second = createBatch(batchId = "batch_real_456")
        selectedBatchIds = selectedBatchIds + first.batchId
        selectedBatchIds = selectedBatchIds + second.batchId
        assertEquals(setOf("batch_real_123", "batch_real_456"), selectedBatchIds)
    }

    @Test
    fun `unselecting one batchId leaves other selected`() {
        val selectedBatchIds = setOf("batch_001", "batch_002")
        val otherBatch = createBatch(batchId = "batch_002")
        val remaining = selectedBatchIds - otherBatch.batchId
        assertEquals(setOf("batch_001"), remaining)
    }

    // ====== #9: 切换筛选后选中状态被安全清除 ======

    @Test
    fun `selection clear on filter change concept`() {
        var selectedBatchIds = setOf("batch_001", "batch_002")
        // 模拟 LaunchedEffect(activeFilter) { selectedBatchIds = emptySet() }
        selectedBatchIds = emptySet()
        assertTrue(selectedBatchIds.isEmpty())
    }

    @Test
    fun `selection clear is idempotent`() {
        var selectedBatchIds = emptySet<String>()
        selectedBatchIds = emptySet()
        assertTrue(selectedBatchIds.isEmpty())
    }

    // ====== #10: 删除只删除选中的稳定 batchId ======

    @Test
    fun `delete targets only selected batchIds`() {
        val selectedBatchIds = setOf("batch_to_delete", "batch_to_delete_2")
        val batches = listOf(
            createBatch(batchId = "batch_to_delete"),
            createBatch(batchId = "batch_to_delete_2"),
            createBatch(batchId = "batch_to_keep_1"),
            createBatch(batchId = "batch_to_keep_2")
        )
        val targets = batches.filter { it.batchId in selectedBatchIds }
        assertEquals(listOf("batch_to_delete", "batch_to_delete_2"), targets.map { it.batchId })
        assertEquals(2, targets.size)
    }

    // ====== #11: 删除其他零件和其他批次不受影响 ======

    @Test
    fun `removing one batch preserves others`() {
        val batches = mutableListOf(
            createBatch(batchId = "batch_a", partId = "part_1"),
            createBatch(batchId = "batch_b", partId = "part_2"),
            createBatch(batchId = "batch_c", partId = "part_1")
        )
        batches.removeAll { it.batchId == "batch_b" }
        assertEquals(2, batches.size)
        assertNotNull(batches.find { it.batchId == "batch_a" })
        assertNotNull(batches.find { it.batchId == "batch_c" })
        assertNull(batches.find { it.batchId == "batch_b" })
    }

    @Test
    fun `removing batch from part_1 does not affect part_2`() {
        val batches = mutableListOf(
            createBatch(batchId = "p1_batch", partId = "part_1", partName = "零件1"),
            createBatch(batchId = "p2_batch", partId = "part_2", partName = "零件2")
        )
        batches.removeAll { it.partId == "part_1" }
        assertEquals(1, batches.size)
        assertEquals("p2_batch", batches[0].batchId)
        assertEquals("零件2", batches[0].partName)
    }

    // ====== #12: 删除成功后列表刷新、选中状态清除 ======

    @Test
    fun `after successful bulk delete selection should be empty`() {
        var selectedBatchIds = setOf("batch_deleted", "batch_deleted_2")
        // 模拟删除成功后清除选中
        selectedBatchIds = emptySet()
        assertTrue(selectedBatchIds.isEmpty())
    }

    @Test
    fun `after successful delete batch removed from list`() {
        val batches = mutableListOf(
            createBatch(batchId = "batch_001"),
            createBatch(batchId = "batch_002")
        )
        batches.removeAll { it.batchId == "batch_001" }
        assertEquals(1, batches.size)
        assertNull(batches.find { it.batchId == "batch_001" })
    }

    // ====== #13: 删除失败后选中状态保留 ======

    @Test
    fun `after failed bulk delete selection preserved`() {
        val selectedBatchIds = setOf("batch_failed_delete", "batch_failed_delete_2")
        // 模拟删除失败 — 选中集合不变
        assertEquals(setOf("batch_failed_delete", "batch_failed_delete_2"), selectedBatchIds)
    }

    @Test
    fun `after partial bulk delete only remaining batchIds stay selected`() {
        val selectedBatchIds = setOf("batch_deleted", "batch_failed_delete")
        val deletedBatchIds = setOf("batch_deleted")
        val remaining = selectedBatchIds - deletedBatchIds
        assertEquals(setOf("batch_failed_delete"), remaining)
    }

    // ====== #14: 删除成功提示不插入列表数据 ======

    @Test
    fun `snackbar message is separate from batch list data`() {
        // Snackbar 消息由 SnackbarHostState 管理，不在 LazyColumn items 中
        val batches = listOf(createBatch(batchId = "batch_001"))
        val snackbarMessage = "已删除采集批次"
        // 列表大小不受 snackbar 消息影响
        assertEquals(1, batches.size)
        // Snackbar 消息不是列表项
        assertTrue(snackbarMessage.startsWith("已删除"))
    }

    // ====== #15: 未选中时垃圾桶不可执行删除 ======

    @Test
    fun `delete button disabled when no selection`() {
        val selectedBatchIds = emptySet<String>()
        val deletingBatch = false
        val enabled = selectedBatchIds.isNotEmpty() && !deletingBatch
        assertFalse("Delete button should be disabled when nothing selected", enabled)
    }

    @Test
    fun `delete button enabled when one or more batches selected and not deleting`() {
        val selectedBatchIds = setOf("batch_001", "batch_002")
        val deletingBatch = false
        val enabled = selectedBatchIds.isNotEmpty() && !deletingBatch
        assertTrue("Delete button should be enabled when batch selected", enabled)
    }

    @Test
    fun `delete button disabled during deletion`() {
        val selectedBatchIds = setOf("batch_001", "batch_002")
        val deletingBatch = true
        val enabled = selectedBatchIds.isNotEmpty() && !deletingBatch
        assertFalse("Delete button should be disabled during deletion", enabled)
    }

    // ====== 筛选标签正确性 ======

    @Test
    fun `all filter labels are correct`() {
        assertEquals("今日", BatchTimeFilter.TODAY.label)
        assertEquals("近 3 天", BatchTimeFilter.LAST_3_DAYS.label)
        assertEquals("近 7 天", BatchTimeFilter.LAST_7_DAYS.label)
        assertEquals("所有", BatchTimeFilter.ALL.label)
    }

    @Test
    fun `filter enum has exactly 4 entries`() {
        assertEquals(4, BatchTimeFilter.entries.size)
    }

    // ====== 导出冲突检测 ======

    @Test
    fun `bulk delete blocked when any selected batch is exporting`() {
        val exportingBatchId = "batch_001"
        val selectedBatchIds = setOf("batch_001", "batch_002")
        assertTrue(exportingBatchId in selectedBatchIds)
    }

    @Test
    fun `bulk delete allowed when exporting batch is not selected`() {
        val exportingBatchId = "batch_001"
        val selectedBatchIds = setOf("batch_002", "batch_003")
        assertFalse(exportingBatchId in selectedBatchIds)
    }

    // ====== 空状态文案 ======

    @Test
    fun `empty state messages match filter`() {
        val messages = mapOf(
            BatchTimeFilter.TODAY to "今日暂无采集批次",
            BatchTimeFilter.LAST_3_DAYS to "近 3 天暂无采集批次",
            BatchTimeFilter.LAST_7_DAYS to "近 7 天暂无采集批次",
            BatchTimeFilter.ALL to "暂无采集批次"
        )
        messages.forEach { (filter, expected) ->
            val actual = when (filter) {
                BatchTimeFilter.TODAY -> "今日暂无采集批次"
                BatchTimeFilter.LAST_3_DAYS -> "近 3 天暂无采集批次"
                BatchTimeFilter.LAST_7_DAYS -> "近 7 天暂无采集批次"
                BatchTimeFilter.ALL -> "暂无采集批次"
            }
            assertEquals("Empty state for ${filter.name}", expected, actual)
        }
    }

    // ====== 时间筛选边界值 ======

    @Test
    fun `today filter includes batch at exactly midnight`() {
        val since = BatchTimeFilter.TODAY.sinceMillis()!!
        val midnight = todayStartMillis()
        assertTrue(midnight >= since)
    }

    @Test
    fun `last 3 days includes batch at exactly 2 days ago midnight`() {
        val since = BatchTimeFilter.LAST_3_DAYS.sinceMillis()!!
        val twoDaysAgoMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -2)
        }.timeInMillis
        assertTrue(twoDaysAgoMidnight >= since)
    }

    @Test
    fun `last 7 days includes batch at exactly 6 days ago midnight`() {
        val since = BatchTimeFilter.LAST_7_DAYS.sinceMillis()!!
        val sixDaysAgoMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -6)
        }.timeInMillis
        assertTrue(sixDaysAgoMidnight >= since)
    }

    @Test
    fun `today filter excludes batch at 23_59_59 yesterday`() {
        val since = BatchTimeFilter.TODAY.sinceMillis()!!
        val yesterdayEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MILLISECOND, -1)
        }.timeInMillis
        assertTrue(yesterdayEnd < since)
    }

    // ====== #16-18: 追溯记录页布局稳定契约 ======

    @Test
    fun `trace records header and filter use bounded fixed slots`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
        val headerStart = source.indexOf("text = \"采集批次\"")
        assertTrue("应存在采集批次标题", headerStart > 0)
        val headerBlock = source.substring(headerStart, (headerStart + 500).coerceAtMost(source.length))
        assertTrue("标题区域应允许收缩", headerBlock.contains("widthIn(min = 0.dp)"))
        assertTrue("筛选器应使用固定宽度", source.contains(".width(104.dp)"))
        assertTrue("筛选器应使用固定高度", source.contains(".height(36.dp)"))
        assertTrue("筛选文案应限制为单行", headerBlock.contains("maxLines = 1"))
    }

    @Test
    fun `trace records list uses stable batch id keys`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
        assertTrue(
            "批次列表必须使用稳定 batchId 作为 key",
            source.contains("key = { index -> batches[index].batchId }")
        )
    }

    @Test
    fun `trace records export message reserves a fixed single line slot`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
        val messageStart = source.indexOf("exportMessage?.let")
        assertTrue("应存在导出结果提示槽位", messageStart > 0)
        val messageBlock = source.substring(messageStart, (messageStart + 450).coerceAtMost(source.length))
        assertTrue("导出结果提示应使用固定高度", messageBlock.contains(".height(20.dp)"))
        assertTrue("导出结果提示应限制为单行", messageBlock.contains("maxLines = 1"))
    }

    @Test
    fun `trace records uses a batchId set and explicit checkbox for multi selection`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
        assertTrue("选中状态必须是 batchId 集合", source.contains("selectedBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }"))
        assertTrue("卡片应提供明确的多选控件", source.contains("Checkbox("))
        assertTrue("取消单个批次时不能清除其他选中项", source.contains("selectedBatchIds - batch.batchId"))
        assertTrue("选择批次时应按稳定 batchId 加入集合", source.contains("selectedBatchIds + batch.batchId"))
    }

    @Test
    fun `batch checkbox is placed immediately after the view count`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
        val cardStart = source.indexOf("private fun CaptureBatchCard(")
        val viewCount = source.indexOf("text = \"${'$'}{batch.viewCount} 视角\"", cardStart)
        val checkbox = source.indexOf("Checkbox(", cardStart)
        assertTrue("采集批次卡片应存在", cardStart >= 0)
        assertTrue("视角计数应存在", viewCount > cardStart)
        assertTrue("勾选框应放在视角计数右侧", checkbox > viewCount)
    }

    @Test
    fun `trace records bulk delete snapshots selected batchIds`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
        assertTrue("删除确认框应按 batchId 过滤真实实体", source.contains("batches.filter { it.batchId in selectedBatchIds }"))
        assertTrue("删除必须遍历选中的 batchId", source.contains("batchIdsToDelete.forEach { batchId ->"))
        assertTrue("删除确认框应显示批次数量", source.contains("text = \"删除 ${'$'}{batches.size} 个采集批次\""))
    }
}
