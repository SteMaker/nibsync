package de.nibsync.canvas

import com.google.common.truth.Truth.assertThat
import de.nibsync.model.BrushFamily
import de.nibsync.model.Layer
import org.junit.Test

class ToolStateTest {
    @Test
    fun defaultIsPressureSensitivePen() {
        val s = ToolState()
        assertThat(s.activePen).isEqualTo(BrushFamily.FOUNTAIN_PEN)
        assertThat(s.activeBrush.family.pressureSensitive).isTrue()
        assertThat(s.drawWithFinger).isFalse()
    }

    @Test
    fun everyPenHasDefaults() {
        val s = ToolState()
        for (family in BrushFamily.entries) {
            val settings = s.pens.getValue(family)
            assertThat(PenPalette.colors(family)).contains(settings.colorArgb)
            assertThat(PenPalette.sizes(family)).contains(settings.sizeMm)
        }
    }

    @Test
    fun penRemembersItsColorAndSize() {
        val s = ToolState()
            .withPen(BrushFamily.BALLPOINT).withColor(PenPalette.inkColors[2]).withSize(0.8f)
            .withPen(BrushFamily.HIGHLIGHTER).withColor(PenPalette.highlighterColors[3])
            .withPen(BrushFamily.BALLPOINT)
        assertThat(s.activeBrush.colorArgb).isEqualTo(PenPalette.inkColors[2])
        assertThat(s.activeBrush.sizeMm).isEqualTo(0.8f)
        assertThat(s.pens.getValue(BrushFamily.HIGHLIGHTER).colorArgb).isEqualTo(PenPalette.highlighterColors[3])
    }

    @Test
    fun highlighterBrushGoesToMarkerLayer() {
        val brush = ToolState().withPen(BrushFamily.HIGHLIGHTER).activeBrush
        assertThat(brush.family.defaultLayer).isEqualTo(Layer.MARKER)
    }

    @Test
    fun highlighterColorsAreTranslucentAndInkIsOpaque() {
        PenPalette.highlighterColors.forEach { assertThat((it ushr 24) and 0xff).isLessThan(0xff) }
        PenPalette.inkColors.forEach { assertThat((it ushr 24) and 0xff).isEqualTo(0xff) }
    }

    @Test
    fun paletteHasSixColorsAndThreeIncreasingSizes() {
        for (family in BrushFamily.entries) {
            assertThat(PenPalette.colors(family)).hasSize(6)
            val sizes = PenPalette.sizes(family)
            assertThat(sizes).hasSize(3)
            assertThat(sizes).isInStrictOrder()
        }
    }

    @Test
    fun eraserRemembersPenAndSwitchesBack() {
        val s = ToolState().withPen(BrushFamily.BALLPOINT).withEraser()
        assertThat(s.eraserActive).isTrue()
        assertThat(s.activePen).isEqualTo(BrushFamily.BALLPOINT)
        val back = s.withPen(BrushFamily.BALLPOINT)
        assertThat(back.eraserActive).isFalse()
    }

    @Test
    fun objectEraserHasFixedSmallTipAndPixelEraserUsesSelectedSize() {
        val obj = ToolState().withEraser(de.nibsync.document.erase.EraserMode.OBJECT).withEraserSize(12f)
        assertThat(obj.eraserRadiusMm).isEqualTo(PenPalette.OBJECT_ERASER_DIAMETER_MM / 2)
        val px = obj.withEraser(de.nibsync.document.erase.EraserMode.PIXEL)
        assertThat(px.eraserRadiusMm).isEqualTo(6f)
        assertThat(PenPalette.eraserSizes).isInStrictOrder()
    }
}
