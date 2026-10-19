package com.stemaker.nibsync.library

/**
 * Read-only view of the library: the live (not deleted) nodes arranged as a tree. Entries
 * whose parent is missing or deleted are hidden, as is anything inside them.
 */
class LibraryTree(allNodes: Collection<LibraryNode>) {
    private val byId: Map<NodeId, LibraryNode> = allNodes.filter { !it.deleted }.associateBy { it.id }
    private val children: Map<NodeId?, List<LibraryNode>> = byId.values
        .filter { it.parent == null || it.parent in byId }
        .groupBy { it.parent }
        .mapValues { (_, list) -> list.sortedWith(ORDER) }

    /** Books, sorted. */
    val books: List<LibraryNode> get() = children[null].orEmpty().filter { it.isFolder }

    operator fun get(id: NodeId): LibraryNode? = byId[id]?.takeIf { isReachable(it) }

    fun childrenOf(id: NodeId): List<LibraryNode> = if (get(id) == null) emptyList() else children[id].orEmpty()

    /** The node and its ancestors, from the book down to [id]; empty if unknown. */
    fun pathTo(id: NodeId): List<LibraryNode> {
        val path = ArrayList<LibraryNode>()
        var current = byId[id]
        val seen = HashSet<NodeId>()
        while (current != null && seen.add(current.id)) {
            path += current
            current = current.parent?.let { byId[it] }
        }
        return if (path.lastOrNull()?.parent == null) path.asReversed() else emptyList()
    }

    /** All live nodes inside [id] (not including it), at any depth. */
    fun descendantsOf(id: NodeId): List<LibraryNode> =
        childrenOf(id).flatMap { listOf(it) + descendantsOf(it.id) }

    /** All reachable documents, depth-first in tree order. */
    val documents: List<LibraryNode>
        get() = books.flatMap { b -> descendantsOf(b.id) }.filter { !it.isFolder }

    /**
     * Whether [id] (a chapter or document, not a book) may go into the folder [target]: not
     * into itself or anything inside it.
     */
    fun canMove(id: NodeId, target: NodeId): Boolean {
        val node = get(id) ?: return false
        val folder = get(target) ?: return false
        if (node.parent == null || !folder.isFolder || id == target) return false
        return pathTo(target).none { it.id == id }
    }

    /**
     * Where [dragged] goes when dropped on the entry [over] in a tree view: into a folder, or
     * next to a document (into its folder). Null if it cannot go there.
     */
    fun dropTarget(dragged: NodeId, over: NodeId): NodeId? {
        val node = get(over) ?: return null
        val target = if (node.isFolder) node.id else node.parent ?: return null
        return target.takeIf { canMove(dragged, it) }
    }

    private fun isReachable(node: LibraryNode): Boolean = pathTo(node.id).isNotEmpty()

    companion object {
        /** Like a file browser: folders first, then documents, each by title (case-insensitive). */
        val ORDER: Comparator<LibraryNode> =
            compareBy<LibraryNode> { if (it.isFolder) 0 else 1 }
                .thenBy { it.title.lowercase() }
                .thenBy { it.createdAtEpochMs }
                .thenBy { it.id.value }
    }
}
