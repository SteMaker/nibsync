package de.nibsync.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.google.common.truth.Truth.assertThat
import de.nibsync.canvas.NoteCanvasView
import de.nibsync.canvas.NoteScreenTags
import de.nibsync.document.DocumentSession
import de.nibsync.model.BrushFamily
import de.nibsync.model.DocumentId
import de.nibsync.model.InputTool
import de.nibsync.model.Layer
import de.nibsync.model.Stroke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Application-level tests on a real device: synthetic stylus and finger input goes through the
 * normal input pipeline into the app. Each test works on its own throw-away document so the
 * user's notes are never touched.
 */
@RunWith(AndroidJUnit4::class)
class NoteAppTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<NibSyncApp>()
    private val documentId = DocumentId("test-" + UUID.randomUUID())
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        scenario = launchActivity()
    }

    @After
    fun cleanUp() {
        if (::scenario.isInitialized) scenario.close()
        runBlocking {
            app.container.closeSession(documentId)
            app.container.repository.delete(documentId)
        }
    }

    private fun launchActivity(): ActivityScenario<MainActivity> {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOCUMENT_ID, documentId.value)
        val s = ActivityScenario.launch<MainActivity>(intent)
        waitUntil("canvas laid out") { canvas(s)?.viewport?.hasSize == true }
        return s
    }

    private fun canvas(s: ActivityScenario<MainActivity> = scenario): NoteCanvasView? {
        var view: NoteCanvasView? = null
        s.onActivity { view = it.canvasView }
        return view
    }

    private fun session(): DocumentSession {
        var session: DocumentSession? = null
        scenario.onActivity { session = it.session }
        return checkNotNull(session)
    }

    private fun strokes(): List<Stroke> = session().state.value.strokes

    // ---- input injection ------------------------------------------------------------------

    /** Draws a polyline given in canvas-relative pixels, with pressure rising from 0.2 to 0.9. */
    private fun drag(
        points: List<Pair<Float, Float>>,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        stepMs: Long = 8,
    ) {
        val view = checkNotNull(canvas())
        val origin = IntArray(2).also { loc -> scenario.onActivity { view.getLocationOnScreen(loc) } }
        val source = if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        } else {
            InputDevice.SOURCE_TOUCHSCREEN
        }
        val down = SystemClock.uptimeMillis()
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val props = MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = origin[0] + x
                this.y = origin[1] + y
                pressure = 0.2f + 0.7f * i / points.lastIndex
                size = 0.1f
            }
            val event = MotionEvent.obtain(down, down + i * stepMs, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 30) =
        List(steps + 1) { i -> (x0 + (x1 - x0) * i / steps) to (y0 + (y1 - y0) * i / steps) }

    /** Waits via the compose rule, which keeps recomposition (driven by its test clock) going. */
    private fun waitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    /** Lets time pass without blocking recomposition. */
    private fun idleFor(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms
        waitUntil("$ms ms passed", ms + 2000) { SystemClock.uptimeMillis() >= end }
    }

    private fun waitForStrokes(count: Int) = waitUntil("$count strokes") { strokes().size == count }

    // ---- tests ----------------------------------------------------------------------------

    @Test
    fun stylusStrokeIsStoredWithPressureInDocumentUnits() {
        val view = checkNotNull(canvas())
        drag(line(100f, 300f, 600f, 350f))
        waitForStrokes(1)

        val stroke = strokes().single()
        assertThat(stroke.tool).isEqualTo(InputTool.STYLUS)
        assertThat(stroke.layer).isEqualTo(Layer.INK)
        assertThat(stroke.brush.family).isEqualTo(BrushFamily.FOUNTAIN_PEN)
        val pressures = stroke.points.map { it.pressure }
        assertThat(pressures.first()).isLessThan(0.4f)
        assertThat(pressures.max()).isGreaterThan(0.7f)

        // Positions are converted from pixels to millimetres on the page.
        val toDoc = view.viewport.viewToDocument
        assertThat(stroke.points.first().x).isWithin(1f).of(toDoc.mapX(100f))
        assertThat(stroke.points.first().y).isWithin(1f).of(toDoc.mapY(300f))
        assertThat(stroke.points.last().x).isWithin(1f).of(toDoc.mapX(600f))
    }

    @Test
    fun fingerScrollsInsteadOfDrawing() {
        val view = checkNotNull(canvas())
        val height = view.height.toFloat()
        drag(line(400f, height * 0.8f, 400f, height * 0.2f, steps = 20), toolType = MotionEvent.TOOL_TYPE_FINGER, stepMs = 16)
        waitUntil("scrolled") { view.viewport.scrollYMm > 0f }
        idleFor(500) // give a wrongly started stroke time to show up
        assertThat(strokes()).isEmpty()
    }

    @Test
    fun fingerDrawsWhenEnabled() {
        compose.onNodeWithTag(NoteScreenTags.FINGER_TOGGLE).performClick()
        drag(line(100f, 300f, 500f, 300f), toolType = MotionEvent.TOOL_TYPE_FINGER)
        waitForStrokes(1)
        assertThat(strokes().single().tool).isEqualTo(InputTool.TOUCH)
    }

    @Test
    fun selectedPenColorAndWidthAreUsed() {
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        compose.onNodeWithTag(NoteScreenTags.color(2)).performClick()
        compose.onNodeWithTag(NoteScreenTags.size(2)).performClick()
        drag(line(100f, 300f, 500f, 300f))
        waitForStrokes(1)
        val brush = strokes().single().brush
        assertThat(brush.family).isEqualTo(BrushFamily.BALLPOINT)
        assertThat(brush.colorArgb).isEqualTo(de.nibsync.canvas.PenPalette.inkColors[2])
        assertThat(brush.sizeMm).isEqualTo(de.nibsync.canvas.PenPalette.sizes(BrushFamily.BALLPOINT)[2])
    }

    @Test
    fun highlighterIsRenderedBehindInk() {
        // Black ink first, then a yellow highlighter across it.
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        compose.onNodeWithTag(NoteScreenTags.size(2)).performClick()
        drag(line(300f, 200f, 300f, 600f))
        waitForStrokes(1)
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.HIGHLIGHTER)).performClick()
        compose.onNodeWithTag(NoteScreenTags.size(2)).performClick()
        drag(line(100f, 400f, 500f, 400f))
        waitForStrokes(2)

        assertThat(strokes().map { it.layer }).containsExactly(Layer.MARKER, Layer.INK).inOrder()

        val view = checkNotNull(canvas())
        waitUntil("overlay handed off") { !view.hasUnfinishedStrokes() }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        scenario.onActivity { view.draw(Canvas(bitmap)) }

        // Where ink and highlighter cross, the ink stays black because it lies on top.
        val crossing = bitmap.getPixel(300, 400)
        assertThat(Color.red(crossing)).isLessThan(60)
        assertThat(Color.green(crossing)).isLessThan(60)
        // Next to the ink line only the highlighter is visible: yellowish.
        val highlighterOnly = bitmap.getPixel(150, 400)
        assertThat(Color.red(highlighterOnly)).isGreaterThan(200)
        assertThat(Color.blue(highlighterOnly)).isLessThan(200)
    }

    @Test
    fun strokesSurviveRestartFromDisk() {
        drag(line(100f, 300f, 500f, 320f))
        drag(line(100f, 400f, 500f, 420f))
        waitForStrokes(2)
        val before = strokes()

        scenario.close()
        // Drop the in-memory session so the next launch really reads the files.
        runBlocking { app.container.closeSession(documentId) }
        scenario = launchActivity()

        waitUntil("reloaded") { strokes().size == 2 }
        assertThat(strokes()).isEqualTo(before)
        assertThat(checkNotNull(canvas()).strokes).isEqualTo(before)
    }

    @Test
    fun pageGrowsWhenWritingAtTheBottom() {
        val view = checkNotNull(canvas())
        val height = view.height.toFloat()
        // Scroll to the end of the (one page high) empty document.
        repeat(3) {
            drag(line(400f, height * 0.9f, 400f, height * 0.1f, steps = 10), toolType = MotionEvent.TOOL_TYPE_FINGER, stepMs = 30)
        }
        idleFor(800) // let a fling settle
        val docHeightBefore = view.viewport.documentHeightMm

        drag(line(200f, height - 40f, 600f, height - 30f))
        waitForStrokes(1)
        waitUntil("document grew") { view.viewport.documentHeightMm > docHeightBefore }
    }
}
