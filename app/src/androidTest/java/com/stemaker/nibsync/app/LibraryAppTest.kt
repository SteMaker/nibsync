package com.stemaker.nibsync.app

import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.canvas.NoteCanvasView
import com.stemaker.nibsync.canvas.NoteScreenTags
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.library.ui.LibraryTags
import com.stemaker.nibsync.background.ui.BackgroundTags
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.Orientation
import com.stemaker.nibsync.model.PaperSize
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The library drawer on the real app. Works in the user's library on the device, so everything
 * happens in a throw-away book that is deleted afterwards, and device settings are restored.
 */
@RunWith(AndroidJUnit4::class)
class LibraryAppTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<NibSyncApp>()
    private val settings = AppSettings(app)
    private val bookTitle = "Test-" + UUID.randomUUID().toString().take(8)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var savedLastDocument: DocumentId? = null
    private var savedPageBreaks = false
    private var savedPens = com.stemaker.nibsync.model.PenPreferences()

    @Before
    fun launch() {
        app.sync.paused = true // never sync test data with the account on the device
        savedLastDocument = settings.lastDocument
        savedPageBreaks = settings.showPageBreaks
        savedPens = settings.pens
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        waitUntil("document shown") { activity { it.documentId } != null && activity { it.canvasView } != null }
    }

    @After
    fun cleanUp() {
        app.sync.paused = false
        if (::scenario.isInitialized) scenario.close()
        runBlocking {
            val lib = app.container.library
            // Every node this test created: books with the test's title, plus deleted ones whose
            // tombstones would otherwise stay in the user's library (and be synced later).
            val testBooks = lib.tree.value.books.filter { it.title.startsWith(bookTitle) }
            val created = testBooks.flatMap { lib.tree.value.descendantsOf(it.id) + it }.map { it.id }.toMutableSet()
            testBooks.forEach {
                app.container.beforeDelete(lib.tree.value.descendantsOf(it.id).filter { n -> !n.isFolder }.map { n -> n.id.asDocumentId }.toSet())
                lib.delete(it.id)
            }
            created += createdDuringTest
            created.forEach { id -> java.io.File(app.container.libraryRoot.toString(), "${id.value}.json").delete() }
        }
        settings.lastDocument = savedLastDocument
        settings.showPageBreaks = savedPageBreaks
        settings.pens = savedPens
    }

    private fun <T> activity(get: (MainActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = get(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    /** Ids of nodes that existed in the test's book at some point (some are deleted by the test itself). */
    private val createdDuringTest = mutableSetOf<NodeId>()

    private fun tree() = app.container.library.tree.value.also { t ->
        t.books.filter { it.title.startsWith(bookTitle) }.forEach { b -> createdDuringTest += t.descendantsOf(b.id).map { it.id } + b.id }
    }

    /** A tree row, scrolled into view: the user's own books may push it off screen. */
    private fun row(title: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(LibraryTags.TREE).performScrollToNode(hasTestTag(LibraryTags.row(title)))
        return compose.onNodeWithTag(LibraryTags.row(title))
    }

    private fun openDrawer() {
        compose.onNodeWithTag(NoteScreenTags.MENU).performClick()
        compose.waitForIdle()
    }

    private fun enterName(name: String) {
        compose.onNodeWithTag(LibraryTags.NAME_FIELD).performTextReplacement(name)
        compose.onNodeWithTag(LibraryTags.CONFIRM).performClick()
        compose.waitForIdle()
    }

    /** Creates book > chapter > document (A5 landscape) through the drawer UI and opens it. */
    private fun createDocumentThroughTheDrawer(): NodeId {
        openDrawer()
        compose.onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
        enterName(bookTitle)
        waitUntil("book created") { tree().books.any { it.title == bookTitle } }

        // The new book is selected: create a chapter in it.
        compose.onNodeWithTag(LibraryTags.NEW_FOLDER).assertIsEnabled().performClick()
        enterName("Kapitel 1")
        waitUntil("chapter created") { tree().books.first { it.title == bookTitle }.let { b -> tree().childrenOf(b.id).any { it.title == "Kapitel 1" } } }

        compose.onNodeWithTag(LibraryTags.NEW_DOCUMENT).performClick()
        compose.onNodeWithTag(LibraryTags.paper(PaperSize.A5)).performClick()
        compose.onNodeWithTag(LibraryTags.orientation(Orientation.LANDSCAPE)).performClick()
        compose.onNodeWithTag(BackgroundTags.kind(BackgroundKind.GRID)).performScrollTo().performClick()
        enterName("Seite A5")
        val book = tree().books.first { it.title == bookTitle }
        val doc = tree().descendantsOf(book.id).first { it.title == "Seite A5" }
        waitUntil("new document opened") { activity { it.documentId } == doc.id.asDocumentId }
        return doc.id
    }

    @Test
    fun createBookChapterAndDocumentAndOpenIt() {
        val docId = createDocumentThroughTheDrawer()
        val book = tree().books.first { it.title == bookTitle }
        assertThat(tree().pathTo(docId).map { it.title }).containsExactly(bookTitle, "Kapitel 1", "Seite A5").inOrder()
        // The page has the chosen width: A5 landscape = 210 mm.
        waitUntil("canvas for new document") { activity { it.canvasView }?.viewport?.pageWidthMm == 210f }
        // ... and the chosen paper.
        assertThat(activity { it.session }?.state?.value?.background).isEqualTo(BackgroundSpec.of(BackgroundKind.GRID))
        assertThat(activity { it.canvasView }?.pageBackground).isEqualTo(BackgroundSpec.of(BackgroundKind.GRID))
        assertThat(tree()[book.id]).isNotNull()
        // It is remembered as the last document.
        assertThat(settings.lastDocument).isEqualTo(docId.asDocumentId)
    }

    @Test
    fun switchingDocumentsKeepsTheirStrokesApart() {
        val first = createDocumentThroughTheDrawer()
        drawLine()
        waitUntil("stroke in first") { activity { it.session }?.state?.value?.strokeCount == 1 }

        // Create a second document next to the first and switch to it.
        openDrawer()
        row("Kapitel 1").performClick() // select the chapter
        compose.onNodeWithTag(LibraryTags.NEW_DOCUMENT).performClick()
        enterName("Seite 2")
        val second = tree().descendantsOf(tree().books.first { it.title == bookTitle }.id).first { it.title == "Seite 2" }.id
        waitUntil("second open") { activity { it.documentId } == second.asDocumentId && activity { it.session }?.state?.value?.strokeCount == 0 }

        // Back to the first one through the tree.
        openDrawer()
        row("Seite A5").performClick()
        waitUntil("first again") { activity { it.documentId } == first.asDocumentId }
        waitUntil("its stroke is back") { activity { it.session }?.state?.value?.strokeCount == 1 }
    }

    @Test
    fun newFolderAndDocumentNeedASelectedFolder() {
        openDrawer()
        compose.onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
        enterName(bookTitle)
        // Selecting a document is not possible; deselect by creating nothing: the book stays selected.
        compose.onNodeWithTag(LibraryTags.NEW_FOLDER).assertIsEnabled()
        compose.onNodeWithTag(LibraryTags.NEW_DOCUMENT).assertIsEnabled()
    }

    @Test
    fun renameAndDeleteWithLongPress() {
        createDocumentThroughTheDrawer()
        openDrawer()
        row("Kapitel 1").performTouchInput { longClick() }
        compose.onNodeWithTag(LibraryTags.RENAME).performClick()
        enterName("Kapitel Eins")
        val book = tree().books.first { it.title == bookTitle }
        waitUntil("renamed") { tree().childrenOf(book.id).any { it.title == "Kapitel Eins" } }

        row(bookTitle).performTouchInput { longClick() }
        compose.onNodeWithTag(LibraryTags.DELETE).performClick()
        compose.onNodeWithTag(LibraryTags.CONFIRM).performClick()
        waitUntil("deleted") { tree().books.none { it.title == bookTitle } }
        // The deleted document was open: the app switched to another one (or the empty state).
        waitUntil("switched away") { activity { it.documentId }?.let { id -> tree().documents.any { it.id.asDocumentId == id } } ?: true }
    }

    @Test
    fun pageBreaksCanBeShown() {
        createDocumentThroughTheDrawer()
        val view = { activity { it.canvasView } as NoteCanvasView }
        val before = view().showPageBreaks
        // Settings: gear in the library, then the switch.
        openDrawer()
        compose.onNodeWithTag(LibraryTags.SETTINGS).performClick()
        compose.onNodeWithTag(SettingsTags.PAGE_BREAKS).performClick()
        compose.onNodeWithTag(SettingsTags.CLOSE).performClick()
        waitUntil("toggled") { view().showPageBreaks != before }
        assertThat(settings.showPageBreaks).isEqualTo(!before)
    }

    @Test
    fun penWidthsComeFromTheSettings() {
        createDocumentThroughTheDrawer()
        openDrawer()
        compose.onNodeWithTag(LibraryTags.SETTINGS).performClick()
        val field = compose.onNodeWithTag(SettingsTags.penWidth(com.stemaker.nibsync.model.BrushFamily.BALLPOINT, 2))
        field.performScrollTo().performTextReplacement("1,5")
        compose.onNodeWithTag(SettingsTags.CLOSE).performClick()
        assertThat(settings.pens.widths.getValue(com.stemaker.nibsync.model.BrushFamily.BALLPOINT)[2]).isEqualTo(1.5f)
        waitUntil("tool bar offers it") {
            activity { it.canvasViewModel }?.tools?.value?.sizes(com.stemaker.nibsync.model.BrushFamily.BALLPOINT)?.get(2) == 1.5f
        }
    }

    private fun drawLine() {
        val view = checkNotNull(activity { it.canvasView })
        val origin = IntArray(2).also { loc -> scenario.onActivity { view.getLocationOnScreen(loc) } }
        val down = SystemClock.uptimeMillis()
        val n = 30
        for (i in 0..n) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; n -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { x = origin[0] + 200f + i * 15; y = origin[1] + 300f; pressure = 0.3f; size = 0.1f }
            val e = MotionEvent.obtain(down, down + i * 8L, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
    }

    /** A pen tap at view pixel ([x], [y]) of the canvas. */
    private fun stylusTap(x: Float, y: Float) {
        val view = checkNotNull(activity { it.canvasView })
        val origin = IntArray(2).also { loc -> scenario.onActivity { view.getLocationOnScreen(loc) } }
        val down = SystemClock.uptimeMillis()
        for ((i, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = origin[0] + x; this.y = origin[1] + y; pressure = 0.3f; size = 0.1f }
            val e = MotionEvent.obtain(down, down + i * 40L, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
    }

    @Test
    fun anchorInOneDocumentAndALinkToItFromAnother() {
        val target = createDocumentThroughTheDrawer()
        compose.onNodeWithTag(NoteScreenTags.INSERT).performClick()
        compose.onNodeWithTag(NoteScreenTags.INSERT_ANCHOR).performClick()
        compose.onNodeWithTag(NoteLinkTags.ANCHOR_NAME).performTextReplacement("Ziel")
        compose.onNodeWithTag(NoteLinkTags.ANCHOR_CONFIRM).performClick()
        waitUntil("anchor set") { activity { it.session }?.state?.value?.anchors?.singleOrNull()?.name == "Ziel" }
        val anchor = checkNotNull(activity { it.session }).state.value.anchors.single()

        // A second document links to it.
        openDrawer()
        row("Kapitel 1").performClick()
        compose.onNodeWithTag(LibraryTags.NEW_DOCUMENT).performClick()
        enterName("Seite 2")
        val second = tree().descendantsOf(tree().books.first { it.title == bookTitle }.id).first { it.title == "Seite 2" }.id
        waitUntil("second open") { activity { it.documentId } == second.asDocumentId && activity { it.canvasView }?.viewport?.hasSize == true }
        compose.onNodeWithTag(NoteScreenTags.INSERT).performClick()
        compose.onNodeWithTag(NoteScreenTags.INSERT_NOTE_LINK).performClick()
        compose.onNodeWithTag(NoteLinkTags.SEARCH).performTextInput("A5")
        compose.onNodeWithTag(NoteLinkTags.document(target.asDocumentId)).performClick()
        waitUntil("anchors listed") { compose.onAllNodesWithTag(NoteLinkTags.anchor("Ziel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(NoteLinkTags.anchor("Ziel")).performClick()
        waitUntil("link added") { activity { it.session }?.state?.value?.elements?.any { it is com.stemaker.nibsync.model.NoteLink } == true }
        val link = checkNotNull(activity { it.session }).state.value.elements.filterIsInstance<com.stemaker.nibsync.model.NoteLink>().single()
        assertThat(link.anchorId).isEqualTo(anchor.anchorId)

        // A pen tap on it (still selected after inserting) opens the first document.
        compose.waitForIdle()
        val t = checkNotNull(activity { it.canvasView }).viewport.documentToView
        stylusTap(t.mapX(link.bounds.left + 4f), t.mapY(link.bounds.top + 4f))
        waitUntil("link followed") { activity { it.documentId } == target.asDocumentId }
    }

    @Test
    fun nameFieldStartsEmptySoTypingGivesExactlyTheName() {
        openDrawer()
        compose.onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
        compose.onNodeWithTag(LibraryTags.NAME_FIELD).performTextInput(bookTitle) // typed, not replaced
        compose.onNodeWithTag(LibraryTags.CONFIRM).performClick()
        waitUntil("book with exactly the typed name") { tree().books.any { it.title == bookTitle } }

        // Confirming without typing uses the suggested name.
        compose.onNodeWithTag(LibraryTags.NEW_FOLDER).performClick()
        compose.onNodeWithTag(LibraryTags.CONFIRM).performClick()
        val book = tree().books.first { it.title == bookTitle }
        waitUntil("default chapter name") { tree().childrenOf(book.id).isNotEmpty() }
        assertThat(tree().childrenOf(book.id).single().title).isAnyOf("Neues Kapitel", "New chapter")
    }

    @Test
    fun libraryShowsOnlyTheSyncStatusTheSettingsHoldTheActions() {
        openDrawer()
        compose.onNodeWithTag(com.stemaker.nibsync.app.sync.SyncTags.STATUS_LINE).assertExists()
        compose.onNodeWithTag(com.stemaker.nibsync.app.sync.SyncTags.ADD_ACCOUNT).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTags.SETTINGS).performClick()
        for (account in app.sync.accounts.value) {
            compose.onNodeWithTag(com.stemaker.nibsync.app.sync.SyncTags.account(account.name)).assertExists()
        }
        compose.onNodeWithTag(com.stemaker.nibsync.app.sync.SyncTags.ADD_ACCOUNT).performScrollTo().performClick()
        for (service in com.stemaker.nibsync.sync.client.SyncService.entries) {
            compose.onNodeWithTag(com.stemaker.nibsync.app.sync.SyncTags.service(service)).assertExists()
        }
        compose.onNodeWithTag(SettingsTags.PAGE_BREAKS).assertExists()
        compose.onNodeWithTag(SettingsTags.DRAW_WITH_FINGER).assertExists()
        compose.onNodeWithTag(SettingsTags.CLOSE).performClick()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun newBookChoosesItsAccountOrStaysOnTheDevice() = runBlocking<Unit> {
        // A throw-away account nobody can reach: it is only chosen, never synced (sync paused).
        val account = checkNotNull(app.sync.addAccount("Testkonto", com.stemaker.nibsync.sync.client.NextcloudConfig(
            com.stemaker.nibsync.sync.webdav.NextcloudAccount("http://127.0.0.1:9", "x", "y"), "nibsync-test-" + UUID.randomUUID().toString().take(6))))
        val previousChoice = settings.lastBookAccount
        try {
            openDrawer()
            compose.onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
            compose.onNodeWithTag(LibraryTags.NAME_FIELD).performTextInput(bookTitle)
            compose.onNodeWithTag(LibraryTags.account("Testkonto · Nextcloud")).performClick()
            compose.onNodeWithTag(LibraryTags.CONFIRM).performClick()
            waitUntil("book") { tree().books.any { it.title == bookTitle } }
            val book = tree().books.first { it.title == bookTitle }
            waitUntil("assigned") { app.sync.books.accountOf(book.id) == account.id }

            // A second book stays on the device; the menu of a local book offers the account.
            compose.onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
            compose.onNodeWithTag(LibraryTags.NAME_FIELD).performTextInput("$bookTitle lokal")
            compose.onNodeWithTag(LibraryTags.account(null)).performClick()
            compose.onNodeWithTag(LibraryTags.CONFIRM).performClick()
            waitUntil("local book") { tree().books.any { it.title == "$bookTitle lokal" } }
            val local = tree().books.first { it.title == "$bookTitle lokal" }
            assertThat(app.sync.books.accountOf(local.id)).isNull()
            row("$bookTitle lokal").performTouchInput { longClick() }
            compose.onNodeWithTag(LibraryTags.assign("Testkonto · Nextcloud")).performClick()
            waitUntil("local book assigned") { app.sync.books.accountOf(local.id) == account.id }
        } finally {
            app.sync.removeAccount(account.id)
            settings.lastBookAccount = previousChoice
        }
    }

    @Test
    fun openTodosPageTicksOffInTheDocument() {
        val docId = createDocumentThroughTheDrawer()
        // A typed to-do in the new document.
        val vm = activity { it.canvasViewModel!! }
        scenario.onActivity {
            vm.selectTodo(ink = false)
            vm.tapAt(30f, 30f)
            vm.todoEditing.value!!.text!!.setContent(com.stemaker.nibsync.model.RichText.plain("Testaufgabe"))
            vm.finishText()
        }
        val session = activity { it.session!! }
        val item = session.state.value.todos.single()

        openDrawer()
        compose.onNodeWithTag(LibraryTags.TODOS).performClick()
        val tag = com.stemaker.nibsync.canvas.TodoListTags.item(item.taskId)
        waitUntil("listed") { compose.onAllNodesWithTag(com.stemaker.nibsync.canvas.TodoListTags.LIST).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(com.stemaker.nibsync.canvas.TodoListTags.LIST).performScrollToNode(hasTestTag(tag))
        // Its circle: 5 mm from the top left at 3.2 dp per mm.
        compose.onNodeWithTag(tag).performTouchInput { click(androidx.compose.ui.geometry.Offset(16.dp.toPx(), 16.dp.toPx())) }
        waitUntil("done in the document") { session.state.value.isDone(item.taskId) }
        waitUntil("gone from the list") { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(com.stemaker.nibsync.canvas.TodoListTags.UNDO).performClick()
        waitUntil("open again") { !session.state.value.isDone(item.taskId) }

        // Tapping the entry opens its document.
        waitUntil("listed again") { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(com.stemaker.nibsync.canvas.TodoListTags.LIST).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 10f, 16.dp.toPx())) }
        waitUntil("document shown") { compose.onAllNodesWithTag(NoteScreenTags.CANVAS).fetchSemanticsNodes().isNotEmpty() }
        assertThat(activity { it.documentId }).isEqualTo(docId.asDocumentId)
    }

    @Test
    fun titleOnThePageRenamesTheDocumentAndCanBeHidden() {
        val savedTitle = settings.showTitle
        val savedCreated = settings.showCreated
        try {
            val docId = createDocumentThroughTheDrawer()
            val title = com.stemaker.nibsync.text.HeaderTags.TITLE
            waitUntil("title shown") { compose.onAllNodesWithTag(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(title).assertTextContains("Seite A5")
            compose.onNodeWithTag(com.stemaker.nibsync.text.HeaderTags.DATE).assertExists()
            compose.onNodeWithTag(com.stemaker.nibsync.text.HeaderTags.PATH).assertTextContains("$bookTitle › Kapitel 1 › Seite A5")

            compose.onNodeWithTag(title).performClick()
            compose.onNodeWithTag(title).performTextReplacement("Protokoll")
            compose.onNodeWithTag(title).performImeAction()
            waitUntil("renamed") { tree()[docId]?.title == "Protokoll" }
            waitUntil("path updated") {
                runCatching { compose.onNodeWithTag(com.stemaker.nibsync.text.HeaderTags.PATH).assertTextContains("$bookTitle › Kapitel 1 › Protokoll") }.isSuccess
            }

            // A tap on the page ends typing (no cursor stays) and renames, too.
            compose.onNodeWithTag(title).performClick()
            compose.onNodeWithTag(title).performTextReplacement("Planung")
            compose.onNodeWithTag(NoteScreenTags.CANVAS).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.6f, height * 0.3f)) }
            waitUntil("renamed by leaving") { tree()[docId]?.title == "Planung" }
            compose.onNodeWithTag(title).assertIsNotFocused()

            // A rename in the library shows on the page.
            runBlocking { app.container.library.rename(docId, "Ideen") }
            waitUntil("title follows the library") { runCatching { compose.onNodeWithTag(title).assertTextContains("Ideen") }.isSuccess }

            // Settings: the date off, then the title.
            openDrawer()
            compose.onNodeWithTag(LibraryTags.SETTINGS).performClick()
            compose.onNodeWithTag(SettingsTags.SHOW_CREATED).performScrollTo().performClick()
            compose.onNodeWithTag(SettingsTags.SHOW_TITLE).performScrollTo().performClick()
            compose.onNodeWithTag(SettingsTags.CLOSE).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(title).assertDoesNotExist()
            compose.onNodeWithTag(com.stemaker.nibsync.text.HeaderTags.DATE).assertDoesNotExist()
            assertThat(settings.showTitle).isFalse()
            assertThat(settings.showCreated).isFalse()
        } finally {
            settings.showTitle = savedTitle
            settings.showCreated = savedCreated
        }
    }

    @Test
    fun longPressAndDragMovesADocumentIntoAnotherBook() {
        val docId = createDocumentThroughTheDrawer()
        // A second book of this test, empty and closed.
        openDrawer()
        compose.onNodeWithTag(LibraryTags.NEW_BOOK).performClick()
        enterName("$bookTitle-B")
        waitUntil("second book") { tree().books.any { it.title == "$bookTitle-B" } }
        val target = tree().books.first { it.title == "$bookTitle-B" }
        fun center(title: String): androidx.compose.ui.geometry.Offset {
            row(title)
            return compose.onNodeWithTag(LibraryTags.row(title)).fetchSemanticsNode().boundsInRoot.center
        }
        val from = center("Seite A5")
        val to = center("$bookTitle-B")
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 30f))
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
        }
        compose.onNodeWithTag(LibraryTags.DRAGGED).assertExists()
        compose.onRoot().performTouchInput { up() }
        waitUntil("moved") { tree()[docId]?.parent == target.id }
        compose.onNodeWithTag(LibraryTags.DRAGGED).assertDoesNotExist()
        // It is still the open document, now in its new place.
        assertThat(activity { it.documentId }).isEqualTo(docId.asDocumentId)
        row("Seite A5").assertExists()
        // A long press without moving still opens the menu.
        row("Seite A5").performTouchInput { longClick() }
        compose.onNodeWithTag(LibraryTags.RENAME).assertExists()
    }
}
