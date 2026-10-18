package com.stemaker.nibsync.sync

/** A file or folder on the server. */
data class RemoteEntry(val name: String, val isDirectory: Boolean, val etag: String, val size: Long)

/** Content of a remote folder. The folder's [etag] changes whenever anything inside changes. */
data class RemoteListing(val etag: String, val entries: List<RemoteEntry>) {
    operator fun get(name: String): RemoteEntry? = entries.firstOrNull { it.name == name }
}

/**
 * Minimal remote file store the sync works with. Paths are relative to the sync folder and
 * use "/" ("" is the sync folder itself). Implemented for WebDAV (Nextcloud), OneDrive and
 * Google Drive, and by a fake in tests. Failures (network, server) are thrown as exceptions.
 *
 * Changes made by other devices are guaranteed to show up after listing the sync folder ("")
 * again; stores that keep a local index refresh it then (the sync engine does this first in
 * every run).
 */
interface RemoteStore {
    /** Lists a folder, or returns null if it does not exist. */
    suspend fun list(path: String): RemoteListing?

    /** Reads a file from byte [offset] to its end. */
    suspend fun read(path: String, offset: Long = 0): ByteArray

    /** Creates or replaces a file (atomically from a reader's point of view). */
    suspend fun write(path: String, bytes: ByteArray)

    /** Creates a folder; does nothing if it exists. The parent must exist. */
    suspend fun makeDirectory(path: String)

    /** Deletes a file or folder with its content; does nothing if it does not exist. */
    suspend fun delete(path: String)
}
