package de.nibsync.app.sync

import de.nibsync.sync.client.SyncConfigCodec
import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncConfigStore
import android.content.Context
import androidx.core.content.edit
import de.nibsync.sync.webdav.NextcloudAccount

/**
 * Keeps the sync login (Nextcloud app password or OneDrive/Google refresh token) in app-private
 * storage, which other apps cannot read. These grant access for NibSync only and can be revoked
 * in the service's account settings.
 */
class SyncAccountStore(context: Context) : SyncConfigStore {
    private val prefs = context.getSharedPreferences("sync-account", Context.MODE_PRIVATE)

    override fun load(): SyncConfig? = SyncConfigCodec.fromMap(SyncConfigCodec.KEYS.associateWith { prefs.getString(it, null) })

    override fun save(config: SyncConfig?) = prefs.edit {
        clear()
        if (config != null) SyncConfigCodec.toMap(config).forEach { (k, v) -> putString(k, v) }
    }

    companion object {
        const val DEFAULT_FOLDER = SyncConfig.DEFAULT_FOLDER
    }
}
