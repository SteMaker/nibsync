package com.stemaker.nibsync.app.sync

import android.content.Context
import androidx.core.content.edit
import com.stemaker.nibsync.sync.client.CloudClients

/** OAuth clients the user entered in the settings (own app registration), on this device only. */
class CloudClientsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud-clients", Context.MODE_PRIVATE)

    fun load(): CloudClients = CloudClients.fromMap(CloudClients.KEYS.associateWith { prefs.getString(it, null) })

    fun save(clients: CloudClients) = prefs.edit {
        clear()
        clients.toMap().filterValues { it.isNotBlank() }.forEach { (k, v) -> putString(k, v.trim()) }
    }
}
