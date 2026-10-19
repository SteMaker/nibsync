package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.sync.client.AccountStore
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists

/**
 * Keeps the sync accounts of the PC app (app passwords, refresh tokens) in the data folder,
 * readable only by the user (rw-------). They grant access for NibSync only and can be revoked
 * in the service's account settings.
 */
class DesktopSyncStore(private val file: java.nio.file.Path) : AccountStore {
    override fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }

    override fun save(entries: Map<String, String>) {
        Files.createDirectories(file.parent)
        val p = Properties().apply { entries.forEach { (k, v) -> setProperty(k, v) } }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.deleteIfExists(tmp)
        Files.createFile(tmp)
        restrictToOwner(tmp)
        Files.newOutputStream(tmp).use { p.store(it, "NibSync sync accounts") }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun restrictToOwner(path: java.nio.file.Path) {
        // POSIX systems (Linux, macOS); on Windows the user profile folder is private already.
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}
