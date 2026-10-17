package de.nibsync.document

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentSessionTest {
    private val written = mutableListOf<DocumentOp>()

    private fun TestScope.session(initial: DocumentState = DocumentState.empty(META)) =
        DocumentSession(initial, DEV_A, { written += it }, backgroundScope, clock = { 1234L })

    @Test
    fun addStrokeUpdatesStateSynchronously() = runTest {
        val s = session()
        val stroke = s.addStroke(draft())
        assertEquals(listOf(stroke), s.state.value.strokes)
        assertEquals(DEV_A, stroke.author)
        assertEquals(1234L, stroke.createdAtEpochMs)
    }

    @Test
    fun opsAreJournaledInOrder() = runTest {
        val s = session()
        val a = s.addStroke(draft())
        val b = s.addStroke(draft(y = 5f))
        s.removeStrokes(listOf(a.id))
        s.flush()
        assertEquals(3, written.size)
        assertEquals(listOf(1L, 2L, 3L), written.map { it.stamp.counter })
        assertEquals(listOf(b), s.state.value.strokes)
        // Replaying the journal reproduces the state.
        assertEquals(s.state.value.strokes, DocumentState.empty(META).applyAll(written).strokes)
    }

    @Test
    fun countersContinueAfterLoadedState() = runTest {
        val loaded = DocumentState.empty(META).apply(add(41, DEV_B, stroke("remote", author = DEV_B)))
        val s = session(loaded)
        s.addStroke(draft())
        s.flush()
        assertEquals(42L, written.single().stamp.counter)
    }

    @Test
    fun undoAndRedoOfAdd() = runTest {
        val s = session()
        val stroke = s.addStroke(draft())
        assertTrue(s.canUndo.value)
        assertTrue(s.undo())
        assertEquals(emptyList(), s.state.value.strokes)
        assertTrue(s.canRedo.value)
        assertTrue(s.redo())
        val restored = s.state.value.strokes.single()
        // Restored as a copy: same content, fresh id (removed ids stay removed).
        assertNotEquals(stroke.id, restored.id)
        assertEquals(stroke.points, restored.points)
    }

    @Test
    fun undoOfRemoveRestoresCopy() = runTest {
        val s = session()
        val stroke = s.addStroke(draft())
        s.removeStrokes(listOf(stroke.id))
        assertTrue(s.undo())
        assertEquals(stroke.points, s.state.value.strokes.single().points)
        assertTrue(s.undo())
        assertEquals(emptyList(), s.state.value.strokes)
        assertFalse(s.undo())
    }

    @Test
    fun newEditClearsRedo() = runTest {
        val s = session()
        s.addStroke(draft())
        s.undo()
        s.addStroke(draft())
        assertFalse(s.canRedo.value)
        assertFalse(s.redo())
    }

    @Test
    fun undoIsJournaledToo() = runTest {
        val s = session()
        s.addStroke(draft())
        s.undo()
        s.flush()
        assertEquals(2, written.size)
        assertEquals(0, DocumentState.empty(META).applyAll(written).strokeCount)
    }

    @Test
    fun remoteOpsMergeButAreNotJournaled() = runTest {
        val s = session()
        s.addStroke(draft())
        s.applyRemote(listOf(add(5, DEV_B, stroke("remote", author = DEV_B))))
        s.flush()
        assertEquals(2, s.state.value.strokeCount)
        assertEquals(1, written.size)
        s.addStroke(draft())
        s.flush()
        assertEquals(6L, written.last().stamp.counter)
    }

    @Test
    fun removingUnknownStrokesDoesNothing() = runTest {
        val s = session()
        s.removeStrokes(listOf(de.nibsync.model.StrokeId("nope")))
        s.flush()
        assertEquals(0, written.size)
        assertFalse(s.canUndo.value)
    }

    @Test
    fun journalingRunsOnTheGivenScope() = runTest(StandardTestDispatcher()) {
        val s = session()
        s.addStroke(draft())
        assertEquals(0, written.size) // not yet: the writer coroutine has not run
        s.flush()
        assertEquals(1, written.size)
    }
}

class UndoManagerTest {
    @Test
    fun depthIsLimited() {
        val m = UndoManager(de.nibsync.model.IdGenerator(), maxDepth = 2)
        repeat(3) { m.record(Edit.Added(listOf(stroke("s$it")))) }
        assertTrue(m.undo() != null)
        assertTrue(m.undo() != null)
        assertEquals(null, m.undo())
    }
}

class UndoChainTest {
    @Test
    fun longUndoRedoChainsStayConsistent() = runTest {
        val written = mutableListOf<DocumentOp>()
        val s = DocumentSession(DocumentState.empty(META), DEV_A, { written += it }, backgroundScope, clock = { 0L })
        val a = s.addStroke(draft(y = 1f))
        s.addStroke(draft(y = 2f))
        s.removeStrokes(listOf(a.id))
        repeat(3) { assertTrue(s.undo()) }
        assertEquals(0, s.state.value.strokeCount)
        repeat(3) { assertTrue(s.redo()) }
        assertEquals(listOf(2f), s.state.value.strokes.map { it.points.first().y })
        repeat(2) { assertTrue(s.undo()) }
        assertEquals(listOf(1f), s.state.value.strokes.map { it.points.first().y })
    }
}

class ReplaceStrokesTest {
    @Test
    fun replaceIsOneUndoStep() = runTest {
        val written = mutableListOf<DocumentOp>()
        val s = DocumentSession(DocumentState.empty(META), DEV_A, { written += it }, backgroundScope, clock = { 0L })
        val shaft = s.addStroke(draft(y = 1f))
        val merged = s.replaceStrokes(listOf(shaft.id), draft(y = 9f))
        assertEquals(listOf(merged), s.state.value.strokes)

        assertTrue(s.undo())
        assertEquals(listOf(1f), s.state.value.strokes.map { it.points.first().y })
        assertTrue(s.redo())
        assertEquals(listOf(9f), s.state.value.strokes.map { it.points.first().y })
        assertTrue(s.undo())
        assertTrue(s.undo())
        assertEquals(0, s.state.value.strokeCount)

        s.flush()
        assertEquals(s.state.value.strokes, DocumentState.empty(META).applyAll(written).strokes)
    }

    @Test
    fun replacingMissingStrokesJustAdds() = runTest {
        val s = DocumentSession(DocumentState.empty(META), DEV_A, {}, backgroundScope, clock = { 0L })
        s.replaceStrokes(listOf(de.nibsync.model.StrokeId("gone")), draft())
        assertEquals(1, s.state.value.strokeCount)
        assertTrue(s.undo())
        assertEquals(0, s.state.value.strokeCount)
    }
}

class ClosedSessionTest {
    @Test
    fun closedSessionIgnoresFlushAndFurtherWrites() = runTest {
        val written = mutableListOf<DocumentOp>()
        val s = DocumentSession(DocumentState.empty(META), DEV_A, { written += it }, backgroundScope, clock = { 0L })
        s.addStroke(draft())
        s.flush()
        s.close()
        s.flush() // must not throw
        s.addStroke(draft()) // e.g. drawing into a document that was just deleted
        s.flush()
        assertEquals(1, written.size)
    }
}
