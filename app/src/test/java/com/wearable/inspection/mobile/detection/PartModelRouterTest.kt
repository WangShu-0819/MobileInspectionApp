package com.wearable.inspection.mobile.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 零件模型路由测试
 *
 * 覆盖：前缀路由、无前缀回退、精确前缀匹配。
 */
class PartModelRouterTest {

    // ── 前缀路由 ──

    @Test
    fun `Black prefix routes to EXP22`() {
        val result = PartModelRouter.resolveModelConfig("Black_part01")
        // exp22 资产未验证时返回 ModelUnavailable
        if (NanoDetModelContractExp22.CONFIG.assetsVerified) {
            assertTrue("should be Success", result is ModelRouteResult.Success)
            val success = result as ModelRouteResult.Success
            assertEquals(ModelRoute.EXP22_BLACK, success.route)
            assertEquals(NanoDetModelContractExp22.CONFIG.version, success.config.version)
            assertEquals(Exp22BlackClassPolicy, success.classPolicy)
        } else {
            assertTrue("should be ModelUnavailable", result is ModelRouteResult.ModelUnavailable)
            val unavailable = result as ModelRouteResult.ModelUnavailable
            assertEquals(ModelRoute.EXP22_BLACK, unavailable.route)
        }
    }

    @Test
    fun `White prefix routes to EXP23`() {
        val result = PartModelRouter.resolveModelConfig("White_part01")
        // exp23 资产未验证时返回 ModelUnavailable
        if (NanoDetModelContractExp23.CONFIG.assetsVerified) {
            assertTrue("should be Success", result is ModelRouteResult.Success)
            val success = result as ModelRouteResult.Success
            assertEquals(ModelRoute.EXP23_WHITE, success.route)
            assertEquals(NanoDetModelContractExp23.CONFIG.version, success.config.version)
            assertEquals(Exp23WhiteClassPolicy, success.classPolicy)
        } else {
            assertTrue("should be ModelUnavailable", result is ModelRouteResult.ModelUnavailable)
            val unavailable = result as ModelRouteResult.ModelUnavailable
            assertEquals(ModelRoute.EXP23_WHITE, unavailable.route)
        }
    }

