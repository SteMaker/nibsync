package com.stemaker.nibsync.sync.cloud

import com.stemaker.nibsync.sync.RemoteEntry
import com.stemaker.nibsync.sync.RemoteListing
import java.security.MessageDigest

/**
 * A local picture of the sync folder in a cloud drive whose folders do not tell whether
 * something below them changed (OneDrive, Google Drive): items by id, kept up to date from the
 * provider's change feed. From it, folders get etags that change whenever anything inside
 * changes, as the sync engine expects (and Nextcloud provides).
 *
 * Drives may hold several items with the same name in a folder (two devices creating
 * "documents" at once). Same-named folders count as one folder with all their children;
 * of same-named files the most recently modified one counts.
 */
class RemoteIndex(val rootId: String) {
    data class Item(
        val id: String,
        val name: String,
        val parentId: String?,
        val isDirectory: Boolean,
        /** Changes whenever the item's content changes. */
        val version: String,
        val size: Long,
        /** Sortable modification time (ISO 8601), to pick between same-named files. */
        val modified: String,
    )

    private val items = HashMap<String, Item>()
    private val children = HashMap<String, MutableSet<String>>()
    private val etags = HashMap<String, String>()

    fun put(item: Item) {
        remove(item.id, recursive = false)
        items[item.id] = item
        item.parentId?.let { children.getOrPut(it) { LinkedHashSet() } += item.id }
        etags.clear()
    }

    /** Removes an item; with [recursive] also everything below it. */
    fun remove(id: String, recursive: Boolean = true) {
        val old = items.remove(id) ?: return
        old.parentId?.let { children[it]?.remove(id) }
        if (recursive) children.remove(id)?.toList()?.forEach { remove(it, true) }
        etags.clear()
    }

    fun clear() {
        items.clear()
        children.clear()
        etags.clear()
    }

    operator fun get(id: String): Item? = items[id]

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

    /** Ids of the folders at [path] (several if there are same-named duplicates), oldest first. */
    fun folderIds(path: String): List<String> {
        var current = listOf(rootId)
        for (segment in segments(path)) {
            current = current.flatMap { parent ->
                children[parent].orEmpty().mapNotNull { items[it] }.filter { it.isDirectory && it.name == segment }
            }.sortedWith(compareBy({ it.modified }, { it.id })).map { it.id }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    /** The file at [path], or null. */
    fun file(path: String): Item? {
        val name = path.substringAfterLast('/')
        val parents = folderIds(path.substringBeforeLast('/', ""))
        return parents.flatMap { p -> children[p].orEmpty().mapNotNull { items[it] } }
            .filter { !it.isDirectory && it.name == name }
            .maxWithOrNull(compareBy({ it.modified }, { it.id }))
    }

    /** All items (files and folders) named like the last segment of [path]. */
    fun itemsAt(path: String): List<Item> {
        if (segments(path).isEmpty()) return listOfNotNull(items[rootId])
        val name = path.substringAfterLast('/')
        return folderIds(path.substringBeforeLast('/', "")).flatMap { p -> children[p].orEmpty().mapNotNull { items[it] } }.filter { it.name == name }
    }

    /** What the sync engine sees of a folder, or null if there is none at [path]. */
    fun listing(path: String): RemoteListing? {
        val ids = folderIds(path)
        if (ids.isEmpty()) return null
        return RemoteListing(folderEtag(path), entries(path, ids))
    }

    private fun entries(path: String, ids: List<String>): List<RemoteEntry> {
        val byName = ids.flatMap { p -> children[p].orEmpty().mapNotNull { items[it] } }.groupBy { it.name }
        return byName.map { (name, group) ->
            val childPath = if (path.isEmpty()) name else "$path/$name"
            if (group.any { it.isDirectory }) {
                RemoteEntry(name, true, folderEtag(childPath), 0)
            } else {
                val f = group.maxWith(compareBy({ it.modified }, { it.id }))
                RemoteEntry(name, false, f.version, f.size)
            }
        }.sortedBy { it.name }
    }

    private fun folderEtag(path: String): String = etags.getOrPut(path) {
        val ids = folderIds(path)
        val text = entries(path, ids).joinToString("\n") { "${it.name}|${it.isDirectory}|${it.etag}|${it.size}" }
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
