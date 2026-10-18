package de.nibsync.desktop

import java.util.Locale

/** UI texts: German for German systems, English otherwise (like values/ and values-de/ on Android). */
class Strings(german: Boolean = Locale.getDefault().language == "de") {
    val appName = "NibSync"
    val library = if (german) "Bibliothek" else "Library"
    val defaultBook = if (german) "Notizen" else "Notes"
    val defaultDocument = if (german) "Notizen" else "Notes"
    val noDocument = if (german) "Kein Dokument ausgewählt" else "No document selected"
    val loading = if (german) "Lädt…" else "Loading…"
}