    @Test
    fun `no prefix routes to EXP09`() {
        val result = PartModelRouter.resolveModelConfig("old_part_001")
        assertTrue("should be Success", result is ModelRouteResult.Success)
        val success = result as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP09, success.route)
        assertEquals(NanoDetModelContract.CONFIG.version, success.config.version)
        assertEquals(Exp09ClassPolicy, success.classPolicy)
    }

    @Test
    fun `empty ID routes to EXP09`() {
        val result = PartModelRouter.resolveModelConfig("")
        assertTrue("should be Success", result is ModelRouteResult.Success)
        val success = result as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP09, success.route)
    }

    // ── 精确前缀匹配 ──

    @Test
    fun `Whitexxx without underscore routes to EXP09`() {
        val result = PartModelRouter.resolveModelConfig("Whitexxx")
        assertTrue("should be Success", result is ModelRouteResult.Success)
        val success = result as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP09, success.route)
    }

    @Test
    fun `Blackxxx without underscore routes to EXP09`() {
        val result = PartModelRouter.resolveModelConfig("Blackxxx")
        assertTrue("should be Success", result is ModelRouteResult.Success)
        val success = result as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP09, success.route)
    }

    @Test
    fun `Black with empty base routes to EXP22`() {
        val result = PartModelRouter.resolveModelConfig("Black_")
        if (NanoDetModelContractExp22.CONFIG.assetsVerified) {
            assertTrue("should be Success", result is ModelRouteResult.Success)
            assertEquals(ModelRoute.EXP22_BLACK, (result as ModelRouteResult.Success).route)
        } else {
            assertTrue("should be ModelUnavailable", result is ModelRouteResult.ModelUnavailable)
            assertEquals(ModelRoute.EXP22_BLACK, (result as ModelRouteResult.ModelUnavailable).route)
        }
    }

    @Test
    fun `White with empty base routes to EXP23`() {
        val result = PartModelRouter.resolveModelConfig("White_")
        if (NanoDetModelContractExp23.CONFIG.assetsVerified) {
            assertTrue("should be Success", result is ModelRouteResult.Success)
            assertEquals(ModelRoute.EXP23_WHITE, (result as ModelRouteResult.Success).route)
        } else {
            assertTrue("should be ModelUnavailable", result is ModelRouteResult.ModelUnavailable)
            assertEquals(ModelRoute.EXP23_WHITE, (result as ModelRouteResult.ModelUnavailable).route)
        }
    }

    // ── 前缀常量 ──

    @Test
    fun `prefix constants are correct`() {
        assertEquals("White_", PartModelRouter.PREFIX_WHITE)
        assertEquals("Black_", PartModelRouter.PREFIX_BLACK)
    }

    // ── 资产验证状态 ──

    @Test
    fun `exp09 assets are verified`() {
        assertTrue(NanoDetModelContract.CONFIG.assetsVerified)
    }

    @Test
    fun `exp22 assets are verified`() {
        assertTrue(NanoDetModelContractExp22.CONFIG.assetsVerified)
    }

    @Test
    fun `exp23 assets are verified`() {
        assertTrue(NanoDetModelContractExp23.CONFIG.assetsVerified)
    }

    // ── 资产路径独立 ──

    @Test
    fun `exp09 asset paths are distinct from exp22`() {
        assertNotEquals(NanoDetModelContract.CONFIG.assetParamPath, NanoDetModelContractExp22.CONFIG.assetParamPath)
        assertNotEquals(NanoDetModelContract.CONFIG.assetModelPath, NanoDetModelContractExp22.CONFIG.assetModelPath)
    }

    @Test
    fun `exp09 asset paths are distinct from exp23`() {
        assertNotEquals(NanoDetModelContract.CONFIG.assetParamPath, NanoDetModelContractExp23.CONFIG.assetParamPath)
        assertNotEquals(NanoDetModelContract.CONFIG.assetModelPath, NanoDetModelContractExp23.CONFIG.assetModelPath)
    }

    @Test
    fun `exp22 and exp23 have distinct model dir names`() {
        assertNotEquals(NanoDetModelContractExp22.CONFIG.modelDirName, NanoDetModelContractExp23.CONFIG.modelDirName)
        assertNotEquals(NanoDetModelContract.CONFIG.modelDirName, NanoDetModelContractExp22.CONFIG.modelDirName)
    }

    // ── 正向路径：已验证资产返回 Success ──

    @Test
    fun `Black prefix with verified assets returns Success for EXP22`() {
        val result = PartModelRouter.resolveModelConfig("Black_part01")
        assertTrue("should be Success", result is ModelRouteResult.Success)
        val success = result as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP22_BLACK, success.route)
        assertEquals(NanoDetModelContractExp22.CONFIG.version, success.config.version)
        assertEquals(Exp22BlackClassPolicy, success.classPolicy)
    }

    @Test
    fun `White prefix with verified assets returns Success for EXP23`() {
        val result = PartModelRouter.resolveModelConfig("White_part01")
        assertTrue("should be Success", result is ModelRouteResult.Success)
        val success = result as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP23_WHITE, success.route)
        assertEquals(NanoDetModelContractExp23.CONFIG.version, success.config.version)
        assertEquals(Exp23WhiteClassPolicy, success.classPolicy)
    }

    // ── 旧 ID 始终路由到 exp09 ──

    @Test
    fun `old ID always routes to EXP09 regardless of case`() {
        val ids = listOf("part_001", "WHITE_part", "BLACK_part", "white_x", "black_x")
        for (id in ids) {
            val result = PartModelRouter.resolveModelConfig(id)
            assertTrue("$id should route to EXP09", result is ModelRouteResult.Success)
            assertEquals(ModelRoute.EXP09, (result as ModelRouteResult.Success).route)
        }
    }
}