package de.nibsync.storage

import de.nibsync.document.DocumentOp
import de.nibsync.document.DocumentState
import de.nibsync.document.OpJournal
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.ByteString.Companion.toByteString
import okio.Path
import okio.buffer
import okio.use

/**
 * Stores every document as a directory, laid out so it can later be synced file by file:
 *
 * ```
 * <root>/<documentId>/meta.json
 * <root>/<documentId>/ops/<deviceId>.oplog   (one append-only log per device)
 * <root>/<documentId>/assets/<sha256>         (pictures, PDFs; immutable)
 * ```
 */
class FileDocumentRepository(
    private val fs: FileSystem,
    private val root: Path,
    private val io: CoroutineDispatcher,
) : DocumentRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serialises writes so concurrent journals of the same file cannot interleave records. */
    private val writeLock = Mutex()

    private fun docDir(id: DocumentId) = root / id.value
    private fun metaFile(id: DocumentId) = docDir(id) / META_FILE
    private fun opsDir(id: DocumentId) = docDir(id) / OPS_DIR
    private fun logFile(id: DocumentId, device: DeviceId) = opsDir(id) / "${device.value}$LOG_SUFFIX"

    override suspend fun list(): List<DocumentMeta> = withContext(io) {
        if (!fs.exists(root)) return@withContext emptyList()
        fs.list(root)
            .filter { fs.exists(it / META_FILE) }
            .mapNotNull { dir -> runCatching { readMeta(dir / META_FILE) }.getOrNull() }
            .sortedBy { it.createdAtEpochMs }
    }

    override suspend fun exists(id: DocumentId): Boolean = withContext(io) { fs.exists(metaFile(id)) }

    override suspend fun create(meta: DocumentMeta) = withContext(io) {
        fs.createDirectories(opsDir(meta.id))
        writeAtomically(metaFile(meta.id), json.encodeToString(MetaRecord.serializer(), meta.toRecord()))
    }

    override suspend fun load(id: DocumentId): DocumentState = withContext(io) {
        val metaFile = metaFile(id)
        if (!fs.exists(metaFile)) throw DocumentNotFoundException(id.value)
        val meta = readMeta(metaFile)
        val opsDir = opsDir(id)
        val ops = if (fs.exists(opsDir)) {
            fs.list(opsDir)
                .filter { it.name.endsWith(LOG_SUFFIX) }
                .sortedBy { it.name }
                .flatMap { readLog(it).ops }
        } else {
            emptyList()
        }
        DocumentState.empty(meta).applyAll(ops)
    }

    override fun journal(id: DocumentId, device: DeviceId): OpJournal {
        val file = logFile(id, device)
        var prepared = false
        return OpJournal { op ->
            writeLock.withLock {
                withContext(io) {
                    if (!prepared) {
                        prepareLog(file)
                        prepared = true
                    }
                    append(file, op)
                }
            }
        }
    }

    override suspend fun delete(id: DocumentId) = withContext(io) { fs.deleteRecursively(docDir(id)) }

    override suspend fun putAsset(id: DocumentId, bytes: ByteArray): String = withContext(io) {
        val sha = bytes.toByteString().sha256().hex()
        val file = assetPath(id, sha)
        if (!fs.exists(file)) {
            fs.createDirectories(file.parent!!)
            val tmp = file.parent!! / "${file.name}.tmp"
            fs.write(tmp) { write(bytes) }
            fs.atomicMove(tmp, file)
        }
        sha
    }

    override suspend fun hasAsset(id: DocumentId, sha256: String): Boolean = withContext(io) { fs.exists(assetPath(id, sha256)) }

    override fun assetPath(id: DocumentId, sha256: String): Path = docDir(id) / ASSETS_DIR / sha256

    private fun readMeta(file: Path): DocumentMeta {
        val text = fs.read(file) { readUtf8() }
        val record = try {
            json.decodeFromString(MetaRecord.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptDataException("Unreadable $file", e)
        }
        return record.toDomain()
    }

    private fun readLog(file: Path): OpLogCodec.ReadResult =
        fs.source(file).buffer().use { OpLogCodec.read(it) }

    /**
     * Creates the log with its header, or repairs an existing one whose last record was cut off,
     * so new records are appended after valid data only.
     */
    private fun prepareLog(file: Path) {
        fs.createDirectories(file.parent!!)
        if (!fs.exists(file) || fs.metadata(file).size == 0L) {
            fs.write(file) { write(OpLogCodec.header()) }
            return
        }
        val result = readLog(file)
        if (result.truncatedTail) {
            val valid = fs.read(file) { readByteString(result.validLength) }
            val tmp = file.parent!! / "${file.name}.tmp"
            fs.write(tmp) {
                if (valid.size == 0) write(OpLogCodec.header()) else write(valid)
            }
            fs.atomicMove(tmp, file)
        }
    }

    private fun append(file: Path, op: DocumentOp) {
        fs.appendingSink(file).buffer().use { it.write(OpLogCodec.encodeRecord(op)) }
    }

    private fun writeAtomically(file: Path, content: String) {
        val tmp = file.parent!! / "${file.name}.tmp"
        fs.write(tmp) { writeUtf8(content) }
        fs.atomicMove(tmp, file)
    }

    companion object {
        const val META_FILE = "meta.json"
        const val OPS_DIR = "ops"
        const val LOG_SUFFIX = ".oplog"
        const val ASSETS_DIR = "assets"
    }
}
