package de.nibsync.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * What this device knows about the server since the last sync. Kept locally (never synced);
 * losing it only makes the next sync do more work.
 */
@Serializable
data class SyncState(
    /** Etag of the sync folder seen at the end of the last complete sync. */
    @SerialName("rootEtag") val rootEtag: String? = null,
    /** Etag of each `library/<node>.json` already merged. */
    @SerialName("libraryEtags") val libraryEtags: Map<String, String> = emptyMap(),
    /** Hash of each node's content as last uploaded or downloaded. */
    @SerialName("nodeHashes") val nodeHashes: Map<String, Int> = emptyMap(),
    /** Etag of each `documents/<id>` folder whose content was fully fetched. */
    @SerialName("documentEtags") val documentEtags: Map<String, String> = emptyMap(),
    /** Bytes of this device's op log of each document that are on the server. */
    @SerialName("uploadedLog") val uploadedLog: Map<String, Long> = emptyMap(),
    /** Documents whose meta.json is on the server. */
    @SerialName("metaUploaded") val metaUploaded: Set<String> = emptySet(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(fs: FileSystem, file: Path): SyncState =
            if (!fs.exists(file)) SyncState() else runCatching { json.decodeFromString(serializer(), fs.read(file) { readUtf8() }) }.getOrElse { SyncState() }

        fun save(fs: FileSystem, file: Path, state: SyncState) {
            file.parent?.let(fs::createDirectories)
            val tmp = file.parent!! / "${file.name}.tmp"
            fs.write(tmp) { writeUtf8(json.encodeToString(serializer(), state)) }
            fs.atomicMove(tmp, file)
        }
    }
}
