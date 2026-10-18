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
    val nextcloud = "Nextcloud"
    val syncNotConfigured = if (german) "Nicht verbunden. Notizen bleiben auf diesem PC." else "Not connected. Notes stay on this PC."
    val syncConnect = if (german) "Mit Nextcloud verbinden" else "Connect to Nextcloud"
    val syncNow = if (german) "Jetzt synchronisieren" else "Sync now"
    val syncSignOut = if (german) "Trennen" else "Disconnect"
    val syncRunning = if (german) "Synchronisiere…" else "Syncing…"
    fun syncDone(time: String) = if (german) "Synchronisiert um $time" else "Synced at $time"
    fun syncFailed(message: String) = if (german) "Sync fehlgeschlagen: $message" else "Sync failed: $message"
    val syncLoginInvalid = if (german) "Anmeldung ungültig. Bitte trennen und neu verbinden." else "Login no longer valid. Please disconnect and connect again."
    val syncServer = if (german) "Server-Adresse" else "Server address"
    val syncFolder = if (german) "Ordner in der Nextcloud" else "Folder in Nextcloud"
    val syncWaiting = if (german) "Im Browser anmelden, dann hierher zurückkehren…" else "Log in in the browser, then come back here…"
    val syncLoginInBrowser = if (german) "Im Browser anmelden" else "Log in in the browser"
    val syncLoginTimeout = if (german) "Die Anmeldung wurde nicht rechtzeitig abgeschlossen." else "The login was not completed in time."
    fun syncOpenBrowser(url: String) = if (german) "Bitte diese Adresse im Browser öffnen: $url" else "Please open this address in a browser: $url"
    val cancel = if (german) "Abbrechen" else "Cancel"
    fun font(font: de.nibsync.model.TextFont) = when (font) {
        de.nibsync.model.TextFont.SANS -> if (german) "Serifenlos" else "Sans serif"
        de.nibsync.model.TextFont.SERIF -> "Serif"
        de.nibsync.model.TextFont.MONO -> if (german) "Festbreite" else "Monospace"
        de.nibsync.model.TextFont.HANDWRITING -> if (german) "Handschrift" else "Handwriting"
    }
}
