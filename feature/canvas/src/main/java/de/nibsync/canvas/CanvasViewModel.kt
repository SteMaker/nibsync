package de.nibsync.canvas

import androidx.lifecycle.ViewModel
import de.nibsync.document.DocumentSession
import de.nibsync.document.DocumentState
import de.nibsync.document.erase.EraseResult
import de.nibsync.document.erase.EraserMode
import de.nibsync.model.BrushFamily
import de.nibsync.model.ShapeRecognizer
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Strokes just stored by an edit (shape, erasing) and the ids of the strokes they replaced. */
class CommittedEdit(val added: List<Stroke>, val removed: Set<StrokeId>)

/**
 * Connects the canvas UI to a [DocumentSession]. The session is owned by the caller (it
 * outlives configuration changes and keeps writing after the screen is gone).
 */
class CanvasViewModel(
    private val session: DocumentSession,
    private val recognizer: ShapeRecognizer = ShapeRecognizer.None,
) : ViewModel() {

    val document: StateFlow<DocumentState> = session.state

    val canUndo: StateFlow<Boolean> = session.canUndo
    val canRedo: StateFlow<Boolean> = session.canRedo

    fun undo(): Boolean = session.undo()

    fun redo(): Boolean = session.redo()

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
    fun commitShape(draft: StrokeDraft): CommittedEdit? {
        if (draft.points.isEmpty()) return null
        val recent = session.state.value.strokes.filter { it.layer == draft.layer }.takeLast(RECENT_STROKES)
        val result = recognizer.recognize(draft, recent) ?: return null
        val stroke = session.replaceStrokes(result.replaces, result.replacement)
        return CommittedEdit(listOf(stroke), result.replaces)
    }

    fun selectEraser(mode: EraserMode) = _tools.update { it.withEraser(mode) }

    fun selectEraserSize(mm: Float) = _tools.update { it.withEraserSize(mm) }

    /** Stores the outcome of an erase gesture as one undo step. */
    fun commitErase(result: EraseResult): CommittedEdit? {
        if (result.isEmpty) return null
        val added = session.replaceStrokes(result.removed, result.added)
        return CommittedEdit(added, result.removed)
    }

    private companion object {
        /** How many of the latest strokes a multi-stroke shape (arrow head + shaft) may join. */
        const val RECENT_STROKES = 10
    }
}
