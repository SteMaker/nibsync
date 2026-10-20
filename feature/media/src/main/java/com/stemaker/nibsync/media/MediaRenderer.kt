package com.stemaker.nibsync.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.ImageElement
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Draws pictures and file links on a canvas in page millimetres. Files are decoded in the
 * background; until then (or while a synced file has not arrived) a placeholder is shown and
 * [onLoaded] asks for a redraw once something is ready. Pixel pictures are decoded at the
 * resolution the current zoom needs; SVGs are kept as vector pictures.
 */
class MediaRenderer(
    /** Where an asset of the shown document is stored, or null if unknown. */
    var assetFile: (Asset) -> File? = { null },
    private val onLoaded: () -> Unit,
) {
    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val sampleOf = HashMap<String, Int>()
    private val pictures = HashMap<String, Picture>()
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "media-decoder").apply { isDaemon = true } }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderFill = Paint().apply { color = 0xffeceff1.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xffb0bec5.toInt()
    }
    private val linkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff5f7fa.toInt() }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff263238.toInt() }
    private val rect = RectF()

    /** Draws [element]; [pxPerMm] is the current zoom, used to pick the decode resolution. */
    fun draw(canvas: Canvas, element: FileElement, pxPerMm: Float) {
        rect.set(element.x, element.y, element.x + element.widthMm, element.y + element.heightMm)
        when (element) {
            is ImageElement -> drawImage(canvas, element, pxPerMm)
            is FileLink -> drawLink(canvas, element)
        }
    }

    /** Forgets files that could not be read, e.g. after a sync brought them. */
    fun retryMissing() {
        failed.clear()
    }

    private fun drawImage(canvas: Canvas, image: ImageElement, pxPerMm: Float) {
        val sha = image.asset.sha256
        if (image.asset.mimeType == Asset.SVG) {
            val picture = pictures[sha]
            if (picture != null) {
                canvas.drawPicture(picture, rect)
            } else {
                placeholder(canvas)
                load(sha) { loadSvg(image.asset) }
            }
            return
        }
        val bitmap = bitmaps.get(sha)
        val neededWidthPx = image.widthMm * pxPerMm
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
            // Zoomed in far beyond the decoded resolution: decode finer in the background.
            if (bitmap.width < neededWidthPx / UPGRADE_FACTOR && (sampleOf[sha] ?: 1) > 1) {
                load(sha) { loadBitmap(image.asset, neededWidthPx) }
            }
        } else {
            placeholder(canvas)
            load(sha) { loadBitmap(image.asset, neededWidthPx) }
        }
    }

    private fun placeholder(canvas: Canvas) {
        canvas.drawRect(rect, placeholderFill)
        outline.strokeWidth = 0.3f
        canvas.drawRect(rect, outline)
    }

    private fun drawLink(canvas: Canvas, link: FileLink) {
        val h = rect.height()
        val r = h * 0.2f
        canvas.drawRoundRect(rect, r, r, linkFill)
        outline.strokeWidth = 0.3f
        canvas.drawRoundRect(rect, r, r, outline)
        // A small badge with the type (red for PDF), then the file name.
        val pad = h * 0.18f
        val badge = RectF(rect.left + pad, rect.top + pad, rect.left + pad + (h - 2 * pad) * 0.8f, rect.bottom - pad)
        badgePaint.color = if (link.asset.mimeType == Asset.PDF) PDF_BADGE else OTHER_BADGE
        canvas.drawRoundRect(badge, h * 0.08f, h * 0.08f, badgePaint)
        val label = link.badge
        badgeText.textSize = badge.height() * (if (label.length > 3) 0.24f else 0.3f)
        canvas.drawText(label, badge.centerX(), badge.centerY() + badgeText.textSize * 0.35f, badgeText)
        nameText.textSize = h * 0.36f
        val textLeft = badge.right + pad
        val available = rect.right - pad - textLeft
        if (available > 0f) {
            val name = TextUtils.ellipsize(link.fileName, nameText, available, TextUtils.TruncateAt.MIDDLE).toString()
            canvas.drawText(name, textLeft, rect.centerY() + nameText.textSize * 0.35f, nameText)
        }
    }

    private fun load(sha: String, work: () -> Boolean) {
        if (sha in failed || !loading.add(sha)) return
        executor.execute {
            val ok = runCatching(work).getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                loading.remove(sha)
                if (!ok) failed += sha
                onLoaded()
            }
        }
    }

    private fun loadSvg(asset: Asset): Boolean {
        val file = assetFile(asset)?.takeIf { it.exists() } ?: return false
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        val picture = svg.renderToPicture()
        android.os.Handler(android.os.Looper.getMainLooper()).post { pictures[asset.sha256] = picture }
        return true
    }

    private fun loadBitmap(asset: Asset, neededWidthPx: Float): Boolean {
        val file = assetFile(asset)?.takeIf { it.exists() } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val rotation = if (asset.mimeType == Asset.JPEG) MediaImport.exifRotation(file.readBytes()) else 0
        val sourceWidth = if (rotation % 180 != 0) bounds.outHeight else bounds.outWidth
        var sample = 1
        val target = max(neededWidthPx, MIN_DECODE_PX)
        while (sourceWidth / (sample * 2) >= target || max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_PX) sample *= 2
        var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        if (rotation != 0) {
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (turned !== bitmap) bitmap.recycle()
            bitmap = turned
        }
        val result = bitmap
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bitmaps.put(asset.sha256, result)
            sampleOf[asset.sha256] = sample
        }
        return true
    }

    private companion object {
        const val CACHE_BYTES = 96 * 1024 * 1024
        const val MAX_DECODE_PX = 4096
        const val MIN_DECODE_PX = 256f
        const val UPGRADE_FACTOR = 1.5f
        const val PDF_BADGE = 0xffd32f2f.toInt()
        const val OTHER_BADGE = 0xff546e7a.toInt()
    }
}
