package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AnchorControllerTest {
    private fun TestScope.controller() = NoteController(
        DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DEV, { }, backgroundScope, clock = { 0L }),
        measureText = { _, _ -> 6f },
    )

    private fun NoteController.click(x: Float, y: Float, at: Long) {
        pointerDown(x, y, at, null, toleranceMm = 1f, handleReachMm = 3f)
        pointerUp(x, y, at + 50, toleranceMm = 1f, pageWidthMm = 210f)
    }

    @Test
    fun theNameShowsAndCanBeClickedOnlyWhileTheAnchorIsSelected() = runTest {
        val c = controller()
        c.insert(listOf(AnchorDraft(20f, 40f, "Besprechung")))
        val anchor = c.session.state.value.anchors.single()
        val nameX = anchor.x + Anchor.HEIGHT_MM * 3
        val midY = anchor.y + Anchor.HEIGHT_MM / 2
        c.click(100f, 150f, at = 1000) // ends the selection of the new anchor
        assertThat(c.selection.value).isEmpty()

        // Not selected: only the flag is there.
        assertThat(c.elementAt(nameX, midY)).isNull()
        c.click(anchor.x + 1f, midY, at = 3000)
        assertThat(c.selection.value).containsExactly(anchor.id)
        assertThat(c.selectionFrame()!!.right).isGreaterThan(anchor.labelBounds.right)

        // Selected: a double click on the name renames it.
        var renamed: Anchor? = null
        c.editAnchor = { renamed = it }
        c.click(nameX, midY, at = 6000)
        c.click(nameX, midY, at = 6200)
        assertThat(renamed).isEqualTo(anchor)
    }

    @Test
    fun anAnchorInTextIsSelectedByAClickRenamedByARightClickAndFloats() = runTest {
        val c = controller()
        c.clickAt(20f, 40f, 210f)
        c.text.editing.value!!.controller.setContent(com.stemaker.nibsync.model.RichText.plain("Kapitel"))
        c.text.editing.value!!.controller.placeCursorAtStart()
        c.insert(listOf(AnchorDraft(0f, 0f, "Start")))
        c.text.finish()
        val anchor = c.session.state.value.anchors.single()
        val box = c.session.state.value.textBoxes.single()
        // The layout says the anchor's sign is at the box's top left.
        c.linkAt = { _, x, y -> if (x < box.x + 2f && y < box.y + 5f) box.text.paragraphs[0].marks.first().style else null }
        c.anchorPlace = { b, _ -> b.x to b.y }
        c.click(box.x + 1f, box.y + 2f, at = 1000)
        assertThat(c.text.editing.value!!.boxId).isEqualTo(box.id)
        assertThat(c.selectedTextAnchor()!!.anchorId).isEqualTo(anchor.anchorId)
        // A right click on it asks for a new name.
        c.text.finish()
        var renamed: Anchor? = null
        c.editAnchor = { renamed = it }
        c.secondaryClick(box.x + 1f, box.y + 2f)
        assertThat(renamed!!.anchorId).isEqualTo(anchor.anchorId)
        c.renameAnchor(renamed!!, "Anfang")
        assertThat(c.session.state.value.anchors.single().name).isEqualTo("Anfang")
        // Selected in the text again, it floats on the page where its sign was, selected.
        c.click(box.x + 1f, box.y + 2f, at = 3000)
        c.floatTextAnchor()
        val free = c.session.state.value.links.single() as Anchor
        assertThat(free.anchorId).isEqualTo(anchor.anchorId)
        assertThat(c.selection.value).containsExactly(free.id)
        assertThat(c.selectedAnchor(c.selection.value)).isEqualTo(free)
        // And back into the text from the selection bar.
        assertThat(c.canSetFloating(c.selection.value, false)).isTrue()
        c.setFloating(false)
        assertThat(c.session.state.value.links).isEmpty()
        assertThat(c.session.state.value.anchors.single().name).isEqualTo("Anfang")
    }
}
