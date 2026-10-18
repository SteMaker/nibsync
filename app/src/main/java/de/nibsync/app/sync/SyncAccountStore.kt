package de.nibsync.app.sync

import android.content.Context
import androidx.core.content.edit
import de.nibsync.sync.webdav.NextcloudAccount

/** Where and as whom to sync. */
data class SyncConfig(val account: NextcloudAccount, val folder: String)

/**
 * Keeps the Nextcloud login. The app password lives in app-private storage, which other apps
 * cannot read; it only grants access for this app and can be revoked in Nextcloud
 * (Settings > Security > Devices & sessions).
 */
class SyncAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync-account", Context.MODE_PRIVATE)

    fun load(): SyncConfig? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val login = prefs.getString(KEY_LOGIN, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return SyncConfig(NextcloudAccount(server, login, password), prefs.getString(KEY_FOLDER, null) ?: DEFAULT_FOLDER)
    }

    fun save(config: SyncConfig?) = prefs.edit {
        if (config == null) {
            clear()
        } else {
            putString(KEY_SERVER, config.account.serverUrl)
            putString(KEY_LOGIN, config.account.loginName)
            putString(KEY_PASSWORD, config.account.appPassword)
            putString(KEY_FOLDER, config.folder)
        }
    }

    companion object {
        const val DEFAULT_FOLDER = "NibSync"
        private const val KEY_SERVER = "server"
        private const val KEY_LOGIN = "login"
        private const val KEY_PASSWORD = "appPassword"
        private const val KEY_FOLDER = "folder"
    }
}
