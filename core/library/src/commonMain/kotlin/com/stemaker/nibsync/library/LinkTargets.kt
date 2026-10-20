package com.stemaker.nibsync.library

import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.LinkElement
import com.stemaker.nibsync.model.LinkTarget
import com.stemaker.nibsync.model.NoteLinkDraft

/** A document a link can lead to, with the titles of the folders above it ("Book › Chapter"). */
data class LinkableDocument(val id: DocumentId, val title: String, val path: String)

/** Choosing where a link to a note leads, and finding that place again. Shared by both apps. */
object LinkTargets {
    /** Documents of the library in tree order whose title or folders contain [query] (any case). */
    fun documents(tree: LibraryTree, query: String = ""): List<LinkableDocument> {
        val q = query.trim()
        return tree.documents.map { node ->
            val folders = tree.pathTo(node.id).dropLast(1).joinToString(" › ") { it.title }
            LinkableDocument(node.id.asDocumentId, node.title, folders)
        }.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.path.contains(q, ignoreCase = true) }
    }

    /** The anchors of a document from top to bottom, left to right. */
    fun anchors(doc: DocumentState): List<Anchor> = doc.anchors.sortedWith(compareBy<Anchor> { it.y }.thenBy { it.x })

    /** A link to [document] (and [anchor] in it, null: its top), centred at ([cx], [cy]) mm on a page [pageWidthMm] wide. */
    fun draft(document: LinkableDocument, anchor: Anchor?, cx: Float, cy: Float, pageWidthMm: Float): NoteLinkDraft {
        val draft = NoteLinkDraft(0f, 0f, document.id, anchor?.anchorId, document.title, anchor?.name)
        val w = LinkElement.widthFor(if (anchor == null) document.title else "${document.title} › ${anchor.name}")
        val h = LinkElement.HEIGHT_MM
        return draft.copy(x = (cx - w / 2).coerceIn(0f, maxOf(0f, pageWidthMm - w)), y = maxOf(0f, cy - h / 2))
    }

    /**
     * Where to scroll in the target document: the anchor's top (mm), or 0 for the document's
     * top or an anchor that no longer exists. Null if the document is not in [tree].
     */
    fun revealY(target: LinkTarget, tree: LibraryTree, doc: DocumentState?): Float? {
        if (tree[NodeId(target.document.value)] == null) return null
        return target.anchorId?.let { doc?.anchor(it)?.y } ?: 0f
    }
}
