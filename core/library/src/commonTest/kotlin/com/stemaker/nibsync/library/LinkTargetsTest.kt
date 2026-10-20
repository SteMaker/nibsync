package com.stemaker.nibsync.library

import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.OpStamp
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.LinkTarget
import com.stemaker.nibsync.model.StrokeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkTargetsTest {
    private val dev = DeviceId("a")

    private fun node(id: String, parent: String?, title: String, folder: Boolean = false) =
        LibraryNode(NodeId(id), if (folder) NodeKind.FOLDER else NodeKind.DOCUMENT, parent?.let(::NodeId), title, 0)

    private val tree = LibraryTree(
        listOf(
            node("b", null, "Arbeit", folder = true),
            node("c", "b", "Projekt", folder = true),
            node("d1", "b", "Notizen"),
            node("d2", "c", "Protokoll"),
        ),
    )

    private fun anchor(id: String, y: Float, name: String) = Anchor(StrokeId("e-$id"), StrokeId(id), 10f, y, name, 0, dev)

    private val doc = DocumentState.empty(DocumentMeta(DocumentId("d2"), "Protokoll", createdAtEpochMs = 0))
        .apply(DocumentOp.AddLinks(OpStamp(1, dev), listOf(anchor("unten", 90f, "Ende"), anchor("oben", 5f, "Beginn"))))

    @Test
    fun documentsWithTheirFoldersAndAFilter() {
        assertEquals(
            listOf(LinkableDocument(DocumentId("d2"), "Protokoll", "Arbeit › Projekt"), LinkableDocument(DocumentId("d1"), "Notizen", "Arbeit")),
            LinkTargets.documents(tree),
        )
        assertEquals(listOf("Protokoll"), LinkTargets.documents(tree, " projekt ").map { it.title })
        assertEquals(listOf("Notizen"), LinkTargets.documents(tree, "NOTIZ").map { it.title })
    }

    @Test
    fun anchorsTopDownAndWhereALinkLeads() {
        assertEquals(listOf("Beginn", "Ende"), LinkTargets.anchors(doc).map { it.name })
        assertEquals(90f, LinkTargets.revealY(LinkTarget(DocumentId("d2"), StrokeId("unten")), tree, doc))
        // Without anchor, or when it was deleted: the top of the document.
        assertEquals(0f, LinkTargets.revealY(LinkTarget(DocumentId("d2"), null), tree, doc))
        assertEquals(0f, LinkTargets.revealY(LinkTarget(DocumentId("d2"), StrokeId("weg")), tree, doc))
        assertNull(LinkTargets.revealY(LinkTarget(DocumentId("unknown"), null), tree, null))
    }

    @Test
    fun aNewLinkIsCentredAndStaysOnThePage() {
        val target = LinkTargets.documents(tree).first()
        val draft = LinkTargets.draft(target, LinkTargets.anchors(doc).first(), cx = 205f, cy = 50f, pageWidthMm = 210f)
        assertEquals(StrokeId("oben"), draft.anchorId)
        assertEquals("Beginn", draft.anchorName)
        assertEquals(46f, draft.y)
        assertEquals(true, draft.x > 0f && draft.x < 210f)
    }
}
