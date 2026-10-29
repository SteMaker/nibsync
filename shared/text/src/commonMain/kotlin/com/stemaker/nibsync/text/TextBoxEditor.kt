package com.stemaker.nibsync.text

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.stemaker.nibsync.model.TextFont

/**
 * The editable text of a box, drawn at the page zoom ([pxPerMm]) so it looks like the box on
 * the page. The caller positions it over the box. [onCursor] gets the cursor's top and bottom
 * in mm below the editor's top whenever the cursor or the text moves.
 */
@Composable
fun TextBoxEditor(
    controller: TextEditController,
    widthMm: Float,
    pxPerMm: Float,
    modifier: Modifier = Modifier,
    onCursor: ((topMm: Float, bottomMm: Float) -> Unit)? = null,
) {
    var layout by remember(controller) { mutableStateOf<TextLayoutResult?>(null) }
    val scale by rememberUpdatedState(pxPerMm)
    val cursorCallback by rememberUpdatedState(onCursor)
    LaunchedEffect(controller) {
        snapshotFlow { layout to controller.state.selection }.collect { (l, sel) ->
            val callback = cursorCallback ?: return@collect
            val r = l?.let { runCatching { it.getCursorRect(sel.end.coerceIn(0, it.layoutInput.text.length)) }.getOrNull() } ?: return@collect
            callback(r.top / scale, r.bottom / scale)
        }
    }
    val requests = controller.pendingStyleRequests
    LaunchedEffect(controller, requests) {
        if (requests == 0) return@LaunchedEffect
        // Let the text field apply its own cursor update first.
        repeat(2) { withFrameNanos { } }
        controller.reapplyPendingStyle()
    }
    // Keep the cursor out of list markers (Home, arrow keys, clicks): they are text to the
    // editor but not to the user.
    LaunchedEffect(controller) {
        var previous: TextRange? = null
        snapshotFlow { controller.state.selection }.collect { sel ->
            val before = previous
            val start = controller.cursorOutsideMarkers(sel.start, before?.start)
            val end = if (sel.collapsed) start else controller.cursorOutsideMarkers(sel.end, before?.end)
            val fixed = TextRange(start, end)
            previous = fixed
            if (fixed != sel) controller.state.selection = fixed
        }
    }
    val density = TextMetrics.densityFor(pxPerMm)
    CompositionLocalProvider(LocalDensity provides density) {
        val width = with(density) { (widthMm * pxPerMm).toDp() }
        BasicRichTextEditor(
            state = controller.state,
            modifier = modifier.width(width).testTag(TextTags.EDITOR).onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && controller.lineBreak(soft = e.isShiftPressed)
            },
            textStyle = TextMetrics.baseStyle,
            cursorBrush = SolidColor(TextMetrics.TEXT_COLOR),
            onTextLayout = { layout = it },
        )
    }
}

/** Test tags used by UI tests. */
object TextTags {
    const val EDITOR = "text-editor"
    const val PRESET = "text-preset"
    fun preset(preset: TextPreset) = "text-preset-${preset.name}"
    const val BOLD = "text-bold"
    const val ITALIC = "text-italic"
    const val FONT = "text-font"
    fun font(font: TextFont) = "text-font-${font.name}"
    const val SIZE = "text-size"
    fun size(pt: Float) = "text-size-${pt.toInt()}"
    const val GROW = "text-grow"
    const val SHRINK = "text-shrink"
    const val BULLETS = "text-bullets"
    const val NUMBERS = "text-numbers"
    const val INDENT = "text-indent"
    const val OUTDENT = "text-outdent"
    const val DELETE = "text-delete"
}
