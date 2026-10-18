package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.MediaFiles
import com.stemaker.nibsync.storage.FileDocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DesktopMediaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun png(w: Int, h: Int): File = tmp.newFile("pic.png").also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }

    @Test
    fun pngIsReadAndSizedAtPrintResolution() {
        val file = DesktopMedia.read(png(300, 150))!!
        assertThat(file.mimeType).isEqualTo(Asset.PNG)
        val size = DesktopMedia.naturalSize(file)!!
        // 300 px at 150 dpi = 2 inch = 50.8 mm.
        assertThat(size.width).isWithin(0.01f).of(50.8f)
        assertThat(size.height).isWithin(0.01f).of(25.4f)
        val draft = MediaFiles.draft(file, "a".repeat(64), size, 210f, 105f, 100f) as ImageDraft
        assertThat(draft.widthMm).isWithin(0.01f).of(50.8f)
    }

    @Test
    fun svgSizeComesFromTheDocument() {
        val f = tmp.newFile("a.svg").apply { writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="960" height="480"><rect width="960" height="480"/></svg>""") }
        val file = DesktopMedia.read(f)!!
        assertThat(file.mimeType).isEqualTo(Asset.SVG)
        assertThat(DesktopMedia.naturalSize(file)!!.width).isWithin(0.5f).of(254f)
    }

    @Test
    fun otherFilesAreRefused() {
        assertThat(DesktopMedia.read(tmp.newFile("a.txt").apply { writeText("hallo") })).isNull()
        assertThat(DesktopMedia.read(File(tmp.root, "missing.png"))).isNull()
    }

    @Test
    fun filesAreCopiedToTheDocumentPastedInto() = runBlocking {
        val repo = FileDocumentRepository(FileSystem.SYSTEM, tmp.root.toOkioPath(), Dispatchers.IO)
        val from = DocumentId("from")
        val to = DocumentId("to")
        val sha = repo.putAsset(from, byteArrayOf(1, 2, 3))
        DesktopMedia.copyAssets(repo, from, to, setOf(Asset(sha, Asset.PNG)))
        assertThat(repo.hasAsset(to, sha)).isTrue()
    }
}
