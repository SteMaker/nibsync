package de.nibsync.document

import de.nibsync.model.DeviceId
import de.nibsync.model.IdGenerator
import de.nibsync.model.Quantization
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokeId
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
        val stroke = Stroke(
            id = ids.newStrokeId(),
            layer = draft.layer,
            brush = draft.brush,
            tool = draft.tool,
            points = Quantization.quantize(draft.points),
            createdAtEpochMs = clock(),
            author = device,
        )
        applyEdit(Edit.Added(listOf(stroke)), record = true)
        return stroke
    }

    fun removeStrokes(ids: Collection<StrokeId>) {
        val current = _state.value
        val strokes = ids.mapNotNull { current[it] }
        if (strokes.isNotEmpty()) applyEdit(Edit.Removed(strokes), record = true)
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

    /** Suspends until all ops issued so far have been handed to the journal. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        pending.send(Pending.Barrier(done))
        done.await()
    }

    fun close() {
        pending.close()
    }

    private fun applyEdit(edit: Edit, record: Boolean) {
        val current = _state.value
        val stamp = OpStamp(current.clock + 1, device)
        val op = when (edit) {
            is Edit.Added -> DocumentOp.AddStrokes(stamp, edit.strokes)
            is Edit.Removed -> DocumentOp.RemoveStrokes(stamp, edit.strokes.map { it.id }.toSet())
        }
        _state.value = current.apply(op)
        pending.trySend(Pending.Write(op))
        if (record) undo.record(edit)
        _canUndo.value = undo.canUndo
        _canRedo.value = undo.canRedo
    }
}
