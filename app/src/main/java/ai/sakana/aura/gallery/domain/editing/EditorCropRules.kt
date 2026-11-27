package ai.sakana.aura.gallery.domain.editing

/** Integer bounds for a centered crop rectangle in source bitmap coordinates. */
data class CropBounds(val left: Int, val top: Int, val width: Int, val height: Int)

/** Deterministic crop geometry kept separate from Android bitmap APIs so it can be unit tested. */
object EditorCropRules {
    fun centerCrop(sourceWidth: Int, sourceHeight: Int, targetAspectRatio: Float): CropBounds? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || !targetAspectRatio.isFinite() || targetAspectRatio <= 0f) return null

        val sourceAspectRatio = sourceWidth.toFloat() / sourceHeight
        return if (sourceAspectRatio > targetAspectRatio) {
            val cropWidth = (sourceHeight * targetAspectRatio).toInt().coerceIn(1, sourceWidth)
            CropBounds(
                left = (sourceWidth - cropWidth) / 2,
                top = 0,
                width = cropWidth,
                height = sourceHeight,
            )
        } else {
            val cropHeight = (sourceWidth / targetAspectRatio).toInt().coerceIn(1, sourceHeight)
            CropBounds(
                left = 0,
                top = (sourceHeight - cropHeight) / 2,
                width = sourceWidth,
                height = cropHeight,
            )
        }
    }

    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
