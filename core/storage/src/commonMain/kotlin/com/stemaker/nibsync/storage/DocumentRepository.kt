package com.stemaker.nibsync.storage

import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.OpJournal
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import okio.Path

/** Access to stored note documents. */
interface DocumentRepository {
    suspend fun list(): List<DocumentMeta>

    suspend fun exists(id: DocumentId): Boolean

    suspend fun create(meta: DocumentMeta)

    /** Loads a document by merging the op logs of all devices. */
    suspend fun load(id: DocumentId): DocumentState

    /** Returns the journal the given device appends its ops to. */
    fun journal(id: DocumentId, device: DeviceId): OpJournal

    suspend fun delete(id: DocumentId)

    /**
     * Stores [bytes] as a file of document [id], named by its SHA-256 (nothing happens if it is
     * there already). Returns the SHA-256 in lower-case hex.
     */
    suspend fun putAsset(id: DocumentId, bytes: ByteArray): String

    suspend fun hasAsset(id: DocumentId, sha256: String): Boolean

    /** Where the asset is (or would be) stored. */
    fun assetPath(id: DocumentId, sha256: String): Path
}
