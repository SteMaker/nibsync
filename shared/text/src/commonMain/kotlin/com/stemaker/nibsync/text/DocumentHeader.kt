package com.stemaker.nibsync.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stemaker.nibsync.model.geometry.Viewport
import kotlin.math.roundToInt

object HeaderTags {
    const val TITLE = "document-title"
    const val DATE = "document-date"
    const val PATH = "document-path"
}

/** Where the header sits on the page and how large it is, in mm. */
object DocumentHeaderLayout {
    const val LEFT_MM = 10f
    const val TOP_MM = 5f
    const val TITLE_MM = 7f
    const val DATE_MM = 3.2f
}

/**
 * The document's title and creation time at the top of the page, scrolling and zooming with it.
 * It is not a page element: it shows the library entry, so it cannot be moved or deleted. The
 * title is changed by typing (a tap focuses it); Enter or leaving the field renames the
 * document with [onRename] (null: read-only, e.g. a document outside the library).
 */
@Composable
fun DocumentHeader(
    title: String,
    created: String,
    showTitle: Boolean,
    showDate: Boolean,
    viewport: Viewport,
    onRename: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** The title is being typed (true) or not anymore; e.g. to keep shortcuts away from it. */
    onEditingChange: (Boolean) -> Unit = {},
) {
    if (!showTitle && !showDate) return
    val t = viewport.documentToView
    val density = LocalDensity.current
    // Text sizes in mm on the page, independent of the screen density.
    fun mm(v: Float) = with(density) { (v * viewport.scale).toSp() }
    val maxWidth = with(density) { ((viewport.pageWidthMm - 2 * DocumentHeaderLayout.LEFT_MM) * viewport.scale).toDp() }
    val rename by rememberUpdatedState(onRename)
    Column(
        modifier.offset { IntOffset(t.mapX(DocumentHeaderLayout.LEFT_MM).roundToInt(), t.mapY(DocumentHeaderLayout.TOP_MM).roundToInt()) },
    ) {
        if (showTitle) {
            val style = TextStyle(color = TITLE_COLOR, fontSize = mm(DocumentHeaderLayout.TITLE_MM), fontWeight = FontWeight.SemiBold)
            if (rename == null) {
                BasicText(title, Modifier.widthIn(max = maxWidth).testTag(HeaderTags.TITLE), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                var value by remember { mutableStateOf(TextFieldValue(title)) }
                var focused by remember { mutableStateOf(false) }
                // A rename from elsewhere (library, other device) shows up unless one is typing.
                LaunchedEffect(title) { if (!focused) value = TextFieldValue(title) }
                val focusManager = LocalFocusManager.current
                fun commit() {
                    val text = value.text.trim()
                    if (text.isEmpty()) value = TextFieldValue(title) else if (text != title) rename?.invoke(text)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.replace("\n", "")) },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(TITLE_COLOR),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .widthIn(min = 40.dp, max = maxWidth)
                        .onFocusChanged {
                            if (focused && !it.isFocused) commit()
                            if (!focused && it.isFocused) value = value.copy(selection = TextRange(value.text.length))
                            if (focused != it.isFocused) onEditingChange(it.isFocused)
                            focused = it.isFocused
                        }
                        .testTag(HeaderTags.TITLE),
                )
            }
        }
        if (showDate) {
            BasicText(created, Modifier.testTag(HeaderTags.DATE), style = TextStyle(color = DATE_COLOR, fontSize = mm(DocumentHeaderLayout.DATE_MM)))
        }
    }
}

/** Where the document is (book › chapter › document), small at the bottom right; read-only. */
@Composable
fun DocumentPath(path: List<String>, modifier: Modifier = Modifier) {
    if (path.isEmpty()) return
    BasicText(
        path.joinToString(" › "),
        modifier
            .padding(8.dp)
            .background(Color(0xccffffff), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(HeaderTags.PATH),
        style = TextStyle(color = DATE_COLOR, fontSize = 11.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val TITLE_COLOR = Color(0xff202124)
private val DATE_COLOR = Color(0xff5f6368)
