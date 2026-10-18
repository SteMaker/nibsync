package com.stemaker.nibsync.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.paragraph.type.OrderedListStyleType
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont

/**
 * State of the text box being edited, and the formatting commands of the text tool bar. With
 * a selection a command formats it; with just a cursor it applies to what is typed next.
 */
@Stable
@OptIn(ExperimentalRichTextApi::class)
class TextEditController internal constructor(internal val state: RichTextState) {

    val isBold: Boolean get() = state.currentSpanStyle.fontWeight == FontWeight.Bold
    val isItalic: Boolean get() = state.currentSpanStyle.fontStyle == FontStyle.Italic
    val font: TextFont get() = (state.currentRichSpanStyle as? FontRun)?.font ?: TextFont.SANS
    val sizePt: Float
        get() = state.currentSpanStyle.fontSize.takeIf { it.type == TextUnitType.Sp }?.value ?: RichText.DEFAULT_SIZE_PT
    val isBulletList: Boolean get() = state.isUnorderedList
    val isNumberedList: Boolean get() = state.isOrderedList
    val canIndent: Boolean get() = state.canIncreaseListLevel
    val canOutdent: Boolean get() = state.canDecreaseListLevel

    fun toggleBold() = state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
    fun toggleItalic() = state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
    fun setFont(font: TextFont) = state.addRichSpan(FontRun(font))
    fun setSize(pt: Float) = state.addSpanStyle(SpanStyle(fontSize = pt.sp))
    fun toggleBulletList() = keepingPendingStyle { state.toggleUnorderedList() }
    fun toggleNumberedList() = keepingPendingStyle { state.toggleOrderedList() }
    fun indent() = keepingPendingStyle { state.increaseListLevel() }
    fun outdent() = keepingPendingStyle { state.decreaseListLevel() }

    /**
     * List changes reset the style chosen for the next typed text (bold, font, size picked
     * with just a cursor); carry it over so "font, size, list, type" works in any order.
     */
    private fun keepingPendingStyle(change: () -> Unit) {
        val collapsed = state.selection.collapsed
        val span = state.currentSpanStyle
        val rich = state.currentRichSpanStyle
        change()
        if (!collapsed) return
        pendingStyle = {
            state.addSpanStyle(span)
            if (rich is FontRun) state.addRichSpan(rich)
        }
        pendingStyle?.invoke()
        // The text field moves the cursor behind the new list prefix a frame later, which
        // drops the style again; TextBoxEditor calls [reapplyPendingStyle] after that.
        pendingStyleRequests++
    }

    private var pendingStyle: (() -> Unit)? = null

    /** Changes when a style should be applied again once the text field has settled. */
    internal var pendingStyleRequests by mutableIntStateOf(0)
        private set

    internal fun reapplyPendingStyle() {
        pendingStyle?.invoke()
        pendingStyle = null
    }

    /** Replaces the whole content (the editor's own undo history starts over). */
    fun setContent(text: RichText) {
        state.setRichTextDocument(RichTextConversion.toDocument(text))
    }

    /** The current content in the stored format. */
    val text: RichText get() = RichTextConversion.fromDocument(state.toRichTextDocument())

    /** Changes whenever the content changes; read it in a snapshot flow to save while typing. */
    val contentVersion: Any get() = state.annotatedString

    companion object {
        fun create(initial: RichText): TextEditController {
            val state = RichTextState()
            // Lists as the page renderer draws them (RichTextLayout).
            state.config.listIndent = TextMetrics.LIST_INDENT_PT
            state.config.orderedListStyleType = OrderedListStyleType.Decimal
            return TextEditController(state).also { it.setContent(initial) }
        }
    }
}

/** A controller for editing [initial]; a new one whenever [key] changes. */
@Composable
fun rememberTextEditController(key: Any?, initial: () -> RichText): TextEditController =
    remember(key) { TextEditController.create(initial()) }
