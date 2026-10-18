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

    private fun save() {
        file.parent?.let(fs::createDirectories)
        fs.write(file) { props.store(outputStream(), "NibSync") }
    }

    private companion object {
        const val LAST_DOCUMENT = "lastDocument"
    }
}
