package com.stemaker.nibsync.app.sync

import android.content.Context
import androidx.core.content.edit
import com.stemaker.nibsync.sync.client.AccountStore

/**
 * Keeps the sync accounts (Nextcloud app passwords, OneDrive/Google refresh tokens) in
 * app-private storage, which other apps cannot read. These grant access for NibSync only and can
 * be revoked in the service's account settings. Holds the single login of older versions too.
 */
class SyncAccountStore(context: Context) : AccountStore {
    private val prefs = context.getSharedPreferences("sync-account", Context.MODE_PRIVATE)

    override fun load(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun save(entries: Map<String, String>) = prefs.edit(commit = true) {
        clear()
        entries.forEach { (k, v) -> putString(k, v) }
    }
}
