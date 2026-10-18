package de.nibsync.sync

import de.nibsync.document.DocumentOp
import de.nibsync.library.Library
import de.nibsync.library.NodeId
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.storage.FileDocumentRepository
import de.nibsync.storage.OpLogCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/** Outcome of one sync run. */
data class SyncReport(
    /** Nothing was transferred because nothing changed on either side. */
    val upToDate: Boolean,
    /** Ops received from other devices, per document, for applying to open sessions. */
    val receivedOps: Map<DocumentId, List<DocumentOp>> = emptyMap(),
    val libraryChanged: Boolean = false,
    val uploads: Int = 0,
    val downloads: Int = 0,
)

/**
 * Synchronises the local documents and library with a remote folder of the same layout:
 *
 * ```
 * <sync folder>/library/<nodeId>.json
 * <sync folder>/documents/<id>/meta.json
 * <sync folder>/documents/<id>/ops/<deviceId>.oplog
 * ```
 *
 * Every device only ever writes its own op logs, and logs only grow, so strokes never
 * conflict: a device uploads its own logs and fetches just the new bytes of the others'.
 * Library entries are merged with [de.nibsync.library.LibraryNode.mergedWith] (latest change
 * wins, deleted stays deleted). Only documents that are part of the library are synced.
 *
 * Runs are serialised; call [sync] as often as convenient. Etags make an unchanged state cost
 * a single request.
 */
