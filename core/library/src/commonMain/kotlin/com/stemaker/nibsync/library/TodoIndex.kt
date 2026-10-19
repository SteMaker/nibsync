package com.stemaker.nibsync.library

import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An open to-do and where it is: the document and its titles from the book down. */
data class OpenTodo(val document: DocumentId, val path: List<String>, val item: TodoItem)

/**
 * Collects the open to-dos of the whole library for the generated "Open to-dos" page. Nothing
 * is stored: the list is derived from the documents each time, so it is never synced.
 */
object TodoIndex {
    /**
     * Open to-dos in library order (books, chapters, documents as in the tree), within a
     * document from top to bottom. [load] returns a document's current state (the open
     * session's if there is one), or null if it cannot be read; such documents are skipped.
     */
    suspend fun collect(tree: LibraryTree, load: suspend (DocumentId) -> DocumentState?): List<OpenTodo> =
        tree.documents.flatMap { node ->
            val doc = runCatching { load(node.id.asDocumentId) }.getOrNull() ?: return@flatMap emptyList()
            val path = tree.pathTo(node.id).map { it.title }
            openTodos(doc).map { OpenTodo(node.id.asDocumentId, path, it) }
        }

    /** The document's open to-dos from top to bottom, left to right. */
    fun openTodos(doc: DocumentState): List<TodoItem> =
        doc.todos.filter { !doc.isDone(it.taskId) }.sortedWith(compareBy<TodoItem> { it.y }.thenBy { it.x })
}

/**
 * [TodoIndex.collect] with stored documents kept in memory between runs: open documents come
 * from [live] (their editing session) every time, others are read with [stored] once until
 * [invalidate] (call it when the sync may have changed files).
 */
class TodoCollector(
    private val live: suspend (DocumentId) -> DocumentState?,
    private val stored: suspend (DocumentId) -> DocumentState,
) {
    private val cache = HashMap<DocumentId, DocumentState>()
    private val lock = Mutex()

    suspend fun invalidate() = lock.withLock { cache.clear() }

    suspend fun collect(tree: LibraryTree): List<OpenTodo> = lock.withLock {
        cache.keys.retainAll(tree.documents.mapTo(HashSet()) { it.id.asDocumentId })
        TodoIndex.collect(tree) { id -> live(id) ?: cache[id] ?: stored(id).also { cache[id] = it } }
    }
}
