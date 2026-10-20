package com.stemaker.nibsync.desktop

import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.MediaFiles
import com.stemaker.nibsync.model.PickedFile
import com.stemaker.nibsync.model.SizeMm
import com.stemaker.nibsync.storage.FileDocumentRepository
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Reading, sizing and opening inserted files on the PC; placement is [MediaFiles]. */
object DesktopMedia {
    /** Reads [file] (pictures are shown, other files become links); null if missing or too large. */
    fun read(file: File): PickedFile? {
        if (!file.isFile || file.length() > MediaFiles.MAX_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return PickedFile(bytes, MediaFiles.fileType(bytes, null, file.name), file.name)
    }

    /** Natural size on paper, or null if the picture cannot be read. */
    fun naturalSize(file: PickedFile): SizeMm? = runCatching {
        when (file.mimeType) {
            Asset.PNG, Asset.JPEG -> {
                val codec = Codec.makeFromData(Data.makeFromBytes(file.bytes))
                if (isQuarterTurn(codec.encodedOrigin)) MediaFiles.printSize(codec.height, codec.width) else MediaFiles.printSize(codec.width, codec.height)
            }
            Asset.SVG -> {
                val size = loadSvgPainter(file.bytes.inputStream(), Density(1f)).intrinsicSize
                if (size.width.isNaN() || size.width <= 0f || size.height <= 0f) SizeMm(100f, 100f) else MediaFiles.svgSize(size.width, size.height)
            }
            else -> null
        }
    }.getOrNull()

    fun isQuarterTurn(origin: EncodedOrigin) = origin == EncodedOrigin.LEFT_TOP || origin == EncodedOrigin.RIGHT_TOP ||
        origin == EncodedOrigin.RIGHT_BOTTOM || origin == EncodedOrigin.LEFT_BOTTOM

    /**
     * A picture or file in the system clipboard: copied files first, then a copied image
     * (stored as PNG). Null if there is nothing usable.
     */
    fun fromClipboard(): PickedFile? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>
            files.firstNotNullOfOrNull(::read)?.let { return it }
        }
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            val image = clipboard.getData(DataFlavor.imageFlavor) as java.awt.Image
            val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
            val out = ByteArrayOutputStream()
            ImageIO.write(buffered, "png", out)
            return PickedFile(out.toByteArray(), Asset.PNG, "clipboard.png")
        }
        null
    }.getOrNull()

    /** True if the clipboard holds something [fromClipboard] can use. */
    fun clipboardHasMedia(): Boolean = runCatching {
        val c = Toolkit.getDefaultToolkit().systemClipboard
        c.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) || c.isDataFlavorAvailable(DataFlavor.imageFlavor)
    }.getOrDefault(false)

    /** Opens a file link with the program the system uses for its type. */
    fun open(repository: FileDocumentRepository, doc: DocumentId, link: FileLink): Boolean {
        val source = File(repository.assetPath(doc, link.asset.sha256).toString())
        if (!source.exists()) return false
        // A copy under the file's real name, so the viewer shows a sensible title.
        val dir = File(System.getProperty("java.io.tmpdir"), "nibsync-open").apply { mkdirs() }
        val name = link.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val copy = File(dir, name)
        source.copyTo(copy, overwrite = true)
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(copy)
                return true
            }
        }
        return runCatching { ProcessBuilder("xdg-open", copy.path).start(); true }.getOrDefault(false)
    }

    /** Copies files of pasted elements from another document of the same data folder. */
    fun copyAssets(repository: FileDocumentRepository, from: DocumentId, to: DocumentId, assets: Set<Asset>) {
        for (asset in assets) {
            val target = File(repository.assetPath(to, asset.sha256).toString())
            if (target.exists()) continue
            val source = File(repository.assetPath(from, asset.sha256).toString())
            if (!source.exists()) continue
            target.parentFile?.mkdirs()
            source.copyTo(File(target.parentFile, "${target.name}.tmp"), overwrite = true).renameTo(target)
        }
    }

    /** Lets the user pick a picture or any other file with the system file dialog. */
    fun chooseFile(parent: java.awt.Frame?, title: String): File? {
        val dialog = java.awt.FileDialog(parent, title, java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }
}
