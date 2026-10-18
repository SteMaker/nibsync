package com.stemaker.nibsync.desktop

import androidx.compose.ui.input.key.KeyEvent

/**
 * Window-wide keyboard shortcuts. The open document registers its [handler]; the window
 * offers every key event to it before the focused element sees it, except while a dialog is
 * open ([blocked]): then Ctrl+V, Delete etc. belong to the dialog's fields.
 */
class KeyRouter {
    var handler: (KeyEvent) -> Boolean = { false }

    var blocked = false

    fun onKey(event: KeyEvent): Boolean = !blocked && handler(event)
}
