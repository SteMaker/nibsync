package com.stemaker.nibsync.desktop

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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.geometry.Viewport

/** How large a page is shown at first: about this many pixels per millimetre. */
const val DEFAULT_PX_PER_MM = 4.5f

/** A press that moves less than this (dp) is a click. */
private const val CLICK_TOLERANCE_DP = 4f

/** How close (dp) to the scale handle a press grabs it. */
private const val HANDLE_REACH_DP = 14f

private const val HOLD_CHECK_MS = 50L

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
    controller: NoteController? = null,
    overlay: PagePainter.Overlay = PagePainter.Overlay(),
) {
    val density = LocalDensity.current.density
    // While drawing, check regularly whether the pen is held still (shape recognition).
    val drawing = overlay.gesture is GesturePreview.Drawing
    LaunchedEffect(drawing, controller) {
        while (drawing && controller != null) {
            delay(HOLD_CHECK_MS)
            controller.checkHold(System.currentTimeMillis())
        }
    }
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
            .pointerInput(controller, viewport) {
                if (controller == null) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val toDoc = viewport.viewToDocument
                        val x = toDoc.mapX(change.position.x)
                        val y = toDoc.mapY(change.position.y)
                        val pressure = if (change.type == PointerType.Stylus || change.type == PointerType.Eraser) change.pressure else null
                        val tolerance = CLICK_TOLERANCE_DP * density / viewport.scale
                        val now = System.currentTimeMillis()
                        when (event.type) {
                            PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                                controller.pointerDown(x, y, now, pressure, tolerance, HANDLE_REACH_DP * density / viewport.scale)
                            }
                            PointerEventType.Move -> if (event.buttons.isPrimaryPressed) controller.pointerMove(x, y, now, pressure)
                            PointerEventType.Release -> controller.pointerUp(x, y, now, tolerance, viewport.pageWidthMm)
                            else -> Unit
                        }
                    }
                }
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
        painter.draw(this, doc, viewport, hiddenTextBox, overlay)
    }
}

/** A viewport for a page of [pageWidthMm] before the view has a size. */
fun initialViewport(pageWidthMm: Float, pageHeightMm: Float) =
    Viewport(pageWidthMm = pageWidthMm, viewWidthPx = 0f, viewHeightPx = 0f, minPageHeightMm = pageHeightMm, minZoom = 0.1f)
