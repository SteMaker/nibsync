package com.stemaker.nibsync.text

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/** Text flow: what is below a growing or shrinking text box or table moves along. */
class TextFlowTest {
    // Every paragraph one 6 mm line, whatever the width.
    private val lines: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * 6f }

    private fun setup(): Triple<DocumentSession, TextBoxEditing, TableEditing> {
        val session = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        val text = TextBoxEditing(session).also { it.measureText = lines }
        val tables = TableEditing(session).also { it.measureText = lines }
        return Triple(session, text, tables)
    }

    @Test
    fun typingMoreLinesPushesTheTableBelowDownAndDeletingPullsItUp() {
        val (s, text, tables) = setup()
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("eins"))
        text.finish()
        tables.insert(20f, 40f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        tables.finish()
        val tableTop = s.state.value.tables.single().y
        // Two more lines in the box: the table moves 12 mm down.
        text.open(s.state.value.textBoxes.single())
        text.editing.value!!.controller.setContent(RichText.plain("eins\nzwei\ndrei"))
        assertThat(text.flowPreview()!!.dy).isWithin(0.01f).of(12f)
        text.save()
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(tableTop + 12f)
        // Back to one line: up again, the gap is as before.
        text.editing.value!!.controller.setContent(RichText.plain("eins"))
        text.finish()
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(tableTop)
        // Undo goes back step by step, box and table together.
        s.undo()
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(tableTop + 12f)
        assertThat(s.state.value.textBoxes.single().heightMm).isWithin(0.05f).of(18f)
    }

    @Test
    fun aTableRowPushesTheTextBelowDown() {
        val (s, text, tables) = setup()
        tables.insert(20f, 20f, rows = 1, columns = 2, header = false, pageWidthMm = 210f)
        text.startNew(20f, 40f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("darunter"))
        text.finish()
        val boxTop = s.state.value.textBoxes.single().y
        val table = s.state.value.tables.single()
        tables.open(table, com.stemaker.nibsync.model.CellKey(table.rows[0].rowId, table.columns[0].colId))
        tables.insertRow(below = true)
        assertThat(s.state.value.textBoxes.single().y).isWithin(0.05f).of(boxTop + com.stemaker.nibsync.model.TableElement.MIN_ROW_MM)
    }

    @Test
    fun aNewTableOnlyPushesWhatItWouldCover() {
        val (s, text, tables) = setup()
        text.startNew(20f, 100f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("weit unten"))
        text.finish()
        // Far above: nothing moves.
        tables.insert(20f, 20f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        assertThat(s.state.value.textBoxes.single().y).isWithin(0.05f).of(100f)
        tables.finish()
        // Right on top of it: it moves below the table.
        val second = tables.insert(20f, 95f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        assertThat(s.state.value.textBoxes.single().y).isAtLeast(second.bounds.bottom)
    }

    @Test
    fun insertingATableAtTheCursorSplitsTheBox() {
        val (s, text, tables) = setup()
        text.startNew(20f, 20f, 120f)
        val controller = text.editing.value!!.controller
        controller.setContent(RichText(listOf(TextParagraph("vorher fett", marks = listOf(TextMark(7, 11, MarkStyle.Bold))), TextParagraph("nachher"))))
        text.save()
        tables.insert(20f, 80f, rows = 1, columns = 1, header = false, pageWidthMm = 210f)
        tables.finish()
        val farTable = s.state.value.tables.single()
        text.open(s.state.value.textBoxes.single())
        // Cursor after "vorher fe".
        text.editing.value!!.controller.state.selection = TextRange(9)
        val table = tables.insertInto(text, rows = 2, columns = 3, header = true)!!
        val boxes = s.state.value.textBoxes.sortedBy { it.y }
        assertThat(boxes.map { it.text.plainText }).containsExactly("vorher fe", "tt\nnachher").inOrder()
        assertThat(boxes[1].text.paragraphs[0].marks).containsExactly(TextMark(0, 2, MarkStyle.Bold))
        assertThat(table.y).isGreaterThan(boxes[0].bounds.bottom)
        assertThat(boxes[1].y).isGreaterThan(table.bounds.bottom)
        assertThat(table.widthMm).isWithin(0.1f).of(120f)
        // The table further down moved below the new stack.
        val moved = s.state.value.tables.first { it.tableId == farTable.tableId }
        assertThat(moved.y).isGreaterThan(farTable.y)
        assertThat(tables.editing.value).isNotNull()
        // One undo: the single box again, the far table back in place.
        tables.finish()
        s.undo()
        assertThat(s.state.value.textBoxes.single().text.plainText).isEqualTo("vorher fett\nnachher")
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(farTable.y)
    }
}

/** Blocks in the text: where they land when dragged, and what happens to the text around them. */
class FlowEditingTest {
    private val lines: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * 6f }

    private class Setup(val s: DocumentSession, val text: TextBoxEditing, val tables: TableEditing, val flows: FlowEditing)

    private fun setup(): Setup {
        val s = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        return Setup(s, TextBoxEditing(s).also { it.measureText = lines }, TableEditing(s).also { it.measureText = lines }, FlowEditing(s).also { it.measureText = lines })
    }

    private fun Setup.box(y: Float, content: String): TextBox {
        text.startNew(20f, y, 120f)
        text.editing.value!!.controller.setContent(RichText.plain(content))
        text.finish()
        return s.state.value.textBoxes.first { it.text.plainText == content }
    }

    private fun Setup.table(y: Float): TableElement {
        val t = tables.insert(20f, y, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        tables.finish()
        return s.state.value.tables.first { it.tableId == t.tableId }
    }

    private fun Setup.plain() = s.state.value.textBoxes.sortedBy { it.y }.map { it.text.plainText }

    @Test
    fun aTableDraggedOntoTextLandsBetweenItsParagraphs() {
        val f = setup()
        val box = f.box(20f, "eins\nzwei\ndrei\nvier")
        val table = f.table(100f)
        // Its top comes down between "zwei" and "drei": 12 mm into the box.
        val dy = box.y + 13f - table.y
        val drop = f.flows.dropFor(setOf(table.id), 0f, dy)!!
        assertThat(drop.y).isWithin(0.01f).of(box.y + 12f)
        val moved = f.flows.moveSelection(setOf(table.id), 0f, dy)
        assertThat(f.plain()).containsExactly("eins\nzwei", "drei\nvier").inOrder()
        val flow = f.s.state.value.flow(box.slot.flowId)!!
        assertThat(flow.blocks.map { it::class }).containsExactly(TextBox::class, TableElement::class, TextBox::class).inOrder()
        assertThat(flow.blocks[1].id).isEqualTo(moved.single())
        assertThat(f.s.state.value.tables.single().tableId).isEqualTo(table.tableId)
        // One undo: the text whole again and the table where it was.
        f.s.undo()
        assertThat(f.plain()).containsExactly("eins\nzwei\ndrei\nvier")
        assertThat(f.s.state.value.tables.single().y).isWithin(0.05f).of(table.y)
    }

    @Test
    fun typingAboveATableInTheTextMovesItAndWhatIsBelow() {
        val f = setup()
        val box = f.box(20f, "eins\nzwei")
        val table = f.table(100f)
        val far = f.box(200f, "weit unten")
        f.flows.moveSelection(setOf(table.id), 0f, box.bounds.bottom + 1f - table.y)
        val before = f.s.state.value.tables.single().y
        val first = f.s.state.value.textBoxes.minBy { it.y }
        f.text.open(first)
        f.text.editing.value!!.controller.setContent(RichText.plain("eins\nzwei\ndrei"))
        assertThat(f.text.flowPreview()!!.dy).isWithin(0.01f).of(6f)
        f.text.finish()
        assertThat(f.s.state.value.tables.single().y).isWithin(0.05f).of(before + 6f)
        assertThat(f.s.state.value.textBoxes.first { it.text.plainText == "weit unten" }.y).isGreaterThan(far.y - 0.1f)
    }

    @Test
    fun aTableDraggedOutJoinsTheTextAgain() {
        val f = setup()
        val box = f.box(20f, "eins\nzwei")
        val table = f.table(100f)
        val dy = box.y + 7f - table.y
        val inText = f.flows.moveSelection(setOf(table.id), 0f, dy)
        assertThat(f.plain()).containsExactly("eins", "zwei").inOrder()
        f.flows.moveSelection(inText, 0f, 150f)
        assertThat(f.plain()).containsExactly("eins\nzwei")
        assertThat(f.s.state.value.flows).hasSize(2)
        // Deleting between two text blocks joins them as well.
        val again = f.s.state.value.tables.single()
        val second = f.flows.moveSelection(setOf(again.id), 0f, box.y + 7f - again.y)
        f.flows.remove(second)
        assertThat(f.plain()).containsExactly("eins\nzwei")
        assertThat(f.s.state.value.tables).isEmpty()
    }

    @Test
    fun aBlockOnFreeSpaceIsABoxOfItsOwnAndPushesHandwriting() {
        val f = setup()
        val ink = f.s.addStroke(
            com.stemaker.nibsync.model.StrokeDraft(
                com.stemaker.nibsync.model.BrushSpec(com.stemaker.nibsync.model.BrushFamily.BALLPOINT, -16777216, 0.5f),
                com.stemaker.nibsync.model.InputTool.STYLUS,
                listOf(com.stemaker.nibsync.model.StrokePoint(30f, 105f, 0), com.stemaker.nibsync.model.StrokePoint(60f, 105f, 10)),
            ),
        )
        val table = f.table(20f)
        val moved = f.flows.moveSelection(setOf(table.id), 0f, 80f).single()
        val placed = f.s.state.value.element(moved)!!
        assertThat(placed.bounds.top).isWithin(0.05f).of(table.y + 80f)
        assertThat(f.s.state.value.strokes.single().bounds.top).isGreaterThan(placed.bounds.bottom)
        assertThat(ink.bounds.top).isLessThan(placed.bounds.bottom)
    }

    @Test
    fun aWholeBoxMovesAsOneAndGoesIntoOtherText() {
        val f = setup()
        val box = f.box(20f, "eins\nzwei")
        val table = f.table(100f)
        f.flows.moveSelection(setOf(table.id), 0f, box.y + 7f - table.y)
        val other = f.box(150f, "anderer\nText")
        val blocks = f.s.state.value.flow(box.slot.flowId)!!.blocks.mapTo(HashSet()) { it.id }
        f.flows.moveSelection(blocks, 30f, 40f)
        assertThat(f.s.state.value.flow(box.slot.flowId)!!.x).isWithin(0.05f).of(50f)
        assertThat(f.s.state.value.flow(box.slot.flowId)!!.blocks.map { it.id }.toSet()).isEqualTo(blocks)
        // Its top lands between the paragraphs of the other box: it goes in there.
        val moved = f.s.state.value.flow(box.slot.flowId)!!
        val into = f.flows.moveSelection(blocks, other.x + 10f - moved.x, other.y + 7f - moved.y)
        // The text around the table joined the text it landed next to, only the table is left selected.
        assertThat(into).containsExactly(f.s.state.value.tables.single().id)
        assertThat(f.s.state.value.flows).hasSize(1)
        assertThat(f.plain()).containsExactly("anderer\neins", "zwei\nText").inOrder()
    }

    @Test
    fun textTappedBelowATableGoesOnInItsFlow() {
        val f = setup()
        val table = f.table(20f)
        val next = f.flows.continuation(30f, table.bounds.bottom + 5f)!!
        assertThat(f.flows.continuation(30f, table.bounds.bottom + 20f)).isNull()
        f.text.startNew(next.x, next.y, next.widthMm, next.slot)
        f.text.editing.value!!.controller.setContent(RichText.plain("danach"))
        f.text.finish()
        val flow = f.s.state.value.flow(table.slot.flowId)!!
        assertThat(flow.blocks.map { it::class }).containsExactly(TableElement::class, TextBox::class).inOrder()
    }

    @Test
    fun aPictureAtTheCursorFitsTheBoxAndPastedBlocksStayTogether() {
        val f = setup()
        f.text.startNew(20f, 20f, 100f)
        f.text.editing.value!!.controller.setContent(RichText.plain("eins"))
        val picture = com.stemaker.nibsync.model.ImageDraft(0f, 0f, 200f, 100f, com.stemaker.nibsync.model.Asset("a".repeat(64), com.stemaker.nibsync.model.Asset.PNG))
        val block = f.text.insertBlocksAtCursor(listOf(picture)).single()
        assertThat(block.widthMm).isWithin(0.05f).of(100f)
        assertThat(block.heightMm).isWithin(0.05f).of(50f)
        assertThat(f.text.editing.value).isNull()
        val selection = f.s.state.value.elements.filter { it is FlowBlock }
        val clip = com.stemaker.nibsync.document.select.ClipContent.of(selection)!!
        f.flows.place(clip.placedAt(100f, 200f))
        assertThat(f.s.state.value.flows).hasSize(2)
        assertThat(f.s.state.value.flows.map { it.blocks.size }).containsExactly(2, 2)
    }
}

/** Anchors and links as letters of the text. */
class LinksInTextTest {
    private val lines: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * 6f }
    private val target = DocumentId("00000000-0000-4000-8000-000000000001")

    private class Setup(val s: DocumentSession, val text: TextBoxEditing, val flows: FlowEditing)

    private fun setup(): Setup {
        val s = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        return Setup(s, TextBoxEditing(s).also { it.measureText = lines }, FlowEditing(s).also { it.measureText = lines })
    }

    private fun Setup.open(content: String) {
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain(content))
    }

    @Test
    fun anAnchorAtTheCursorIsALetterThatGoesAlongWithTheText() {
        val f = setup()
        f.open("eins\nzwei")
        f.text.editing.value!!.controller.state.selection = TextRange(5)
        assertThat(f.text.insertLinkAtCursor(com.stemaker.nibsync.model.AnchorDraft(0f, 0f, "Kapitel"))).isTrue()
        f.text.finish()
        val box = f.s.state.value.textBoxes.single()
        assertThat(box.text.plainText).isEqualTo("eins\n${MarkStyle.Anchor.SIGN}zwei")
        val anchor = f.s.state.value.anchors.single()
        assertThat(anchor.name).isEqualTo("Kapitel")
        assertThat(anchor.y).isWithin(0.05f).of(box.y + 6f)
        // Text above it: the anchor goes along, under the same id.
        f.text.open(box)
        val typed = f.text.editing.value!!.controller
        typed.state.selection = TextRange(0)
        typed.state.addTextAfterSelection("null\n")
        f.text.finish()
        val again = f.s.state.value.anchor(anchor.anchorId)!!
        assertThat(again.y).isWithin(0.05f).of(box.y + 12f)
        // Renaming keeps it.
        assertThat(f.flows.renameInText(anchor.anchorId, "Neu")).isTrue()
        assertThat(f.s.state.value.anchor(anchor.anchorId)!!.name).isEqualTo("Neu")
    }

    @Test
    fun aLinkAtTheCursorShowsItsTitleAndTextTypedBesideItStaysOutside() {
        val f = setup()
        f.open("siehe ")
        val c = f.text.editing.value!!.controller
        c.state.selection = TextRange(6)
        f.text.insertLinkAtCursor(com.stemaker.nibsync.model.NoteLinkDraft(0f, 0f, target, null, "Projekt", null))
        c.state.addTextAfterSelection(" weiter")
        f.text.finish()
        val p = f.s.state.value.textBoxes.single().text.paragraphs.single()
        assertThat(p.text).isEqualTo("siehe Projekt weiter")
        val mark = p.marks.single { it.style is MarkStyle.NoteLink }
        assertThat(p.text.substring(mark.start, mark.end)).isEqualTo("Projekt")
        assertThat((mark.style as MarkStyle.NoteLink).linkTarget.document).isEqualTo(target)
        assertThat(RichTextLayout.linkAt(f.s.state.value.textBoxes.single().text, 8)).isEqualTo(mark.style)
        assertThat(RichTextLayout.linkAt(f.s.state.value.textBoxes.single().text, 3)).isNull()
    }

    @Test
    fun aFreeAnchorDroppedOnTextBecomesALetterAndStaysFreeElsewhere() {
        val f = setup()
        f.open("eins\nzwei")
        f.text.finish()
        val free = f.flows.place(listOf(com.stemaker.nibsync.model.AnchorDraft(150f, 100f, "Frei"))).single()
        assertThat(f.s.state.value.links).hasSize(1)
        val box = f.s.state.value.textBoxes.single()
        f.flows.moveSelection(setOf(free.id), 30f - 150f, box.y + 7f - 100f)
        assertThat(f.s.state.value.links).isEmpty()
        assertThat(f.s.state.value.textBoxes.single().text.plainText).isEqualTo("eins\n${MarkStyle.Anchor.SIGN}zwei")
        assertThat(f.s.state.value.anchors.single().anchorId).isEqualTo((free as com.stemaker.nibsync.model.Anchor).anchorId)
    }

    @Test
    fun aCopiedTextHasNewAnchors() {
        val f = setup()
        f.open("eins")
        f.text.insertLinkAtCursor(com.stemaker.nibsync.model.AnchorDraft(0f, 0f, "A"))
        f.text.finish()
        val clip = com.stemaker.nibsync.document.select.ClipContent.of(f.s.state.value.textBoxes)!!
        f.flows.place(clip.placedAt(100f, 200f))
        assertThat(f.s.state.value.anchors.map { it.anchorId }.toSet()).hasSize(2)
    }
}
