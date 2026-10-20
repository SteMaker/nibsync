package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.PenWidths
import okio.Path.Companion.toOkioPath
import okio.FileSystem
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PenPreferencesStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun lastPenAndWidthsSurviveARestart() {
        val file = tmp.root.toOkioPath() / "settings.properties"
        val settings = Settings(FileSystem.SYSTEM, file)
        val widths = PenWidths.DEFAULTS + (BrushFamily.FOUNTAIN_PEN to listOf(0.4f, 0.9f, 2f))
        settings.pens = settings.pens.copy(widths = widths).used(BrushFamily.FOUNTAIN_PEN, 2f)

        val tools = DesktopToolState.from(Settings(FileSystem.SYSTEM, file).pens)
        assertThat(tools.tool).isEqualTo(DesktopTool.TEXT)
        assertThat(tools.pen).isEqualTo(BrushFamily.FOUNTAIN_PEN)
        assertThat(tools.brush.sizeMm).isEqualTo(2f)
        assertThat(tools.sizes(BrushFamily.FOUNTAIN_PEN)).containsExactly(0.4f, 0.9f, 2f).inOrder()
    }

    @Test
    fun changedWidthsKeepThePensPosition() {
        val tools = DesktopToolState().copy(pens = DesktopToolState().pens + (BrushFamily.BALLPOINT to PenChoice(0, 0.35f)))
        val changed = tools.withWidths(PenWidths.DEFAULTS + (BrushFamily.BALLPOINT to listOf(0.25f, 0.6f, 1f)))
        assertThat(changed.brush.sizeMm).isEqualTo(0.25f)
    }
}
