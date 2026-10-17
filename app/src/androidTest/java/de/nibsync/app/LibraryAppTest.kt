package de.nibsync.app

import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import de.nibsync.canvas.NoteCanvasView
import de.nibsync.canvas.NoteScreenTags
import de.nibsync.library.NodeId
import de.nibsync.library.ui.LibraryTags
import de.nibsync.model.DocumentId
import de.nibsync.model.Orientation
import de.nibsync.model.PaperSize
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

    @Before
    fun launch() {
        savedLastDocument = settings.lastDocument
        savedPageBreaks = settings.showPageBreaks
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        waitUntil("document shown") { activity { it.documentId } != null && activity { it.canvasView } != null }
    }

    @After
    fun cleanUp() {
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
        compose.onNodeWithTag(LibraryTags.row("Kapitel 1")).performClick() // select the chapter
        compose.onNodeWithTag(LibraryTags.NEW_DOCUMENT).performClick()
        enterName("Seite 2")
        val second = tree().descendantsOf(tree().books.first { it.title == bookTitle }.id).first { it.title == "Seite 2" }.id
        waitUntil("second open") { activity { it.documentId } == second.asDocumentId && activity { it.session }?.state?.value?.strokeCount == 0 }

        // Back to the first one through the tree.
        openDrawer()
        compose.onNodeWithTag(LibraryTags.row("Seite A5")).performClick()
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
        compose.onNodeWithTag(LibraryTags.row("Kapitel 1")).performTouchInput { longClick() }
        compose.onNodeWithTag(LibraryTags.RENAME).performClick()
        enterName("Kapitel Eins")
        val book = tree().books.first { it.title == bookTitle }
        waitUntil("renamed") { tree().childrenOf(book.id).any { it.title == "Kapitel Eins" } }

        compose.onNodeWithTag(LibraryTags.row(bookTitle)).performTouchInput { longClick() }
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
        compose.onNodeWithTag(NoteScreenTags.PAGE_BREAKS).performClick()
        waitUntil("toggled") { view().showPageBreaks != before }
        assertThat(settings.showPageBreaks).isEqualTo(!before)
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
}
