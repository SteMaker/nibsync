package com.stemaker.nibsync.text

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.select.Reflow
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.BlockDraft
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.Flow
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.FlowSlot
import com.stemaker.nibsync.model.FractionalIndex
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.NoteLinkDraft
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.toDraft
import com.stemaker.nibsync.model.geometry.Rect

/**
 * Where blocks would land in the flow [flowId]: behind the block with the order [after] (null: in
 * front of all), in the text box [split] (if any) between its paragraphs [paragraph] - 1 and
 * [paragraph]; or pictures beside the picture [besideOf] (left or [toTheRight]). The line to show
 * there while dragging goes from ([left], [y]) to ([right], [bottom]) mm: across the box, or upright
 * beside a picture.
 */
data class FlowDrop(
    val flowId: StrokeId,
    val y: Float,
    val left: Float,
    val right: Float,
    internal val after: String?,
    internal val split: TextBox? = null,
    internal val paragraph: Int = 0,
    val bottom: Float = y,
    internal val besideOf: StrokeId? = null,
    internal val toTheRight: Boolean = false,
    internal val moving: Set<StrokeId> = emptySet(),
)

/** Where a new text block continues the flow [slot] belongs to, see [FlowEditing.continuation]. */
data class FlowContinuation(val x: Float, val y: Float, val widthMm: Float, val slot: FlowSlot)

/**
 * The rules for blocks in the text, the same on every platform: a block dropped on text goes in
 * between its paragraphs, dropped elsewhere it is a box of its own that pushes down what it would
 * cover. It never lies over text.
 */
