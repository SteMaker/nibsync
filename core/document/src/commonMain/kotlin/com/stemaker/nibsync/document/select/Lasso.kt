package com.stemaker.nibsync.document.select

import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.toDraft
import com.stemaker.nibsync.model.geometry.Rect

/** Freehand selection of page elements. Platform-neutral so every client selects the same way. */
object Lasso {
    /**
     * A stroke is selected when at least this share of its points lies inside the lasso; other
     * (rectangular) elements when this share of a grid of sample points over their area does.
     */
    const val INSIDE_SHARE = 0.5f

    /** Sample points per side of a rectangular element. */
    private const val BOX_SAMPLES = 5

    /** Lassos with a smaller area (mm²) select nothing; they are taps. */
    const val MIN_AREA_MM2 = 4f

    /**
     * Ids of the strokes enclosed by the lasso [polygon] (x0, y0, x1, y1, ... in mm; it is
     * closed implicitly).
     */
    fun select(strokes: List<PageElement>, polygon: FloatArray): Set<StrokeId> {
        if (polygon.size < 6 || area(polygon) < MIN_AREA_MM2) return emptySet()
        val box = bounds(polygon)
        return strokes.filter { e -> e.bounds.intersects(box) && insideShare(e, polygon) >= INSIDE_SHARE }
            .mapTo(LinkedHashSet()) { it.id }
    }

    private fun insideShare(e: PageElement, polygon: FloatArray): Float = when (e) {
        is Stroke -> e.points.count { contains(polygon, it.x, it.y) }.toFloat() / e.points.size
        else -> {
            val b = e.bounds
            var inside = 0
            for (i in 0 until BOX_SAMPLES) for (j in 0 until BOX_SAMPLES) {
                val x = b.left + b.width * (i + 0.5f) / BOX_SAMPLES
                val y = b.top + b.height * (j + 0.5f) / BOX_SAMPLES
                if (contains(polygon, x, y)) inside++
            }
            inside.toFloat() / (BOX_SAMPLES * BOX_SAMPLES)
        }
    }

    /** Even-odd point-in-polygon test. */
    fun contains(polygon: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = polygon.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[2 * i]
            val yi = polygon[2 * i + 1]
            val xj = polygon[2 * j]
            val yj = polygon[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Absolute area by the shoelace formula. */
    fun area(polygon: FloatArray): Float {
        val n = polygon.size / 2
        var sum = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += polygon[2 * i] * polygon[2 * j + 1] - polygon[2 * j] * polygon[2 * i + 1]
        }
        return kotlin.math.abs(sum) / 2
    }

    private fun bounds(polygon: FloatArray): Rect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in polygon.indices step 2) {
            l = minOf(l, polygon[i]); r = maxOf(r, polygon[i])
            t = minOf(t, polygon[i + 1]); b = maxOf(b, polygon[i + 1])
        }
        return Rect(l, t, r, b)
    }
}

/** Union of the bounds of [strokes], or null if there are none. */
fun boundsOf(strokes: Collection<PageElement>): Rect? {
    if (strokes.isEmpty()) return null
    return strokes.map { it.bounds }.reduce { a, b ->
        Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }
}

/** The stroke moved by ([dx], [dy]) mm, as a draft for a new stroke. */
fun Stroke.translated(dx: Float, dy: Float): StrokeDraft =
    StrokeDraft(brush, tool, points.map { it.copy(x = it.x + dx, y = it.y + dy) }, layer)

/** The element moved by ([dx], [dy]) mm, as a draft for a new element. */
fun PageElement.translated(dx: Float, dy: Float): ElementDraft = toDraft().translated(dx, dy)

/**
 * Copied elements, kept relative to the centre of their bounds so they can be pasted anywhere.
 */
class ClipContent private constructor(val drafts: List<ElementDraft>, val width: Float, val height: Float) {

    /** The copied elements centred at ([cx], [cy]). */
    fun placedAt(cx: Float, cy: Float): List<ElementDraft> = drafts.map { it.translated(cx, cy) }

    companion object {
        fun of(strokes: List<PageElement>): ClipContent? {
            val b = boundsOf(strokes) ?: return null
            val cx = (b.left + b.right) / 2
            val cy = (b.top + b.bottom) / 2
            return ClipContent(strokes.map { it.translated(-cx, -cy) }, b.width, b.height)
        }
    }
}
