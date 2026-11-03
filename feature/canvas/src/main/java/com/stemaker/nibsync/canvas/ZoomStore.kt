package com.stemaker.nibsync.canvas

import android.content.Context
import com.stemaker.nibsync.model.DocumentId

/** The zoom each document was last shown at on this device; local, not synced. */
class ZoomStore(context: Context) {
    private val prefs = context.getSharedPreferences("zoom", Context.MODE_PRIVATE)

    fun zoom(id: DocumentId): Float? = prefs.getFloat(id.value, 0f).takeIf { it > 0f }

    fun setZoom(id: DocumentId, zoom: Float) {
        prefs.edit().putFloat(id.value, zoom).apply()
    }
}

/** The zoom is stored once it has stayed this long (ms). */
internal const val ZOOM_SAVE_DELAY_MS = 500L
