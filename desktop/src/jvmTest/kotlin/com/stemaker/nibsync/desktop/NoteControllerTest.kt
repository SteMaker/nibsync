package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.RichText
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NoteControllerTest {
    private fun TestScope.controller() = NoteController(
        DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DEV, { }, backgroundScope, clock = { 0L }),
        measureText = { _, _ -> 6f },
    )

    private fun NoteController.type(text: String) = this.text.editing.value!!.controller.setContent(RichText.plain(text))

    @Test
    fun aPictureInsertedAtTheCursorIsAParagraphTheCursorGoesThrough() = runTest {
        val c = controller()
        c.clickAt(30f, 40f, pageWidthMm = 210f)
        c.type("oben\nunten")
        c.text.editing.value!!.controller.placeCursor(0, 4)
        c.insert(listOf(com.stemaker.nibsync.model.ImageDraft(0f, 0f, 60f, 30f, com.stemaker.nibsync.model.Asset("e".repeat(64), com.stemaker.nibsync.model.Asset.PNG))))
        // Typing goes on after the picture, nothing is selected.
        assertThat(c.selection.value).isEmpty()
        assertThat(c.text.editing.value!!.controller.text.plainText).isEqualTo("unten")
        // Backspace at its start selects the picture; Enter on it goes into the text below.
        assertThat(c.boundary(com.stemaker.nibsync.text.Boundary.BACKSPACE, inCell = false)).isTrue()
        val picture = c.session.state.value.files.single()
        assertThat(c.selection.value).containsExactly(picture.id)
        assertThat(c.blockKey(com.stemaker.nibsync.text.BlockKey.ENTER)).isTrue()
        assertThat(c.selection.value).isEmpty()
        assertThat(c.tools.value.tool).isEqualTo(DesktopTool.TEXT)
        assertThat(c.text.editing.value!!.controller.text.plainText).isEqualTo("unten")
        // The whole box gets narrower: text and picture.
        c.resizeBox(-100f, done = false)
        c.resizeBox(-100f, done = true)
        val flow = c.session.state.value.flows.single()
        assertThat(flow.blocks.map { it.widthMm }).containsExactly(65f, 60f, 65f).inOrder()
    }

    @Test
    fun clickOnFreePaperStartsABoxAtTheClick() = runTest {
        val c = controller()
        c.clickAt(30f, 40f, pageWidthMm = 210f)
        val e = c.text.editing.value!!
        assertThat(e.boxId).isNull()
        assertThat(e.x).isEqualTo(30f)
        assertThat(e.y).isEqualTo(37.5f)
        // Up to the right margin.
        assertThat(e.widthMm).isEqualTo(165f)
        c.clickAt(150f, 40f, pageWidthMm = 210f)
        assertThat(c.text.editing.value!!.widthMm).isEqualTo(45f)
        // At the edge it moves left instead of getting too narrow.
        c.clickAt(200f, 40f, pageWidthMm = 210f)
        assertThat(c.text.editing.value!!.x).isEqualTo(165f)
        assertThat(c.text.editing.value!!.widthMm).isEqualTo(30f)
    }

    @Test
    fun typedTextIsStoredAndAClickReopensTheBox() = runTest {
        val c = controller()
        c.clickAt(30f, 40f, 210f)
        c.type("Hallo PC")
        c.clickAt(30f, 150f, 210f) // elsewhere: stores the first box, starts another
        val box = c.session.state.value.textBoxes.single()
        assertThat(box.text.plainText).isEqualTo("Hallo PC")
        assertThat(c.text.editing.value!!.boxId).isNull()
        c.clickAt(box.x + 1f, box.y + 1f, 210f)
        assertThat(c.text.editing.value!!.boxId).isEqualTo(box.id)
        // The empty second box left nothing behind.
        assertThat(c.session.state.value.textBoxes).hasSize(1)
    }

    @Test
    fun clickBesideThePageOnlyEndsEditing() = runTest {
        val c = controller()
        c.clickAt(30f, 40f, 210f)
        c.type("x")
        c.clickAt(-5f, 40f, 210f)
        assertThat(c.text.editing.value).isNull()
        assertThat(c.session.state.value.textBoxes).hasSize(1)
    }

    @Test
    fun undoStoresTheTextFirst() = runTest {
        val c = controller()
        c.clickAt(30f, 40f, 210f)
        c.type("eins")
        assertThat(c.undo()).isTrue()
        assertThat(c.session.state.value.textBoxes).isEmpty()
        assertThat(c.redo()).isTrue()
        assertThat(c.session.state.value.textBoxes.single().text.plainText).isEqualTo("eins")
    }
}
