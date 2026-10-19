package com.stemaker.nibsync.library.ui

import androidx.lifecycle.ViewModel
import com.stemaker.nibsync.library.Library
import com.stemaker.nibsync.library.LibraryNode
import com.stemaker.nibsync.library.LibraryTree
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.PageFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One visible line of the tree. */
data class TreeRow(val node: LibraryNode, val depth: Int, val expanded: Boolean, val hasChildren: Boolean)

/**
 * State of the library drawer: which folders are open and which folder is selected as the
 * place for new entries. The actions are suspending; the UI launches them.
 */
class LibraryViewModel(
    private val library: Library,
    /** Called with the documents about to be deleted, e.g. to close their editing sessions. */
    private val beforeDelete: suspend (Set<DocumentId>) -> Unit = {},
) : ViewModel() {

    val tree: StateFlow<LibraryTree> = library.tree

    private val _expanded = MutableStateFlow<Set<NodeId>>(emptySet())
    val expanded: StateFlow<Set<NodeId>> = _expanded.asStateFlow()

    private val _selectedFolder = MutableStateFlow<NodeId?>(null)

    /** Folder new chapters and documents are created in; null = none selected. */
    val selectedFolder: StateFlow<NodeId?> = _selectedFolder.asStateFlow()

    private val _highlighted = MutableStateFlow<NodeId?>(null)

    /** The one entry shown as selected: the folder or document tapped last. */
    val highlighted: StateFlow<NodeId?> = _highlighted.asStateFlow()

    /**
     * A document was opened: it becomes the highlighted entry and its folder the place for new
     * entries, without being highlighted itself.
     */
    fun onDocumentOpened(id: NodeId) {
        _highlighted.value = id
        tree.value[id]?.parent?.let { _selectedFolder.value = it }
    }

    /** Lines to show: books and the content of every expanded folder, in tree order. */
    fun rows(tree: LibraryTree, expanded: Set<NodeId>): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        fun add(node: LibraryNode, depth: Int) {
            val children = if (node.isFolder) tree.childrenOf(node.id) else emptyList()
            val open = node.id in expanded
            out += TreeRow(node, depth, open, children.isNotEmpty())
            if (open) children.forEach { add(it, depth + 1) }
        }
        tree.books.forEach { add(it, 0) }
        return out
    }

    /** Tapping a folder selects it and opens or closes it. */
    fun onFolderTapped(id: NodeId) {
        val wasSelected = _selectedFolder.value == id && _highlighted.value == id
        _selectedFolder.value = id
        _highlighted.value = id
        // A first tap on a closed folder opens it; tapping the selected folder again toggles it.
        _expanded.update { if (id !in it) it + id else if (wasSelected) it - id else it }
    }

    /** Shows the document in the tree: opens all folders above it. */
    fun reveal(id: NodeId) {
        val path = tree.value.pathTo(id)
        _expanded.update { it + path.dropLast(1).map { n -> n.id } }
    }

    suspend fun createBook(title: String): LibraryNode =
        library.createBook(title).also { selectAndOpen(it.id) }

    suspend fun createFolder(title: String): LibraryNode? {
        val parent = _selectedFolder.value ?: return null
        return library.createFolder(parent, title).also {
            _expanded.update { e -> e + parent }
            selectAndOpen(it.id)
        }
    }

    /** Creates a document in the selected folder; returns it so the caller can open it. */
    suspend fun createDocument(title: String, format: PageFormat, background: BackgroundSpec = BackgroundSpec()): LibraryNode? {
        val parent = _selectedFolder.value ?: return null
        return library.createDocument(parent, title, format, background).also { _expanded.update { e -> e + parent } }
    }

    suspend fun rename(id: NodeId, title: String) = library.rename(id, title)

    /** Opens [id] in the tree (e.g. a folder held under a dragged entry). */
    fun expand(id: NodeId) = _expanded.update { it + id }

    /** Moves a chapter or document into [target]; the new place is shown open. */
    suspend fun move(id: NodeId, target: NodeId): Boolean {
        if (!library.move(id, target)) return false
        _expanded.update { it + tree.value.pathTo(target).map { n -> n.id } }
        return true
    }

    suspend fun delete(id: NodeId) {
        val affected = tree.value.descendantsOf(id) + listOfNotNull(tree.value[id])
        val removed = affected.map { it.id }.toSet()
        beforeDelete(affected.filter { !it.isFolder }.map { it.id.asDocumentId }.toSet())
        library.delete(id)
        if (_selectedFolder.value in removed) _selectedFolder.value = null
        if (_highlighted.value in removed) _highlighted.value = null
        _expanded.update { it - removed }
    }

    private fun selectAndOpen(id: NodeId) {
        _selectedFolder.value = id
        _highlighted.value = id
        _expanded.update { it + id }
    }
}
