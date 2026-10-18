package com.stemaker.nibsync.desktop

import androidx.compose.ui.input.key.KeyEvent

/**
 * Window-wide keyboard shortcuts. The open document registers its [handler]; the window
 * offers every key event to it before the focused element sees it.
 */
class KeyRouter {
    var handler: (KeyEvent) -> Boolean = { false }

    fun onKey(event: KeyEvent): Boolean = handler(event)
}
