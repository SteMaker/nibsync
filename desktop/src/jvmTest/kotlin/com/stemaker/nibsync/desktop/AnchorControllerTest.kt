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
}
