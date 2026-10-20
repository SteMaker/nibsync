package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.FileLinkDraft
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
        is TextBoxDraft -> TextBox(
            id = ids.newStrokeId(),
            x = Quantization.quantizePosition(draft.x),
            y = Quantization.quantizePosition(draft.y),
            widthMm = Quantization.quantizePosition(draft.widthMm),
            heightMm = Quantization.quantizePosition(draft.heightMm),
            text = draft.text,
            createdAtEpochMs = clock(),
            author = device,
        )
        is ImageDraft -> ImageElement(
            id = ids.newStrokeId(),
            x = Quantization.quantizePosition(draft.x),
            y = Quantization.quantizePosition(draft.y),
            widthMm = Quantization.quantizePosition(draft.widthMm),
            heightMm = Quantization.quantizePosition(draft.heightMm),
            asset = draft.asset,
            createdAtEpochMs = clock(),
            author = device,
        )
        is FileLinkDraft -> FileLink(
            id = ids.newStrokeId(),
            x = Quantization.quantizePosition(draft.x),
            y = Quantization.quantizePosition(draft.y),
            widthMm = Quantization.quantizePosition(draft.widthMm),
            heightMm = Quantization.quantizePosition(draft.heightMm),
            asset = draft.asset,
            fileName = draft.fileName,
            createdAtEpochMs = clock(),
            author = device,
        )
        is TodoDraft -> TodoItem(
            id = ids.newStrokeId(),
            taskId = draft.taskId ?: ids.newStrokeId(),
            x = Quantization.quantizePosition(draft.x),
            y = Quantization.quantizePosition(draft.y),
            widthMm = Quantization.quantizePosition(draft.widthMm),
            heightMm = Quantization.quantizePosition(draft.heightMm),
            content = draft.content,
            createdAtEpochMs = clock(),
            author = device,
        )
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
    fun replaceElements(replaces: Collection<StrokeId>, drafts: List<ElementDraft>): List<PageElement> {
        val current = _state.value
        val removed = replaces.mapNotNull { current.element(it) }
        val added = drafts.map(::newElement)
        val edit = when {
            removed.isEmpty() && added.isEmpty() -> return emptyList()
            removed.isEmpty() -> Edit.Added(added)
            added.isEmpty() -> Edit.Removed(removed)
            else -> Edit.Replaced(removed, added)
        }
        applyEdit(edit, record = true)
        return added
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

    /** One add op per element kind: strokes, text boxes, files, to-dos, anchors and links. */
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
        }
        if (record) undo.record(edit)
        _canUndo.value = undo.canUndo
        _canRedo.value = undo.canRedo
    }
}
