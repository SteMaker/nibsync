package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.sync.client.AccountId
import com.stemaker.nibsync.sync.client.AccountsCodec
import com.stemaker.nibsync.sync.client.NextcloudConfig
import com.stemaker.nibsync.sync.client.OneDriveConfig
import com.stemaker.nibsync.sync.client.SyncAccount
import com.stemaker.nibsync.sync.client.SyncConfigCodec
import com.stemaker.nibsync.sync.webdav.NextcloudAccount
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopSyncStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val nextcloud = NextcloudConfig(NextcloudAccount("https://cloud.example.org", "stefan", "app-pass-123"), "NibSync")

    @Test
    fun accountsRoundTripAndOnlyTheUserCanReadThem() {
        val file = tmp.root.toPath().resolve("data/sync-account.properties")
        val store = DesktopSyncStore(file)
        assertThat(store.load()).isEmpty()
        val accounts = listOf(SyncAccount(AccountId.new(), "Privat", nextcloud), SyncAccount(AccountId.new(), "Arbeit", OneDriveConfig("me@work.com", "rt")))
        store.save(AccountsCodec.toMap(accounts))
        assertThat(AccountsCodec.fromMap(store.load())).isEqualTo(accounts)
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
    }

    @Test
    fun theLoginOfOlderVersionsIsRead() {
        val file = tmp.root.toPath().resolve("sync-account.properties")
        val store = DesktopSyncStore(file)
        store.save(SyncConfigCodec.toMap(nextcloud))
        assertThat(AccountsCodec.fromMap(store.load())).containsExactly(SyncAccount(AccountId.LEGACY, "Nextcloud", nextcloud))
    }
}
