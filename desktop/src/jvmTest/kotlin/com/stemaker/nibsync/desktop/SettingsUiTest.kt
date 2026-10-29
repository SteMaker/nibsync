package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.stemaker.nibsync.sync.client.AccountStore
import com.stemaker.nibsync.sync.client.NextcloudConfig
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.webdav.NextcloudAccount
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

    private class MemoryStore(var entries: Map<String, String> = emptyMap()) : AccountStore {
        override fun load() = entries
        override fun save(entries: Map<String, String>) {
            this.entries = entries
        }
    }

    @Test
    fun newBooksChooseTheirAccountAndLocalBooksCanJoinOneLater() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val container = DesktopContainer(root, appScope = scope)
        // Nobody listens there: the syncs fail, which does not matter for the assignment.
        val sync = SyncController(container, MemoryStore(), scope)
        val work = sync.addAccount("Arbeit", NextcloudConfig(NextcloudAccount("http://127.0.0.1:9", "x", "y")))!!
        setContent { App(container, Settings(container.fs, root / "settings.properties"), Strings(german = true), sync = sync) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Notizen")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Arbeit · Nextcloud").fetchSemanticsNodes().isNotEmpty().let(::assert)

        onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
        onNodeWithTag(NewBookTags.NAME).performTextInput("Projekte")
        onNodeWithTag(NewBookTags.choice("Arbeit")).performClick()
        onNodeWithTag(NewBookTags.CREATE).performClick()
        waitUntil(timeoutMillis = 5000) { container.library.tree.value.books.any { it.title == "Projekte" } }
        val projects = container.library.tree.value.books.first { it.title == "Projekte" }
        waitUntil(timeoutMillis = 5000) { sync.books.accountOf(projects.id) == work.id }

        // The first book of a new installation is only on this PC, until it joins an account.
        val notes = container.library.tree.value.books.first { it.title == "Notizen" }
        assert(sync.books.accountOf(notes.id) == null)
        onNodeWithTag(LibraryTags.bookMenu("Notizen")).performClick()
        onNodeWithTag(LibraryTags.assign("Arbeit")).performClick()
        waitUntil(timeoutMillis = 5000) { sync.books.accountOf(notes.id) == work.id }
        runBlocking { container.closeAll() }
        scope.cancel()
    }

    @Test
    fun libraryShowsTheStatusTheSettingsOfferTheServices() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val container = DesktopContainer(root, appScope = scope)
        val sync = SyncController(container, MemoryStore(), scope)
        setContent { App(container, Settings(container.fs, root / "settings.properties"), Strings(german = true), sync = sync) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(SyncTags.STATUS_LINE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Kein Konto. Alle Bücher bleiben auf diesem PC.").assertExists()

        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        onNodeWithTag(SyncTags.ADD_ACCOUNT).performClick()
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
        val sync = SyncController(container, MemoryStore(), scope)
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

    @Test
    fun darkModeAndColourSchemeAreChosenAndKept() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent {
            App(container, settings, Strings(german = true))
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.SETTINGS).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        onNodeWithTag(com.stemaker.nibsync.theme.AppearanceTags.mode(com.stemaker.nibsync.theme.ThemeMode.DARK)).performClick()
        onNodeWithTag(com.stemaker.nibsync.theme.AppearanceTags.palette(com.stemaker.nibsync.theme.NibSyncPalette.INK)).performClick()
        waitForIdle()
        // Stored for the next start.
        val again = Settings(container.fs, root / "settings.properties")
        assert(again.themeMode == com.stemaker.nibsync.theme.ThemeMode.DARK)
        assert(again.palette == com.stemaker.nibsync.theme.NibSyncPalette.INK)
        onNodeWithTag(SettingsTags.CLOSE).performClick()
        runBlocking { container.closeAll() }
    }
}