class FlowEditing(private val session: DocumentSession, private val ids: IdGenerator = IdGenerator()) {
    /** Height in mm of a text at a given width; the platform sets its real text layout. */
    var measureText: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * RichText.DEFAULT_SIZE_PT * RichText.MM_PER_PT * 1.4f }

    /**
     * Where something landing at ([x], [y]) mm would go in a flow, not counting the blocks [moving].
     * [pictures] landing on the outer part of a picture go beside it.
     */
    fun dropAt(x: Float, y: Float, moving: Set<StrokeId> = emptySet(), pictures: Boolean = false): FlowDrop? {
        for (flow in session.state.value.flows) {
            val blocks = flow.blocks.filter { it.id !in moving }
            if (blocks.isEmpty()) continue
            if (pictures) besideDrop(flow, blocks, x, y, moving)?.let { return it }
            val b = flow.bounds
            val bottom = blocks.last().bounds.bottom
            if (x < b.left || x > b.right || y < blocks.first().y || y > bottom + Flow.GAP_MM) continue
            return dropIn(flow, blocks, blocks.last { it.y <= y }, y)
        }
        return null
    }

    /** On the left or right [BESIDE_SHARE] of a picture: beside it, in its row. */
    private fun besideDrop(flow: Flow, blocks: List<FlowBlock>, x: Float, y: Float, moving: Set<StrokeId>): FlowDrop? {
        val picture = blocks.firstOrNull { it is ImageElement && it.bounds.contains(x, y) } ?: return null
        val b = picture.bounds
        val right = when {
            x < b.left + b.width * BESIDE_SHARE -> false
            x > b.right - b.width * BESIDE_SHARE -> true
            else -> return null
        }
        val lineX = if (right) b.right + Flow.GAP_MM / 2 else b.left - Flow.GAP_MM / 2
        return FlowDrop(flow.id, b.top, lineX, lineX, null, bottom = b.bottom, besideOf = picture.id, toTheRight = right, moving = moving)
    }

    private fun dropIn(flow: Flow, blocks: List<FlowBlock>, at: FlowBlock, y: Float): FlowDrop {
        val before = blocks.getOrNull(blocks.indexOf(at) - 1)?.slot?.order
        fun drop(lineY: Float, after: String?, split: TextBox? = null, paragraph: Int = 0) =
            FlowDrop(flow.id, lineY, flow.bounds.left, flow.bounds.right, after, split, paragraph)
        val box = at as? TextBox
        if (y > at.bounds.bottom || box == null) {
            return if (y < at.y + at.heightMm / 2) drop(at.y, before) else drop(at.bounds.bottom, at.slot.order)
        }
        val tops = paragraphTops(box)
        val nearest = tops.indices.minBy { kotlin.math.abs(box.y + tops[it] - y) }
        return when (nearest) {
            0 -> drop(box.y, before)
            tops.lastIndex -> drop(box.bounds.bottom, box.slot.order)
            else -> drop(box.y + tops[nearest], box.slot.order, box, nearest)
        }
    }

    /** Distance from the box's top of each paragraph's top; the last entry is the box's height. */
    private fun paragraphTops(box: TextBox): List<Float> {
        val paragraphs = box.text.paragraphs
        return List(paragraphs.size + 1) { i ->
            when (i) {
                0 -> 0f
                paragraphs.size -> box.heightMm
                else -> measureText(RichText(paragraphs.subList(0, i)), box.widthMm)
            }
        }
    }

    /** Where the selected [ids] would land if moved by ([dx], [dy]) mm; for the line shown while dragging. */
    fun dropFor(ids: Set<StrokeId>, dx: Float, dy: Float): FlowDrop? {
        val blocks = session.state.value.elements.filter { it.id in ids }.filterIsInstance<FlowBlock>()
        return dropOf(blocks, dx, dy)
    }

    /** They land where their top ends up, in the middle of their width. */
    private fun dropOf(blocks: List<FlowBlock>, dx: Float, dy: Float): FlowDrop? {
        if (blocks.isEmpty()) return null
        val left = blocks.minOf { it.x }
        val right = blocks.maxOf { it.x + it.widthMm }
        val pictures = blocks.all { it is ImageElement }
        val ids = blocks.mapTo(HashSet()) { it.id }
        // Pictures land where the pointer is: their middle, to go beside a picture.
        if (pictures) dropAt((left + right) / 2 + dx, (blocks.minOf { it.y } + blocks.maxOf { it.bounds.bottom }) / 2 + dy, ids, pictures = true)?.takeIf { it.besideOf != null }?.let { return it }
        return dropAt((left + right) / 2 + dx, blocks.minOf { it.y } + dy, ids)
    }

    /**
     * Moves the selection [selection] by ([dx], [dy]) mm in one undo step and returns the new ids of
     * what was moved: strokes and free links go there, blocks by the rules of this class. Blocks of
     * which only a part of a flow is selected leave it, whole flows stay together.
     */
    fun moveSelection(selection: Set<StrokeId>, dx: Float, dy: Float): Set<StrokeId> {
        val selected = session.state.value.elements.filter { it.id in selection }
        if (selected.isEmpty() || (dx == 0f && dy == 0f)) return selection
        val blocks = selected.filterIsInstance<FlowBlock>().sortedBy { it.y }
        val others = selected.filterNot { it is FlowBlock }
        return session.edit {
            val result = LinkedHashSet<StrokeId>()
            // Anchors and links landing on text become letters of it.
            val letters = others.filter { it !is Stroke && intoText(it.toDraft().translated(dx, dy)) }
            replaceElements(letters.map { it.id }, emptyList())
            move(others - letters.toSet(), dx, dy).mapTo(result) { it.id }
            val drop = dropOf(blocks, dx, dy)
            val flows = blocks.mapNotNullTo(LinkedHashSet()) { state.flow(it.slot.flowId) }
            val whole = flows.filter { f -> drop == null && f.blocks.all { it.id in selection } }
            val single = blocks.filter { b -> whole.none { it.id == b.slot.flowId } }
            for (flow in whole) {
                moveFlow(flow.id, maxOf(0f, flow.x + dx), maxOf(0f, flow.y + dy))
                flow.blocks.mapTo(result) { it.id }
            }
            val placed = if (single.isEmpty()) emptyList() else {
                val drafts = single.map { state.naturalDraft(it).translated(dx, dy) }
                if (drop != null) insert(drop, drafts) else newFlow(drafts)
            }
            removeBlocks(single.map { it.id }, measureText, pull = true)
            placed.mapTo(result) { it.id }
            // What moved flows would cover moves down below them.
            for (flow in whole) pushBelow(flow.id, result)
            if (drop == null) placed.firstOrNull()?.let { pushBelow((it as FlowBlock).slot.flowId, result) }
            result.filterTo(LinkedHashSet()) { it in state }
        }
    }

    private fun DocumentSession.Transaction.pushBelow(flowId: StrokeId, moved: Set<StrokeId>) {
        val flow = state.flow(flowId) ?: return
        val source = moved + flow.blocks.map { it.id }
        val (pushed, dy) = Reflow.plan(state.elements, source, null, flow.bounds)
        move(pushed, 0f, dy)
    }

    /**
     * Adds [drafts] (inserted or pasted elements, in one undo step). Blocks of one source flow stay
     * together; like moved blocks they go into the text they land on, else in a box of their own.
     */
    fun place(drafts: List<ElementDraft>): List<PageElement> = session.edit {
        val blocks = drafts.filterIsInstance<BlockDraft>().map(::withNewAnchors)
        val others = drafts.filterNot { it is BlockDraft }
        val added = replaceElements(emptySet(), others.filterNot { intoText(it) }).toMutableList()
        val groups = blocks.groupBy { it.slot?.flowId ?: ids.newStrokeId() }.values
        for (group in groups) {
            val sorted = group.sortedBy { it.y }
            val first = sorted.first()
            val drop = dropAt(first.x + first.widthMm / 2, first.y)
            val new = if (drop != null) insert(drop, sorted) else newFlow(sorted)
            added += new
            if (drop == null) pushBelow((new.first() as FlowBlock).slot.flowId, new.mapTo(HashSet()) { it.id })
        }
        added
    }

    /** A copied anchor is a new anchor: text gets new ids for the anchors in it. */
    private fun withNewAnchors(draft: BlockDraft): BlockDraft {
        if (draft !is TextBoxDraft) return draft
        return draft.copy(
            text = RichText(
                draft.text.paragraphs.map { p ->
                    p.copy(marks = p.marks.map { m -> (m.style as? MarkStyle.Anchor)?.let { m.copy(style = it.copy(anchorId = ids.newStrokeId())) } ?: m })
                },
            ),
        )
    }

    /**
     * An anchor or link landing on text becomes a letter of it: an anchor in front of the paragraph
     * there, a link behind it. Returns false if it is not one or there is no text at its place.
     */
    private fun DocumentSession.Transaction.intoText(draft: ElementDraft): Boolean {
        val (x, y) = when (draft) {
            is AnchorDraft -> draft.x to draft.y
            is NoteLinkDraft -> draft.x to draft.y
            else -> return false
        }
        val (text, style) = linkText(draft, ids) ?: return false
        val box = state.textBoxes.firstOrNull { it.bounds.contains(x, y) } ?: return false
        val index = paragraphTops(box).indexOfLast { box.y + it <= y }.coerceIn(0, box.text.paragraphs.lastIndex)
        val p = box.text.paragraphs[index]
        val mark = TextMark(0, text.length, style)
        val changed = if (draft is AnchorDraft) {
            p.copy(text = text + p.text, marks = listOf(mark) + p.marks.map { it.copy(start = it.start + text.length, end = it.end + text.length) })
        } else {
            val gap = if (p.text.isEmpty()) "" else " "
            val at = p.text.length + gap.length
            p.copy(text = p.text + gap + text, marks = p.marks + mark.copy(start = at, end = at + text.length))
        }
        val content = RichText(box.text.paragraphs.toMutableList().also { it[index] = changed }).withAnchorsPlaced(box.widthMm, measureText)
        flowing(box.slot.flowId) {
            replaceElements(listOf(box.id), listOf(TextBoxDraft(box.x, box.y, box.widthMm, measureText(content, box.widthMm), content, box.slot)))
        }
        return true
    }

    /** Gives the anchor [anchorId] in a text the name [name]. Returns false if no text has it. */
    fun renameInText(anchorId: StrokeId, name: String): Boolean {
        val box = session.state.value.textBoxes.firstOrNull { b -> b.anchors.any { it.anchorId == anchorId } } ?: return false
        val text = RichText(
            box.text.paragraphs.map { p ->
                p.copy(marks = p.marks.map { m -> (m.style as? MarkStyle.Anchor)?.takeIf { it.anchorId == anchorId }?.let { m.copy(style = it.copy(name = name)) } ?: m })
            },
        )
        session.replaceElements(listOf(box.id), listOf(TextBoxDraft(box.x, box.y, box.widthMm, box.heightMm, text, box.slot)))
        return true
    }

    private fun DocumentSession.Transaction.newFlow(drafts: List<BlockDraft>): List<PageElement> {
        val flowId = ids.newStrokeId()
        var order: String? = null
        val slotted = drafts.map { d -> d.inSlot(FlowSlot(flowId, FractionalIndex.between(order, null).also { order = it })) }
        return replaceElements(emptySet(), slotted)
    }

    /**
     * Puts the pictures [drafts] beside the picture [FlowDrop.besideOf], in its row: the row's pictures
     * get new keys left to right (a picture alone becomes a row with the new ones).
     */
    private fun DocumentSession.Transaction.insertBeside(drop: FlowDrop, drafts: List<BlockDraft>): List<PageElement> = flowing(drop.flowId) {
        val anchor = state.element(drop.besideOf!!) as FlowBlock
        val flow = state.flow(drop.flowId)!!
        val row = Flow.rowsOf(flow.blocks).first { r -> r.any { it.id == anchor.id } }.filter { it.id !in drop.moving }
        val at = row.indexOfFirst { it.id == anchor.id } + if (drop.toTheRight) 1 else 0
        val items: List<Any> = row.subList(0, at) + drafts + row.subList(at, row.size)
        var key: String? = null
        fun slot() = anchor.slot.copy(beside = FractionalIndex.between(key, null).also { key = it })
        val replaced = ArrayList<StrokeId>()
        val out = ArrayList<BlockDraft>()
        val newOnes = ArrayList<Int>()
        for (item in items) {
            if (item is FlowBlock) {
                replaced += item.id
                out += state.naturalDraft(item).inSlot(slot())
            } else {
                newOnes += out.size
                out += (item as BlockDraft).inSlot(slot())
            }
        }
        val added = replaceElements(replaced, out)
        newOnes.map { added[it] }
    }

    /** Puts [drafts] into the flow at [drop]; the text box it lands in is split at the paragraph. */
    private fun DocumentSession.Transaction.insert(drop: FlowDrop, drafts: List<BlockDraft>): List<PageElement> =
        if (drop.besideOf != null && drafts.all { it is ImageDraft }) insertBeside(drop, drafts) else insertBetween(drop, drafts)

    private fun DocumentSession.Transaction.insertBetween(drop: FlowDrop, drafts: List<BlockDraft>): List<PageElement> = flowing(drop.flowId) {
        val flow = state.flow(drop.flowId)
        val after = drop.after
        val high = flow?.blocks?.firstOrNull { after == null || it.slot.order > after }?.slot?.order
        var order = after
        fun slot() = FlowSlot(drop.flowId, FractionalIndex.between(order, high).also { order = it })
        val width = flow?.bounds?.width ?: drafts.maxOf { it.widthMm }
        val split = drop.split
        // Under a list item a block goes in like the item's text, under an indented block like it.
        val indent = if (split != null) indentBelow(split.text.paragraphs[drop.paragraph - 1]) else indentBelow(flow?.blocks?.lastOrNull { it.slot.order == after })
        val blocks = drafts.map { d ->
            if (d is TextBoxDraft) d.inSlot(slot()) else d.fittedTo(width - indent * Flow.INDENT_MM).inSlot(slot().copy(indent = indent))
        }
        if (split == null) {
            replaceElements(emptySet(), blocks)
        } else {
            val paragraphs = split.text.paragraphs
            val head = RichText(paragraphs.subList(0, drop.paragraph))
            val tail = RichText(paragraphs.subList(drop.paragraph, paragraphs.size))
            fun part(text: RichText, slot: FlowSlot) = TextBoxDraft(split.x, split.y, split.widthMm, measureText(text, split.widthMm), text.withAnchorsPlaced(split.widthMm, measureText), slot)
            val added = replaceElements(listOf(split.id), listOf(part(head, split.slot)) + blocks + part(tail, slot()))
            added.subList(1, added.size - 1)
        }
    }.also { joinText(drop.flowId, measureText) }

    /** The indent of a block right below [above]: a list item's text, an indented block's own. */
    private fun indentBelow(above: Any?): Int = when (above) {
        is TextBox -> indentBelow(above.text.paragraphs.last())
        is com.stemaker.nibsync.model.TextParagraph -> if (above.list == com.stemaker.nibsync.model.ListKind.NONE) 0 else above.indent + 1
        is FlowBlock -> above.slot.indent
        else -> 0
    }.coerceIn(0, FlowSlot.MAX_INDENT)

    /**
     * Indents the selected tables, pictures, files and to-dos by [delta] steps (one undo step, text
     * stays as it is). Returns the new ids of the selection.
     */
    fun indent(selection: Set<StrokeId>, delta: Int): Set<StrokeId> {
        val state = session.state.value
        val blocks = state.elements.filter { it.id in selection }.filterIsInstance<FlowBlock>()
            .filter { it !is TextBox && (it.slot.indent + delta) in 0..FlowSlot.MAX_INDENT }
        if (blocks.isEmpty()) return selection
        return session.edit {
            val result = LinkedHashSet(selection)
            for (b in blocks) {
                val flow = state.flow(b.slot.flowId) ?: continue
                val slot = b.slot.copy(indent = b.slot.indent + delta)
                val draft = (b.toDraft() as BlockDraft).let { it.translated(flow.x + slot.offsetMm - it.x, 0f).inSlot(slot) }
                val added = flowing(flow.id) { replaceElements(listOf(b.id), listOf(draft)) }
                result -= b.id
                added.mapTo(result) { it.id }
            }
            result
        }
    }

    /** True if [delta] steps of [indent] can be applied to something in [selection]. */
    fun canIndent(selection: Set<StrokeId>, delta: Int): Boolean =
        session.state.value.elements.any { it.id in selection && it is FlowBlock && it !is TextBox && (it.slot.indent + delta) in 0..FlowSlot.MAX_INDENT }

    /** Replaces the picture or file link [element] by [draft] of a new size; what is below moves along. */
    fun resize(element: FileElement, draft: BlockDraft): PageElement = session.edit {
        flowing(element.slot.flowId) { replaceElements(listOf(element.id), listOf(draft.inSlot(element.slot))).single() }
    }

    /** Moves the box [flowId] by ([dx], [dy]) mm by its handle, not onto other text (one undo step). */
    fun moveBy(flowId: StrokeId, dx: Float, dy: Float) = session.edit { dragFlow(flowId, dx, dy) }

    /** Removes the elements [ids] and, if [pull], moves what was below their flows up; see [removeBlocks]. */
    fun remove(ids: Collection<StrokeId>, pull: Boolean = true) = session.edit { removeBlocks(ids, measureText, pull) }

    /**
     * Where text begun at ([x], [y]) mm goes into a box: in the gap between two lines of it that are
     * not text (pictures, a row of them, tables, files, to-dos; the outer [BAND_MM] of a picture
     * counts as gap, so it is easy to hit), or up to [CONTINUE_MM] below a box that does not end in
     * text. Null if it is a new box of its own.
     */
    fun continuation(x: Float, y: Float): FlowContinuation? {
        for (flow in session.state.value.flows) {
            val b = flow.bounds
            if (x < b.left || x > b.right) continue
            val lines = Flow.rowsOf(flow.blocks)
            for ((i, line) in lines.withIndex()) {
                if (line.size == 1 && line.single() is TextBox) continue
                val next = lines.getOrNull(i + 1)
                if (next != null && next.size == 1 && next.single() is TextBox) continue
                val bottom = line.maxOf { it.bounds.bottom }
                val from = bottom - if (line.any { it is TableElement }) 0f else BAND_MM
                val to = when {
                    next == null -> bottom + CONTINUE_MM
                    next.any { it is TableElement } -> next.minOf { it.y }
                    else -> next.minOf { it.y } + BAND_MM
                }
                if (y <= from || y > to) continue
                val slot = FlowSlot(flow.id, FractionalIndex.between(line.first().slot.order, next?.first()?.slot?.order))
                val width = flow.blocks.filterIsInstance<TextBox>().maxOfOrNull { it.widthMm } ?: b.width
                return FlowContinuation(b.left, bottom + Flow.GAP_MM, width, slot)
            }
        }
        return null
    }

    private companion object {
        /** How far below a flow (mm) new text still continues it. */
        const val CONTINUE_MM = 12f

        /** How far into a picture (mm) a text tap still counts as the gap next to it. */
        const val BAND_MM = 3f

        /** Share of a picture's width at either side where pictures dropped go beside it. */
        const val BESIDE_SHARE = 0.3f
    }
}

