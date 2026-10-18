package com.stemaker.nibsync.app

import android.app.Application
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.stemaker.nibsync.app.sync.GoogleDeviceTokens
import com.stemaker.nibsync.app.sync.SyncAccountStore
import com.stemaker.nibsync.sync.client.CloudClients
import com.stemaker.nibsync.sync.client.SyncController
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class NibSyncApp : Application() {
    lateinit var container: AppContainer
        private set

    lateinit var sync: SyncController
        private set

    /** OneDrive client built in from cloud.properties (see docs/cloud-setup.md). */
    private val cloudClients by lazy {
        // Google Drive needs no id on Android, only Google Play services.
        val play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        CloudClients(oneDriveClientId = BuildConfig.ONEDRIVE_CLIENT_ID, googleOnDevice = play)
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(FileSystem.SYSTEM, filesDir.toOkioPath())
        sync = SyncController(
            container,
            SyncAccountStore(this),
            container.appScope,
            clients = cloudClients,
            googleTokens = GoogleDeviceTokens(this),
        )
    }
}
