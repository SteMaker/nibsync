package de.nibsync.app

import android.app.Application
import de.nibsync.app.sync.SyncAccountStore
import de.nibsync.app.sync.SyncController
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class NibSyncApp : Application() {
    lateinit var container: AppContainer
        private set

    lateinit var sync: SyncController
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(FileSystem.SYSTEM, filesDir.toOkioPath())
        sync = SyncController(container, SyncAccountStore(this), container.appScope)
    }
}
