package de.nibsync.canvas

import android.annotation.SuppressLint
import android.view.MotionEvent

/**
 * Decides what a pointer does on the canvas: a stylus writes, fingers navigate (scroll, zoom).
 * Writing with a finger can be enabled for devices without a pen.
 */
object InputRouting {
    enum class Action { DRAW, NAVIGATE, IGNORE }

    fun actionFor(toolType: Int, drawWithFinger: Boolean): Action = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> Action.DRAW
        MotionEvent.TOOL_TYPE_FINGER -> if (drawWithFinger) Action.DRAW else Action.NAVIGATE
        MotionEvent.TOOL_TYPE_MOUSE -> Action.DRAW
        // Reserved for the eraser feature; until then the eraser end of a pen does nothing.
        MotionEvent.TOOL_TYPE_ERASER -> Action.IGNORE
        else -> Action.NAVIGATE
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
