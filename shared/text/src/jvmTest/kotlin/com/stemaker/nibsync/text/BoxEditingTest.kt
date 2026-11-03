package com.stemaker.nibsync.text

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.TextBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class BoxEditingTest {
    private val lines: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * 6f }
    private val session = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
    private val text = TextBoxEditing(session).also { it.measureText = lines }
    private val tables = TableEditing(session).also { it.measureText = lines }
    private val flows = FlowEditing(session).also { it.measureText = lines }
    private val box = BoxEditing(session, text, tables, flows)

    /** A box "oben", a picture, a table and "unten", in this order. */
    private fun build() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben\nunten"))
        text.editing.value!!.controller.placeCursor(0, 4)
        text.insertBlocksAtCursor(listOf(ImageDraft(0f, 0f, 60f, 30f, Asset("e".repeat(64), Asset.PNG))))
        val after = session.state.value.textBoxes.single { it.text.plainText == "unten" }
        text.open(after) { it.placeCursorAtStart() }
        tables.insertInto(text, rows = 2, columns = 2, header = false)
        tables.finish()
    }

    private fun blocks() = session.state.value.flows.single().blocks

    @Test
    fun theCursorGoesThroughTextPicturesAndTables() {
        build()
        assertThat(blocks().map { it::class.simpleName }).containsExactly("TextBox", "ImageElement", "TableElement", "TextBox").inOrder()
        text.open(blocks()[0] as TextBox) { it.placeCursorAtEnd() }
        assertThat(box.openFlow()!!.id).isEqualTo(blocks()[0].slot.flowId)
        // Down from the last line: the picture is selected.
        val picture = box.fromText(Boundary.DOWN)!!
        assertThat(picture).containsExactly(blocks()[1].id)
        assertThat(text.editing.value).isNull()
        // On into the table's first cell, through its cells, out into the text below.
        assertThat(box.fromBlock(picture.single(), BlockKey.DOWN)).isEmpty()
        val table = blocks()[2] as TableElement
        assertThat(tables.editing.value!!.cell).isEqualTo(table.geometry.key(table.geometry.areas.first()))
        repeat(3) { assertThat(box.fromCell(Boundary.RIGHT)).isEmpty() }
        assertThat(box.fromCell(Boundary.RIGHT)).isEmpty()
        assertThat(tables.editing.value).isNull()
        assertThat(text.editing.value!!.boxId).isEqualTo(blocks()[3].id)
        // Back up: into the table's last cell.
        assertThat(box.fromText(Boundary.UP)).isEmpty()
        assertThat(tables.editing.value!!.cell).isEqualTo(table.geometry.key(table.geometry.areas.last()))
        // Nothing above the first text: the editor keeps the key.
        tables.finish()
        text.open(blocks()[0] as TextBox) { it.placeCursorAtStart() }
        assertThat(box.fromText(Boundary.UP)).isNull()
    }

    @Test
    fun backspaceSelectsThePictureAndASecondOneRemovesItAndJoinsTheText() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben\nunten"))
        text.editing.value!!.controller.placeCursor(0, 4)
        text.insertBlocksAtCursor(listOf(ImageDraft(0f, 0f, 60f, 30f, Asset("e".repeat(64), Asset.PNG))))
        text.open(session.state.value.textBoxes.single { it.text.plainText == "unten" }) { it.placeCursorAtStart() }
        val picture = box.fromText(Boundary.BACKSPACE)!!.single()
        assertThat(session.state.value.element(picture)).isInstanceOf(ImageElement::class.java)
        assertThat(box.fromBlock(picture, BlockKey.BACKSPACE)).isEmpty()
        val joined = session.state.value.textBoxes.single()
        assertThat(joined.text.plainText).isEqualTo("oben\nunten")
        // The cursor is where the picture was: behind "oben".
        assertThat(text.editing.value!!.boxId).isEqualTo(joined.id)
        assertThat(text.editing.value!!.controller.isAtEnd).isFalse()
        text.editing.value!!.controller.state.replaceSelectedText("!")
        assertThat(text.editing.value!!.controller.text.plainText).isEqualTo("oben!\nunten")
    }

    @Test
    fun enterOnAPictureAtTheEndStartsTextBelowIt() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben"))
        text.editing.value!!.controller.placeCursorAtEnd()
        val picture = text.insertBlocksAtCursor(listOf(ImageDraft(0f, 0f, 60f, 30f, Asset("e".repeat(64), Asset.PNG)))).single()
        assertThat(box.fromBlock(picture.id, BlockKey.ENTER)).isEmpty()
        text.editing.value!!.controller.setContent(RichText.plain("unten"))
        text.finish()
        assertThat(blocks().map { it::class.simpleName }).containsExactly("TextBox", "ImageElement", "TextBox").inOrder()
        assertThat((blocks()[2] as TextBox).y).isGreaterThan(picture.bounds.bottom)
    }

    @Test
    fun aNarrowerBoxScalesWidePicturesAndTablesAndKeepsTheTextOpen() {
        build()
        text.open(blocks()[0] as TextBox) { it.placeCursor(0, 2) }
        box.setWidth(blocks()[0].slot.flowId, 50f)
        val b = blocks()
        assertThat(b.filterIsInstance<TextBox>().map { it.widthMm }).containsExactly(50f, 50f)
        assertThat(b[1].widthMm).isWithin(0.01f).of(50f)
        assertThat((b[1] as ImageElement).heightMm).isWithin(0.01f).of(25f)
        assertThat(b[2].widthMm).isWithin(0.1f).of(50f)
        assertThat(text.editing.value!!.boxId).isEqualTo(b[0].id)
        assertThat(text.editing.value!!.widthMm).isEqualTo(50f)
        // One undo step.
        session.undo()
        assertThat(blocks()[2].widthMm).isGreaterThan(100f)
    }
}
