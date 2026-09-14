package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.ui.screens.ContentRectBounds

data class MappedNanoDetBox(
    val roiBox: NanoDetBox,
    val imageBox: NanoDetBox
)

object NanoDetCoordinateMapper {
    /** Clamp a crop-relative detector box to its ROI, then offset it into upright photo pixels. */
    fun mapRoiBoxToPhoto(
        box: NanoDetBox,
        roiBounds: ContentRectBounds,
        imageWidth: Int,
        imageHeight: Int
    ): MappedNanoDetBox? {
        require(imageWidth > 0 && imageHeight > 0)
        require(roiBounds.left >= 0 && roiBounds.top >= 0 && roiBounds.right <= imageWidth && roiBounds.bottom <= imageHeight)
        if (roiBounds.width <= 0 || roiBounds.height <= 0) return null

        val roiBox = NanoDetBox(
            left = box.left.coerceIn(0.0, roiBounds.width.toDouble()),
            top = box.top.coerceIn(0.0, roiBounds.height.toDouble()),
            right = box.right.coerceIn(0.0, roiBounds.width.toDouble()),
            bottom = box.bottom.coerceIn(0.0, roiBounds.height.toDouble())
        )
        if (roiBox.right <= roiBox.left || roiBox.bottom <= roiBox.top) return null
        val imageBox = NanoDetBox(
            left = (roiBounds.left + roiBox.left).coerceIn(0.0, imageWidth.toDouble()),
            top = (roiBounds.top + roiBox.top).coerceIn(0.0, imageHeight.toDouble()),
            right = (roiBounds.left + roiBox.right).coerceIn(0.0, imageWidth.toDouble()),
            bottom = (roiBounds.top + roiBox.bottom).coerceIn(0.0, imageHeight.toDouble())
        )
        if (imageBox.right <= imageBox.left || imageBox.bottom <= imageBox.top) return null
        return MappedNanoDetBox(roiBox, imageBox)
    }
}
