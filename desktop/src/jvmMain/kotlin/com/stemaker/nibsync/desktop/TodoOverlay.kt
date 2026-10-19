package com.stemaker.nibsync.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.geometry.Viewport
import com.stemaker.nibsync.text.TextBoxEditor
import com.stemaker.nibsync.text.TodoEdit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

object TodoTags {
    const val MOVE = "move-todo"
    const val RESIZE = "resize-todo"
}

/** Idle time after typing before the to-do is stored. */
private const val SAVE_DELAY_MS = 1500L

/**
 * Over the open to-do (the page draws its frame and circle): a handle to move it (top left),
 * one to resize it (bottom right corner) and, for a typed to-do, the text editor. Handwriting
 * goes through the page, so the rest of the frame lets the pen through.
 */
@OptIn(FlowPreview::class)
@Composable
fun TodoOverlay(edit: TodoEdit, viewport: Viewport, strings: Strings, controller: NoteController) {
    val t = viewport.documentToView
    val scale by rememberUpdatedState(viewport.scale)
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    fun px(mm: Float) = with(density) { (mm * viewport.scale).toDp() }
    edit.text?.let { text ->
        val focus = remember(edit.key) { FocusRequester() }
        LaunchedEffect(edit.key) {
            focus.requestFocus()
            snapshotFlow { text.contentVersion }.drop(1).debounce(SAVE_DELAY_MS).collect { controller.todo.save() }
        }
        Box(Modifier.offset { IntOffset(t.mapX(edit.x + TodoItem.CONTENT_OFFSET_MM).roundToInt(), t.mapY(edit.y + TodoItem.PADDING_MM).roundToInt()) }) {
            TextBoxEditor(
                controller = text,
                widthMm = edit.widthMm - TodoItem.CONTENT_OFFSET_MM - TodoItem.PADDING_MM,
                pxPerMm = viewport.scale,
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
    Box(Modifier.offset { IntOffset(t.mapX(edit.x).roundToInt(), t.mapY(edit.y).roundToInt()) }) {
        Icon(
            Icons.Filled.OpenWith,
            contentDescription = strings.moveTodo,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .offset(x = (-12).dp, y = (-26).dp)
                .size(22.dp)
                .background(accent, CircleShape)
                .padding(3.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(TodoTags.MOVE)
                .pointerInput(edit.key) {
                    detectDragGestures(onDragEnd = { controller.todo.save() }) { change, drag ->
                        change.consume()
                        controller.todo.move(drag.x / scale, drag.y / scale)
                    }
                },
        )
        Box(
            Modifier
                .offset(x = px(edit.widthMm) - 8.dp, y = px(edit.heightMm) - 8.dp)
                .size(16.dp)
                .background(accent, CircleShape)
                .pointerHoverIcon(PointerIcon.Crosshair)
                .semantics { contentDescription = strings.resizeTodo }
                .testTag(TodoTags.RESIZE)
                .pointerInput(edit.key) {
                    detectDragGestures(onDragEnd = { controller.todo.save() }) { change, drag ->
                        change.consume()
                        controller.todo.resize(drag.x / scale, drag.y / scale)
                    }
                },
        )
    }
}
