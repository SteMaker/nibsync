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

    private fun picture(w: Float, h: Float) = ImageDraft(0f, 0f, w, h, Asset("e".repeat(64), Asset.PNG))

    @Test
    fun picturesDroppedBesideAPictureStandInARowAsHighAsEachOtherFillingTheBox() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben\nunten"))
        text.editing.value!!.controller.placeCursor(0, 4)
        val first = text.insertBlocksAtCursor(listOf(picture(60f, 30f))).single()
        text.finish()
        // Put on the page without text it floats; into text it goes as a box of its own there.
        val floating = flows.place(listOf(picture(40f, 40f).translated(150f, 150f))).single() as ImageElement
        assertThat(floating.slot.floating).isTrue()
        val sunk = flows.setFloating(setOf(floating.id), false)
        val second = session.state.value.element(sunk.single())!!
        // Dragged onto the right part of the first picture: a line upright beside it.
        val b = first.bounds
        val dx = b.right - 5f - (second.bounds.left + second.bounds.right) / 2
        val dy = (b.top + b.bottom) / 2 - (second.bounds.top + second.bounds.bottom) / 2
        val drop = flows.dropFor(setOf(second.id), dx, dy)!!
        assertThat(drop.left).isEqualTo(drop.right)
        assertThat(drop.left).isGreaterThan(b.right)
        flows.moveSelection(setOf(second.id), dx, dy)
        val flow = session.state.value.flows.single()
        assertThat(flow.blocks.map { it::class.simpleName }).containsExactly("TextBox", "ImageElement", "ImageElement", "TextBox").inOrder()
        val (left, right) = flow.blocks.filterIsInstance<ImageElement>()
        // Side by side at their own sizes (60 + 40 mm fit into 120 mm), top aligned; the higher one sets the row.
        assertThat(left.widthMm).isWithin(0.01f).of(60f)
        assertThat(right.widthMm).isWithin(0.01f).of(40f)
        assertThat(right.x).isWithin(0.01f).of(left.bounds.right + com.stemaker.nibsync.model.Flow.GAP_MM)
        assertThat(left.y).isEqualTo(right.y)
        assertThat(flow.blocks.last().y).isWithin(0.01f).of(right.bounds.bottom + com.stemaker.nibsync.model.Flow.GAP_MM)
        // Too wide for a narrower box: both scaled down alike to fit, and back when wider again.
        box.setWidth(flow.id, 70f)
        val (l2, r2) = session.state.value.flows.single().blocks.filterIsInstance<ImageElement>()
        assertThat(r2.bounds.right).isWithin(0.01f).of(20f + 70f)
        assertThat(l2.widthMm / r2.widthMm).isWithin(0.01f).of(1.5f)
        box.setWidth(flow.id, 120f)
        assertThat(session.state.value.flows.single().blocks.filterIsInstance<ImageElement>().map { it.widthMm }).containsExactly(60f, 40f).inOrder()
        // The text below comes after the row.
        assertThat(flow.blocks.last().y).isGreaterThan(left.bounds.bottom)
        // Dragged out below the box again: the first picture stands alone at its size.
        flows.moveSelection(setOf(right.id), 0f, 200f)
        val alone = session.state.value.flows.first { it.blocks.size > 1 }.blocks.filterIsInstance<ImageElement>().single()
        assertThat(alone.widthMm / alone.heightMm).isWithin(0.01f).of(2f)
        assertThat(alone.widthMm).isAtMost(120f)
        // Undo puts the row back (and the widths before).
        session.undo()
        session.undo()
        session.undo()
        assertThat(session.state.value.flows.single().blocks.filterIsInstance<ImageElement>().map { it.y }.distinct()).hasSize(1)
    }

    @Test
    fun textGoesBetweenAPictureAndATableAndBehindTheLastCell() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben"))
        text.editing.value!!.controller.placeCursorAtEnd()
        val pic = text.insertBlocksAtCursor(listOf(picture(60f, 30f))).single()
        box.fromBlock(pic.id, BlockKey.ENTER)
        tables.insertInto(text, rows = 1, columns = 2, header = false)
        tables.finish()
        // A text tap just above the table's top, inside the picture's lower edge: text between them.
        val next = flows.continuation(30f, pic.bounds.bottom - 1f)!!
        text.startNew(next.x, next.y, next.widthMm, next.slot)
        text.editing.value!!.controller.setContent(RichText.plain("dazwischen"))
        text.finish()
        assertThat(blocks().map { it::class.simpleName }).containsExactly("TextBox", "ImageElement", "TextBox", "TableElement").inOrder()
        // Inside the picture or the table: no.
        assertThat(flows.continuation(30f, pic.bounds.top + 10f)).isNull()
        // From behind the last character of the last cell, right goes behind the table.
        val table = blocks().last() as TableElement
        tables.open(table, table.geometry.key(table.geometry.areas.last()))
        assertThat(box.fromCell(Boundary.RIGHT)).isEmpty()
        assertThat(tables.editing.value).isNull()
        text.editing.value!!.controller.setContent(RichText.plain("danach"))
        text.finish()
        assertThat(blocks().map { it::class.simpleName }).containsExactly("TextBox", "ImageElement", "TextBox", "TableElement", "TextBox").inOrder()
    }

    @Test
    fun aFloatingTableLiesOverTextAndGoesInOrOutOfIt() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("eins\nzwei\ndrei"))
        text.finish()
        // Inserted with no text open: it floats over the text, nothing moves.
        val table = tables.insert(30f, 25f, rows = 1, columns = 1, header = false, pageWidthMm = 210f)
        tables.finish()
        assertThat(table.slot.floating).isTrue()
        assertThat(session.state.value.textBoxes.single().y).isEqualTo(20f)
        // Text growing below it does not push it.
        text.open(session.state.value.textBoxes.single()) { it.placeCursorAtEnd() }
        text.editing.value!!.controller.setContent(RichText.plain("eins\nzwei\ndrei\nvier\nfünf"))
        text.finish()
        assertThat(session.state.value.tables.single().y).isEqualTo(25f)
        // Moved, it goes where it is dropped, not into the text.
        flows.moveSelection(setOf(session.state.value.tables.single().id), 0f, 1f)
        assertThat(session.state.value.textBoxes).hasSize(1)
        // Into the text: between the paragraphs where it lies.
        val inText = flows.setFloating(setOf(session.state.value.tables.single().id), false).single()
        assertThat(session.state.value.textBoxes).hasSize(2)
        val placed = session.state.value.element(inText) as TableElement
        assertThat(placed.slot.floating).isFalse()
        // Out again: it stays where it is, the text closes up under it.
        val y = placed.y
        val out = flows.setFloating(setOf(inText), true).single()
        assertThat((session.state.value.element(out) as TableElement).y).isEqualTo(y)
        assertThat(session.state.value.textBoxes.single().text.plainText).isEqualTo("eins\nzwei\ndrei\nvier\nfünf")
    }

    @Test
    fun aToDoGoesInAtTheCursorAsAParagraphAndCanFloat() {
        val todos = TodoEditing(session).also { it.measureText = lines }
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben\nunten"))
        text.editing.value!!.controller.placeCursor(0, 4)
        val item = todos.insertInto(text)!!
        assertThat(todos.editing.value!!.itemId).isEqualTo(item.id)
        todos.editing.value!!.text!!.setContent(RichText.plain("Anrufen"))
        todos.finish()
        assertThat(blocks().map { it::class.simpleName }).containsExactly("TextBox", "TodoItem", "TextBox").inOrder()
        val inText = blocks()[1] as com.stemaker.nibsync.model.TodoItem
        assertThat(inText.widthMm).isEqualTo(120f)
        // Floating it stays where it is, over everything; the text closes up.
        val out = flows.setFloating(setOf(inText.id), true).single()
        val floating = session.state.value.element(out) as com.stemaker.nibsync.model.TodoItem
        assertThat(floating.y).isEqualTo(inText.y)
        assertThat(floating.taskId).isEqualTo(inText.taskId)
        assertThat(session.state.value.elements.last().id).isEqualTo(out)
        assertThat(session.state.value.textBoxes.single().text.plainText).isEqualTo("oben\nunten")
    }

    @Test
    fun aFloatingPictureGoesIntoTheTextWhereTheDropLineShowsBesideAPicture() {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("oben"))
        text.editing.value!!.controller.placeCursorAtEnd()
        val first = text.insertBlocksAtCursor(listOf(picture(40f, 30f))).single()
        val floating = flows.place(listOf(picture(30f, 30f))).single()
        // Moved onto the right part of the picture in the text: still floating, the line shows beside it.
        val dx = first.bounds.right - 4f - (floating.bounds.left + floating.bounds.right) / 2
        val dy = (first.bounds.top + first.bounds.bottom) / 2 - (floating.bounds.top + floating.bounds.bottom) / 2
        assertThat(flows.dropFor(setOf(floating.id), dx, dy)!!.left).isGreaterThan(first.bounds.right)
        val moved = flows.moveSelection(setOf(floating.id), dx, dy).single()
        assertThat((session.state.value.element(moved) as ImageElement).slot.floating).isTrue()
        // Into the text: beside the picture, as the line showed.
        flows.setFloating(setOf(moved), false)
        val pictures = session.state.value.flows.single().blocks.filterIsInstance<ImageElement>()
        assertThat(pictures).hasSize(2)
        assertThat(pictures[0].y).isEqualTo(pictures[1].y)
    }
}
