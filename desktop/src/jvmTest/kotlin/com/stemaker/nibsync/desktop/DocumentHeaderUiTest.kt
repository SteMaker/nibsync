package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.text.HeaderTags
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class DocumentHeaderUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun titleRenamesTheDocumentAndSettingsHideTitleAndDate() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(HeaderTags.TITLE).fetchSemanticsNodes().isNotEmpty() }
        val doc = runBlocking { container.library.tree.value.documents.single() }

        // Title, creation time and the path at the bottom.
        onNodeWithTag(HeaderTags.TITLE).assertTextContains("Notizen")
        onNodeWithTag(HeaderTags.DATE).assertExists()
        onNodeWithTag(HeaderTags.PATH).assertTextContains("Notizen › Notizen")

        // Typing a new title renames the document in the library.
        onNodeWithTag(HeaderTags.TITLE).performClick()
        onNodeWithTag(HeaderTags.TITLE).performTextClearance()
        onNodeWithTag(HeaderTags.TITLE).performTextInput("Besprechung")
        onNodeWithTag(HeaderTags.TITLE).performImeAction()
        waitUntil(timeoutMillis = 5000) { container.library.tree.value[doc.id]?.title == "Besprechung" }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Besprechung")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(HeaderTags.PATH).assertTextContains("Notizen › Besprechung")

        // The settings switch title and date separately.
        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        onNodeWithTag(SettingsTags.SHOW_CREATED).performClick()
        onNodeWithTag(SettingsTags.CLOSE).performClick()
        waitForIdle()
        onNodeWithTag(HeaderTags.DATE).assertDoesNotExist()
        onNodeWithTag(HeaderTags.TITLE).assertExists()
        assertThat(Settings(container.fs, root / "settings.properties").showCreated).isFalse()
        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        onNodeWithTag(SettingsTags.SHOW_TITLE).performClick()
        onNodeWithTag(SettingsTags.CLOSE).performClick()
        waitForIdle()
        onNodeWithTag(HeaderTags.TITLE).assertDoesNotExist()
        // The path stays.
        onNodeWithTag(HeaderTags.PATH).assertExists()
        runBlocking { container.closeAll() }
    }
}
