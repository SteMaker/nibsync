package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.geometry.Offset
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
        onNodeWithTag(HeaderTags.TITLE).assertIsNotFocused()

        // A click on the page also ends typing (no cursor stays behind) and renames.
        onNodeWithTag(HeaderTags.TITLE).performClick()
        onNodeWithTag(HeaderTags.TITLE).assertIsFocused()
        onNodeWithTag(HeaderTags.TITLE).performTextClearance()
        onNodeWithTag(HeaderTags.TITLE).performTextInput("Planung")
        onNodeWithTag("page").performMouseInput { click(Offset(400f, 500f)) }
        waitUntil(timeoutMillis = 5000) { container.library.tree.value[doc.id]?.title == "Planung" }
        onNodeWithTag(HeaderTags.TITLE).assertIsNotFocused()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Planung")).fetchSemanticsNodes().isNotEmpty() }

        // A rename in the library shows on the page.
        runBlocking { container.library.rename(doc.id, "Ideen") }
        waitUntil(timeoutMillis = 5000) { runCatching { onNodeWithTag(HeaderTags.TITLE).assertTextContains("Ideen") }.isSuccess }

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
