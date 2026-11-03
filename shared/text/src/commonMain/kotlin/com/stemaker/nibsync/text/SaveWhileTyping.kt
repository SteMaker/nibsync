package com.stemaker.nibsync.text

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/**
 * Stores typed text while typing: at once when its height changed, so the box, the table row and
 * everything below follow the new line right away, else once typing paused for [delayMs].
 */
suspend fun saveWhileTyping(controller: TextEditController, heightChanged: () -> Boolean, save: () -> Unit, delayMs: Long) {
    snapshotFlow { controller.contentVersion }.drop(1).collectLatest {
        if (!heightChanged()) delay(delayMs)
        save()
    }
}
