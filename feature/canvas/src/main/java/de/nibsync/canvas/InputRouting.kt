package de.nibsync.canvas

import android.annotation.SuppressLint
import android.view.MotionEvent

/**
 * Decides what a pointer does on the canvas: a stylus writes (or erases), fingers navigate.
 * Writing with a finger can be enabled for devices without a pen.
 */
object InputRouting {
    enum class Action { DRAW, ERASE, NAVIGATE, IGNORE }

    /**
     * @param eraserActive the eraser tool is selected: everything that would write erases.
     */
    fun actionFor(toolType: Int, drawWithFinger: Boolean, eraserActive: Boolean = false): Action {
        val write = if (eraserActive) Action.ERASE else Action.DRAW
        return when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> write
            MotionEvent.TOOL_TYPE_FINGER -> if (drawWithFinger) write else Action.NAVIGATE
            MotionEvent.TOOL_TYPE_MOUSE -> write
            // The eraser end of a pen (if it has one) always erases.
            MotionEvent.TOOL_TYPE_ERASER -> Action.ERASE
            else -> Action.NAVIGATE
        }
    }

    /**
     * True if the system cancelled this pointer, e.g. because it was recognised as a palm.
     * FLAG_CANCELED is only set from API 33 on; the inlined constant is harmless before that.
     */
    @SuppressLint("InlinedApi")
    fun isCanceled(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
}
