package com.stemaker.nibsync.canvas

import com.stemaker.nibsync.document.select.ClipContent
import com.stemaker.nibsync.model.DocumentId
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

    /** Document the content was copied from (its files are copied along when pasting elsewhere). */
    var source: DocumentId? = null
        private set

    fun set(content: ClipContent?, source: DocumentId? = null) {
        this.source = source
        _content.value = content
    }
}
