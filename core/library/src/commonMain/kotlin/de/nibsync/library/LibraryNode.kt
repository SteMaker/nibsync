package de.nibsync.library

import de.nibsync.model.DocumentId
import kotlin.jvm.JvmInline

/**
 * Id of a library entry. A document's node id equals its [DocumentId], so the id names both
 * the tree entry and the stored document. Restricted to [A-Za-z0-9_-] (used as file name).
 */
@JvmInline
value class NodeId(val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Invalid NodeId '$value'" }
    }

    val asDocumentId: DocumentId get() = DocumentId(value)

    override fun toString(): String = value
}

enum class NodeKind {
    /** A book (top level) or chapter: contains folders and documents. */
    FOLDER,

    /** A note document. */
    DOCUMENT,
}

/**
 * One entry of the library tree. Top-level folders are books; documents only live inside
 * folders. Deleted entries are kept as tombstones ([deleted]) so a later sync cannot bring
 * them back.
 */
data class LibraryNode(
    val id: NodeId,
    val kind: NodeKind,
    /** Containing folder; null for books. */
    val parent: NodeId?,
    val title: String,
    val createdAtEpochMs: Long,
    val deleted: Boolean = false,
    /** Time of the last change (rename, delete); decides between versions from different devices. */
    val modifiedAtEpochMs: Long = createdAtEpochMs,
) {
    init {
        require(kind == NodeKind.FOLDER || parent != null) { "Documents must be inside a folder" }
        require(parent != id) { "A node cannot contain itself" }
    }

    val isFolder: Boolean get() = kind == NodeKind.FOLDER

    /**
     * Combines two versions of the same node (e.g. this device's and one from the server):
     * deleted stays deleted, otherwise the later change wins (ties: the larger title, so every
     * device picks the same).
     */
    fun mergedWith(other: LibraryNode): LibraryNode {
        require(other.id == id) { "Different nodes" }
        val newer = when {
            other.modifiedAtEpochMs != modifiedAtEpochMs -> if (other.modifiedAtEpochMs > modifiedAtEpochMs) other else this
            else -> if (other.title > title) other else this
        }
        return if (deleted || other.deleted) newer.copy(deleted = true) else newer
    }
}
