package com.stemaker.nibsync.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.model.geometry.Viewport
import com.stemaker.nibsync.text.TextBoxEditor
import com.stemaker.nibsync.text.TextEditing
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

object OverlayTags {
    const val MOVE = "move-text"
    const val RESIZE = "resize-text"
}

/** Idle time after typing before the text box is stored. */
private const val SAVE_DELAY_MS = 1500L

/**
 * The editor over the open text box, at the page zoom, with a handle to move the box (top
 * left) and one to change its width (right edge). Saves while typing.
 */
@OptIn(FlowPreview::class)
@Composable
fun TextBoxOverlay(editing: TextEditing, viewport: Viewport, strings: Strings, controller: NoteController) {
    val t = viewport.documentToView
    val scale by rememberUpdatedState(viewport.scale)
    val focus = remember(editing.key) { FocusRequester() }
    LaunchedEffect(editing.key) {
        focus.requestFocus()
        snapshotFlow { editing.controller.contentVersion }.drop(1).debounce(SAVE_DELAY_MS).collect { controller.text.save() }
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.offset { IntOffset(t.mapX(editing.x).roundToInt(), t.mapY(editing.y).roundToInt()) }) {
        TextBoxEditor(
            controller = editing.controller,
            widthMm = editing.widthMm,
            pxPerMm = viewport.scale,
            modifier = Modifier.focusRequester(focus).border(1.dp, accent.copy(alpha = 0.6f)),
        )
        Icon(
            Icons.Filled.OpenWith,
            contentDescription = strings.moveTextBox,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .offset(x = (-12).dp, y = (-26).dp)
                .size(22.dp)
                .background(accent, CircleShape)
                .padding(3.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(OverlayTags.MOVE)
                .pointerInput(editing.key) {
                    detectDragGestures(onDragEnd = { controller.text.save() }) { change, drag ->
                        change.consume()
                        controller.text.move(drag.x / scale, drag.y / scale)
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 8.dp)
                .size(width = 8.dp, height = 26.dp)
                .background(accent, RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(OverlayTags.RESIZE)
                .pointerInput(editing.key) {
                    detectHorizontalDragGestures(onDragEnd = { controller.text.save() }) { change, dx ->
                        change.consume()
                        controller.text.resize(dx / scale)
                    }
                },
        )
    }
}
