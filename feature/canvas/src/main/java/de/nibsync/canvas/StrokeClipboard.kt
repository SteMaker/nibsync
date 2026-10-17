package de.nibsync.canvas

import de.nibsync.document.select.ClipContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-internal clipboard for strokes. One instance is shared by the app so strokes can later be
 * pasted into other documents too.
 */
class StrokeClipboard {
    private val _content = MutableStateFlow<ClipContent?>(null)
    val content: StateFlow<ClipContent?> = _content.asStateFlow()

    fun set(content: ClipContent?) {
        _content.value = content
    }
}
