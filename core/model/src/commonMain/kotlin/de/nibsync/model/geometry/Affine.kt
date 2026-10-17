package de.nibsync.model.geometry

/**
 * 2D affine transform restricted to uniform scale plus translation, which is all the canvas
 * needs: `x' = scale * x + tx`, `y' = scale * y + ty`.
 */
data class ScaleTranslate(val scale: Float, val tx: Float, val ty: Float) {
    init {
        require(scale > 0f && scale.isFinite()) { "scale must be positive, was $scale" }
    }

    fun mapX(x: Float): Float = scale * x + tx
    fun mapY(y: Float): Float = scale * y + ty

    fun inverse(): ScaleTranslate = ScaleTranslate(1f / scale, -tx / scale, -ty / scale)

    fun mapRect(r: Rect): Rect = Rect(mapX(r.left), mapY(r.top), mapX(r.right), mapY(r.bottom))

    /** Row-major 3x3 matrix values, as used by `android.graphics.Matrix.setValues`. */
    fun toMatrixValues(): FloatArray = floatArrayOf(scale, 0f, tx, 0f, scale, ty, 0f, 0f, 1f)

    companion object {
        val IDENTITY = ScaleTranslate(1f, 0f, 0f)
    }
}
