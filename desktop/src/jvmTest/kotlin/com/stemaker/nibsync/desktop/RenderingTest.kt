package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.graphics.asSkiaBitmap
import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.OpStamp
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.StrokePoint
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import java.io.File

class RenderingTest {
    private val viewport = initialViewport(210f, 297f).withViewSize(1050f, 800f) // 5 px/mm, page fills the width

    private fun render(doc: DocumentState = sampleDocument(), overlay: PagePainter.Overlay = PagePainter.Overlay()): ImageBitmap {
        val image = ImageBitmap(1050, 800)
        val painter = PagePainter(DesktopTextLayout(), MediaCache(TestScope()) { null as File? })
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1050f, 800f)) {
            painter.draw(this, doc, viewport, overlay = overlay)
        }
        return image
    }

    @Test
    fun anAnchorShowsItsNameOnlyWhileSelected() {
        val anchor = Anchor(StrokeId("a"), StrokeId("anchor"), 40f, 100f, "Besprechung mit Team", 0, DEV)
        val doc = sampleDocument().apply(DocumentOp.AddLinks(OpStamp(3, DEV), listOf(anchor)))
        val t = viewport.documentToView
        // Teal pixels where the name goes, right of the flag.
        fun nameInk(image: ImageBitmap): Int {
            val pixels = image.toPixelMap()
            var n = 0
            for (x in t.mapX(anchor.x + Anchor.HEIGHT_MM * 1.2f).toInt() until t.mapX(anchor.labelBounds.right).toInt()) {
                for (y in t.mapY(anchor.y).toInt() until t.mapY(anchor.y + Anchor.HEIGHT_MM).toInt()) {
                    val p = pixels[x, y]
                    if (p.green > p.red + 0.2f && p.red < 0.4f) n++
                }
            }
            return n
        }
        val plain = render(doc)
        val selected = render(doc, PagePainter.Overlay(selection = setOf(anchor.id)))
        System.getenv("NIBSYNC_ANCHOR_PNG")?.let { out ->
            File(out).writeBytes(org.jetbrains.skia.Image.makeFromBitmap(selected.asSkiaBitmap()).encodeToData()!!.bytes)
        }
        assertThat(nameInk(plain)).isEqualTo(0)
        assertThat(nameInk(selected)).isGreaterThan(30)
    }

    @Test
    fun pageShowsPaperPatternStrokeAndText() {
        val pixels = render().toPixelMap()
        val t = viewport.documentToView
        fun dark(xMm: Float, yMm: Float) = pixels[t.mapX(xMm).toInt(), t.mapY(yMm).toInt()].red < 0.3f
        // The ballpoint line at y = 30 mm.
        assertThat(dark(70f, 30f)).isTrue()
        assertThat(dark(70f, 33f)).isFalse()
        // Ruled lines every 7 mm in the pattern colour (blue-grey).
        val rule = pixels[t.mapX(150f).toInt(), t.mapY(70f).toInt()]
        assertThat(rule.blue).isGreaterThan(rule.red)
        // Text: some dark pixels in the box's first line.
        var textPixels = 0
        for (x in t.mapX(20f).toInt() until t.mapX(60f).toInt()) for (y in t.mapY(50f).toInt() until t.mapY(56f).toInt()) {
            if (pixels[x, y].red < 0.3f) textPixels++
        }
        assertThat(textPixels).isGreaterThan(50)
        // Paper is white, the desk around it grey.
        assertThat(pixels[t.mapX(180f).toInt(), t.mapY(150f).toInt()].red).isGreaterThan(0.99f)
    }

    @Test
    fun fountainPenFollowsStylusPressure() {
        val light = stroke("a", BrushFamily.FOUNTAIN_PEN, line(0f, 100f, 0f, pressure = 0f), size = 1f)
        val firm = stroke("b", BrushFamily.FOUNTAIN_PEN, line(0f, 100f, 0f, pressure = 0.5f), size = 1f)
        val mouse = stroke("c", BrushFamily.FOUNTAIN_PEN, line(0f, 100f, 0f, pressure = 0.5f), size = 1f, tool = InputTool.MOUSE)
        // Middle of the stroke, away from the taper at the end.
        assertThat(StrokePainter.widths(light)[10]).isWithin(0.001f).of(0.28f)
        assertThat(StrokePainter.widths(firm)[10]).isWithin(0.001f).of(1.6f)
        assertThat(StrokePainter.widths(mouse)[10]).isWithin(0.001f).of(1f)
        // The last point is tapered to 75 %.
        assertThat(StrokePainter.widths(mouse).last()).isWithin(0.001f).of(0.75f)
    }

    @Test
    fun pointsWithoutPressureUseTheNominalWidth() {
        val s = stroke("a", BrushFamily.FOUNTAIN_PEN, List(30) { StrokePoint(it * 2f, 0f, it * 5L) }, size = 0.8f)
        assertThat(StrokePainter.widths(s)[5]).isWithin(0.001f).of(0.8f)
    }
}
