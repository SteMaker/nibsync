package de.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import de.nibsync.sync.client.NextcloudConfig
import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.webdav.NextcloudAccount
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopSyncStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun loginRoundTripsAndOnlyTheUserCanReadIt() {
        val file = tmp.root.toPath().resolve("data/sync-account.properties")
        val store = DesktopSyncStore(file)
        assertThat(store.load()).isNull()
        val config = NextcloudConfig(NextcloudAccount("https://cloud.example.org", "stefan", "app-pass-123"), "NibSync")
        store.save(config)
        assertThat(store.load()).isEqualTo(config)
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
        store.save(null)
        assertThat(Files.exists(file)).isFalse()
        assertThat(store.load()).isNull()
    }
}
