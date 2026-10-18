package de.nibsync.desktop

import java.util.Locale

/** UI texts: German for German systems, English otherwise (like values/ and values-de/ on Android). */
class Strings(private val german: Boolean = Locale.getDefault().language == "de") {
    val appName = "NibSync"
    val library = if (german) "Bibliothek" else "Library"
    val defaultBook = if (german) "Notizen" else "Notes"
    val defaultDocument = if (german) "Notizen" else "Notes"
    val noDocument = if (german) "Kein Dokument ausgewählt" else "No document selected"
    val loading = if (german) "Lädt…" else "Loading…"
    val undo = if (german) "Rückgängig (Strg+Z)" else "Undo (Ctrl+Z)"
    val redo = if (german) "Wiederholen (Strg+Y)" else "Redo (Ctrl+Y)"
    val bold = if (german) "Fett (Strg+B)" else "Bold (Ctrl+B)"
    val italic = if (german) "Kursiv (Strg+I)" else "Italic (Ctrl+I)"
    val bullets = if (german) "Aufzählung" else "Bulleted list"
    val numbers = if (german) "Nummerierte Liste" else "Numbered list"
    val indent = if (german) "Einzug vergrößern" else "Indent more"
    val outdent = if (german) "Einzug verkleinern" else "Indent less"
    val deleteTextBox = if (german) "Textbox löschen" else "Delete text box"
    val moveTextBox = if (german) "Textbox verschieben" else "Move text box"
    val resizeTextBox = if (german) "Breite ändern" else "Change width"
    val clickHint = if (german) "Klicken, um zu schreiben" else "Click to write"
    fun sizePt(pt: String) = "$pt pt"
    fun font(font: de.nibsync.model.TextFont) = when (font) {
        de.nibsync.model.TextFont.SANS -> if (german) "Serifenlos" else "Sans serif"
        de.nibsync.model.TextFont.SERIF -> "Serif"
        de.nibsync.model.TextFont.MONO -> if (german) "Festbreite" else "Monospace"
        de.nibsync.model.TextFont.HANDWRITING -> if (german) "Handschrift" else "Handwriting"
    }
}