/**
 * Removes the elements [ids]: text blocks that become neighbours are joined, and with [pull] what
 * was below a flow moves up. [measure] gives a text's height at a width.
 */
internal fun DocumentSession.Transaction.removeBlocks(ids: Collection<StrokeId>, measure: (RichText, Float) -> Float, pull: Boolean) {
    val flows = ids.mapNotNullTo(LinkedHashSet()) { state.flowOf(it)?.id }
    val free = ids.filter { state.flowOf(it) == null }
    for (flowId in flows) {
        val remove = {
            replaceElements(ids.filter { state.flowOf(it)?.id == flowId }, emptyList())
            joinText(flowId, measure)
        }
        if (pull) flowing(flowId, remove) else remove()
    }
    replaceElements(free, emptyList())
}

/** Joins text blocks of the flow [flowId] that are neighbours into one. */
internal fun DocumentSession.Transaction.joinText(flowId: StrokeId, measure: (RichText, Float) -> Float) {
    while (true) {
        val blocks = state.flow(flowId)?.blocks ?: return
        val i = blocks.indices.firstOrNull { it > 0 && blocks[it - 1] is TextBox && blocks[it] is TextBox } ?: return
        val first = blocks[i - 1] as TextBox
        val second = blocks[i] as TextBox
        val text = RichText(first.text.paragraphs + second.text.paragraphs).withAnchorsPlaced(first.widthMm, measure)
        replaceElements(listOf(first.id, second.id), listOf(TextBoxDraft(first.x, first.y, first.widthMm, measure(text, first.widthMm), text, first.slot)))
    }
}

