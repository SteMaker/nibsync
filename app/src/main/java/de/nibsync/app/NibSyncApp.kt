package de.nibsync.app

import android.app.Application
import de.nibsync.app.sync.GoogleDeviceTokens
import de.nibsync.app.sync.SyncAccountStore
import de.nibsync.sync.client.CloudClients
import de.nibsync.sync.client.SyncController
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class NibSyncApp : Application() {
    lateinit var container: AppContainer
        private set

    lateinit var sync: SyncController
        private set

    /** OneDrive / Google Drive clients of this build (from cloud.properties, see docs/cloud-setup.md). */
    val cloudClients = CloudClients(oneDriveClientId = BuildConfig.ONEDRIVE_CLIENT_ID, googleOnDevice = BuildConfig.GOOGLE_ON_DEVICE)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(FileSystem.SYSTEM, filesDir.toOkioPath())
        sync = SyncController(container, SyncAccountStore(this), container.appScope, clients = cloudClients, googleTokens = GoogleDeviceTokens(this))
    }
}
