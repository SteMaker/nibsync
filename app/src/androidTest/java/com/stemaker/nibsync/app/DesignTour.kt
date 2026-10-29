package com.stemaker.nibsync.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stemaker.nibsync.canvas.NoteScreenTags
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.library.ui.LibraryTags
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.NoteLinkDraft
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoDraft
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Not a test: walks through the app's screens with sample content and saves screen shots
 * (design review). Removes everything it created afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DesignTour {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<NibSyncApp>()
    private val settings = AppSettings(app)
    private val savedLastDocument = settings.lastDocument
    private val books = mutableListOf<NodeId>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val out = File(app.getExternalFilesDir(null), "tour").apply { deleteRecursively(); mkdirs() }

    @After
    fun cleanUp() {
        app.sync.paused = false
        if (::scenario.isInitialized) scenario.close()
        runBlocking {
            val lib = app.container.library
            for (book in books) {
                val nodes = lib.tree.value.descendantsOf(book)
                app.container.beforeDelete(nodes.filter { !it.isFolder }.map { it.id.asDocumentId }.toSet())
                lib.delete(book)
                (nodes.map { it.id } + book).forEach { id -> File(app.container.libraryRoot.toString(), "${id.value}.json").delete() }
            }
        }
        settings.lastDocument = savedLastDocument
    }

    private fun shot(name: String) {
        // Compose runs frames (and effects) only while the test waits for it.
        repeat(4) {
            compose.waitForIdle()
            SystemClock.sleep(400)
        }
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun back() {
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(500)
    }

    private fun <T> activity(get: (MainActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = get(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(timeoutMs: Long = 8000, condition: () -> Boolean) = compose.waitUntil(timeoutMs) { condition() }

    /** Handwriting-like words: one looping stroke per word along a line. */
    private fun writing(x0: Float, y: Float, words: List<Float>, brush: BrushSpec): List<ElementDraft> {
        var x = x0
        var t = 0L
        return words.mapIndexed { w, len ->
            val pts = ArrayList<StrokePoint>()
            val steps = (len * 10).toInt()
            for (i in 0..steps) {
                val a = i * 0.21f + w
                // Forward along the line with small loops back, like joined-up letters.
                val px = x + len * i / steps - 0.9f * sin(a + 1.2f)
                val py = y + 1.7f * sin(a) * (0.75f + 0.25f * sin(i * 0.05f + w))
                pts += StrokePoint(px, py, t, 0.45f + 0.25f * sin(i * 0.11f))
                t += 6
            }
            x += len + 3.5f
            StrokeDraft(brush, InputTool.STYLUS, pts)
        }
    }

    /** A pen line through [points], with a sample every 0.3 mm like real input. */
    private fun polyline(points: List<Pair<Float, Float>>, brush: BrushSpec): StrokeDraft {
        val pts = ArrayList<StrokePoint>()
        var t = 0L
        for ((a, b) in points.zipWithNext()) {
            val n = maxOf(1, (kotlin.math.hypot(b.first - a.first, b.second - a.second) / 0.3f).toInt())
            for (i in 0 until n) {
                pts += StrokePoint(a.first + (b.first - a.first) * i / n, a.second + (b.second - a.second) * i / n, t, 0.6f)
                t += 6
            }
        }
        pts += StrokePoint(points.last().first, points.last().second, t, 0.6f)
        return StrokeDraft(brush, InputTool.STYLUS, pts)
    }

    private fun stylus(points: List<Pair<Float, Float>>) {
        val view = checkNotNull(activity { it.canvasView })
        val origin = IntArray(2).also { loc -> scenario.onActivity { view.getLocationOnScreen(loc) } }
        val down = SystemClock.uptimeMillis()
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = origin[0] + x; this.y = origin[1] + y; pressure = 0.4f; size = 0.1f }
            val e = MotionEvent.obtain(down, down + i * 12L, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
    }

    @Test
    fun tour() {
        // Only on request (-Pandroid.testInstrumentationRunnerArguments.tour=1), not in test runs.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("tour") == "1")
        app.sync.paused = true
        val lib = app.container.library
        val ink = BrushSpec(BrushFamily.FOUNTAIN_PEN, 0xff1a1a2e.toInt(), 0.6f)
        val blue = BrushSpec(BrushFamily.BALLPOINT, 0xff1a3fa8.toInt(), 0.5f)
        val marker = BrushSpec(BrushFamily.HIGHLIGHTER, 0x66ffeb3b, 5f)
        var main = DocumentId("tour-placeholder")
        runBlocking {
            val work = lib.createBook("Arbeit").also { books += it.id }
            val projects = lib.createFolder(work.id, "Projekte")
            val meetings = lib.createFolder(work.id, "Besprechungen")
            val lined = BackgroundSpec(BackgroundKind.LINED, 7f)
            val atlas = lib.createDocument(projects.id, "Projekt Atlas", PageFormat(), lined)
            val ideas = lib.createDocument(projects.id, "Ideensammlung", PageFormat(), lined)
            lib.createDocument(meetings.id, "Jour fixe 14.10.", PageFormat(), lined)
            lib.createDocument(meetings.id, "Jour fixe 21.10.", PageFormat(), lined)
            val home = lib.createBook("Privat").also { books += it.id }
            lib.createDocument(home.id, "Einkaufsliste", PageFormat(), lined)
            lib.createDocument(home.id, "Urlaub 2027", PageFormat(), BackgroundSpec(BackgroundKind.DOTTED, 5f))
            main = atlas.id.asDocumentId

            val ideasSession = app.container.openSession(ideas.id.asDocumentId, "Ideensammlung")
            ideasSession.replaceElements(emptySet(), listOf(AnchorDraft(20f, 40f, "Offene Fragen")))
            val s = app.container.openSession(main, "Projekt Atlas")
            val heading = RichText(listOf(TextParagraph("Kickoff Projekt Atlas", marks = listOf(TextMark(0, 21, MarkStyle.Bold), TextMark(0, 21, MarkStyle.Size(24f))))))
            val list = RichText(
                listOf(
                    TextParagraph("Ziele", marks = listOf(TextMark(0, 5, MarkStyle.Bold), TextMark(0, 5, MarkStyle.Size(14f)))),
                    TextParagraph("Prototyp bis Ende November", ListKind.BULLET),
                    TextParagraph("Budget mit Einkauf klären", ListKind.BULLET),
                    TextParagraph("Rahmenvertrag liegt noch nicht vor", ListKind.BULLET, continued = true),
                    TextParagraph("Testgruppe aus drei Teams", ListKind.BULLET),
                ),
            )
            val drafts = ArrayList<ElementDraft>()
            drafts += TextBoxDraft(20f, 26f, 170f, 12f, heading)
            drafts += writing(22f, 53f, listOf(18f, 9f, 24f, 14f, 20f, 11f), ink)
            drafts += writing(22f, 60f, listOf(12f, 26f, 8f, 17f, 22f), ink)
            drafts += writing(22f, 67f, listOf(21f, 15f, 30f, 10f), ink)
            drafts += polyline(listOf(20f to 60f, 68f to 60f), marker)
            drafts += TextBoxDraft(20f, 78f, 110f, 34f, list)
            // A sketch: two boxes and an arrow.
            drafts += polyline(listOf(140f to 82f, 185f to 82f, 185f to 100f, 140f to 100f, 140f to 82f), blue)
            drafts += polyline(listOf(140f to 120f, 185f to 120f, 185f to 138f, 140f to 138f, 140f to 120f), blue)
            drafts += polyline(listOf(162f to 101f, 162f to 118f), blue)
            drafts += polyline(listOf(158f to 113f, 162f to 118f, 166f to 113f), blue)
            drafts += writing(146f, 91f, listOf(10f, 14f), blue)
            drafts += writing(146f, 129f, listOf(16f, 8f), blue)
            drafts += AnchorDraft(20f, 148f, "Entscheidungen")
            drafts += writing(30f, 152f, listOf(14f, 22f, 9f, 18f), ink)
            drafts += TodoDraft(20f, 162f, 110f, TodoItem.MIN_HEIGHT_MM, TodoContent.Text(RichText.plain("Angebot an Lieferanten schicken")))
            drafts += TodoDraft(20f, 180f, 110f, TodoItem.MIN_HEIGHT_MM, TodoContent.Text(RichText.plain("Termin Testgruppe festlegen")))
            drafts += NoteLinkDraft(20f, 202f, ideas.id.asDocumentId, null, "Ideensammlung", null)
            s.replaceElements(emptySet(), drafts)
            val done = s.state.value.todos.first { (it.content as TodoContent.Text).text.plainText.startsWith("Termin") }
            s.setTodoDone(done.taskId, true)
            app.container.flushOpenSessions()
        }
        settings.lastDocument = main
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        waitUntil { activity { it.documentId } == main && activity { it.canvasView }?.viewport?.hasSize == true }
        shot("01-note-pen")

        // Open the list box with the pen: editor, format bar and keyboard.
        val view = checkNotNull(activity { it.canvasView })
        val t = view.viewport.documentToView
        stylus(listOf(t.mapX(40f) to t.mapY(88f), t.mapX(40f) to t.mapY(88f)))
        waitUntil { activity { it.canvasViewModel?.textEditing?.value } != null }
        shot("03-text-editing")
        compose.onNodeWithTag(com.stemaker.nibsync.text.TextTags.PRESET).performClick()
        shot("04-text-style-menu")
        back()
        stylus(listOf(t.mapX(195f) to t.mapY(30f), t.mapX(195f) to t.mapY(30f)))
        waitUntil { activity { it.canvasViewModel?.textEditing?.value } == null }
        SystemClock.sleep(1500)

        // Lasso around the sketch.
        compose.onNodeWithTag(NoteScreenTags.LASSO).performClick()
        val loop = (0..40).map { i ->
            val a = i / 40.0 * 2 * Math.PI
            t.mapX(162f + 30f * kotlin.math.cos(a).toFloat()) to t.mapY(110f + 34f * kotlin.math.sin(a).toFloat())
        }
        stylus(loop)
        shot("05-lasso-selection")

        compose.onNodeWithTag(NoteScreenTags.TODO).performClick()
        shot("06-todo-tool")
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.HIGHLIGHTER)).performClick()
        shot("07-highlighter")

        compose.onNodeWithTag(NoteScreenTags.BACKGROUND).performClick()
        shot("08-background-dialog")
        back()

        compose.onNodeWithTag(NoteScreenTags.MENU).performClick()
        shot("09-library-drawer")
        compose.onNodeWithTag(LibraryTags.TODOS).performClick()
        shot("10-open-todos")
        compose.onNodeWithTag(NoteScreenTags.MENU).performClick()
        compose.onNodeWithTag(LibraryTags.SETTINGS).performClick()
        shot("11-settings")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(500)

        compose.onNodeWithTag(NoteScreenTags.INSERT).performClick()
        shot("02-insert-menu")
        compose.onNodeWithTag(NoteScreenTags.INSERT_NOTE_LINK).performClick()
        shot("12-link-dialog")
    }
}
