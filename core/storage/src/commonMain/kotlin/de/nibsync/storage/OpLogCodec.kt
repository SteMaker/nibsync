package de.nibsync.storage

import de.nibsync.document.DocumentOp
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * Binary op log: a header followed by length-prefixed records.
 *
 * ```
 * header : "NSOL" (4 bytes) | version (1 byte)
 * record : length (4 bytes, big endian) | CBOR-encoded OpRecord
 * ```
 *
 * Logs are append-only and every device writes only its own log, so they can be synced as
 * plain files. A record cut off at the end (crash during write, partial sync) is ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
object OpLogCodec {
    val MAGIC: ByteString = "NSOL".encodeUtf8()
    const val VERSION: Int = 1
    const val HEADER_SIZE: Long = 5
    private const val MAX_RECORD_SIZE = 64 * 1024 * 1024

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun header(): ByteString = Buffer().write(MAGIC).writeByte(VERSION).readByteString()

    fun encodeRecord(op: DocumentOp): ByteString {
        val payload = cbor.encodeToByteArray(OpRecord.serializer(), op.toRecord())
        return Buffer().writeInt(payload.size).write(payload).readByteString()
    }

    class ReadResult(
        val ops: List<DocumentOp>,
        /** Number of bytes that form a valid header plus complete records. */
        val validLength: Long,
        /** True if there were trailing bytes that do not form a complete record. */
        val truncatedTail: Boolean,
    )

    /** Reads a complete log. Throws [CorruptDataException] if a complete record cannot be decoded. */
    fun read(source: BufferedSource): ReadResult {
        if (source.exhausted()) return ReadResult(emptyList(), 0, truncatedTail = false)
        if (!source.request(HEADER_SIZE)) return ReadResult(emptyList(), 0, truncatedTail = true)
        if (source.readByteString(4) != MAGIC) throw CorruptDataException("Not an op log")
        val version = source.readByte().toInt()
        if (version > VERSION) throw UnsupportedFormatException(version)

        val ops = mutableListOf<DocumentOp>()
        var valid = HEADER_SIZE
        while (!source.exhausted()) {
            if (!source.request(4)) return ReadResult(ops, valid, truncatedTail = true)
            val size = source.readInt()
            if (size < 0 || size > MAX_RECORD_SIZE) throw CorruptDataException("Invalid record size $size")
            if (!source.request(size.toLong())) return ReadResult(ops, valid, truncatedTail = true)
            val bytes = source.readByteArray(size.toLong())
            val record = try {
                cbor.decodeFromByteArray(OpRecord.serializer(), bytes)
            } catch (e: SerializationException) {
                throw CorruptDataException("Undecodable record at offset $valid", e)
            } catch (e: IllegalArgumentException) {
                throw CorruptDataException("Invalid record at offset $valid", e)
            }
            try {
                record.toDomain()?.let(ops::add)
            } catch (e: IllegalArgumentException) {
                throw CorruptDataException("Invalid record content at offset $valid", e)
            }
            valid += 4 + size
        }
        return ReadResult(ops, valid, truncatedTail = false)
    }
}