/** Moves the flow [flowId] by ([dx], [dy]) mm (its open block's handle was dragged), not onto other text. */
internal fun DocumentSession.Transaction.dragFlow(flowId: StrokeId, dx: Float, dy: Float) {
    val flow = state.flow(flowId) ?: return
    val b = flow.bounds
    val x = maxOf(0f, flow.x + dx)
    moveFlow(flowId, x, state.freeTop(x, x + b.width, maxOf(0f, flow.y + dy), flowId))
    val moved = state.flow(flowId) ?: return
    val (pushed, shift) = Reflow.plan(state.elements, moved.blocks.mapTo(HashSet()) { it.id }, null, moved.bounds)
    move(pushed, 0f, shift)
}

/**
 * Where a block spanning [left]..[right] mm can start at or below [y]: not inside another flow
 * than [except] (it goes below that one instead), so it never covers text.
 */
internal fun DocumentState.freeTop(left: Float, right: Float, y: Float, except: StrokeId? = null): Float {
    val others = flows.filter { it.id != except }.map { it.bounds }
    var top = y
    while (true) {
        val inside = others.firstOrNull { it.left < right && it.right > left && it.top <= top && it.bottom > top } ?: return top
        top = inside.bottom + Flow.GAP_MM
    }
}

/** What moves with the edited [block] of the flow [flowId] once it is stored with the extent [after]. */
internal fun DocumentState.flowPreview(block: FlowBlock?, flowId: StrokeId?, after: Rect): FlowPreview? {
    val flow = flowId?.let(::flow)
    if (flow == null) {
        val (moving, dy) = Reflow.plan(elements, emptySet(), null, after)
        return if (moving.isEmpty() || dy == 0f) null else FlowPreview(moving.mapTo(HashSet()) { it.id }, dy)
    }
    val dy = if (block == null) after.height + Flow.GAP_MM else after.bottom - block.bounds.bottom
    if (kotlin.math.abs(dy) < Reflow.MIN_SHIFT_MM) return null
    val b = flow.bounds
    val (below, _) = Reflow.plan(elements, flow.blocks.mapTo(HashSet()) { it.id }, b, Rect(b.left, b.top, maxOf(b.right, after.right), b.bottom + dy))
    val later = if (block == null) emptyList() else flow.blocks.dropWhile { it.id != block.id }.drop(1)
    return FlowPreview((below + later).mapTo(HashSet()) { it.id }, dy).takeIf { it.ids.isNotEmpty() }
}

