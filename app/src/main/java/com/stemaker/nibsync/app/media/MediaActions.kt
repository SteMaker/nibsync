package com.stemaker.nibsync.app.media

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import com.stemaker.nibsync.app.AppContainer
import com.stemaker.nibsync.app.R
import com.stemaker.nibsync.canvas.CanvasViewModel
import com.stemaker.nibsync.media.MediaImport
import com.stemaker.nibsync.model.PickedFile
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.geometry.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Inserting pictures and PDFs into the open document (file picker, clipboard, sharing) and
 * opening file links in other apps.
 */
class MediaActions(private val context: Context, private val container: AppContainer) {

    /** File types offered in the picker and accepted from other apps. */
    val mimeTypes = arrayOf(Asset.PNG, Asset.JPEG, Asset.SVG, Asset.PDF)

    /**
     * Reads [uri], stores it with document [doc] and adds it to the page in the middle of what
     * [viewport] shows. Problems are reported with a short message.
     */
    suspend fun insert(vm: CanvasViewModel, doc: DocumentId, uri: Uri, viewport: Viewport?) {
        val file = try {
            withContext(Dispatchers.IO) { read(uri) }
        } catch (e: TooLarge) {
            return toast(R.string.media_too_big)
        } catch (e: Exception) {
            return toast(R.string.media_unreadable)
        } ?: return toast(R.string.media_unsupported)
        val sha = container.repository.putAsset(doc, file.bytes)
        val pageWidth = viewport?.pageWidthMm ?: 210f
        val visible = viewport?.takeIf { it.hasSize }?.visibleDocumentRect
        val cx = visible?.let { (it.left + it.right) / 2 } ?: (pageWidth / 2)
        val cy = visible?.let { (it.top + it.bottom) / 2 } ?: 50f
        val draft = withContext(Dispatchers.Default) { MediaImport.draft(file, sha, pageWidth, cx, cy) }
            ?: return toast(R.string.media_unreadable)
        vm.insert(listOf(draft))
    }

    private class TooLarge : Exception()

    /** The file behind [uri] if it is a supported type, else null. */
    private fun read(uri: Uri): PickedFile? {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MediaImport.MAX_BYTES) throw TooLarge()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val data = input.readNBytesCompat(MediaImport.MAX_BYTES + 1)
            if (data.size > MediaImport.MAX_BYTES) throw TooLarge()
            data
        } ?: return null
        val type = MediaImport.detectType(bytes, resolver.getType(uri), name) ?: return null
        return PickedFile(bytes, type, name)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= limit) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** A picture or PDF in the system clipboard, if there is one. */
    fun clipboardUri(): Uri? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        val description = clip.description
        val accepted = mimeTypes.any { description.hasMimeType(it) } || description.hasMimeType("image/*")
        if (!accepted || clip.itemCount == 0) return null
        return clip.getItemAt(0).uri
    }

    suspend fun insertFromClipboard(vm: CanvasViewModel, doc: DocumentId, viewport: Viewport?) {
        val uri = clipboardUri() ?: return toast(R.string.media_clipboard_empty)
        insert(vm, doc, uri, viewport)
    }

    /** Opens a file link of document [doc] in an app that can show it. */
    fun openLink(doc: DocumentId, link: FileLink) {
        val source = File(container.repository.assetPath(doc, link.asset.sha256).toString())
        if (!source.exists()) return toast(R.string.media_not_synced)
        // A copy under the file's real name, so the viewer shows a sensible title.
        val dir = File(context.cacheDir, "open").apply { mkdirs() }
        val name = link.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file.pdf" }
        val copy = File(dir, name)
        source.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, link.asset.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.media_no_viewer)
        }
    }

    /** Copies files of pasted elements from the document they were copied in. */
    fun copyAssets(from: DocumentId, to: DocumentId, assets: Set<Asset>) {
        for (asset in assets) {
            val target = File(container.repository.assetPath(to, asset.sha256).toString())
            if (target.exists()) continue
            val source = File(container.repository.assetPath(from, asset.sha256).toString())
            if (!source.exists()) continue
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            source.copyTo(tmp, overwrite = true)
            tmp.renameTo(target)
        }
    }

    /** Where an asset of document [doc] is stored. */
    fun assetFile(doc: DocumentId, asset: Asset): File =
        File(container.repository.assetPath(doc, asset.sha256).toString())

    private fun toast(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
