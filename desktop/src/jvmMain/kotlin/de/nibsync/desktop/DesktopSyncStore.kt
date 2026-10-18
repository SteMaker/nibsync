package de.nibsync.desktop

import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncConfigStore
import de.nibsync.sync.webdav.NextcloudAccount
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists

/**
 * Keeps the Nextcloud login of the PC app in the data folder, readable only by the user
 * (rw-------). The app password only grants access for NibSync and can be revoked in Nextcloud
 * (Settings > Security > Devices & sessions).
 */
class DesktopSyncStore(private val file: java.nio.file.Path) : SyncConfigStore {
    override fun load(): SyncConfig? {
        if (!file.exists()) return null
        val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
        val server = p.getProperty(SERVER) ?: return null
        val login = p.getProperty(LOGIN) ?: return null
        val password = p.getProperty(PASSWORD) ?: return null
        return SyncConfig(NextcloudAccount(server, login, password), p.getProperty(FOLDER) ?: SyncConfig.DEFAULT_FOLDER)
    }

    override fun save(config: SyncConfig?) {
        if (config == null) {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(file.parent)
        val p = Properties().apply {
            setProperty(SERVER, config.account.serverUrl)
            setProperty(LOGIN, config.account.loginName)
            setProperty(PASSWORD, config.account.appPassword)
            setProperty(FOLDER, config.folder)
        }
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

    private companion object {
        const val SERVER = "server"
        const val LOGIN = "login"
        const val PASSWORD = "appPassword"
        const val FOLDER = "folder"
    }
}
