package com.wearable.inspection.mobile.dpm

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wearable.inspection.mobile.data.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DPM 设置持久化 instrumented 测试
 *
 * 验证 SettingsStore.dpmDimensionMode 的读写、默认值和非法值回退。
 * 在真机/模拟器上运行，使用真实 SharedPreferences。
 */
@RunWith(AndroidJUnit4::class)
class DpmSettingsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var store: SettingsStore
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "mobile_inspection_settings"
        private const val KEY_DPM_DIMENSION_MODE = "dpm_dimension_mode"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 清除测试残留
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = SettingsStore(context)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Test
    fun defaultDimensionModeIsAuto() {
        assertEquals(
            "未设置时默认 AUTO",
            DpmDimensionMode.AUTO,
            store.dpmDimensionMode
        )
    }

    @Test
    fun writeAndReadDimensionModes() {
        for (mode in DpmDimensionMode.entries) {
            store.dpmDimensionMode = mode
            assertEquals(
                "写入 ${mode.name} 后应能读回",
                mode,
                store.dpmDimensionMode
            )
        }
    }

    @Test
    fun dimensionModePersistsAcrossInstances() {
        store.dpmDimensionMode = DpmDimensionMode.DIM_18
        // 新建 SettingsStore 实例模拟进程重启
        val store2 = SettingsStore(context)
        assertEquals(
            "新实例应读到持久化的 DIM_18",
            DpmDimensionMode.DIM_18,
            store2.dpmDimensionMode
        )
    }

    @Test
    fun illegalValueFallsBackToAuto() {
        prefs.edit().putString(KEY_DPM_DIMENSION_MODE, "INVALID_VALUE").commit()
        assertEquals(
            "非法值应回退 AUTO",
            DpmDimensionMode.AUTO,
            store.dpmDimensionMode
        )
    }

    @Test
    fun nullValueFallsBackToAuto() {
        prefs.edit().remove(KEY_DPM_DIMENSION_MODE).commit()
        assertEquals(
            "缺失 key 应回退 AUTO",
            DpmDimensionMode.AUTO,
            store.dpmDimensionMode
        )
    }

    @Test
    fun emptyStringFallsBackToAuto() {
        prefs.edit().putString(KEY_DPM_DIMENSION_MODE, "").commit()
        assertEquals(
            "空字符串应回退 AUTO",
            DpmDimensionMode.AUTO,
            store.dpmDimensionMode
        )
    }

    @Test
    fun caseSensitiveMatch() {
        prefs.edit().putString(KEY_DPM_DIMENSION_MODE, "dim_16").commit()
        assertEquals(
            "小写 dim_16 不匹配 DIM_16，应回退 AUTO",
            DpmDimensionMode.AUTO,
            store.dpmDimensionMode
        )
    }

    @Test
    fun allModesHaveCorrectLabel() {
        assertEquals("自动（16/18/20）", DpmDimensionMode.AUTO.label)
        assertEquals("16×16", DpmDimensionMode.DIM_16.label)
        assertEquals("18×18", DpmDimensionMode.DIM_18.label)
        assertEquals("20×20", DpmDimensionMode.DIM_20.label)
    }

    @Test
    fun allModesHaveCorrectDimensions() {
        assertArrayEquals(intArrayOf(16, 18, 20), DpmDimensionMode.AUTO.dimensions())
        assertArrayEquals(intArrayOf(16), DpmDimensionMode.DIM_16.dimensions())
        assertArrayEquals(intArrayOf(18), DpmDimensionMode.DIM_18.dimensions())
        assertArrayEquals(intArrayOf(20), DpmDimensionMode.DIM_20.dimensions())
    }

    @Test
    fun parseRoundTrip() {
        for (mode in DpmDimensionMode.entries) {
            assertEquals(
                "parse(${mode.name}) 应返回 $mode",
                mode,
                DpmDimensionMode.parse(mode.name)
            )
        }
    }
}
