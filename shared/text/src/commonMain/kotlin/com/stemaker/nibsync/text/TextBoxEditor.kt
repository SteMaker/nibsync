package com.stemaker.nibsync.text

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.stemaker.nibsync.model.TextFont

/**
 * The editable text of a box, drawn at the page zoom ([pxPerMm]) so it looks like the box on
 * the page. The caller positions it over the box.
 */
@Composable
fun TextBoxEditor(controller: TextEditController, widthMm: Float, pxPerMm: Float, modifier: Modifier = Modifier) {
    val requests = controller.pendingStyleRequests
    LaunchedEffect(controller, requests) {
        if (requests == 0) return@LaunchedEffect
        // Let the text field apply its own cursor update first.
        repeat(2) { withFrameNanos { } }
        controller.reapplyPendingStyle()
    }
    val density = TextMetrics.densityFor(pxPerMm)
    CompositionLocalProvider(LocalDensity provides density) {
        val width = with(density) { (widthMm * pxPerMm).toDp() }
        BasicRichTextEditor(
            state = controller.state,
            modifier = modifier.width(width).testTag(TextTags.EDITOR),
            textStyle = TextMetrics.baseStyle,
            cursorBrush = SolidColor(TextMetrics.TEXT_COLOR),
        )
    }
}

/** Test tags used by UI tests. */
object TextTags {
    const val EDITOR = "text-editor"
    const val BOLD = "text-bold"
    const val ITALIC = "text-italic"
    const val FONT = "text-font"
    fun font(font: TextFont) = "text-font-${font.name}"
    const val SIZE = "text-size"
    fun size(pt: Float) = "text-size-${pt.toInt()}"
    const val BULLETS = "text-bullets"
    const val NUMBERS = "text-numbers"
    const val INDENT = "text-indent"
    const val OUTDENT = "text-outdent"
    const val DELETE = "text-delete"
}
