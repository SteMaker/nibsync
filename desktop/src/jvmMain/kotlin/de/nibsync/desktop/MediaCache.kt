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

    private fun decode(asset: Asset): Loaded? {
        val file = fileOf(asset)?.takeIf { it.exists() } ?: return null
        return when (asset.mimeType) {
            Asset.SVG -> file.inputStream().use { Loaded.Vector(loadSvgPainter(it, Density(1f))) }
            Asset.PNG, Asset.JPEG -> Loaded.Bitmap(org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap())
            else -> null
        }
    }
}
