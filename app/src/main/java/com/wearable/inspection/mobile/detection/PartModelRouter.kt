package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType

/**
 * 零件 ID 前缀到推理模型的路由。
 *
 * - `White_` 前缀 → exp23 白件模型（2 类）
 * - `Black_` 前缀 → exp22 黑件模型（4 类）
 * - 无前缀（旧零件）→ exp09 历史模型（4 类）
 *
 * 路由仅由 PartEntity.id 前缀决定，不由 ROI 类别或图片内容推断。
 */
object PartModelRouter {

    const val PREFIX_WHITE = "White_"
    const val PREFIX_BLACK = "Black_"

    /**
     * 根据零件 ID 解析应使用的模型配置。
     *
     * 资产未验证（assetsVerified=false）时显式返回 [ModelRouteResult.ModelUnavailable]。
     * 不靠文件名判断模型类型；各模型使用独立的资产路径、缓存目录和 SHA-256。
     *
     * @return 路由结果
     */
    fun resolveModelConfig(partId: String): ModelRouteResult {
        return when {
            partId.startsWith(PREFIX_WHITE) -> {
                val config = NanoDetModelContractExp23.CONFIG
                if (!config.assetsVerified) {
                    ModelRouteResult.ModelUnavailable(
                        route = ModelRoute.EXP23_WHITE,
                        reason = "exp23 白件模型资产未验证（T2 待核验）",
                    )
                } else {
                    ModelRouteResult.Success(
                        route = ModelRoute.EXP23_WHITE,
                        config = config,
                        classPolicy = Exp23WhiteClassPolicy,
                    )
                }
            }
            partId.startsWith(PREFIX_BLACK) -> {
                val config = NanoDetModelContractExp22.CONFIG
                if (!config.assetsVerified) {
                    ModelRouteResult.ModelUnavailable(
                        route = ModelRoute.EXP22_BLACK,
                        reason = "exp22 黑件模型资产未验证（T2 待核验）",
                    )
                } else {
                    ModelRouteResult.Success(
                        route = ModelRoute.EXP22_BLACK,
                        config = config,
                        classPolicy = Exp22BlackClassPolicy,
                    )
                }
            }
            else -> ModelRouteResult.Success(
                route = ModelRoute.EXP09,
                config = NanoDetModelContract.CONFIG,
                classPolicy = Exp09ClassPolicy,
            )
        }
    }
}

/** 模型路由结果。 */
sealed class ModelRouteResult {
    /** 路由成功。 */
    data class Success(
        val route: ModelRoute,
        val config: ModelConfig,
        val classPolicy: ModelClassPolicy,
    ) : ModelRouteResult()

    /** 模型不可用（资产缺失或校验失败）。 */
    data class ModelUnavailable(
        val route: ModelRoute,
        val reason: String,
    ) : ModelRouteResult()
}

/** 模型路由枚举。 */
enum class ModelRoute { EXP09, EXP22_BLACK, EXP23_WHITE }

/** 模型配置。 */
data class ModelConfig(
    val version: String,
    val paramSha256: String,
    val modelSha256: String,
    val assetParamPath: String,
    val assetModelPath: String,
    val modelDirName: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val classCount: Int,
    /** 资产是否已验证/接入。false 时推理应返回 MODEL_UNAVAILABLE。 */
    val assetsVerified: Boolean = true,
)

/**
 * 模型类别策略接口。
 *
 * 每个模型使用独立的类别映射和类别名称。
 */
interface ModelClassPolicy {
    /** ROI 目标类型到模型类别索引的映射。FEATURE/null 返回 null。 */
    fun classIndex(targetType: RoiTargetType?): Int?

    /** 模型类别索引到类别名称。 */
    fun className(index: Int): String

    /** 某个 ROI 目标类型是否被此模型支持（BOLT/NUTSERT 对 exp23 白件不支持）。 */
    fun isTargetSupported(targetType: RoiTargetType): Boolean

    /** 模型的类别名称数组。 */
    val classNames: Array<String>
}

// ── exp09：历史 4 类模型 ──

/** exp09 类别策略：NUT→0, THREAD→1, BOLT→2, NUTSERT→3。 */
object Exp09ClassPolicy : ModelClassPolicy {
    override val classNames = arrayOf("nut", "thread", "bolt", "nutsert")

    override fun classIndex(targetType: RoiTargetType?): Int? = when (targetType) {
        RoiTargetType.NUT -> 0
        RoiTargetType.THREAD -> 1
        RoiTargetType.BOLT -> 2
        RoiTargetType.NUTSERT -> 3
        RoiTargetType.FEATURE, null -> null
    }

    override fun className(index: Int): String = classNames[index]

    override fun isTargetSupported(targetType: RoiTargetType): Boolean =
        targetType != RoiTargetType.FEATURE
}

// ── exp22：黑件 4 类模型 ──

/** exp22 黑件类别策略：THREAD→0, NUTSERT→1, NUT→2, BOLT→3。 */
object Exp22BlackClassPolicy : ModelClassPolicy {
    override val classNames = arrayOf("Black Thread", "Black Nutsert", "Black Nut", "Black Bolt")

    override fun classIndex(targetType: RoiTargetType?): Int? = when (targetType) {
        RoiTargetType.THREAD -> 0
        RoiTargetType.NUTSERT -> 1
        RoiTargetType.NUT -> 2
        RoiTargetType.BOLT -> 3
        RoiTargetType.FEATURE, null -> null
    }

    override fun className(index: Int): String = classNames[index]

    override fun isTargetSupported(targetType: RoiTargetType): Boolean =
        targetType != RoiTargetType.FEATURE
}

// ── exp23：白件 2 类模型 ──

/** exp23 白件类别策略：NUT→0, THREAD→1；BOLT/NUTSERT 不支持。 */
object Exp23WhiteClassPolicy : ModelClassPolicy {
    override val classNames = arrayOf("White Nut", "White Thread")

    override fun classIndex(targetType: RoiTargetType?): Int? = when (targetType) {
        RoiTargetType.NUT -> 0
        RoiTargetType.THREAD -> 1
        RoiTargetType.FEATURE, null -> null
        // BOLT 和 NUTSERT 对白件模型不支持，返回 null（由 isTargetSupported 拦截）
        RoiTargetType.BOLT, RoiTargetType.NUTSERT -> null
    }

    override fun className(index: Int): String = classNames[index]

    override fun isTargetSupported(targetType: RoiTargetType): Boolean = when (targetType) {
        RoiTargetType.NUT, RoiTargetType.THREAD -> true
        RoiTargetType.BOLT, RoiTargetType.NUTSERT -> false
        RoiTargetType.FEATURE -> false
    }
}