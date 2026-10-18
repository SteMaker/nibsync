package com.stemaker.nibsync.document.erase

import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokeId

enum class EraserMode {
    /** Removes every stroke the eraser touches. */
    OBJECT,

    /** Removes only the touched parts of strokes, splitting them into pieces. */
    PIXEL,
}

/** Outcome of an erase gesture: strokes to remove and pieces to add in their place. */
data class EraseResult(val removed: Set<StrokeId>, val added: List<StrokeDraft>) {
    val isEmpty: Boolean get() = removed.isEmpty()
}

/**
 * One eraser movement from pen down to pen up. Feed positions with [moveTo]; [visible] is what
 * the page should show meanwhile, [result] what to store at the end. Platform-neutral so every
 * client erases the same way.
 *
 * @param strokes the document's strokes when the gesture starts, back to front.
 * @param pieceId creates ids for the temporary piece strokes shown during the gesture.
 */
class EraseGesture(
    private val mode: EraserMode,
    private val radiusMm: Float,
    strokes: List<Stroke>,
    private val pieceId: () -> StrokeId,
) {
    private sealed interface State {
        data object Untouched : State
        data object Removed : State
        class Cut(val pieces: List<Stroke>) : State
    }

    private val originals = strokes
    private val states = HashMap<StrokeId, State>()
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    /** True if anything was erased so far. */
    val hasChanges: Boolean get() = states.isNotEmpty()

    /** Strokes to show while erasing, back to front (pieces take the place of their stroke). */
    var visible: List<Stroke> = strokes
        private set

    fun moveTo(x: Float, y: Float) {
        val ax = if (lastX.isNaN()) x else lastX
        val ay = if (lastY.isNaN()) y else lastY
        lastX = x
        lastY = y
        val box = EraseGeometry.segmentBox(ax, ay, x, y)
        var changed = false
        for (stroke in originals) {
            val state = states[stroke.id] ?: State.Untouched
            if (state == State.Removed) continue
            val reach = StrokeCutter.reach(stroke, radiusMm)
            val b = stroke.bounds
            if (!EraseGeometry.boxesOverlap(b.left, b.top, b.right, b.bottom, radiusMm, box[0], box[1], box[2], box[3])) continue
            when (mode) {
                EraserMode.OBJECT -> if (StrokeCutter.touches(stroke.points, reach, ax, ay, x, y)) {
                    states[stroke.id] = State.Removed
                    changed = true
                }
                EraserMode.PIXEL -> {
                    val current = if (state is State.Cut) state.pieces else listOf(stroke)
                    var touched = false
                    val next = current.flatMap { piece ->
                        val cut = StrokeCutter.cut(piece.points, reach, ax, ay, x, y)
                        if (cut == null) listOf(piece) else {
                            touched = true
                            cut.map { points -> piece.copy(id = pieceId(), points = points) }
                        }
                    }
                    if (touched) {
                        states[stroke.id] = if (next.isEmpty()) State.Removed else State.Cut(next)
                        changed = true
                    }
                }
            }
        }
        if (changed) visible = originals.flatMap { s ->
            when (val st = states[s.id]) {
                null, State.Untouched -> listOf(s)
                State.Removed -> emptyList()
                is State.Cut -> st.pieces
            }
        }
    }

    fun result(): EraseResult {
        val byId = originals.associateBy { it.id }
        val added = states.flatMap { (id, st) ->
            if (st is State.Cut) st.pieces.map { StrokeDraft(byId.getValue(id).brush, it.tool, it.points, it.layer) } else emptyList()
        }
        return EraseResult(states.keys.toSet(), added)
    }
}
