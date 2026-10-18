package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.stemaker.nibsync.text.TextTags
import okio.Path.Companion.toPath
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Manual check of text editing on the PC: clicks into a copy of a data folder, types, and
 * saves a screenshot. Only runs with NIBSYNC_RENDER_DATA and NIBSYNC_RENDER_OUT set.
 */
@OptIn(ExperimentalTestApi::class)
class EditingPreview {
    @Test
    fun clickAndType() = runComposeUiTest {
        val data = System.getenv("NIBSYNC_RENDER_DATA")
        val out = System.getenv("NIBSYNC_RENDER_OUT")
        assumeTrue(data != null && out != null)
        val container = DesktopContainer(data!!.toPath())
        val settings = Settings(container.fs, data.toPath() / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithTag("page").performMouseInput { click(Offset(450f, 620f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        val editor = onNodeWithTag(TextTags.EDITOR)
        editor.performTextInput("Am PC geschrieben, ")
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        editor.performTextInput("fett")
        mainClock.advanceTimeBy(500)
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out!!))
    }
}
