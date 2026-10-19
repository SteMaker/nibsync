package com.stemaker.nibsync.library

import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.OpStamp
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoIndexTest {
    private val dev = DeviceId("a")

    private fun node(id: String, parent: String?, title: String, folder: Boolean = false) =
        LibraryNode(NodeId(id), if (folder) NodeKind.FOLDER else NodeKind.DOCUMENT, parent?.let(::NodeId), title, 0)

    private fun todo(id: String, y: Float, text: String) =
        TodoItem(StrokeId(id), StrokeId("task-$id"), 0f, y, 60f, 14f, TodoContent.Text(RichText.plain(text)), 0, dev)

    private fun doc(id: String, vararg todos: TodoItem, done: Set<String> = emptySet()): DocumentState {
        var counter = 0L
        val ops = listOf(DocumentOp.AddTodos(OpStamp(++counter, dev), todos.toList())) +
            done.map { DocumentOp.SetTodoDone(OpStamp(++counter, dev), StrokeId("task-$it"), true) }
        return DocumentState.empty(DocumentMeta(DocumentId(id), id, createdAtEpochMs = 0)).applyAll(ops)
    }

    @Test
    fun collectsOpenTodosInLibraryOrder() = runTest {
        val tree = LibraryTree(
            listOf(
                node("b", null, "Arbeit", folder = true),
                node("c", "b", "Projekt", folder = true),
                node("d1", "b", "Notizen"),
                node("d2", "c", "Protokoll"),
                node("gone", "b", "Gelöscht").copy(deleted = true),
            ),
        )
        val docs = mapOf(
            "d1" to doc("d1", todo("x", 50f, "später"), todo("y", 10f, "zuerst"), todo("z", 20f, "erledigt"), done = setOf("z")),
            "d2" to doc("d2", todo("p", 0f, "Protokoll schicken")),
            "gone" to doc("gone", todo("g", 0f, "weg")),
        )
        val open = TodoIndex.collect(tree) { docs[it.value] }
        // Chapters before documents (tree order), then top to bottom.
        assertEquals(listOf("p", "y", "x"), open.map { it.item.id.value })
        assertEquals(listOf("Arbeit", "Projekt", "Protokoll"), open.first().path)
        assertEquals(DocumentId("d1"), open.last().document)
    }

    @Test
    fun unreadableDocumentsAreSkipped() = runTest {
        val tree = LibraryTree(listOf(node("b", null, "B", folder = true), node("d1", "b", "1"), node("d2", "b", "2")))
        val open = TodoIndex.collect(tree) { if (it.value == "d1") error("broken") else doc("d2", todo("t", 0f, "t")) }
        assertEquals(listOf("t"), open.map { it.item.id.value })
    }
}

class TodoCollectorTest {
    @Test
    fun storedDocumentsAreReadOnceUntilInvalidated() = runTest {
        val dev = DeviceId("a")
        val tree = LibraryTree(
            listOf(
                LibraryNode(NodeId("b"), NodeKind.FOLDER, null, "B", 0),
                LibraryNode(NodeId("d"), NodeKind.DOCUMENT, NodeId("b"), "D", 0),
            ),
        )
        val item = TodoItem(StrokeId("t"), StrokeId("k"), 0f, 0f, 60f, 14f, TodoContent.Text(RichText.plain("x")), 0, dev)
        val empty = DocumentState.empty(DocumentMeta(DocumentId("d"), "d", createdAtEpochMs = 0))
        var stored = empty.apply(DocumentOp.AddTodos(OpStamp(1, dev), listOf(item)))
        var reads = 0
        var live: DocumentState? = null
        val collector = TodoCollector({ live }, { reads++; stored })
        assertEquals(1, collector.collect(tree).size)
        stored = stored.apply(DocumentOp.SetTodoDone(OpStamp(2, dev), StrokeId("k"), true))
        assertEquals(1, collector.collect(tree).size)
        assertEquals(1, reads)
        collector.invalidate()
        assertEquals(0, collector.collect(tree).size)
        // An open document is always read live.
        live = empty.apply(DocumentOp.AddTodos(OpStamp(1, dev), listOf(item)))
        assertEquals(1, collector.collect(tree).size)
        assertEquals(2, reads)
    }
}
