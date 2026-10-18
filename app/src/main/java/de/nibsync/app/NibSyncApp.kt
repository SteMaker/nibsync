package de.nibsync.app

import android.app.Application
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import de.nibsync.app.sync.CloudClientsStore
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

    /** OneDrive client built in from cloud.properties (see docs/cloud-setup.md); the settings can override it. */
    val builtInClients by lazy {
        // Google Drive needs no id on Android, only Google Play services.
        val play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        CloudClients(oneDriveClientId = BuildConfig.ONEDRIVE_CLIENT_ID, googleOnDevice = play)
    }

    val cloudClientsStore by lazy { CloudClientsStore(this) }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(FileSystem.SYSTEM, filesDir.toOkioPath())
        sync = SyncController(
            container,
            SyncAccountStore(this),
            container.appScope,
            clients = builtInClients.overriddenBy(cloudClientsStore.load()),
            googleTokens = GoogleDeviceTokens(this),
        )
    }
}
