package com.stemaker.nibsync.text

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.FlowSlot
import com.stemaker.nibsync.model.FractionalIndex
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TableColumn
import com.stemaker.nibsync.model.TableDraft
import com.stemaker.nibsync.model.TableRow
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.TextParagraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class RichTextLayoutTest {
    private fun numbered(text: String, indent: Int = 0) = TextParagraph(text, ListKind.NUMBERED, indent)

    @Test
    fun nestedNumbersShowTheNumbersAbove() {
        val text = RichText(listOf(numbered("a"), numbered("b"), numbered("b1", 1), numbered("b2", 1), numbered("b2x", 2), numbered("c")))
        assertThat(RichTextLayout.annotated(text) { 5f }.text).isEqualTo("1. a 2. b 2.1. b1 2.2. b2 2.2.1. b2x 3. c")
    }

    @Test
    fun aNumberedItemBelowABulletCountsOnItsOwn() {
        val text = RichText(listOf(TextParagraph("x", ListKind.BULLET), numbered("y", 1)))
        assertThat(RichTextLayout.annotated(text) { 5f }.text).isEqualTo("• x 1. y")
    }

    @Test
    fun numberingGoesOnBelowATableInTheSameFlow() {
        val session = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        val flow = StrokeId("flow")
        val a = FractionalIndex.between(null, null)
        val b = FractionalIndex.between(a, null)
        val c = FractionalIndex.between(b, null)
        session.replaceElements(
            emptySet(),
            listOf(
                    TextBoxDraft(10f, 10f, 100f, 10f, RichText(listOf(numbered("eins"), numbered("zwei"))), FlowSlot(flow, a)),
                    TableDraft(10f, 10f, listOf(TableColumn(StrokeId("c"), 100f)), listOf(TableRow(StrokeId("r"))), header = false, cells = emptyMap(), merges = emptyList(), slot = FlowSlot(flow, b)),
                    TextBoxDraft(10f, 10f, 100f, 10f, RichText(listOf(numbered("drei"), numbered("drei.eins", 1))), FlowSlot(flow, c)),
            ),
        )
        val state = session.state.value
        val second = state.textBoxes.single { it.text.paragraphs[0].text == "drei" }
        val start = RichTextLayout.listStart(state, second)
        assertThat(RichTextLayout.annotated(second.text, start) { 5f }.text).isEqualTo("3. drei 3.1. drei.eins")
        // The editor counts on too.
        val editor = TextEditController.create(second.text, start)
        assertThat(editor.state.annotatedString.text).startsWith("3.")
        assertThat(editor.text).isEqualTo(second.text)
    }
}
