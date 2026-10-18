package com.stemaker.nibsync.model.geometry

/** Axis-aligned rectangle. Coordinates follow screen convention: y grows downwards. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: Rect): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}