/** [block] as a draft where it is shown, a picture of a row at its own size (not as scaled down there). */
internal fun DocumentState.naturalDraft(block: FlowBlock): BlockDraft {
    val draft = block.toDraft() as BlockDraft
    val own = stored(block.id) as? ImageElement ?: return draft
    return (draft as ImageDraft).copy(widthMm = own.widthMm, heightMm = own.heightMm)
}

/** What shows an anchor or link in text, and its mark; null for other drafts. */
internal fun linkText(draft: ElementDraft, ids: IdGenerator): Pair<String, MarkStyle>? = when (draft) {
    is AnchorDraft -> MarkStyle.Anchor.SIGN to MarkStyle.Anchor(draft.anchorId ?: ids.newStrokeId(), draft.name)
    is NoteLinkDraft -> (if (draft.anchorName == null) draft.title else "${draft.title} › ${draft.anchorName}") to
        MarkStyle.NoteLink(draft.target, draft.anchorId, draft.anchorName)
    else -> null
}

/** The text with its anchors' [MarkStyle.Anchor.yMm] set for a box [widthMm] wide. */
internal fun RichText.withAnchorsPlaced(widthMm: Float, measure: (RichText, Float) -> Float): RichText {
    if (paragraphs.none { p -> p.marks.any { it.style is MarkStyle.Anchor } }) return this
    return RichText(
        paragraphs.mapIndexed { i, p ->
            if (p.marks.none { it.style is MarkStyle.Anchor }) return@mapIndexed p
            val y = if (i == 0) 0f else measure(RichText(paragraphs.subList(0, i)), widthMm)
            p.copy(marks = p.marks.map { m -> (m.style as? MarkStyle.Anchor)?.let { m.copy(style = it.copy(yMm = y)) } ?: m })
        },
    )
}

/** A picture wider than [widthMm] scaled down to it; other blocks stay as they are. */
internal fun BlockDraft.fittedTo(widthMm: Float): BlockDraft =
    if (this is ImageDraft && this.widthMm > widthMm) copy(widthMm = widthMm, heightMm = heightMm * widthMm / this.widthMm) else this
