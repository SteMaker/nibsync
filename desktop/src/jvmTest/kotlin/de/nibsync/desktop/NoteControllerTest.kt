package de.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import de.nibsync.document.DocumentSession
import de.nibsync.document.DocumentState
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.model.RichText
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NoteControllerTest {
    private fun TestScope.controller() = NoteController(
        DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DEV, { }, backgroundScope, clock = { 0L }),
    ) { _, _ -> 6f }

    private fun NoteController.type(text: String) = this.text.editing.value!!.controller.setContent(RichText.plain(text))

    @Test
    fun clickOnFreePaperStartsABoxAtTheClick() = runTest {
        val c = controller()
        c.clickAt(30f, 40f, pageWidthMm = 210f)
        val e = c.text.editing.value!!
        assertThat(e.boxId).isNull()
        assertThat(e.x).isEqualTo(30f)
        assertThat(e.y).isEqualTo(37.5f)
        assertThat(e.widthMm).isEqualTo(120f)
        // Further right the box ends at the margin.
        c.clickAt(150f, 40f, pageWidthMm = 210f)
        assertThat(c.text.editing.value!!.widthMm).isEqualTo(55f)
        // At the edge it moves left instead of getting too narrow.
        c.clickAt(200f, 40f, pageWidthMm = 210f)
        assertThat(c.text.editing.value!!.x).isEqualTo(175f)
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