class SyncEngine(
    private val fs: FileSystem,
    private val documentsRoot: Path,
    private val stateFile: Path,
    private val library: Library,
    private val device: DeviceId,
    private val remote: RemoteStore,
    private val io: CoroutineDispatcher,
    /** Called before documents are deleted because another device deleted them. */
    private val beforeDocumentsDeleted: suspend (Set<DocumentId>) -> Unit = {},
) {
    private val lock = Mutex()
    private var foldersReady = false
    private val ownLog = "${device.value}${FileDocumentRepository.LOG_SUFFIX}"

    suspend fun sync(): SyncReport = lock.withLock {
        var state = withContext(io) { SyncState.load(fs, stateFile) }
        ensureFolders()

        val root = remote.list("") ?: error("Sync folder vanished")
        val nodes = library.allNodes()
        val nodeHash = nodes.associate { it.id.value to library.encode(it).contentHashCode() }
        val pendingNodes = nodes.filter { state.nodeHashes[it.id.value] != nodeHash[it.id.value] }
        val syncedDocs = nodes.filter { !it.isFolder && !it.deleted }.map { it.id.asDocumentId }
        val pendingLogs = syncedDocs.filter { localValidLength(it, ownLog) > (state.uploadedLog[it.value] ?: 0L) || it.value !in state.metaUploaded }
        if (root.etag == state.rootEtag && pendingNodes.isEmpty() && pendingLogs.isEmpty()) {
            return SyncReport(upToDate = true)
        }

        var uploads = 0
        var downloads = 0

        // 1. Library: fetch changed entries, merge, then upload what differs from the server.
        val libraryListing = remote.list(LIBRARY) ?: RemoteListing("", emptyList())
        val received = ArrayList<de.nibsync.library.LibraryNode>()
        val libraryEtags = state.libraryEtags.toMutableMap()
        val nodeHashes = state.nodeHashes.toMutableMap()
        for (entry in libraryListing.entries) {
            if (entry.isDirectory || !entry.name.endsWith(".json") || libraryEtags[entry.name] == entry.etag) continue
            val bytes = remote.read("$LIBRARY/${entry.name}")
            downloads++
            library.decode(bytes)?.let {
                received += it
                nodeHashes[it.id.value] = library.encode(it).contentHashCode()
            }
            libraryEtags[entry.name] = entry.etag
        }
        val libraryChanged = library.mergeRemote(received, beforeDocumentsDeleted).isNotEmpty()
        for (node in library.allNodes()) {
            val encoded = library.encode(node)
            if (nodeHashes[node.id.value] == encoded.contentHashCode()) continue
            remote.write("$LIBRARY/${node.id.value}.json", encoded)
            uploads++
            nodeHashes[node.id.value] = encoded.contentHashCode()
            libraryEtags.remove("${node.id.value}.json") // re-read our own version once; harmless
        }
        state = state.copy(libraryEtags = libraryEtags, nodeHashes = nodeHashes)

        // 2. Documents.
        val allNodes = library.allNodes()
        val deletedDocs = allNodes.filter { !it.isFolder && it.deleted }.map { it.id.value }.toSet()
        val liveDocs = allNodes.filter { !it.isFolder && !it.deleted }.map { it.id.asDocumentId }
        val documentsListing = remote.list(DOCUMENTS) ?: RemoteListing("", emptyList())
        val documentEtags = state.documentEtags.toMutableMap()
        val uploadedLog = state.uploadedLog.toMutableMap()
        val metaUploaded = state.metaUploaded.toMutableSet()
        val receivedOps = HashMap<DocumentId, List<DocumentOp>>()

        for (entry in documentsListing.entries) {
            if (entry.isDirectory && entry.name in deletedDocs) {
                remote.delete("$DOCUMENTS/${entry.name}")
                documentEtags.remove(entry.name)
            }
        }
        for (doc in liveDocs) {
            val dir = "$DOCUMENTS/${doc.value}"
            val remoteDir = documentsListing[doc.value]
            val localDir = documentsRoot / doc.value

            // Up: meta and this device's log.
            if (remoteDir == null) {
                if (!fs.exists(localDir / FileDocumentRepository.META_FILE)) continue // not here yet, nowhere else either
                remote.makeDirectory(dir)
                remote.makeDirectory("$dir/$OPS")
            }
            if (doc.value !in metaUploaded && fs.exists(localDir / FileDocumentRepository.META_FILE)) {
                if (remoteDir == null || remote.list(dir)?.get(FileDocumentRepository.META_FILE) == null) {
                    remote.write("$dir/${FileDocumentRepository.META_FILE}", withContext(io) { fs.read(localDir / FileDocumentRepository.META_FILE) { readByteArray() } })
                    uploads++
                }
                metaUploaded += doc.value
            }
            val ownLength = localValidLength(doc, ownLog)
            if (ownLength > (uploadedLog[doc.value] ?: 0L)) {
                val bytes = withContext(io) { fs.read(localDir / FileDocumentRepository.OPS_DIR / ownLog) { readByteArray(ownLength) } }
                remote.write("$dir/$OPS/$ownLog", bytes)
                uploads++
                uploadedLog[doc.value] = ownLength
            }

            // Down: new bytes of other devices' logs, and the meta if missing.
            if (remoteDir == null || documentEtags[doc.value] == remoteDir.etag) continue
            if (!fs.exists(localDir / FileDocumentRepository.META_FILE)) {
                val meta = remote.read("$dir/${FileDocumentRepository.META_FILE}")
                downloads++
                withContext(io) {
                    fs.createDirectories(localDir / FileDocumentRepository.OPS_DIR)
                    writeAtomically(localDir / FileDocumentRepository.META_FILE, meta)
                }
            }
            val ops = remote.list("$dir/$OPS")?.entries.orEmpty()
            val newOps = ArrayList<DocumentOp>()
            for (log in ops) {
                if (log.isDirectory || log.name == ownLog || !log.name.endsWith(FileDocumentRepository.LOG_SUFFIX)) continue
                val have = localValidLength(doc, log.name)
                if (log.size <= have) continue
                val bytes = remote.read("$dir/$OPS/${log.name}", have)
                downloads++
                newOps += appendToLocalLog(localDir / FileDocumentRepository.OPS_DIR / log.name, have, bytes)
            }
            if (newOps.isNotEmpty()) receivedOps[doc] = newOps
            documentEtags[doc.value] = remoteDir.etag
        }

        state = state.copy(
            rootEtag = root.etag,
            documentEtags = documentEtags,
            uploadedLog = uploadedLog,
            metaUploaded = metaUploaded,
        )
        withContext(io) { SyncState.save(fs, stateFile, state) }
        SyncReport(upToDate = false, receivedOps = receivedOps, libraryChanged = libraryChanged, uploads = uploads, downloads = downloads)
    }

    private suspend fun ensureFolders() {
        if (foldersReady) return
        remote.makeDirectory("")
        remote.makeDirectory(LIBRARY)
        remote.makeDirectory(DOCUMENTS)
        foldersReady = true
    }

    /** Length of the complete records of a local log (0 if missing). */
    private suspend fun localValidLength(doc: DocumentId, logName: String): Long = withContext(io) {
        val file = documentsRoot / doc.value / FileDocumentRepository.OPS_DIR / logName
        if (!fs.exists(file)) 0L else runCatching { fs.source(file).buffer().use { OpLogCodec.read(it).validLength } }.getOrDefault(0L)
    }

    /**
     * Appends bytes fetched from offset [have] of a remote log to the local copy, keeping only
     * complete records. Returns the ops that were added.
     */
    private suspend fun appendToLocalLog(file: Path, have: Long, bytes: ByteArray): List<DocumentOp> = withContext(io) {
        val before = if (have == 0L) Buffer() else Buffer().write(fs.read(file) { readByteString(have) })
        val known = if (have == 0L) 0 else OpLogCodec.read(before.copy()).ops.size
        val combined = before.write(bytes)
        val parsed = OpLogCodec.read(combined.copy())
        val valid = combined.readByteString(parsed.validLength)
        file.parent?.let(fs::createDirectories)
        writeAtomically(file, valid.toByteArray())
        parsed.ops.drop(known)
    }

    private fun writeAtomically(file: Path, bytes: ByteArray) {
        val tmp = file.parent!! / "${file.name}.tmp"
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, file)
    }

    companion object {
        const val LIBRARY = "library"
        const val DOCUMENTS = "documents"
        const val OPS = FileDocumentRepository.OPS_DIR
    }
}
