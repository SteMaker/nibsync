package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.BackgroundPattern
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.geometry.Viewport
import kotlin.math.roundToInt

/**
 * Draws a document page like the Android NoteCanvasView: desk, paper, pattern, then the
 * elements back to front (pictures, highlighter, text and links, ink).
 */
class PagePainter(private val text: DesktopTextLayout, private val media: MediaCache) {
    /** Measures link labels in layout pixels (1 sp = 1 px), scaled down when drawn. */
    private val labels = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    /** What is drawn on top of the document: gesture in progress and selection. */
    class Overlay(
        val gesture: GesturePreview? = null,
        val selection: Set<StrokeId> = emptySet(),
        val selectionFrame: com.stemaker.nibsync.model.geometry.Rect? = null,
        val resizable: Boolean = false,
    )

    fun draw(scope: DrawScope, doc: DocumentState, viewport: Viewport, hiddenTextBox: Any? = null, overlay: Overlay = Overlay()) = with(scope) {
        drawRect(DESK)
        if (!viewport.hasSize) return
        val t = viewport.documentToView
        val pageLeft = t.mapX(0f)
        val pageTop = t.mapY(0f)
        val pageSize = Size(viewport.pageWidthMm * t.scale, viewport.documentHeightMm * t.scale)
        drawRect(SHADOW, Offset(pageLeft + 4f, pageTop + 4f), pageSize)
        drawRect(Color.White, Offset(pageLeft, pageTop), pageSize)

        val visible = viewport.visibleDocumentRect
        withTransform({
            translate(t.tx, t.ty)
            scale(t.scale, t.scale, pivot = Offset.Zero)
        }) {
            drawPattern(this, doc, viewport)
            val gesture = overlay.gesture
            // While erasing, the strokes as they would be after the gesture.
            val elements = if (gesture is GesturePreview.Erasing) {
                (doc.elements.filter { it !is Stroke } + gesture.visible).sortedBy { it.layer.zIndex }
            } else {
                doc.elements
            }
            for (element in elements) {
                val selected = element.id in overlay.selection
                when {
                    selected && gesture is GesturePreview.Moving -> translate(gesture.dx, gesture.dy) { drawElement(this, element, hiddenTextBox) }
                    selected && gesture is GesturePreview.Resizing && element is com.stemaker.nibsync.model.FileElement -> withTransform({
                        translate(element.x, element.y)
                        scale(gesture.factor, gesture.factor, pivot = Offset.Zero)
                        translate(-element.x, -element.y)
                    }) { drawElement(this, element, hiddenTextBox) }
                    element.bounds.intersects(visible) -> drawElement(this, element, hiddenTextBox)
                }
            }
            if (gesture is GesturePreview.Drawing) StrokePainter.draw(this, gesture.stroke)
        }
        drawOverlay(this, viewport, overlay)
    }

    private fun drawElement(scope: DrawScope, element: com.stemaker.nibsync.model.PageElement, hiddenTextBox: Any?) {
        when (element) {
            is Stroke -> StrokePainter.draw(scope, element)
            is TextBox -> if (element.id != hiddenTextBox) drawTextBox(scope, element)
            is ImageElement -> drawImage(scope, element)
            is FileLink -> drawLink(scope, element)
        }
    }

