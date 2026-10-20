package com.stemaker.nibsync.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.library.LibraryTree
import com.stemaker.nibsync.library.LinkTargets
import com.stemaker.nibsync.library.LinkableDocument
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.DocumentId

object NoteLinkTags {
    const val SEARCH = "note-link-search"
    fun document(id: DocumentId) = "note-link-doc-${id.value}"
    const val TOP = "note-link-top"
    fun anchor(name: String) = "note-link-anchor-$name"
}

/**
 * Choosing a link target: first a document of the library (with a search field), then its top
 * or one of its anchors. [anchorsOf] loads a document's anchors.
 */
@Composable
fun NoteLinkDialog(
    strings: Strings,
    tree: LibraryTree,
    anchorsOf: suspend (DocumentId) -> List<Anchor>,
    onDismiss: () -> Unit,
    onConfirm: (LinkableDocument, Anchor?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<LinkableDocument?>(null) }
    var anchors by remember { mutableStateOf<List<Anchor>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(chosen) {
        val doc = chosen
        if (doc == null) {
            focus.requestFocus()
            return@LaunchedEffect
        }
        anchors = null
        anchors = runCatching { anchorsOf(doc.id) }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chosen?.title ?: strings.linkToNote) },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val doc = chosen
                if (doc == null) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(strings.searchNotes) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(NoteLinkTags.SEARCH),
                    )
                    val documents = remember(tree, query) { LinkTargets.documents(tree, query) }
                    if (documents.isEmpty()) Text(strings.noNotesFound, Modifier.padding(8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(documents, key = { it.id.value }) { d ->
                            ListItem(
                                headlineContent = { Text(d.title) },
                                supportingContent = { if (d.path.isNotEmpty()) Text(d.path, style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.clickable { chosen = d }.testTag(NoteLinkTags.document(d.id)),
                            )
                        }
                    }
                } else {
                    Text(strings.noteAnchorsHint, style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            ListItem(
                                headlineContent = { Text(strings.noteTop) },
                                leadingContent = { Icon(Icons.Filled.VerticalAlignTop, contentDescription = null) },
                                modifier = Modifier.clickable { onConfirm(doc, null) }.testTag(NoteLinkTags.TOP),
                            )
                        }
                        items(anchors.orEmpty(), key = { it.anchorId.value }) { a ->
                            ListItem(
                                headlineContent = { Text(a.name) },
                                leadingContent = { Icon(Icons.Filled.Flag, contentDescription = null) },
                                modifier = Modifier.clickable { onConfirm(doc, a) }.testTag(NoteLinkTags.anchor(a.name)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (chosen != null) TextButton(onClick = { chosen = null }) { Text(strings.back) }
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}
