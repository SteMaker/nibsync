package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.sync.client.CloudClients
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists

/**
 * OAuth clients the user entered in the settings (own app registrations for OneDrive and
 * Google Drive), kept in the data folder of this PC and readable only by the user.
 */
class DesktopClientsStore(private val file: Path) {
    fun load(): CloudClients {
        if (!file.exists()) return CloudClients()
        val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
        return CloudClients.fromMap(CloudClients.KEYS.associateWith { p.getProperty(it) })
    }

    fun save(clients: CloudClients) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.deleteIfExists(tmp)
        Files.createFile(tmp)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        val p = Properties().apply { clients.toMap().filterValues { it.isNotBlank() }.forEach { (k, v) -> setProperty(k, v.trim()) } }
        Files.newOutputStream(tmp).use { p.store(it, "NibSync OAuth clients") }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