    /** Selection frame, scale handle, lasso and eraser in screen pixels. */
    private fun drawOverlay(scope: DrawScope, viewport: Viewport, overlay: Overlay) = with(scope) {
        val t = viewport.documentToView
        val dash = StrokeStyle(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        val gesture = overlay.gesture
        overlay.selectionFrame?.let { f ->
            var r = f
            if (gesture is GesturePreview.Moving) r = com.stemaker.nibsync.model.geometry.Rect(f.left + gesture.dx, f.top + gesture.dy, f.right + gesture.dx, f.bottom + gesture.dy)
            if (gesture is GesturePreview.Resizing) {
                val pad = NoteController.SELECTION_PADDING_MM
                r = com.stemaker.nibsync.model.geometry.Rect(f.left, f.top, f.left + pad + (f.width - 2 * pad) * gesture.factor + pad, f.top + pad + (f.height - 2 * pad) * gesture.factor + pad)
            }
            drawRect(SELECTION, Offset(t.mapX(r.left), t.mapY(r.top)), Size(r.width * t.scale, r.height * t.scale), style = dash)
            if (overlay.resizable && gesture !is GesturePreview.Moving) {
                drawCircle(SELECTION, HANDLE_RADIUS_PX, Offset(t.mapX(r.right), t.mapY(r.bottom)))
            }
        }
        when (gesture) {
            is GesturePreview.Lassoing -> {
                val path = androidx.compose.ui.graphics.Path()
                val p = gesture.path
                for (i in p.indices step 2) {
                    if (i == 0) path.moveTo(t.mapX(p[i]), t.mapY(p[i + 1])) else path.lineTo(t.mapX(p[i]), t.mapY(p[i + 1]))
                }
                drawPath(path, SELECTION, style = dash)
            }
            is GesturePreview.Erasing -> drawCircle(ERASER, gesture.radiusMm * t.scale, Offset(t.mapX(gesture.x), t.mapY(gesture.y)), style = StrokeStyle(2f))
            else -> Unit
        }
    }

    private fun drawPattern(scope: DrawScope, doc: DocumentState, viewport: Viewport) = with(scope) {
        val spec = doc.background
        if (!BackgroundPattern.hasRows(spec) || spec.spacingMm * viewport.scale < 6f) return
        val visible = viewport.visibleDocumentRect
        val top = maxOf(0f, visible.top)
        val bottom = minOf(viewport.documentHeightMm, visible.bottom)
        val rows = BackgroundPattern.rows(spec, top, bottom)
        val columns = BackgroundPattern.columns(spec, viewport.pageWidthMm)
        val px = 1f / viewport.scale
        if (BackgroundPattern.isDotted(spec)) {
            val r = maxOf(1f * px, 0.225f)
            for (y in rows) for (x in columns) drawCircle(PATTERN, r, Offset(x, y))
        } else {
            val w = maxOf(px, 0.15f)
            for (y in rows) drawLine(PATTERN, Offset(0f, y), Offset(viewport.pageWidthMm, y), w)
            for (x in columns) drawLine(PATTERN, Offset(x, top), Offset(x, bottom), w)
        }
    }

    private fun drawTextBox(scope: DrawScope, box: TextBox) = with(scope) {
        val layout = text.layoutOf(box)
        val f = 1f / DesktopTextLayout.LAYOUT_PX_PER_MM
        withTransform({
            translate(box.x, box.y)
            scale(f, f, pivot = Offset.Zero)
        }) {
            drawText(layout)
        }
    }

    private fun drawImage(scope: DrawScope, image: ImageElement) = with(scope) {
        when (val loaded = media.get(image.asset)) {
            is MediaCache.Loaded.Bitmap -> withTransform({
                translate(image.x, image.y)
                scale(image.widthMm / loaded.image.width, image.heightMm / loaded.image.height, pivot = Offset.Zero)
            }) { drawImage(loaded.image) }
            is MediaCache.Loaded.Vector -> translate(image.x, image.y) {
                with(loaded.painter) { draw(Size(image.widthMm, image.heightMm)) }
            }
            null -> {
                drawRect(PLACEHOLDER, Offset(image.x, image.y), Size(image.widthMm, image.heightMm))
                drawRect(OUTLINE, Offset(image.x, image.y), Size(image.widthMm, image.heightMm), style = StrokeStyle(0.3f))
            }
        }
    }

    private fun drawLink(scope: DrawScope, link: FileLink) = with(scope) {
        val h = link.heightMm
        val r = CornerRadius(h * 0.2f)
        val topLeft = Offset(link.x, link.y)
        val size = Size(link.widthMm, h)
        drawRoundRect(LINK_FILL, topLeft, size, r)
        drawRoundRect(OUTLINE, topLeft, size, r, style = StrokeStyle(0.3f))
        val pad = h * 0.18f
        val badgeH = h - 2 * pad
        drawRoundRect(BADGE, Offset(link.x + pad, link.y + pad), Size(badgeH * 0.8f, badgeH), CornerRadius(h * 0.08f))
        // Labels are measured at 20 px/mm and scaled down, like text boxes.
        val k = DesktopTextLayout.LAYOUT_PX_PER_MM
        val label = if (link.asset.mimeType == Asset.PDF) "PDF" else "FILE"
        val badge = labels.measure(label, TextStyle(color = Color.White, fontSize = (badgeH * 0.3f * k).sp))
        val name = labels.measure(
            link.fileName,
            TextStyle(color = NAME, fontSize = (h * 0.36f * k).sp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = ((link.widthMm - badgeH * 0.8f - 3 * pad) * k).roundToInt().coerceAtLeast(1)),
        )
        withTransform({
            translate(link.x, link.y)
            scale(1f / k, 1f / k, pivot = Offset.Zero)
        }) {
            drawText(badge, topLeft = Offset((pad + badgeH * 0.4f) * k - badge.size.width / 2f, h * k / 2 - badge.size.height / 2f))
            drawText(name, topLeft = Offset((2 * pad + badgeH * 0.8f) * k, h * k / 2 - name.size.height / 2f))
        }
    }

    companion object {
        val DESK = Color(0xffe0e0e0)
        const val HANDLE_RADIUS_PX = 9f
        private val SELECTION = Color(0xff1a3fa8)
        private val ERASER = Color(0xff757575)
        private val SHADOW = Color(0x22000000)
        private val PATTERN = Color(0xffb4c6e2)
        private val PLACEHOLDER = Color(0xffeceff1)
        private val OUTLINE = Color(0xffb0bec5)
        private val LINK_FILL = Color(0xfff5f7fa)
        private val BADGE = Color(0xffd32f2f)
        private val NAME = Color(0xff263238)
    }
}
