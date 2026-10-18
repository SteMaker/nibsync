package de.nibsync.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import de.nibsync.document.DocumentState
import de.nibsync.model.geometry.Viewport

/** How large a page is shown at first: about this many pixels per millimetre. */
const val DEFAULT_PX_PER_MM = 4.5f

/** Pixels scrolled per mouse wheel notch. */
private const val WHEEL_STEP_PX = 60f

/**
 * The endless page of a document. The mouse wheel scrolls, Ctrl + wheel zooms around the
 * pointer. [viewport] state is hoisted so editors can be placed over the page.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PageView(
    doc: DocumentState,
    painter: PagePainter,
    viewport: Viewport,
    onViewportChange: (Viewport) -> Unit,
    modifier: Modifier = Modifier,
    hiddenTextBox: Any? = null,
) {
    var initialised by remember(doc.meta.id) { mutableStateOf(false) }
    LaunchedEffect(doc.contentBottomMm) {
        if (doc.contentBottomMm != viewport.contentBottomMm) onViewportChange(viewport.withContentBottom(doc.contentBottomMm))
    }
    Canvas(
        modifier
            .fillMaxSize()
            .testTag("page")
            .onSizeChanged { size ->
                var v = viewport.withViewSize(size.width.toFloat(), size.height.toFloat())
                if (!initialised && v.hasSize) {
                    // Start at a comfortable size instead of filling a wide monitor with the page.
                    val zoom = (DEFAULT_PX_PER_MM * v.pageWidthMm / v.viewWidthPx).coerceIn(v.minZoom, v.maxZoom)
                    v = v.copy(zoom = zoom).clamped()
                    initialised = true
                }
                onViewportChange(v)
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val delta = change.scrollDelta
                if (event.keyboardModifiers.isCtrlPressed) {
                    val factor = if (delta.y < 0) 1.1f else 1f / 1.1f
                    onViewportChange(viewport.zoomBy(factor, change.position.x, change.position.y))
                } else {
                    onViewportChange(viewport.scrollByPx(delta.x * WHEEL_STEP_PX, delta.y * WHEEL_STEP_PX))
                }
            },
    ) {
        painter.draw(this, doc, viewport, hiddenTextBox)
    }
}

/** A viewport for a page of [pageWidthMm] before the view has a size. */
fun initialViewport(pageWidthMm: Float, pageHeightMm: Float) =
    Viewport(pageWidthMm = pageWidthMm, viewWidthPx = 0f, viewHeightPx = 0f, minPageHeightMm = pageHeightMm, minZoom = 0.1f)
