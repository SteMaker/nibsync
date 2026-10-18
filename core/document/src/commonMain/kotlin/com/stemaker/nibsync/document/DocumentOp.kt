package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox

/**
 * Lamport timestamp of an operation. Unique per op (a device never reuses a counter value) and
 * totally ordered, so every device derives the same stroke z-order from the same set of ops.
 */
data class OpStamp(val counter: Long, val device: DeviceId) : Comparable<OpStamp> {
    override fun compareTo(other: OpStamp): Int =
        compareValuesBy(this, other, { it.counter }, { it.device.value })
}

/**
 * A change to a document. Documents are stored and synced as a set of ops; the visible state is
 * the result of applying all of them. Applying is idempotent and order-independent, see
 * [DocumentState.apply].
 */
sealed interface DocumentOp {
    val stamp: OpStamp

    data class AddStrokes(override val stamp: OpStamp, val strokes: List<Stroke>) : DocumentOp

    data class AddTextBoxes(override val stamp: OpStamp, val boxes: List<TextBox>) : DocumentOp

    /** Adds pictures and file links. Their files must be in the document's assets. */
    data class AddFiles(override val stamp: OpStamp, val elements: List<FileElement>) : DocumentOp

    /** Removes elements of any kind (strokes, text boxes) by id. */
    data class RemoveStrokes(override val stamp: OpStamp, val ids: Set<StrokeId>) : DocumentOp

    /** Changes the paper pattern. Concurrent changes: the one with the highest stamp wins. */
    data class SetBackground(override val stamp: OpStamp, val background: BackgroundSpec) : DocumentOp
}
