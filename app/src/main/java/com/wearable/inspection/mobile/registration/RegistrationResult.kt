package com.wearable.inspection.mobile.registration

/**
 * 单张照片配准结果。
 *
 * 成功时包含 Homography 矩阵和投影后的模板 ROI 四角坐标。
 * 失败时 [status] 为 [RegistrationStatus.FAILED] 或 [RegistrationStatus.FALLBACK_FULL_IMAGE]，
 * [projectedRoiCorners] 必须为 null —— 配准不可靠时禁止返回看似有效的错误坐标。
 */
data class RegistrationResult(
    val status: RegistrationStatus,
    /** 3×3 Homography 矩阵（行主序 DoubleArray，长度 9）。成功时非 null，失败时 null。 */
    val homography: DoubleArray?,
    /**
     * 模板 ROI 四角经 Homography 投影到采集照片坐标系后的四边形。
     * 顺序：[左上, 右上, 右下, 左下]。成功时非 null，失败时 null。
     */
    val projectedRoiCorners: List<ProjectedPoint>?,
    val inlierCount: Int,
    val inlierRatio: Double,
    val reprojectionError: Double,
    /** 空间覆盖率：inlier 凸包面积 / inlier bbox 面积 */
    val spatialCoverage: Double,
    /** 投影四边形是否合法（凸、面积合理、无 NaN/Inf、在图像边界内） */
    val quadrilateralValid: Boolean,
    val matcherName: String,
    val matcherVersion: String,
    /** 失败时的可解释原因；成功时为 null */
    val failureReason: String?,
) {
    val isSuccess: Boolean get() = status == RegistrationStatus.SUCCESS

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegistrationResult) return false
        return status == other.status &&
                homography.contentEquals(other.homography) &&
                projectedRoiCorners == other.projectedRoiCorners &&
                inlierCount == other.inlierCount &&
                inlierRatio == other.inlierRatio &&
                reprojectionError == other.reprojectionError &&
                spatialCoverage == other.spatialCoverage &&
                quadrilateralValid == other.quadrilateralValid &&
                matcherName == other.matcherName &&
                matcherVersion == other.matcherVersion &&
                failureReason == other.failureReason
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + (homography?.contentHashCode() ?: 0)
        result = 31 * result + (projectedRoiCorners?.hashCode() ?: 0)
        result = 31 * result + inlierCount
        result = 31 * result + inlierRatio.hashCode()
        result = 31 * result + reprojectionError.hashCode()
        result = 31 * result + spatialCoverage.hashCode()
        result = 31 * result + quadrilateralValid.hashCode()
        result = 31 * result + matcherName.hashCode()
        result = 31 * result + matcherVersion.hashCode()
        result = 31 * result + (failureReason?.hashCode() ?: 0)
        return result
    }
}

enum class RegistrationStatus {
    /** 配准成功，投影坐标可用 */
    SUCCESS,
    /** 配准失败，建议使用整图检测 */
    FALLBACK_FULL_IMAGE,
    /** 配准失败，无可用建议 */
    FAILED,
}

/** 二维点（像素坐标） */
data class ProjectedPoint(val x: Double, val y: Double)
