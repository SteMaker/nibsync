package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.Reflow
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BlockDraft
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.FileLinkDraft
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.FlowSlot
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.Quantization
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoDraft
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.LinkElement
import com.stemaker.nibsync.model.NoteLink
import com.stemaker.nibsync.model.NoteLinkDraft
import com.stemaker.nibsync.model.TableDraft
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.toDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Persists ops of the local device in the order they were created. */
fun interface OpJournal {
    suspend fun append(op: DocumentOp)
}

/**
 * The editing session of one open document on this device.
 *
 * All mutating calls must come from one thread (the UI thread). State updates are visible
 * synchronously through [state]; persisting happens asynchronously but strictly in order on
 * [scope]. Call [flush] to wait until everything has been written.
 */
class DocumentSession(
    initial: DocumentState,
    val device: DeviceId,
    private val journal: OpJournal,
    scope: CoroutineScope,
    private val ids: IdGenerator = IdGenerator(),
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<DocumentState> = _state.asStateFlow()

    private val undo = UndoManager(ids)
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private sealed interface Pending {
        class Write(val op: DocumentOp) : Pending
        class Barrier(val done: CompletableDeferred<Unit>) : Pending
    }

    private val pending = Channel<Pending>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in pending) {
                when (item) {
                    is Pending.Write -> journal.append(item.op)
                    is Pending.Barrier -> item.done.complete(Unit)
                }
            }
        }
    }

    /**
     * Turns a captured draft into a stroke of this document and adds it. Points are snapped to
     * the storage grid ([Quantization]) so the stroke looks the same after reloading.
     */
    fun addStroke(draft: StrokeDraft): Stroke {
        val stroke = newStroke(draft)
        applyEdit(Edit.Added(listOf(stroke)), record = true)
        return stroke
    }

    private fun newElement(draft: ElementDraft): PageElement = when (draft) {
        is StrokeDraft -> newStroke(draft)
        is BlockDraft -> newBlock(draft)
        is AnchorDraft -> Anchor(
            id = ids.newStrokeId(),
            anchorId = draft.anchorId ?: ids.newStrokeId(),
            x = Quantization.quantizePosition(draft.x),
            y = Quantization.quantizePosition(draft.y),
            name = draft.name,
            createdAtEpochMs = clock(),
            author = device,
        )
        is NoteLinkDraft -> NoteLink(
            id = ids.newStrokeId(),
            x = Quantization.quantizePosition(draft.x),
            y = Quantization.quantizePosition(draft.y),
            target = draft.target,
            anchorId = draft.anchorId,
            title = draft.title,
            anchorName = draft.anchorName,
            createdAtEpochMs = clock(),
            author = device,
        )
    }

    /**
     * A block in the draft's slot, or the only one of a new flow. It is stored with the position of
     * its flow: its own one is derived, but it is what a flow without a position register starts at.
     */
    private fun newBlock(draft: BlockDraft): FlowBlock {
        val id = ids.newStrokeId()
        val slot = draft.slot ?: FlowSlot(id, FlowSlot.FIRST)
        val flow = _state.value.flow(slot.flowId)
        // A draft is where the block is shown, an indented one right of its flow's edge.
        val x = Quantization.quantizePosition(flow?.x ?: (draft.x - slot.offsetMm))
        val y = Quantization.quantizePosition(flow?.y ?: draft.y)
        return when (draft) {
            is TextBoxDraft -> TextBox(
                id = id,
                x = x,
                y = y,
                widthMm = Quantization.quantizePosition(draft.widthMm),
                heightMm = Quantization.quantizePosition(draft.heightMm),
                text = draft.text,
                createdAtEpochMs = clock(),
                author = device,
                slot = slot,
            )
            is ImageDraft -> ImageElement(
                id = id,
                x = x,
                y = y,
                widthMm = Quantization.quantizePosition(draft.widthMm),
                heightMm = Quantization.quantizePosition(draft.heightMm),
                asset = draft.asset,
                createdAtEpochMs = clock(),
                author = device,
                slot = slot,
            )
            is FileLinkDraft -> FileLink(
                id = id,
                x = x,
                y = y,
                widthMm = Quantization.quantizePosition(draft.widthMm),
                heightMm = Quantization.quantizePosition(draft.heightMm),
                asset = draft.asset,
                fileName = draft.fileName,
                createdAtEpochMs = clock(),
                author = device,
                slot = slot,
            )
            is TodoDraft -> TodoItem(
                id = id,
                taskId = draft.taskId ?: ids.newStrokeId(),
                x = x,
                y = y,
                widthMm = Quantization.quantizePosition(draft.widthMm),
                heightMm = Quantization.quantizePosition(draft.heightMm),
                content = draft.content,
                createdAtEpochMs = clock(),
                author = device,
                slot = slot,
            )
            is TableDraft -> TableElement(
                id = id,
                tableId = draft.tableId ?: ids.newStrokeId(),
                x = x,
                y = y,
                columns = draft.columns.map { it.copy(widthMm = Quantization.quantizePosition(it.widthMm)) },
                rows = draft.rows,
                header = draft.header,
                cells = draft.cells.mapValues { (_, c) -> c.copy(heightMm = Quantization.quantizePosition(c.heightMm)) },
                merges = draft.merges,
                createdAtEpochMs = clock(),
                author = device,
                slot = slot,
            )
        }
    }

    /**
     * Handwriting for a to-do: [drafts] with points relative to its content area become strokes
     * (snapped to the storage grid like [addStroke]); they are only stored with the to-do.
     */
    fun todoInk(drafts: List<StrokeDraft>): TodoContent.Ink = TodoContent.Ink(drafts.map(::newStroke))

    private fun newStroke(draft: StrokeDraft): Stroke {
        return Stroke(
            id = ids.newStrokeId(),
            layer = draft.layer,
            brush = draft.brush,
            tool = draft.tool,
            points = Quantization.quantize(draft.points),
            createdAtEpochMs = clock(),
            author = device,
        )
    }

    /**
     * Adds [draft] (quantized like [addStroke]) and removes the strokes [replaces] in one undo step.
     * Ids that no longer exist are ignored.
     */
    fun replaceStrokes(replaces: Collection<StrokeId>, draft: StrokeDraft): Stroke =
        replaceStrokes(replaces, listOf(draft)).single()

    /**
     * Removes [replaces] and adds [drafts] in one undo step (erasing, shape merging). Returns the
     * added strokes. Ids that no longer exist are ignored.
     */
    fun replaceStrokes(replaces: Collection<StrokeId>, drafts: List<StrokeDraft>): List<Stroke> =
        replaceElements(replaces, drafts).map { it as Stroke }

    /** Adds a text box; see [replaceElements] for changing one. */
    fun addTextBox(draft: TextBoxDraft): TextBox = replaceElements(emptySet(), listOf(draft)).single() as TextBox

    /**
     * Removes [replaces] (elements of any kind) and adds [drafts] in one undo step: moving or
     * pasting a selection, storing an edited text box. Returns the added elements in the
     * order of [drafts]. Ids that no longer exist are ignored.
     */
    fun replaceElements(replaces: Collection<StrokeId>, drafts: List<ElementDraft>): List<PageElement> =
        edit { replaceElements(replaces, drafts) }

    /**
     * Several changes as one undo step, e.g. a text box that grew and what moved below it.
     * Returns what [block] returns.
     */
    fun <T> edit(block: Transaction.() -> T): T {
        val t = Transaction()
        val result = t.block()
        when (t.edits.size) {
            0 -> Unit
            1 -> undo.record(t.edits.single())
            else -> undo.record(Edit.Steps(t.edits.toList()))
        }
        _canUndo.value = undo.canUndo
        _canRedo.value = undo.canRedo
        return result
    }

    /** The changes of one [edit] step; each is applied at once, they are undone together. */
    inner class Transaction internal constructor() {
        internal val edits = ArrayList<Edit>()

        val state: DocumentState get() = _state.value

        private fun apply(e: Edit) {
            applyEdit(e, record = false)
            edits += e
        }

        /** Removes [replaces] and adds [drafts]; returns the added elements in the order of [drafts]. */
        fun replaceElements(replaces: Collection<StrokeId>, drafts: List<ElementDraft>): List<PageElement> {
            val current = _state.value
            val removed = replaces.mapNotNull { current.element(it) }
            val added = drafts.map(::newElement)
            when {
                removed.isEmpty() && added.isEmpty() -> return emptyList()
                removed.isEmpty() -> apply(Edit.Added(added))
                added.isEmpty() -> apply(Edit.Removed(removed))
                else -> apply(Edit.Replaced(removed, added))
            }
            return added
        }

        /** Sets cells of the table [tableId]; cells that already hold the same content are left out. */
        fun setTableCells(tableId: StrokeId, updates: List<CellUpdate>) {
            val table = _state.value.tables.firstOrNull { it.tableId == tableId } ?: return
            val quantized = updates.map { it.copy(cell = it.cell.copy(heightMm = Quantization.quantizePosition(it.cell.heightMm))) }
            val changed = quantized.filter { table.cell(it.key) != it.cell }
            if (changed.isEmpty()) return
            apply(Edit.TableCells(tableId, changed, changed.map { CellUpdate(it.key, table.cell(it.key)) }))
        }

        /** Puts the flow [flowId] at ([x], [y]) mm. */
        fun moveFlow(flowId: StrokeId, x: Float, y: Float) {
            val flow = _state.value.flow(flowId) ?: return
            val qx = Quantization.quantizePosition(x)
            val qy = Quantization.quantizePosition(y)
            if (qx != flow.x || qy != flow.y) apply(Edit.FlowPosition(flowId, qx, qy, flow.x, flow.y))
        }

        /**
         * Moves [elements] by ([dx], [dy]) mm: the flows of the blocks among them as a whole, the rest
         * (strokes, free anchors and links) by remove + add. Returns what was added.
         */
        fun move(elements: List<PageElement>, dx: Float, dy: Float): List<PageElement> {
            if (elements.isEmpty() || (dx == 0f && dy == 0f)) return emptyList()
            val (blocks, free) = elements.partition { it is FlowBlock }
            for (flowId in blocks.mapTo(LinkedHashSet()) { (it as FlowBlock).slot.flowId }) {
                val flow = _state.value.flow(flowId) ?: continue
                moveFlow(flowId, flow.x + dx, flow.y + dy)
            }
            return if (free.isEmpty()) emptyList() else replaceElements(free.map { it.id }, free.map { it.toDraft().translated(dx, dy) })
        }

        /** Runs [change] on the flow [flowId]; what is below it moves with the flow's new extent ([Reflow.plan]). */
        fun <T> flowing(flowId: StrokeId, change: () -> T): T {
            val before = _state.value.flow(flowId)
            val result = change()
            val after = _state.value.flow(flowId)
            val source = (before?.blocks.orEmpty() + after?.blocks.orEmpty()).mapTo(HashSet()) { it.id }
            val (moving, dy) = Reflow.plan(_state.value.elements, source, before?.bounds, after?.bounds)
            move(moving, 0f, dy)
            return result
        }
    }

    /** Removes elements of any kind in one undo step. */
    fun removeStrokes(ids: Collection<StrokeId>) {
        val current = _state.value
        val strokes = ids.mapNotNull { current.element(it) }
        if (strokes.isNotEmpty()) applyEdit(Edit.Removed(strokes), record = true)
    }

    /** Ticks the task off or reopens it, in one undo step. Does nothing if it already is so. */
    fun setTodoDone(taskId: StrokeId, done: Boolean) {
        val was = _state.value.isDone(taskId)
        if (was != done) applyEdit(Edit.TodoDone(taskId, done, was), record = true)
    }

    /**
     * Sets the content of cells of the table [tableId] in one undo step. Cells that already hold
     * the same content are left out; nothing happens if none changes.
     */
    fun setTableCells(tableId: StrokeId, updates: List<CellUpdate>) = edit { setTableCells(tableId, updates) }

    /**
     * Replaces the table element [replaces] by [draft] (same table id: moved, resized or its rows
     * and columns changed) and sets [cells] of it, all in one undo step. Returns the new element.
     */
    fun replaceTable(replaces: StrokeId, draft: TableDraft, cells: List<CellUpdate>): TableElement = edit {
        val table = replaceElements(listOf(replaces), listOf(draft)).single() as TableElement
        setTableCells(table.tableId, cells)
        state.tables.first { it.id == table.id }
    }

    /** Changes the paper pattern. It is a document setting, not an edit: undo does not revert it. */
    fun setBackground(background: BackgroundSpec) {
        if (background == _state.value.background) return
        emit { DocumentOp.SetBackground(it, background) }
    }

    fun undo(): Boolean {
        val edit = undo.undo() ?: return false
        applyEdit(edit, record = false)
        return true
    }

    fun redo(): Boolean {
        val edit = undo.redo() ?: return false
        applyEdit(edit, record = false)
        return true
    }

    /** Merges ops created by other devices (sync). They are not written to the local journal. */
    fun applyRemote(ops: Iterable<DocumentOp>) {
        _state.value = _state.value.applyAll(ops)
    }

    /**
     * Suspends until all ops issued so far have been handed to the journal. Returns at once for
     * a closed session (e.g. its document was deleted), where nothing more is written.
     */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (pending.trySend(Pending.Barrier(done)).isFailure) return
        done.await()
    }

    /** Stops writing; ops still queued are written, later edits only change the in-memory state. */
    fun close() {
        pending.close()
    }

    /** Applies one op with the next Lamport stamp and queues it for the journal. */
    private fun emit(makeOp: (OpStamp) -> DocumentOp) {
        val current = _state.value
        val op = makeOp(OpStamp(current.clock + 1, device))
        _state.value = current.apply(op)
        pending.trySend(Pending.Write(op))
    }

    /** One add op per element kind: strokes, text boxes, files, to-dos, anchors and links, tables. */
    private fun emitAdd(elements: List<PageElement>) {
        val strokes = elements.filterIsInstance<Stroke>()
        val boxes = elements.filterIsInstance<TextBox>()
        if (strokes.isNotEmpty()) emit { DocumentOp.AddStrokes(it, strokes) }
        if (boxes.isNotEmpty()) emit { DocumentOp.AddTextBoxes(it, boxes) }
        val files = elements.filterIsInstance<FileElement>()
        if (files.isNotEmpty()) emit { DocumentOp.AddFiles(it, files) }
        val todos = elements.filterIsInstance<TodoItem>()
        if (todos.isNotEmpty()) emit { DocumentOp.AddTodos(it, todos) }
        val links = elements.filterIsInstance<LinkElement>()
        if (links.isNotEmpty()) emit { DocumentOp.AddLinks(it, links) }
        val tables = elements.filterIsInstance<TableElement>()
        if (tables.isNotEmpty()) emit { DocumentOp.AddTables(it, tables) }
    }

    private fun applyEdit(edit: Edit, record: Boolean) {
        when (edit) {
            is Edit.Added -> emitAdd(edit.strokes)
            is Edit.Removed -> emit { DocumentOp.RemoveStrokes(it, edit.strokes.map { s -> s.id }.toSet()) }
            is Edit.Replaced -> {
                emit { DocumentOp.RemoveStrokes(it, edit.removed.map { s -> s.id }.toSet()) }
                emitAdd(edit.added)
            }
            is Edit.TodoDone -> emit { DocumentOp.SetTodoDone(it, edit.taskId, edit.done) }
            is Edit.TableCells -> emit { DocumentOp.SetTableCells(it, edit.tableId, edit.cells) }
            is Edit.FlowPosition -> emit { DocumentOp.SetFlowPosition(it, edit.flowId, edit.x, edit.y) }
            is Edit.Steps -> edit.edits.forEach { applyEdit(it, record = false) }
        }
        if (record) undo.record(edit)
        _canUndo.value = undo.canUndo
        _canRedo.value = undo.canRedo
    }
}
