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
import com.mohamedrejeb.richeditor.document.RichTextBlockType
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.withoutStrayBreaks
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
    val canGrow: Boolean get() = RichText.SIZES_PT.any { it > sizePt }
    val canShrink: Boolean get() = RichText.SIZES_PT.any { it < sizePt }

    /** One step up in [RichText.SIZES_PT], from the size at the cursor (a selection gets that size + 1 step). */
    fun grow() {
        RichText.SIZES_PT.firstOrNull { it > sizePt }?.let(::setSize)
    }

    fun shrink() {
        RichText.SIZES_PT.lastOrNull { it < sizePt }?.let(::setSize)
    }
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

    /**
     * The current content in the stored format. Should the editor's model ever be inconsistent
     * (e.g. after an unusual paste), the text is kept without formatting rather than crashing.
     */
    val text: RichText
        get() = try {
            RichTextConversion.fromDocument(state.toRichTextDocument())
        } catch (e: IllegalArgumentException) {
            RichText.plain(state.annotatedString.text.replace("\r\n", "\n")).withoutStrayBreaks()
        } catch (e: IndexOutOfBoundsException) {
            RichText.plain(state.annotatedString.text.replace("\r\n", "\n")).withoutStrayBreaks()
        }

    /**
     * Where the editor shows list markers ("• ", "1. ") as text: a cursor must not stand before
     * or inside them. Each range spans the marker; its end is where the paragraph's text begins.
     */
    internal fun listMarkers(): List<IntRange> {
        val shown = state.annotatedString.text
        val blocks = runCatching { state.toRichTextDocument().blocks }.getOrNull() ?: return emptyList()
        val out = ArrayList<IntRange>()
        var pos = 0
        for (block in blocks) {
            if (pos > shown.length) break
            var marker = 0
            if (block.type is RichTextBlockType.ListItem) {
                // The marker is one token and a space, e.g. "• " or "12. ".
                marker = LIST_MARKER.matchAt(shown, pos)?.value?.length ?: 0
                if (marker > 0) out += pos until pos + marker
            }
            // Paragraphs are separated by one character.
            pos += marker + block.text.length + 1
        }
        return out
    }

    /**
     * Where the cursor goes instead of [offset] if that is before or inside a list marker:
     * behind the marker (Home, a click, an arrow from the left), or to the end of the previous
     * paragraph when it stepped left from the start of the text ([from] is the previous
     * position), so it can still leave the line to the left.
     */
    internal fun cursorOutsideMarkers(offset: Int, from: Int?): Int {
        val marker = listMarkers().firstOrNull { offset in it } ?: return offset
        val textStart = marker.last + 1
        val steppedLeft = from == textStart && offset < from
        return if (steppedLeft && marker.first > 0) marker.first - 1 else textStart
    }

    /** Changes whenever the content changes; read it in a snapshot flow to save while typing. */
    val contentVersion: Any get() = state.annotatedString

    companion object {
        private val LIST_MARKER = Regex("\\S+ ")

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
