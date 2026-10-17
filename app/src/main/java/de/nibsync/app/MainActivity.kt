package de.nibsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nibsync.canvas.CanvasViewModel
import de.nibsync.canvas.NoteCanvasView
import de.nibsync.canvas.NoteScreen
import de.nibsync.document.DocumentSession
import de.nibsync.model.DocumentId
import kotlinx.coroutines.launch

/**
 * Shows one note document. For now this is a single default document; the book/chapter
 * library will later pass the document to open via [EXTRA_DOCUMENT_ID].
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as NibSyncApp).container

    val documentId: DocumentId by lazy {
        intent.getStringExtra(EXTRA_DOCUMENT_ID)?.let(::DocumentId) ?: DEFAULT_DOCUMENT
    }

    /** The canvas, once created. Exposed for instrumented tests. */
    var canvasView: NoteCanvasView? = null
        private set

    var session: DocumentSession? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val title = getString(R.string.default_document_title)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff1a3fa8))) {
                Surface(Modifier.fillMaxSize()) {
                    var openSession by remember { mutableStateOf<DocumentSession?>(null) }
                    LaunchedEffect(documentId) {
                        openSession = container.openSession(documentId, title).also { session = it }
                    }
                    val current = openSession
                    if (current == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        val vm = viewModel(key = documentId.value) { CanvasViewModel(current) }
                        NoteScreen(vm, Modifier.safeDrawingPadding(), onCanvasCreated = { canvasView = it })
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Make sure everything written so far reaches the disk while the app is in background.
        val s = session ?: return
        container.appScope.launch { s.flush() }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "de.nibsync.extra.DOCUMENT_ID"
        val DEFAULT_DOCUMENT = DocumentId("default")
    }
}
