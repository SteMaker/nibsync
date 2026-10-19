package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.BrushFamily
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DrawingUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun drawWithTheMouseThenLassoAndDelete() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val session = runBlocking { container.openSession(docId) }

        onNodeWithTag(ToolbarTags.pen(BrushFamily.BALLPOINT)).performClick()
        // Colours and widths are in the settings bar right of the page.
        onNode(hasTestTag(ToolbarTags.color(2)) and hasAnyAncestor(hasTestTag(ToolbarTags.TOOL_SETTINGS))).assertExists()
        assert(onNodeWithTag(ToolbarTags.TOOL_SETTINGS).fetchSemanticsNode().boundsInRoot.left >= onNodeWithTag("page").fetchSemanticsNode().boundsInRoot.right - 1f)
        onNodeWithTag(ToolbarTags.color(2)).performClick()
        onNodeWithTag("page").performMouseInput {
            moveTo(Offset(300f, 300f))
            press()
            for (i in 1..20) moveTo(Offset(300f + i * 10f, 300f + i * 3f))
            release()
        }
        waitUntil(timeoutMillis = 5000) { session.state.value.strokes.size == 1 }
        assertThat(session.state.value.strokes.single().brush.colorArgb).isEqualTo(DesktopPalette.inkColors[2])

        System.getenv("NIBSYNC_RENDER_OUT")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it)) }

        // Lasso around it, then Delete.
        onNodeWithTag(ToolbarTags.LASSO).performClick()
        onNodeWithTag("page").performMouseInput {
            moveTo(Offset(280f, 280f))
            press()
            for (p in listOf(Offset(540f, 280f), Offset(540f, 380f), Offset(280f, 380f), Offset(280f, 282f))) moveTo(p)
            release()
        }
        waitForIdle()
        onNodeWithTag(ToolbarTags.DELETE_SELECTION).assertIsEnabled()
        onNodeWithTag(ToolbarTags.DELETE_SELECTION).performClick()
        waitUntil(timeoutMillis = 5000) { session.state.value.strokes.isEmpty() }
        // Undo brings it back. (Ctrl+Z without a focused element goes through the window's key
        // handler, which a test without a window does not have.)
        onNodeWithTag(ToolbarTags.UNDO).performClick()
        waitUntil(timeoutMillis = 5000) { session.state.value.strokes.size == 1 }
        runBlocking { container.closeAll() }
    }
}
