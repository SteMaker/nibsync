package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.translated
import com.stemaker.nibsync.model.PageElement
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
    fun backgroundChangeIsJournaledButNotUndone() = runTest {
        val s = session()
        val grid = com.stemaker.nibsync.model.BackgroundSpec(com.stemaker.nibsync.model.BackgroundKind.GRID, 5f)
        s.addStroke(draft())
        s.setBackground(grid)
        s.setBackground(grid) // unchanged: no second op
        s.flush()
        assertEquals(grid, s.state.value.background)
        assertEquals(DocumentOp.SetBackground(OpStamp(2, DEV_A), grid), written.last())
        assertEquals(2, written.size)
        // Undo reverts the stroke, not the paper.
        assertTrue(s.undo())
        assertEquals(grid, s.state.value.background)
        assertEquals(0, s.state.value.strokeCount)
    }

    @Test
    fun textBoxesAreAddedEditedAndUndone() = runTest {
        val s = session()
        val box = s.addTextBox(textDraft(text = "Hallo").copy(x = 10.123456f))
        assertEquals(10.12f, box.x) // on the storage grid
        val edited = s.replaceElements(listOf(box.id), listOf(textDraft(text = "Hallo Welt"))).single()
        assertEquals(listOf("Hallo Welt"), s.state.value.textBoxes.map { it.text.plainText })
        s.flush()
        assertEquals(
            listOf(DocumentOp.AddTextBoxes::class, DocumentOp.RemoveStrokes::class, DocumentOp.AddTextBoxes::class),
            written.map { it::class },
        )
        // Undo restores the earlier text (as a copy with a new id), redo the edit.
        assertTrue(s.undo())
        val restored = s.state.value.textBoxes.single()
        assertEquals("Hallo", restored.text.plainText)
        assertNotEquals(box.id, restored.id)
        assertTrue(s.redo())
        assertEquals("Hallo Welt", s.state.value.textBoxes.single().text.plainText)
        assertFalse(edited.id in s.state.value) // redo re-adds a copy, too
        assertTrue(s.undo())
        assertTrue(s.undo())
        assertEquals(emptyList(), s.state.value.elements)
    }

    @Test
    fun picturesAndLinksAreAddedAsFileOps() = runTest {
        val s = session()
        val asset = com.stemaker.nibsync.model.Asset("a".repeat(64), com.stemaker.nibsync.model.Asset.PNG)
        val added = s.replaceElements(
            emptySet(),
            listOf(
                com.stemaker.nibsync.model.ImageDraft(10f, 10f, 40f, 30f, asset),
                com.stemaker.nibsync.model.FileLinkDraft(10f, 50f, 60f, 12f, asset.copy(mimeType = com.stemaker.nibsync.model.Asset.PDF), "a.pdf"),
            ),
        )
        s.flush()
        assertEquals(listOf(DocumentOp.AddFiles::class), written.map { it::class })
        assertEquals(added, s.state.value.files)
        // Pictures lie behind highlighter and ink, links with the text.
        assertEquals(com.stemaker.nibsync.model.Layer.IMAGE, added[0].layer)
        assertEquals(com.stemaker.nibsync.model.Layer.TEXT, added[1].layer)
        assertTrue(s.undo())
        assertEquals(emptyList(), s.state.value.files)
    }

    @Test
    fun mixedSelectionMovesInOneStep() = runTest {
        val s = session()
        val stroke = s.addStroke(draft())
        val box = s.addTextBox(textDraft())
        val moved = s.replaceElements(listOf(stroke.id, box.id), listOf<PageElement>(stroke, box).map { it.translated(0f, 20f) })
        assertEquals(2, moved.size)
        assertEquals(20f, s.state.value.textBoxes.single().y)
        assertEquals(1, s.state.value.strokeCount)
        assertTrue(s.undo())
        assertEquals(0f, s.state.value.textBoxes.single().y)
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
        s.removeStrokes(listOf(com.stemaker.nibsync.model.StrokeId("nope")))
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
        val m = UndoManager(com.stemaker.nibsync.model.IdGenerator(), maxDepth = 2)
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
        s.replaceStrokes(listOf(com.stemaker.nibsync.model.StrokeId("gone")), draft())
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
