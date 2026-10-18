package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        assert(onAllNodesWithText("noch nicht eingerichtet", substring = true).fetchSemanticsNodes().size == 2)
        onNodeWithTag(SyncTags.service(SyncService.NEXTCLOUD)).performClick()
        onNodeWithTag(SyncTags.SERVER_FIELD).assertExists()
        runBlocking { container.closeAll() }
        scope.cancel()
    }

    @Test
    fun shortcutsOfTheDocumentPauseWhileSettingsAreOpen() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val container = DesktopContainer(root, appScope = scope)
        val sync = SyncController(container, object : SyncConfigStore {
            override fun load(): SyncConfig? = null
            override fun save(config: SyncConfig?) = Unit
        }, scope)
        val keys = KeyRouter()
        setContent { App(container, Settings(container.fs, root / "settings.properties"), Strings(german = true), keys = keys, sync = sync) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.SETTINGS).fetchSemanticsNodes().isNotEmpty() }
        assert(!keys.blocked)
        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        waitForIdle()
        // Ctrl+V, Delete, ... in a dialog field must reach the field, not the open document.
        assert(keys.blocked)
        onNodeWithTag(SettingsTags.CLOSE).performClick()
        waitForIdle()
        assert(!keys.blocked)
        runBlocking { container.closeAll() }
        scope.cancel()
    }
}
