package com.stemaker.nibsync.sync.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteIndexTest {
    private fun dir(id: String, name: String, parent: String, modified: String = "2026-01-01") = RemoteIndex.Item(id, name, parent, true, "v-$id", 0, modified)
    private fun file(id: String, name: String, parent: String, version: String = "1", size: Long = 10, modified: String = "2026-01-01") =
        RemoteIndex.Item(id, name, parent, false, version, size, modified)

    private fun index() = RemoteIndex("root").apply {
        put(RemoteIndex.Item("root", "NibSync", null, true, "r", 0, ""))
        put(dir("lib", "library", "root"))
        put(dir("docs", "documents", "root"))
        put(dir("d1", "doc1", "docs"))
        put(dir("ops", "ops", "d1"))
        put(file("log", "a.oplog", "ops"))
        put(file("n1", "n1.json", "lib"))
    }

    @Test
    fun listsFoldersWithEtagsThatChangeWithAnythingBelow() {
        val i = index()
        val root = i.listing("")!!
        assertThat(root.entries.map { it.name }).containsExactly("documents", "library")
        val before = root.etag
        val docBefore = i.listing("documents")!!["doc1"]!!.etag
        // A longer log deep down changes every folder above it, but not the library.
        val libBefore = i.listing("library")!!.etag
        i.put(file("log", "a.oplog", "ops", version = "2", size = 20))
        assertThat(i.listing("")!!.etag).isNotEqualTo(before)
        assertThat(i.listing("documents")!!["doc1"]!!.etag).isNotEqualTo(docBefore)
        assertThat(i.listing("library")!!.etag).isEqualTo(libBefore)
        assertThat(i.listing("documents/doc1/ops")!!["a.oplog"]!!.size).isEqualTo(20)
        assertThat(i.listing("nothing")).isNull()
    }

    @Test
    fun sameNamedFoldersCountAsOne() {
        val i = index()
        // A second "documents" folder made by another device at the same time.
        i.put(dir("docs2", "documents", "root", modified = "2026-01-02"))
        i.put(dir("d2", "doc2", "docs2"))
        assertThat(i.listing("")!!.entries.map { it.name }).containsExactly("documents", "library")
        assertThat(i.listing("documents")!!.entries.map { it.name }).containsExactly("doc1", "doc2")
        assertThat(i.folderIds("documents")).containsExactly("docs", "docs2").inOrder()
    }

    @Test
    fun ofSameNamedFilesTheNewestCounts() {
        val i = index()
        i.put(file("n1b", "n1.json", "lib", version = "9", modified = "2026-03-01"))
        assertThat(i.file("library/n1.json")!!.id).isEqualTo("n1b")
        assertThat(i.listing("library")!!.entries.single().etag).isEqualTo("9")
    }

    @Test
    fun removingAFolderRemovesWhatIsInside() {
        val i = index()
        i.remove("d1")
        assertThat(i.listing("documents")!!.entries).isEmpty()
        assertThat(i.file("documents/doc1/ops/a.oplog")).isNull()
        assertThat(i["log"]).isNull()
    }
}
