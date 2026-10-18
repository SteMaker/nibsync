package de.nibsync.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import de.nibsync.document.DocumentSession
import de.nibsync.library.NodeId
import de.nibsync.model.DocumentId
import java.io.File

/** The main window content: library on the left, the open document on the right. */
@Composable
fun App(container: DesktopContainer, settings: Settings, strings: Strings = Strings()) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff1a3fa8))) {
        Surface(Modifier.fillMaxSize()) {
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                container.library.load(strings.defaultBook, strings.defaultDocument)
                ready = true
            }
            val tree by container.library.tree.collectAsState()
            var documentId by remember { mutableStateOf<DocumentId?>(null) }
            LaunchedEffect(ready, tree) {
                if (!ready) return@LaunchedEffect
                val docs = tree.documents.map { it.id.asDocumentId }
                if (documentId == null || documentId !in docs) {
                    documentId = settings.lastDocument?.takeIf { it in docs } ?: docs.firstOrNull()
                }
            }
            Row(Modifier.fillMaxSize()) {
                LibraryPane(
                    tree = tree,
                    current = documentId?.let { NodeId(it.value) },
                    strings = strings,
                    onOpen = {
                        documentId = it.asDocumentId
                        settings.lastDocument = it.asDocumentId
                    },
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    val id = documentId
                    if (id == null) {
                        if (ready) Text(strings.noDocument) else CircularProgressIndicator()
                    } else {
                        DocumentPane(container, id)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentPane(container: DesktopContainer, id: DocumentId) {
    var session by remember(id) { mutableStateOf<DocumentSession?>(null) }
    LaunchedEffect(id) { session = container.openSession(id) }
    val s = session ?: return CircularProgressIndicator()
    val doc by s.state.collectAsState()
    val scope = rememberCoroutineScope()
    val painter = remember(id) {
        PagePainter(DesktopTextLayout(), MediaCache(scope) { File(container.repository.assetPath(id, it.sha256).toString()) })
    }
    var viewport by remember(id) { mutableStateOf(initialViewport(doc.meta.format.widthMm, doc.meta.format.pageHeightMm)) }
    PageView(doc, painter, viewport, onViewportChange = { viewport = it })
}
