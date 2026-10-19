package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.withId

/**
 * A locally performed change, recorded so it can be undone. Undo/redo never revives removed
 * stroke ids (see [DocumentState]); restoring a stroke re-adds a copy with a fresh id.
 */
sealed interface Edit {
    data class Added(val strokes: List<PageElement>) : Edit
    data class Removed(val strokes: List<PageElement>) : Edit

    /** [removed] were replaced by [added] in one step (e.g. two strokes merged into one shape). */
    data class Replaced(val removed: List<PageElement>, val added: List<PageElement>) : Edit

    /** A to-do task was ticked off or reopened ([done]); it was [was] before. */
    data class TodoDone(val taskId: StrokeId, val done: Boolean, val was: Boolean) : Edit
}

/**
 * Undo/redo history of the local user. Only local edits are recorded; changes arriving from
 * other devices are not undoable here.
 */
class UndoManager(private val ids: IdGenerator, private val maxDepth: Int = 200) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /**
     * Restoring removed strokes gives them new ids, so older history entries that still refer to
     * the old id are resolved through this map (old id -> restored copy).
     */
    private val restoredAs = mutableMapOf<StrokeId, PageElement>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Pops the latest edit and returns the edit that reverts it, or null if there is nothing to
     * undo. Strokes that were removed meanwhile (e.g. by another device) are skipped by the caller.
     */
    fun undo(): Edit? {
        val edit = undoStack.removeLastOrNull() ?: return null
        val inverse = invert(edit)
        redoStack.addLast(inverse)
        return inverse
    }

    fun redo(): Edit? {
        val edit = redoStack.removeLastOrNull() ?: return null
        val inverse = invert(edit)
        undoStack.addLast(inverse)
        return inverse
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        restoredAs.clear()
    }

    private fun current(stroke: PageElement): PageElement {
        var s = stroke
        while (true) s = restoredAs[s.id] ?: return s
    }

    private fun invert(edit: Edit): Edit = when (edit) {
        is Edit.Added -> Edit.Removed(edit.strokes.map(::current))
        is Edit.Removed -> Edit.Added(restore(edit.strokes))
        is Edit.Replaced -> Edit.Replaced(removed = edit.added.map(::current), added = restore(edit.removed))
        is Edit.TodoDone -> Edit.TodoDone(edit.taskId, done = edit.was, was = edit.done)
    }

    private fun restore(strokes: List<PageElement>): List<PageElement> = strokes.map(::current).map { old ->
        old.withId(ids.newStrokeId()).also { restoredAs[old.id] = it }
    }
}
