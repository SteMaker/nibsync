package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.ClipContent
import com.stemaker.nibsync.document.select.selectionBoundsOf
import com.stemaker.nibsync.document.select.translated
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.NoteLink
import com.stemaker.nibsync.model.NoteLinkDraft
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteLinkTest {
    private val written = mutableListOf<DocumentOp>()

    private fun TestScope.session() =
        DocumentSession(DocumentState.empty(META), DEV_A, { written += it }, backgroundScope, clock = { 1234L })

    @Test
    fun movedAnchorKeepsItsIdAndACopyGetsANewOne() = runTest {
        val s = session()
        val anchor = s.replaceElements(emptySet(), listOf(AnchorDraft(10f, 20f, "Start"))).single() as Anchor
        assertNotEquals(anchor.id, anchor.anchorId)
        val moved = s.replaceElements(setOf(anchor.id), listOf(anchor.translated(0f, 30f))).single() as Anchor
        assertEquals(anchor.anchorId, moved.anchorId)
        assertEquals(50f, s.state.value.anchor(anchor.anchorId)!!.y)

        val copy = s.replaceElements(emptySet(), ClipContent.of(listOf(moved))!!.placedAt(100f, 100f)).single() as Anchor
        assertNotEquals(anchor.anchorId, copy.anchorId)
        assertEquals("Start", copy.name)
    }

    @Test
    fun linksAndAnchorsAreWrittenAsOneOpAndReplayed() = runTest {
        val s = session()
        val target = DocumentId("target")
        s.replaceElements(emptySet(), listOf(AnchorDraft(0f, 0f, "A"), NoteLinkDraft(5f, 5f, target, null, "Ziel", null)))
        s.flush()
        assertEquals(listOf(DocumentOp.AddLinks::class), written.map { it::class })
        val replayed = DocumentState.empty(META).applyAll(written)
        val link = assertIs<NoteLink>(replayed.elements.single { it is NoteLink })
        assertEquals(target, link.target)
        assertNull(link.anchorId)
        assertEquals("Ziel", link.label("Ziel"))
        assertEquals("Neu › A", link.copy(anchorName = "A").label("Neu"))
    }

    @Test
    fun anAnchorIsAFlagAndShowsItsNameOnlyWhenSelected() = runTest {
        val s = session()
        val anchor = s.replaceElements(emptySet(), listOf(AnchorDraft(10f, 20f, "Besprechung"))).single() as Anchor
        // 30 % smaller than a link, and only the flag when not selected.
        assertEquals(Anchor.HEIGHT_MM, anchor.bounds.height, 0.001f)
        assertEquals(Anchor.HEIGHT_MM, anchor.bounds.width, 0.001f)
        assertEquals(0.7f * com.stemaker.nibsync.model.LinkElement.HEIGHT_MM, Anchor.HEIGHT_MM, 0.001f)
        // Selected, the frame takes in the name.
        val frame = selectionBoundsOf(listOf(anchor))!!
        assertEquals(anchor.labelBounds, frame)
        assertTrue(frame.width > anchor.bounds.width * 3)
        assertEquals(anchor.bounds.height, frame.height, 0.001f)
    }
}
