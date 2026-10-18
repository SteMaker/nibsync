package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.stemaker.nibsync.sync.client.SyncConfig
import com.stemaker.nibsync.sync.client.SyncConfigStore
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.client.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun libraryShowsTheStatusTheSettingsOfferTheServices() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val container = DesktopContainer(root, appScope = scope)
        val sync = SyncController(container, object : SyncConfigStore {
            override fun load(): SyncConfig? = null
            override fun save(config: SyncConfig?) = Unit
        }, scope)
        setContent { App(container, Settings(container.fs, root / "settings.properties"), Strings(german = true), sync = sync) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(SyncTags.STATUS_LINE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Nicht verbunden. Notizen bleiben auf diesem PC.").assertExists()
        onNodeWithTag(SyncTags.SYNC_NOW).assertDoesNotExist()

        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        for (service in SyncService.entries) onNodeWithTag(SyncTags.service(service)).assertExists()
        // Without client ids OneDrive and Google Drive are not available yet.
        assert(onAllNodesWithText("Keine Client-ID", substring = true).fetchSemanticsNodes().size == 2)
        onNodeWithTag(SyncTags.service(SyncService.NEXTCLOUD)).performClick()
        onNodeWithTag(SyncTags.SERVER_FIELD).assertExists()
        runBlocking { container.closeAll() }
        scope.cancel()
    }

    @Test
    fun enteredClientIdsMakeTheServiceAvailableAndAreKept() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val container = DesktopContainer(root, appScope = scope)
        val sync = SyncController(container, object : SyncConfigStore {
            override fun load(): SyncConfig? = null
            override fun save(config: SyncConfig?) = Unit
        }, scope)
        val store = DesktopClientsStore(tmp.root.toPath().resolve("cloud-clients.properties"))
        setContent { App(container, Settings(container.fs, root / "settings.properties"), Strings(german = true), sync = sync, clientsStore = store) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.SETTINGS).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        assert(!sync.available(SyncService.ONEDRIVE))
        onNodeWithTag(ClientTags.ONEDRIVE_ID).performTextInput("my-entra-client")
        onNodeWithTag(ClientTags.SAVE).performClick()
        waitUntil(timeoutMillis = 5000) { sync.available(SyncService.ONEDRIVE) }
        assert(onAllNodesWithText("Keine Client-ID", substring = true).fetchSemanticsNodes().size == 1)
        assert(store.load().oneDriveClientId == "my-entra-client")
        assert(sync.clients.value.oneDriveClientId == "my-entra-client")
        runBlocking { container.closeAll() }
        scope.cancel()
    }
}
