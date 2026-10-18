package de.nibsync.sync.client

import de.nibsync.sync.webdav.NextcloudAccount

/**
 * A [SyncConfig] as key/value pairs, for whatever local storage the platform has (Android
 * preferences, a properties file on the PC). Entries written before there was a choice of
 * service have no "service" key and are Nextcloud.
 */
object SyncConfigCodec {
    fun toMap(config: SyncConfig): Map<String, String> = when (config) {
        is NextcloudConfig -> mapOf(
            SERVICE to config.service.name,
            SERVER to config.account.serverUrl,
            LOGIN to config.account.loginName,
            PASSWORD to config.account.appPassword,
            FOLDER to config.folder,
        )
        is OneDriveConfig -> mapOf(SERVICE to config.service.name, USER to config.user, REFRESH_TOKEN to config.refreshToken)
        is GoogleDriveConfig -> mapOf(SERVICE to config.service.name, USER to config.user, REFRESH_TOKEN to config.refreshToken)
    }

    fun fromMap(map: Map<String, String?>): SyncConfig? {
        val service = map[SERVICE]?.let { name -> SyncService.entries.firstOrNull { it.name == name } ?: return null } ?: SyncService.NEXTCLOUD
        return when (service) {
            SyncService.NEXTCLOUD -> NextcloudConfig(
                NextcloudAccount(map[SERVER] ?: return null, map[LOGIN] ?: return null, map[PASSWORD] ?: return null),
                map[FOLDER] ?: SyncConfig.DEFAULT_FOLDER,
            )
            SyncService.ONEDRIVE -> OneDriveConfig(map[USER] ?: return null, map[REFRESH_TOKEN] ?: return null)
            SyncService.GOOGLE_DRIVE -> GoogleDriveConfig(map[USER] ?: return null, map[REFRESH_TOKEN] ?: return null)
        }
    }

    /** All keys this codec may write (to clear them). */
    val KEYS = listOf("service", "server", "login", "appPassword", "folder", "user", "refreshToken")

    private const val SERVICE = "service"
    private const val SERVER = "server"
    private const val LOGIN = "login"
    private const val PASSWORD = "appPassword"
    private const val FOLDER = "folder"
    private const val USER = "user"
    private const val REFRESH_TOKEN = "refreshToken"
}
