package com.stemaker.nibsync.app

import android.content.Context
import androidx.core.content.edit
import com.stemaker.nibsync.model.DocumentId

/** Device-local preferences (not synced). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var lastDocument: DocumentId?
        get() = prefs.getString(KEY_LAST_DOCUMENT, null)?.let { runCatching { DocumentId(it) }.getOrNull() }
        set(value) = prefs.edit { putString(KEY_LAST_DOCUMENT, value?.value) }

    var showPageBreaks: Boolean
        get() = prefs.getBoolean(KEY_PAGE_BREAKS, false)
        set(value) = prefs.edit { putBoolean(KEY_PAGE_BREAKS, value) }

    var drawWithFinger: Boolean
        get() = prefs.getBoolean(KEY_DRAW_WITH_FINGER, false)
        set(value) = prefs.edit { putBoolean(KEY_DRAW_WITH_FINGER, value) }

    /** Show the document title at the top of the page. */
    var showTitle: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TITLE, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_TITLE, value) }

    /** Show when the document was created, below the title. */
    var showCreated: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CREATED, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_CREATED, value) }

    /** Account chosen for the last new book (null: only on this device). */
    var lastBookAccount: String?
        get() = prefs.getString(KEY_LAST_BOOK_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_LAST_BOOK_ACCOUNT, value) }

    private companion object {
        const val KEY_LAST_BOOK_ACCOUNT = "lastBookAccount"
        const val KEY_DRAW_WITH_FINGER = "drawWithFinger"
        const val KEY_LAST_DOCUMENT = "lastDocument"
        const val KEY_PAGE_BREAKS = "showPageBreaks"
        const val KEY_SHOW_TITLE = "showTitle"
        const val KEY_SHOW_CREATED = "showCreated"
    }
}
