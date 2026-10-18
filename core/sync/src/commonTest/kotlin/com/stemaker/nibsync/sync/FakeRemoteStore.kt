package com.stemaker.nibsync.sync

/**
 * In-memory stand-in for a WebDAV server. Folder etags change when anything below them
 * changes, like on Nextcloud. Counts requests so tests can check the cost of a sync.
 */
class FakeRemoteStore : RemoteStore {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = linkedSetOf("")
    var requests = 0
    var writes = 0
    var failNext: Exception? = null

    private fun check() {
        requests++
        failNext?.let { failNext = null; throw it }
    }

    private fun parent(path: String) = path.substringBeforeLast('/', "")

    private fun etagOf(path: String): String =
        files[path]?.contentHashCode()?.toString()
            ?: children(path).joinToString("|") { "${it}:${etagOf(join(path, it))}" }.hashCode().toString()

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun children(dir: String): List<String> =
        (files.keys + dirs).filter { it != dir && it.isNotEmpty() && parent(it) == dir }.map { it.substringAfterLast('/') }.distinct().sorted()

    override suspend fun list(path: String): RemoteListing? {
        check()
        if (path !in dirs) return null
        return RemoteListing(etagOf(path), children(path).map { name ->
            val full = join(path, name)
            RemoteEntry(name, full in dirs, etagOf(full), files[full]?.size?.toLong() ?: 0L)
        })
    }

    override suspend fun read(path: String, offset: Long): ByteArray {
        check()
        val bytes = files[path] ?: error("404 $path")
        return bytes.copyOfRange(offset.toInt(), bytes.size)
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        check()
        require(parent(path) in dirs) { "409 parent of $path missing" }
        files[path] = bytes.copyOf()
        writes++
    }

    override suspend fun makeDirectory(path: String) {
        check()
        if (path.isNotEmpty()) require(parent(path) in dirs) { "409 parent of $path missing" }
        dirs += path
    }

    override suspend fun delete(path: String) {
        check()
        files.keys.removeAll { it == path || it.startsWith("$path/") }
        dirs.removeAll { it == path || it.startsWith("$path/") }
    }
}
