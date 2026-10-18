package de.nibsync.model

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Globally unique, stable identifier of a page element: a stroke or a [TextBox] (the name is
 * historical). All elements share this id space, so removal ops and tombstones cover every
 * kind. Stays the same across devices and syncs.
 */
@JvmInline
value class StrokeId(val value: String) {
    init {
        require(value.isNotBlank()) { "StrokeId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifier of a note document. Used as directory name in storage, so restricted to [A-Za-z0-9_-]. */
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Invalid DocumentId '$value'"
        }
    }

    override fun toString(): String = value
}

/** Identifier of an app installation. Every device writes its own op log, keyed by this id. */
@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Invalid DeviceId '$value'"
        }
    }

    override fun toString(): String = value
}

/** Creates random RFC 4122 version 4 UUID strings without relying on platform APIs. */
class IdGenerator(private val random: Random = Random.Default) {

    fun newUuid(): String {
        val bytes = random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    fun newStrokeId(): StrokeId = StrokeId(newUuid())

    fun newDocumentId(): DocumentId = DocumentId(newUuid())

    fun newDeviceId(): DeviceId = DeviceId(newUuid())
}
