package de.nibsync.canvas

import androidx.lifecycle.ViewModel
import de.nibsync.document.DocumentSession
import de.nibsync.document.DocumentState
import de.nibsync.model.BrushFamily
import de.nibsync.model.ShapeRecognizer
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A recognised shape that was added, and the ids of the strokes it replaced. */
class CommittedShape(val stroke: Stroke, val replaced: Set<StrokeId>)

/**
 * Connects the canvas UI to a [DocumentSession]. The session is owned by the caller (it
 * outlives configuration changes and keeps writing after the screen is gone).
 */
class CanvasViewModel(
    private val session: DocumentSession,
    private val recognizer: ShapeRecognizer = ShapeRecognizer.None,
) : ViewModel() {

    val document: StateFlow<DocumentState> = session.state

    private val _tools = MutableStateFlow(ToolState())
    val tools: StateFlow<ToolState> = _tools.asStateFlow()

    fun selectPen(pen: BrushFamily) = _tools.update { it.withPen(pen) }

    fun selectColor(argb: Int) = _tools.update { it.withColor(argb) }

    fun selectSize(mm: Float) = _tools.update { it.withSize(mm) }

    fun setDrawWithFinger(enabled: Boolean) = _tools.update { it.copy(drawWithFinger = enabled) }

    /** Called on the UI thread when a stroke has been completed on the canvas. */
    fun commitStroke(draft: StrokeDraft): Stroke? {
        if (draft.points.isEmpty()) return null
        return session.addStroke(draft)
    }

    /**
     * Called when the pen is held still at the end of a stroke. Returns the clean shape that
     * was added instead of the stroke, or null if no shape was recognised (the stroke goes on).
     */
    fun commitShape(draft: StrokeDraft): CommittedShape? {
        if (draft.points.isEmpty()) return null
        val recent = session.state.value.strokes.filter { it.layer == draft.layer }.takeLast(RECENT_STROKES)
        val result = recognizer.recognize(draft, recent) ?: return null
        val stroke = session.replaceStrokes(result.replaces, result.replacement)
        return CommittedShape(stroke, result.replaces)
    }

    private companion object {
        /** How many of the latest strokes a multi-stroke shape (arrow head + shaft) may join. */
        const val RECENT_STROKES = 10
    }
}
