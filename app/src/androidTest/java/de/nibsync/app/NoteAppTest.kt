package de.nibsync.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    /**
     * Draws [points] in real time (so the app's hold timer sees real time passing), then
     * optionally keeps the pen still at the last point for [holdMs] before lifting it.
     */
    private fun drawLive(points: List<Pair<Float, Float>>, holdMs: Long = 0) {
        val view = checkNotNull(canvas())
        val origin = IntArray(2).also { loc -> scenario.onActivity { view.getLocationOnScreen(loc) } }
        val source = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val props = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = origin[0] + x
                this.y = origin[1] + y
                pressure = 0.2f
                size = 0.1f
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        points.forEachIndexed { i, (x, y) ->
            send(if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, x, y)
            Thread.sleep(4)
        }
        val (lx, ly) = points.last()
        val holdEnd = SystemClock.uptimeMillis() + holdMs
        var jitter = 0
        while (SystemClock.uptimeMillis() < holdEnd) {
            // A resting pen still reports tiny movements.
            send(MotionEvent.ACTION_MOVE, lx + (jitter++ % 2), ly)
            Thread.sleep(30)
        }
        send(MotionEvent.ACTION_UP, lx, ly)
    }

    /** A hand-drawn looking rectangle (wobbly edges) in canvas pixels. */
    private fun wobblyRectangle(left: Float, top: Float, right: Float, bottom: Float): List<Pair<Float, Float>> {
        val corners = listOf(left to top, right to top, right to bottom, left to bottom, left + 3 to top + 4)
        return corners.zipWithNext().flatMap { (a, b) ->
            List(40) { i ->
                val t = i / 40f
                val wobble = 6f * kotlin.math.sin(t * Math.PI.toFloat())
                val dx = b.first - a.first
                val dy = b.second - a.second
                val len = kotlin.math.hypot(dx, dy)
                (a.first + dx * t - dy / len * wobble) to (a.second + dy * t + dx / len * wobble)
            }
        } + corners.last()
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

    // ---- shape recognition (hold the pen still at the end) -------------------------------

    @Test
    fun holdingThePenTurnsARectangleIntoACleanOne() {
        drawLive(wobblyRectangle(200f, 200f, 900f, 600f), holdMs = 900)
        waitForStrokes(1)
        val stroke = strokes().single()
        val b = stroke.bounds
        val inset = stroke.brush.sizeMm / 2
        // Every point lies exactly on the outline of an axis-aligned rectangle.
        for (p in stroke.points) {
            val onVertical = kotlin.math.abs(p.x - (b.left + inset)) < 0.02f || kotlin.math.abs(p.x - (b.right - inset)) < 0.02f
            val onHorizontal = kotlin.math.abs(p.y - (b.top + inset)) < 0.02f || kotlin.math.abs(p.y - (b.bottom - inset)) < 0.02f
            assertThat(onVertical || onHorizontal).isTrue()
        }
        // Size matches what was drawn (700 x 400 px).
        val scale = checkNotNull(canvas()).viewport.scale
        assertThat((b.width - 2 * inset) * scale).isWithin(40f).of(700f)
        assertThat((b.height - 2 * inset) * scale).isWithin(40f).of(400f)
        // Even pressure, so the fountain pen draws an even line.
        assertThat(stroke.points.map { it.pressure }.toSet()).hasSize(1)
    }

    @Test
    fun withoutHoldingTheStrokeStaysHandwritten() {
        drawLive(wobblyRectangle(200f, 200f, 900f, 600f), holdMs = 0)
        waitForStrokes(1)
        idleFor(800)
        val stroke = strokes().single()
        assertThat(stroke.points.size).isGreaterThan(150) // raw input, not a 4-sided outline
        val b = stroke.bounds
        val inset = stroke.brush.sizeMm / 2
        val offOutline = stroke.points.count { p ->
            listOf(p.x - b.left - inset, b.right - inset - p.x, p.y - b.top - inset, b.bottom - inset - p.y).minOf { kotlin.math.abs(it) } > 0.1f
        }
        assertThat(offOutline).isGreaterThan(20)
    }

    @Test
    fun holdingOnHandwritingKeepsIt() {
        val zigzag = listOf(200f to 300f, 260f to 500f, 320f to 320f, 380f to 500f, 440f to 300f)
            .zipWithNext().flatMap { (a, b) -> line(a.first, a.second, b.first, b.second, steps = 15).dropLast(1) } + (440f to 300f)
        drawLive(zigzag, holdMs = 900)
        waitForStrokes(1)
        assertThat(strokes().single().points.size).isAtLeast(zigzag.size - 5)
    }

    @Test
    fun recognisedShapeCanBeUndone() {
        drawLive(line(200f, 300f, 900f, 310f, steps = 60), holdMs = 900)
        waitForStrokes(1)
        val line = strokes().single()
        assertThat(line.points.first().y).isEqualTo(line.points.last().y) // snapped horizontal
        scenario.onActivity { it.session!!.undo() }
        waitForStrokes(0)
    }

    @Test
    fun arrowHeadDrawnOntoALineMakesOneArrow() {
        // Shaft without holding, then the head as a separate "V" with holding.
        drawLive(line(200f, 400f, 900f, 405f, steps = 60))
        waitForStrokes(1)
        val shaft = strokes().single()
        val head = line(820f, 340f, 900f, 405f, steps = 15) + line(900f, 405f, 825f, 470f, steps = 15).drop(1)
        drawLive(head, holdMs = 900)
        waitUntil("shaft and head merged") {
            strokes().size == 1 && strokes().single().id != shaft.id
        }
        val arrow = strokes().single()
        val toDoc = checkNotNull(canvas()).viewport.viewToDocument
        // Snapped horizontal shaft from the tail to the tip, plus head points behind the tip.
        assertThat(arrow.points.first().y).isEqualTo(arrow.points[1].y)
        assertThat(arrow.points.first().x).isWithin(2f).of(toDoc.mapX(200f))
        assertThat(arrow.points.maxOf { it.x }).isWithin(2f).of(toDoc.mapX(900f))
        assertThat(arrow.bounds.height).isGreaterThan(3f)

        scenario.onActivity { it.session!!.undo() }
        waitUntil("undo restores the shaft only") { strokes().size == 1 && strokes().single().points == shaft.points }
    }

    // ---- erasers --------------------------------------------------------------------------

    @Test
    fun objectEraserRemovesOnlyTheCrossedStroke() {
        drag(line(200f, 300f, 900f, 300f))
        drag(line(200f, 600f, 900f, 600f))
        waitForStrokes(2)
        val lower = strokes().maxBy { it.bounds.top }

        compose.onNodeWithTag(NoteScreenTags.eraserMode(de.nibsync.document.erase.EraserMode.OBJECT)).performClick()
        drag(line(500f, 250f, 520f, 400f))
        waitForStrokes(1)
        assertThat(strokes().single()).isEqualTo(lower)

        scenario.onActivity { it.session!!.undo() }
        waitForStrokes(2)

        // Picking a pen again writes again.
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        drag(line(200f, 800f, 600f, 800f))
        waitForStrokes(3)
    }

    @Test
    fun pixelEraserCutsALineInTwo() {
        drag(line(200f, 300f, 900f, 300f))
        waitForStrokes(1)
        val original = strokes().single()

        compose.onNodeWithTag(NoteScreenTags.eraserMode(de.nibsync.document.erase.EraserMode.PIXEL)).performClick()
        compose.onNodeWithTag(NoteScreenTags.eraserSize(1)).performClick() // 5 mm
        drag(line(550f, 200f, 550f, 400f))
        waitForStrokes(2)

        val toDoc = checkNotNull(canvas()).viewport.viewToDocument
        val cutX = toDoc.mapX(550f)
        val (left, right) = strokes().sortedBy { it.points.first().x }
        val reach = 2.5f + original.brush.sizeMm / 2
        assertThat(left.points.maxOf { it.x }).isWithin(0.3f).of(cutX - reach)
        assertThat(right.points.minOf { it.x }).isWithin(0.3f).of(cutX + reach)
        assertThat(left.brush).isEqualTo(original.brush)

        scenario.onActivity { it.session!!.undo() }
        waitUntil("restored") { strokes().size == 1 && strokes().single().points == original.points }
    }

    @Test
    fun erasingDoesNotScrollOrDraw() {
        drag(line(200f, 300f, 900f, 300f))
        waitForStrokes(1)
        compose.onNodeWithTag(NoteScreenTags.eraserMode(de.nibsync.document.erase.EraserMode.OBJECT)).performClick()
        val scrollBefore = checkNotNull(canvas()).viewport.scrollYMm
        drag(line(300f, 700f, 800f, 750f)) // erasing empty paper
        idleFor(500)
        assertThat(strokes()).hasSize(1)
        assertThat(checkNotNull(canvas()).viewport.scrollYMm).isEqualTo(scrollBefore)
    }

    // ---- undo / redo ----------------------------------------------------------------------

    @Test
    fun undoAndRedoButtons() {
        compose.onNodeWithTag(NoteScreenTags.UNDO).assertIsNotEnabled()
        compose.onNodeWithTag(NoteScreenTags.REDO).assertIsNotEnabled()
        drag(line(200f, 300f, 900f, 300f))
        drag(line(200f, 500f, 900f, 500f))
        waitForStrokes(2)
        val strokesDrawn = strokes()

        compose.onNodeWithTag(NoteScreenTags.UNDO).assertIsEnabled().performClick()
        waitForStrokes(1)
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitForStrokes(0)
        compose.onNodeWithTag(NoteScreenTags.UNDO).assertIsNotEnabled()

        compose.onNodeWithTag(NoteScreenTags.REDO).assertIsEnabled().performClick()
        compose.onNodeWithTag(NoteScreenTags.REDO).performClick()
        waitForStrokes(2)
        compose.onNodeWithTag(NoteScreenTags.REDO).assertIsNotEnabled()
        // Redone strokes look the same as before (copies with new ids).
        assertThat(strokes().map { it.points }).isEqualTo(strokesDrawn.map { it.points })
        // The canvas shows them too.
        waitUntil("canvas updated") { checkNotNull(canvas()).strokes.size == 2 }
    }

    @Test
    fun undoAndRedoWithKeyboardShortcuts() {
        drag(line(200f, 300f, 900f, 300f))
        waitForStrokes(1)
        fun press(keyCode: Int, meta: Int) {
            val now = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            instrumentation.sendKeySync(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        val ctrl = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        press(android.view.KeyEvent.KEYCODE_Z, ctrl)
        waitForStrokes(0)
        press(android.view.KeyEvent.KEYCODE_Y, ctrl)
        waitForStrokes(1)
        press(android.view.KeyEvent.KEYCODE_Z, ctrl)
        waitForStrokes(0)
        press(android.view.KeyEvent.KEYCODE_Z, ctrl or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON)
        waitForStrokes(1)
    }

    @Test
    fun undoRevertsAnEraseInOneStep() {
        drag(line(200f, 300f, 900f, 300f))
        waitForStrokes(1)
        compose.onNodeWithTag(NoteScreenTags.eraserMode(de.nibsync.document.erase.EraserMode.PIXEL)).performClick()
        drag(line(400f, 200f, 400f, 400f))
        drag(line(700f, 200f, 700f, 400f))
        waitForStrokes(3)
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitForStrokes(2) // only the second cut is undone
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitForStrokes(1)
    }
}
