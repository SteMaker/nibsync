package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.sync.client.SyncConfigCodec
import com.stemaker.nibsync.sync.client.SyncConfig
import com.stemaker.nibsync.sync.client.SyncConfigStore
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists

/**
 * Keeps the sync login of the PC app (app password or refresh token) in the data folder,
 * readable only by the user (rw-------). It grants access for NibSync only and can be revoked in
 * the service's account settings.
 */
class DesktopSyncStore(private val file: java.nio.file.Path) : SyncConfigStore {
    override fun load(): SyncConfig? {
        if (!file.exists()) return null
        val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
        return SyncConfigCodec.fromMap(SyncConfigCodec.KEYS.associateWith { p.getProperty(it) })
    }

    override fun save(config: SyncConfig?) {
        if (config == null) {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(file.parent)
        val p = Properties().apply { SyncConfigCodec.toMap(config).forEach { (k, v) -> setProperty(k, v) } }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.deleteIfExists(tmp)
        Files.createFile(tmp)
        restrictToOwner(tmp)
        Files.newOutputStream(tmp).use { p.store(it, "NibSync Nextcloud login") }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun restrictToOwner(path: java.nio.file.Path) {
        // POSIX systems (Linux, macOS); on Windows the user profile folder is private already.
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}
