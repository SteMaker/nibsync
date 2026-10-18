package de.nibsync.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import de.nibsync.document.DocumentState
import de.nibsync.model.Asset
import de.nibsync.model.BackgroundPattern
import de.nibsync.model.FileLink
import de.nibsync.model.ImageElement
import de.nibsync.model.Stroke
import de.nibsync.model.TextBox
import de.nibsync.model.geometry.Viewport
import kotlin.math.roundToInt

/**
 * Draws a document page like the Android NoteCanvasView: desk, paper, pattern, then the
 * elements back to front (pictures, highlighter, text and links, ink).
 */
class PagePainter(private val text: DesktopTextLayout, private val media: MediaCache) {
    /** Measures link labels in layout pixels (1 sp = 1 px), scaled down when drawn. */
    private val labels = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    fun draw(scope: DrawScope, doc: DocumentState, viewport: Viewport, hiddenTextBox: Any? = null) = with(scope) {
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
            for (element in doc.elements) {
                if (!element.bounds.intersects(visible)) continue
                when (element) {
                    is Stroke -> StrokePainter.draw(this, element)
                    is TextBox -> if (element.id != hiddenTextBox) drawTextBox(this, element)
                    is ImageElement -> drawImage(this, element)
                    is FileLink -> drawLink(this, element)
                }
            }
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
        private val SHADOW = Color(0x22000000)
        private val PATTERN = Color(0xffb4c6e2)
        private val PLACEHOLDER = Color(0xffeceff1)
        private val OUTLINE = Color(0xffb0bec5)
        private val LINK_FILL = Color(0xfff5f7fa)
        private val BADGE = Color(0xffd32f2f)
        private val NAME = Color(0xff263238)
    }
}
