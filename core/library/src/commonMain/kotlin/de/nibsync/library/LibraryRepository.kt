package de.nibsync.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** Storage of the library tree. */
interface LibraryRepository {
    suspend fun loadAll(): List<LibraryNode>

    /** Creates or replaces one node. */
    suspend fun save(node: LibraryNode)
}

/**
 * Stores every node in its own file `<root>/<nodeId>.json`. Different devices creating or
 * changing different nodes therefore never touch the same file, which keeps file-based sync
 * simple; two edits of the same node resolve as "last write wins".
 */
class FileLibraryRepository(
    private val fs: FileSystem,
    private val root: Path,
    private val io: CoroutineDispatcher,
) : LibraryRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun loadAll(): List<LibraryNode> = withContext(io) {
        if (!fs.exists(root)) return@withContext emptyList()
        fs.list(root)
            .filter { it.name.endsWith(SUFFIX) }
            .mapNotNull { file ->
                // A damaged or foreign file must not break the whole library.
                runCatching { json.decodeFromString(NodeRecord.serializer(), fs.read(file) { readUtf8() }).toDomain() }
                    .getOrNull()
            }
    }

    override suspend fun save(node: LibraryNode) = withContext(io) {
        fs.createDirectories(root)
        val file = root / "${node.id.value}$SUFFIX"
        val tmp = root / "${node.id.value}$SUFFIX.tmp"
        fs.write(tmp) { writeUtf8(json.encodeToString(NodeRecord.serializer(), node.toRecord())) }
        fs.atomicMove(tmp, file)
    }

    companion object {
        const val SUFFIX = ".json"
    }
}

// On-disk format of a node. Never rename serial names; add new fields with defaults.
@Serializable
private data class NodeRecord(
    @SerialName("formatVersion") val formatVersion: Int = 1,
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("parent") val parent: String? = null,
    @SerialName("title") val title: String,
    @SerialName("createdAt") val createdAtEpochMs: Long,
    @SerialName("deleted") val deleted: Boolean = false,
)

private fun LibraryNode.toRecord() = NodeRecord(
    id = id.value,
    kind = kind.name,
    parent = parent?.value,
    title = title,
    createdAtEpochMs = createdAtEpochMs,
    deleted = deleted,
)

private fun NodeRecord.toDomain(): LibraryNode {
    val kind = NodeKind.entries.firstOrNull { it.name == kind } ?: throw SerializationException("Unknown kind $kind")
    return LibraryNode(NodeId(id), kind, parent?.let(::NodeId), title, createdAtEpochMs, deleted)
}
