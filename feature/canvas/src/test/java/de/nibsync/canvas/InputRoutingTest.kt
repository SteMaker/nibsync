package de.nibsync.canvas

import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import de.nibsync.canvas.InputRouting.Action
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InputRoutingTest {
    @Test
    fun stylusDraws() {
        assertThat(InputRouting.actionFor(MotionEvent.TOOL_TYPE_STYLUS, drawWithFinger = false)).isEqualTo(Action.DRAW)
    }

    @Test
    fun fingerNavigatesUnlessFingerDrawingIsOn() {
        assertThat(InputRouting.actionFor(MotionEvent.TOOL_TYPE_FINGER, drawWithFinger = false)).isEqualTo(Action.NAVIGATE)
        assertThat(InputRouting.actionFor(MotionEvent.TOOL_TYPE_FINGER, drawWithFinger = true)).isEqualTo(Action.DRAW)
    }

    @Test
    fun eraserEndIsIgnoredForNow() {
        assertThat(InputRouting.actionFor(MotionEvent.TOOL_TYPE_ERASER, drawWithFinger = true)).isEqualTo(Action.IGNORE)
    }

    @Test
    fun unknownToolNavigates() {
        assertThat(InputRouting.actionFor(MotionEvent.TOOL_TYPE_UNKNOWN, drawWithFinger = false)).isEqualTo(Action.NAVIGATE)
    }

    @Test
    fun cancelAndCanceledFlagAreDetected() {
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        assertThat(InputRouting.isCanceled(cancel)).isTrue()
        val up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
        assertThat(InputRouting.isCanceled(up)).isFalse()
        val palm = MotionEvent.obtain(0, 0, MotionEvent.ACTION_POINTER_UP, 1, arrayOf(MotionEvent.PointerProperties()), arrayOf(MotionEvent.PointerCoords()), 0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_CANCELED)
        assertThat(InputRouting.isCanceled(palm)).isTrue()
        listOf(cancel, up, palm).forEach { it.recycle() }
    }
}
