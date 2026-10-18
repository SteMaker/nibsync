package de.nibsync.media

import com.google.common.truth.Truth.assertThat
import de.nibsync.model.Asset
import de.nibsync.model.FileLinkDraft
import de.nibsync.model.ImageDraft
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaImportTest {
    private val sha = "ab".repeat(32)
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(20)

    @Test
    fun typesAreRecognisedByContent() {
        assertThat(MediaImport.detectType(bytes(0x89, 0x50, 0x4E, 0x47), null, null)).isEqualTo(Asset.PNG)
        assertThat(MediaImport.detectType(bytes(0xFF, 0xD8, 0xFF, 0xE0), "image/png", "x.png")).isEqualTo(Asset.JPEG)
        assertThat(MediaImport.detectType("%PDF-1.7\n".encodeToByteArray(), null, "a.bin")).isEqualTo(Asset.PDF)
        assertThat(MediaImport.detectType("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray(), null, null))
            .isEqualTo(Asset.SVG)
        assertThat(MediaImport.detectType("GIF89a".encodeToByteArray(), "image/gif", "a.gif")).isNull()
        assertThat(MediaImport.detectType("hello".encodeToByteArray(), "text/plain", "a.txt")).isNull()
    }

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="960" height="480"><rect width="960" height="480"/></svg>"""

    @Test
    fun svgSizeComesFromItsDocument() {
        // 960 x 480 px at 96 dpi = 254 x 127 mm.
        val size = MediaImport.naturalSizeMm(svg.encodeToByteArray(), Asset.SVG)!!
        assertThat(size.width).isWithin(0.01f).of(254f)
        assertThat(size.height).isWithin(0.01f).of(127f)
        val viewBoxOnly = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 48"/>"""
        assertThat(MediaImport.naturalSizeMm(viewBoxOnly.encodeToByteArray(), Asset.SVG)!!.width).isWithin(0.01f).of(25.4f)
    }

    @Test
    fun bigPicturesShrinkToTheCentredPageShare() {
        val draft = MediaImport.draft(PickedFile(svg.encodeToByteArray(), Asset.SVG, "a.svg"), sha, pageWidthMm = 210f, centerX = 105f, centerY = 100f) as ImageDraft
        assertThat(draft.widthMm).isWithin(0.01f).of(168f) // 80 % of 210
        assertThat(draft.heightMm).isWithin(0.01f).of(84f) // aspect kept
        assertThat(draft.x).isWithin(0.01f).of(21f)
        assertThat(draft.y).isWithin(0.01f).of(58f)
        assertThat(draft.asset).isEqualTo(Asset(sha, Asset.SVG))
    }

    @Test
    fun picturesStayOnThePage() {
        val draft = MediaImport.draft(PickedFile(svg.encodeToByteArray(), Asset.SVG, "a.svg"), sha, pageWidthMm = 210f, centerX = 200f, centerY = 10f) as ImageDraft
        assertThat(draft.x + draft.widthMm).isAtMost(210f)
        assertThat(draft.y).isEqualTo(0f)
    }

    @Test
    fun pdfBecomesALink() {
        val draft = MediaImport.draft(PickedFile("%PDF".encodeToByteArray(), Asset.PDF, "Skript.pdf"), sha, 210f, 105f, 100f) as FileLinkDraft
        assertThat(draft.fileName).isEqualTo("Skript.pdf")
        assertThat(draft.asset.mimeType).isEqualTo(Asset.PDF)
        assertThat(draft.x).isWithin(0.01f).of(75f)
    }

    @Test
    fun unreadablePictureGivesNothing() {
        assertThat(MediaImport.draft(PickedFile("<svg".encodeToByteArray(), Asset.SVG, "a.svg"), sha, 210f, 105f, 100f)).isNull()
    }
}
