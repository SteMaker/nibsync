package de.nibsync.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import de.nibsync.model.Asset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decoded pictures of the open document, loaded in the background. Reading [get] in
 * composition or drawing redraws once a picture is ready.
 */
class MediaCache(private val scope: CoroutineScope, private val fileOf: (Asset) -> File?) {
    sealed interface Loaded {
        class Bitmap(val image: ImageBitmap) : Loaded
        class Vector(val painter: Painter) : Loaded
    }

    private val loaded = mutableStateMapOf<String, Loaded>()
    private val requested = HashSet<String>()

    /** The picture if it is decoded; otherwise starts loading it and returns null. */
    fun get(asset: Asset): Loaded? {
        loaded[asset.sha256]?.let { return it }
        if (requested.add(asset.sha256)) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { decode(asset) }.getOrNull() }
                if (result != null) loaded[asset.sha256] = result else requested.remove(asset.sha256)
            }
        }
        return null
    }

    /** Forgets failed loads, e.g. after a sync brought the files. */
    fun retryMissing() {
        requested.retainAll(loaded.keys)
    }

    /** Decodes a picture turned the way its EXIF data says (camera photos). */
    private fun decodeUpright(bytes: ByteArray): org.jetbrains.skia.Image {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val origin = runCatching { org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)).encodedOrigin }
            .getOrDefault(org.jetbrains.skia.EncodedOrigin.TOP_LEFT)
        val degrees = when (origin) {
            org.jetbrains.skia.EncodedOrigin.RIGHT_TOP -> 90f
            org.jetbrains.skia.EncodedOrigin.BOTTOM_RIGHT -> 180f
            org.jetbrains.skia.EncodedOrigin.LEFT_BOTTOM -> 270f
            else -> return image
        }
        val turned = degrees != 180f
        val w = if (turned) image.height else image.width
        val h = if (turned) image.width else image.height
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surface.canvas.apply {
            translate(w / 2f, h / 2f)
            rotate(degrees)
            drawImage(image, -image.width / 2f, -image.height / 2f)
        }
        return surface.makeImageSnapshot()
    }

    private fun decode(asset: Asset): Loaded? {
        val file = fileOf(asset)?.takeIf { it.exists() } ?: return null
        return when (asset.mimeType) {
            Asset.SVG -> file.inputStream().use { Loaded.Vector(loadSvgPainter(it, Density(1f))) }
            Asset.PNG, Asset.JPEG -> Loaded.Bitmap(decodeUpright(file.readBytes()).toComposeImageBitmap())
            else -> null
        }
    }
}
