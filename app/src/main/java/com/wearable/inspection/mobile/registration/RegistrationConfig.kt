package com.wearable.inspection.mobile.registration

/**
 * 配准引擎集中配置。所有质量门禁阈值在此定义，不得散落在调用方。
 *
 * 阈值基于旧工程 V4 经验和静态照片场景调整。
 * 后续使用真实金属零件数据校准时修改此处即可。
 */
object RegistrationConfig {

    // ---- AKAZE 特征提取 ----
    /** AKAZE 描述子类型：M-LDB（二进制，配合 NORM_HAMMING） */
    const val AKAZE_DESCRIPTOR_TYPE = 1 // DESCRIPTOR_MLDB
    /** AKAZE 描述子大小：0 = 默认（M-LDB 61bit） */
    const val AKAZE_DESCRIPTOR_SIZE = 0
    /** AKAZE 描述子通道数：3 = 彩色 M-LDB */
    const val AKAZE_DESCRIPTOR_CHANNELS = 3
    /** AKAZE 响应阈值（越小特征越多） */
    const val AKAZE_THRESHOLD = 0.002f
    /**
     * AKAZE 特征提取分辨率上限（长边像素）。
     *
     * AKAZE 非线性尺度空间按输入分辨率分配 native 内存（每个 evolution 层约 4 个
     * CV_32F 全尺寸缓冲）。48MP（8000x6000）原图直接检测会分配约 4~5GB，
     * 触发系统 OOM kill（SEA-AL10 实测 PSS 5~6GB 后进程死亡，无 Java/native 崩溃标记）。
     * 检测阶段降采样到该上限后，关键点坐标换算回原图坐标系；
     * 匹配、RANSAC、质量门禁与 ROI 投影全部保持原图坐标与原阈值语义，本参数不改变
     * Lowe 比率、RANSAC 阈值或任何门禁阈值的物理含义。
     */
    const val AKAZE_MAX_WORKING_SIDE = 2048
    /** AKAZE 特征数上限：按 response 降序截断 */
    const val AKAZE_MAX_FEATURES = 1000
    /** 最少模板特征点数 */
    const val MIN_TEMPLATE_KEYPOINTS = 15
    /** 最少场景特征点数 */
    const val MIN_SCENE_KEYPOINTS = 15

    // ---- Lowe Ratio ----
    const val LOWE_RATIO = 0.80f
    /** 最少 good matches 数量（Lowe 后） */
    const val MIN_GOOD_MATCHES = 10

    // ---- GMS 网格滤波 ----
    /** GMS 网格数（G×G） */
    const val GMS_GRID_SIZE = 20
    /** GMS 支持度阈值 */
    const val GMS_SUPPORT_THRESHOLD = 6
    /** 匹配数不足该值时跳过 GMS 直接 RANSAC */
    const val GMS_MIN_MATCHES_TO_APPLY = 24

    // ---- Homography 估计 ----
    /** RANSAC 重投影阈值 */
    const val RANSAC_THRESHOLD = 8.0
    /** USAC_MAGSAC 最大迭代次数 */
    const val USAC_MAX_ITERS = 500
    /** USAC_MAGSAC 置信度 */
    const val USAC_CONFIDENCE = 0.995

    // ---- 质量门禁 ----
    /** 最少内点数 */
    const val MIN_INLIER_COUNT = 10
    /** 最小内点比例（内点数 / good matches 数） */
    const val MIN_INLIER_RATIO = 0.30
    /** 最大中位重投影误差（像素） */
    const val MAX_MEDIAN_REPROJECTION_ERROR = 8.0
    /** 最小空间覆盖率（inlier 凸包面积 / inlier bbox 面积） */
    const val MIN_SPATIAL_COVERAGE = 0.15
    /** 最小投影面积占图像面积比 */
    const val MIN_PROJECTED_AREA_RATIO = 0.005
    /** 最大投影面积占图像面积比 */
    const val MAX_PROJECTED_AREA_RATIO = 0.95
    /** 投影点允许超出图像边界的容差（像素） */
    const val BOUNDARY_MARGIN_PX = 50.0

    // ---- 引擎信息 ----
    const val MATCHER_NAME = "AKAZE"
    const val MATCHER_VERSION = "1.0.0-opencv-4.10.0"
}
