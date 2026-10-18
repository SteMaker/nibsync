package com.stemaker.nibsync.sync.client

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.sync.webdav.NextcloudAccount
import org.junit.Test

class SyncConfigCodecTest {
    @Test
    fun allServicesRoundTrip() {
        for (config in listOf(
            NextcloudConfig(NextcloudAccount("https://c.example.org", "stefan", "pw"), "Notes"),
            OneDriveConfig("user@outlook.example.com", "rt-1"),
            GoogleDriveConfig("user@gmail.example.com", "rt-2"),
        )) {
            assertThat(SyncConfigCodec.fromMap(SyncConfigCodec.toMap(config))).isEqualTo(config)
        }
    }

    @Test
    fun entriesFromBeforeTheServiceChoiceAreNextcloud() {
        val old = mapOf("server" to "https://c.example.org", "login" to "stefan", "appPassword" to "pw", "folder" to "NibSync")
        assertThat(SyncConfigCodec.fromMap(old)).isEqualTo(NextcloudConfig(NextcloudAccount("https://c.example.org", "stefan", "pw"), "NibSync"))
    }

    @Test
    fun incompleteOrUnknownEntriesAreNoConfig() {
        assertThat(SyncConfigCodec.fromMap(emptyMap())).isNull()
        assertThat(SyncConfigCodec.fromMap(mapOf("service" to "ONEDRIVE", "user" to "x"))).isNull()
        assertThat(SyncConfigCodec.fromMap(mapOf("service" to "DROPBOX", "user" to "x", "refreshToken" to "y"))).isNull()
    }

    @Test
    fun nextcloudStateKeyIsUnchanged() {
        // Existing devices keep their sync state file.
        val c = NextcloudConfig(NextcloudAccount("https://c.example.org", "stefan", "pw"), "NibSync")
        assertThat(c.stateKey).isEqualTo("https://c.example.org|stefan|NibSync")
    }
}
