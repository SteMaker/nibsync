package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.model.DocumentId
import okio.FileSystem
import okio.Path
import java.util.Properties

/** Small local preferences of the PC app (not synced), stored as a properties file. */
class Settings(private val fs: FileSystem, private val file: Path) {
    private val props = Properties().apply {
        if (fs.exists(file)) fs.read(file) { load(inputStream()) }
    }

    var lastDocument: DocumentId?
        get() = props.getProperty(LAST_DOCUMENT)?.let { runCatching { DocumentId(it) }.getOrNull() }
        set(value) {
            if (value == null) props.remove(LAST_DOCUMENT) else props.setProperty(LAST_DOCUMENT, value.value)
            save()
        }

    /** Account chosen for the last new book (null: only on this PC). */
    var lastBookAccount: String?
        get() = props.getProperty(LAST_BOOK_ACCOUNT)
        set(value) {
            if (value == null) props.remove(LAST_BOOK_ACCOUNT) else props.setProperty(LAST_BOOK_ACCOUNT, value)
            save()
        }

    /** Show the document title at the top of the page. */
    var showTitle: Boolean
        get() = props.getProperty(SHOW_TITLE)?.toBoolean() ?: true
        set(value) {
            props.setProperty(SHOW_TITLE, value.toString())
            save()
        }

    /** Show when the document was created, below the title. */
    var showCreated: Boolean
        get() = props.getProperty(SHOW_CREATED)?.toBoolean() ?: true
        set(value) {
            props.setProperty(SHOW_CREATED, value.toString())
            save()
        }

    private fun save() {
        file.parent?.let(fs::createDirectories)
        fs.write(file) { props.store(outputStream(), "NibSync") }
    }

    private companion object {
        const val LAST_DOCUMENT = "lastDocument"
        const val LAST_BOOK_ACCOUNT = "lastBookAccount"
        const val SHOW_TITLE = "showTitle"
        const val SHOW_CREATED = "showCreated"
    }
}
