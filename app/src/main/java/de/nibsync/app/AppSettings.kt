package de.nibsync.app

import android.content.Context
import androidx.core.content.edit
import de.nibsync.model.DocumentId

/** Device-local preferences (not synced). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var lastDocument: DocumentId?
        get() = prefs.getString(KEY_LAST_DOCUMENT, null)?.let { runCatching { DocumentId(it) }.getOrNull() }
        set(value) = prefs.edit { putString(KEY_LAST_DOCUMENT, value?.value) }

    var showPageBreaks: Boolean
        get() = prefs.getBoolean(KEY_PAGE_BREAKS, false)
        set(value) = prefs.edit { putBoolean(KEY_PAGE_BREAKS, value) }

    private companion object {
        const val KEY_LAST_DOCUMENT = "lastDocument"
        const val KEY_PAGE_BREAKS = "showPageBreaks"
    }
}
