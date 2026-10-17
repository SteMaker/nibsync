package de.nibsync.storage

import de.nibsync.document.DocumentState
import de.nibsync.document.OpJournal
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta

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
}
