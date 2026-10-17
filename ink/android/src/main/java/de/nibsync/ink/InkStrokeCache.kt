package de.nibsync.ink

import de.nibsync.model.Stroke
import de.nibsync.model.StrokeId
import androidx.ink.strokes.Stroke as InkStroke

/**
 * Keeps the Ink representation (with its generated mesh) of every displayed stroke, so meshes
 * are built once and not on every frame. Domain strokes are immutable, so the id is a safe key.
 */
class InkStrokeCache(private val convert: (Stroke) -> InkStroke = StrokeConverter::toInk) {
    private val cache = HashMap<StrokeId, InkStroke>()

    val size: Int get() = cache.size

    operator fun get(stroke: Stroke): InkStroke = cache.getOrPut(stroke.id) { convert(stroke) }

    /** Registers an Ink stroke that already exists (e.g. just finished drawing) to avoid rebuilding it. */
    fun put(id: StrokeId, ink: InkStroke) {
        cache[id] = ink
    }

    /** Drops entries of strokes that are no longer displayed. */
    fun retainOnly(ids: Set<StrokeId>) {
        cache.keys.retainAll(ids)
    }

    fun clear() = cache.clear()
}
