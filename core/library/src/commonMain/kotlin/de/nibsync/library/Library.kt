package de.nibsync.library

import de.nibsync.model.BackgroundSpec
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.model.IdGenerator
import de.nibsync.model.PageFormat
import de.nibsync.storage.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The library of books, chapters and documents: keeps the tree, enforces its rules and creates
 * the stored documents for new document entries.
 */
class Library(
    private val nodes: LibraryRepository,
    private val documents: DocumentRepository,
    private val ids: IdGenerator = IdGenerator(),
    private val clock: () -> Long,
) {
    private val all = LinkedHashMap<NodeId, LibraryNode>()
    private val lock = Mutex()
    private val _tree = MutableStateFlow(LibraryTree(emptyList()))
    val tree: StateFlow<LibraryTree> = _tree.asStateFlow()

    /**
     * Loads the library. A new installation gets a first book with one document; notes from
     * before the library existed (document [legacyDocument]) are put into that book.
     */
    suspend fun load(defaultBookTitle: String, defaultDocumentTitle: String, legacyDocument: DocumentId? = null) = lock.withLock {
        all.clear()
        nodes.loadAll().forEach { all[it.id] = it }
        if (all.values.none { !it.deleted }) {
            val book = folderNode(null, defaultBookTitle)
            store(book)
            if (legacyDocument != null && documents.exists(legacyDocument)) {
                store(LibraryNode(NodeId(legacyDocument.value), NodeKind.DOCUMENT, book.id, defaultDocumentTitle, clock()))
            } else {
                createDocumentLocked(book.id, defaultDocumentTitle, PageFormat())
            }
        }
        publish()
    }

    suspend fun createBook(title: String): LibraryNode = lock.withLock {
        folderNode(null, title).also { store(it); publish() }
    }

    suspend fun createFolder(parent: NodeId, title: String): LibraryNode = lock.withLock {
        requireFolder(parent)
        folderNode(parent, title).also { store(it); publish() }
    }

    suspend fun createDocument(
        parent: NodeId,
        title: String,
        format: PageFormat,
        background: BackgroundSpec = BackgroundSpec(),
    ): LibraryNode = lock.withLock {
        requireFolder(parent)
        createDocumentLocked(parent, title, format, background).also { publish() }
    }

    suspend fun rename(id: NodeId, title: String) = lock.withLock {
        val node = requireNotNull(_tree.value[id]) { "Unknown node $id" }
        store(node.copy(title = title.trim().ifEmpty { node.title }, modifiedAtEpochMs = clock()))
        publish()
    }

    /** Deletes the entry and, for folders, everything inside, including stored documents. */
    suspend fun delete(id: NodeId) = lock.withLock {
        val tree = _tree.value
        val node = tree[id] ?: return@withLock
        val now = clock()
        for (n in tree.descendantsOf(id) + node) {
            store(n.copy(deleted = true, modifiedAtEpochMs = now))
            if (!n.isFolder) documents.delete(n.id.asDocumentId)
        }
        publish()
    }

    /** Encoded node as stored, for transferring it unchanged. */
    fun encode(node: LibraryNode): ByteArray = nodes.encode(node)

    fun decode(bytes: ByteArray): LibraryNode? = nodes.decode(bytes)

    /** Every stored node including tombstones, e.g. for uploading. */
    suspend fun allNodes(): List<LibraryNode> = lock.withLock { all.values.toList() }

    /**
     * Merges nodes received from other devices (see [LibraryNode.mergedWith]). Documents that
     * become deleted lose their stored content; [beforeDocumentsDeleted] runs first, e.g. to
     * close their editing sessions. Returns the nodes that changed locally.
     */
    suspend fun mergeRemote(
        remote: List<LibraryNode>,
        beforeDocumentsDeleted: suspend (Set<DocumentId>) -> Unit = {},
    ): List<LibraryNode> = lock.withLock {
        val changed = ArrayList<LibraryNode>()
        val newlyDeletedDocs = HashSet<DocumentId>()
        for (incoming in remote) {
            val local = all[incoming.id]
            val merged = local?.mergedWith(incoming) ?: incoming
            if (merged == local) continue
            if (merged.deleted && local?.deleted != true && !merged.isFolder) newlyDeletedDocs += merged.id.asDocumentId
            changed += merged
        }
        if (newlyDeletedDocs.isNotEmpty()) beforeDocumentsDeleted(newlyDeletedDocs)
        for (n in changed) store(n)
        for (doc in newlyDeletedDocs) documents.delete(doc)
        if (changed.isNotEmpty()) publish()
        changed
    }

    private suspend fun createDocumentLocked(
        parent: NodeId,
        title: String,
        format: PageFormat,
        background: BackgroundSpec = BackgroundSpec(),
    ): LibraryNode {
        val node = LibraryNode(NodeId(ids.newUuid()), NodeKind.DOCUMENT, parent, title.trim().ifEmpty { "?" }, clock())
        documents.create(DocumentMeta(node.id.asDocumentId, node.title, format, background, node.createdAtEpochMs))
        store(node)
        return node
    }

    private fun folderNode(parent: NodeId?, title: String) =
        LibraryNode(NodeId(ids.newUuid()), NodeKind.FOLDER, parent, title.trim().ifEmpty { "?" }, clock())

    private fun requireFolder(id: NodeId) {
        val node = requireNotNull(_tree.value[id]) { "Unknown folder $id" }
        require(node.isFolder) { "$id is not a folder" }
    }

    private suspend fun store(node: LibraryNode) {
        nodes.save(node)
        all[node.id] = node
    }

    private fun publish() {
        _tree.value = LibraryTree(all.values.toList())
    }
}
