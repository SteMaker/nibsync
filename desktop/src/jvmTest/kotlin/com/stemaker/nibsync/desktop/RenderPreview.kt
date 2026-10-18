package com.stemaker.nibsync.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Renders the main window for a data folder into a PNG, without a screen: a manual check of
 * how real notes look on the PC. Only runs with NIBSYNC_RENDER_DATA (a copy of a data folder,
 * it gets written to) and NIBSYNC_RENDER_OUT (the PNG); optionally NIBSYNC_RENDER_DOC.
 */
class RenderPreview {
    @Test
    fun renderWindow() {
        val data = System.getenv("NIBSYNC_RENDER_DATA")
        val out = System.getenv("NIBSYNC_RENDER_OUT")
        assumeTrue(data != null && out != null)
        val container = DesktopContainer(data!!.toPath())
        val settings = Settings(FileSystem.SYSTEM, data.toPath() / "settings.properties")
        System.getenv("NIBSYNC_RENDER_DOC")?.let { settings.lastDocument = com.stemaker.nibsync.model.DocumentId(it) }
        val scene = ImageComposeScene(1400, 900, Density(1f)) { App(container, settings, Strings(german = true)) }
        var time = 0L
        repeat(60) {
            scene.render(time)
            time += 50_000_000L
            Thread.sleep(50)
        }
        val image = scene.render(time)
        File(out!!).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
