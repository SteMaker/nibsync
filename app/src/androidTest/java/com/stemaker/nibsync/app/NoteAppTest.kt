package com.stemaker.nibsync.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.canvas.NoteCanvasView
import com.stemaker.nibsync.canvas.NoteScreenTags
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.background.ui.BackgroundTags
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundPattern
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.text.TextTags
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.Layer
import com.stemaker.nibsync.model.Stroke
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
    private val settings = AppSettings(app)
    private var savedPens = com.stemaker.nibsync.model.PenPreferences()

    @Before
    fun launch() {
        app.sync.paused = true // never sync test data with the account on the device
        // Every test starts with the default pens, whatever an earlier test or the user chose.
        savedPens = settings.pens
        settings.pens = com.stemaker.nibsync.model.PenPreferences()
        scenario = launchActivity()
    }

    @After
    fun cleanUp() {
        app.sync.paused = false
        if (::scenario.isInitialized) scenario.close()
        settings.pens = savedPens
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
        val settings = AppSettings(app)
        val before = settings.drawWithFinger
        try {
            // Settings: menu, gear in the library, the switch, back.
            compose.onNodeWithTag(NoteScreenTags.MENU).performClick()
            compose.onNodeWithTag(com.stemaker.nibsync.library.ui.LibraryTags.SETTINGS).performClick()
            compose.onNodeWithTag(SettingsTags.DRAW_WITH_FINGER).performClick()
            compose.onNodeWithTag(SettingsTags.CLOSE).performClick()
            compose.waitForIdle()
            waitUntil("finger drawing on") { canvas()?.drawWithFinger == true }
            assertThat(settings.drawWithFinger).isTrue()
            // Close the library with a tap beside it, then draw.
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 40f, height / 2f)) }
            waitUntil("library closed") {
                compose.onAllNodesWithTag(com.stemaker.nibsync.library.ui.LibraryTags.DRAWER).fetchSemanticsNodes().all { it.boundsInRoot.right <= 0f }
            }
            drag(line(100f, 300f, 500f, 300f), toolType = MotionEvent.TOOL_TYPE_FINGER)
            waitForStrokes(1)
            assertThat(strokes().single().tool).isEqualTo(InputTool.TOUCH)
        } finally {
            settings.drawWithFinger = before
        }
    }

    @Test
    fun selectedPenColorAndWidthAreUsed() {
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        // Colours and widths are in the settings bar on the right, not in the tool bar.
        val inSettings = hasAnyAncestor(hasTestTag(NoteScreenTags.TOOL_SETTINGS))
        compose.onNode(hasTestTag(NoteScreenTags.color(2)) and inSettings).assertExists()
        compose.onNode(hasTestTag(NoteScreenTags.size(2)) and inSettings).assertExists()
        compose.onNode(hasTestTag(NoteScreenTags.color(0)) and hasAnyAncestor(hasTestTag(NoteScreenTags.TOOLBAR))).assertDoesNotExist()
        val settings = compose.onNodeWithTag(NoteScreenTags.TOOL_SETTINGS).fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag(NoteScreenTags.CANVAS).fetchSemanticsNode().boundsInRoot
        assertThat(settings.left).isAtLeast(canvas.right - 1f)
        compose.onNodeWithTag(NoteScreenTags.color(2)).performClick()
        compose.onNodeWithTag(NoteScreenTags.size(2)).performClick()
        drag(line(100f, 300f, 500f, 300f))
        waitForStrokes(1)
        val brush = strokes().single().brush
        assertThat(brush.family).isEqualTo(BrushFamily.BALLPOINT)
        assertThat(brush.colorArgb).isEqualTo(com.stemaker.nibsync.canvas.PenPalette.inkColors[2])
        assertThat(brush.sizeMm).isEqualTo(com.stemaker.nibsync.model.PenWidths.DEFAULTS.getValue(BrushFamily.BALLPOINT)[2])
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
    fun paperPatternIsChosenDrawnAndKept() {
        compose.onNodeWithTag(NoteScreenTags.BACKGROUND).performClick()
        compose.onNodeWithTag(BackgroundTags.kind(BackgroundKind.LINED)).performClick()
        compose.onNodeWithTag(BackgroundTags.spacing(10f)).performClick()
        compose.onNodeWithTag(NoteScreenTags.BACKGROUND_CONFIRM).performClick()
        val lined = BackgroundSpec(BackgroundKind.LINED, 10f)
        waitUntil("background set") { session().state.value.background == lined }
        waitUntil("canvas shows it") { canvas()?.pageBackground == lined }

        // A ruled line is drawn in blue-grey on the page, the paper between lines stays white.
        val view = checkNotNull(canvas())
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        scenario.onActivity { view.draw(Canvas(bitmap)) }
        val v = view.viewport
        val t = v.documentToView
        val x = t.mapX(v.pageWidthMm / 2).toInt()
        val lineMm = BackgroundPattern.rows(lined, v.visibleDocumentRect.top + 1f, v.visibleDocumentRect.bottom).first()
        val lineY = t.mapY(lineMm).toInt()
        val onLine = (lineY - 2..lineY + 2).map { bitmap.getPixel(x, it) }.minBy { Color.red(it) }
        assertThat(Color.red(onLine)).isLessThan(230)
        assertThat(Color.blue(onLine)).isGreaterThan(Color.red(onLine))
        assertThat(bitmap.getPixel(x, t.mapY(lineMm + 5f).toInt())).isEqualTo(Color.WHITE)

        // Undo does not touch the paper; it survives a restart from disk.
        compose.onNodeWithTag(NoteScreenTags.UNDO).assertIsNotEnabled()
        scenario.close()
        runBlocking { app.container.closeSession(documentId) }
        scenario = launchActivity()
        waitUntil("reloaded with lined paper") { canvas()?.pageBackground == lined }
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

        compose.onNodeWithTag(NoteScreenTags.eraserMode(com.stemaker.nibsync.document.erase.EraserMode.OBJECT)).performClick()
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

        compose.onNodeWithTag(NoteScreenTags.eraserMode(com.stemaker.nibsync.document.erase.EraserMode.PIXEL)).performClick()
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
        compose.onNodeWithTag(NoteScreenTags.eraserMode(com.stemaker.nibsync.document.erase.EraserMode.OBJECT)).performClick()
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
        // Delivered straight to the activity: which window the system routes hardware keys to
        // depends on the device (other floating apps can hold the focus), not on NibSync.
        fun press(keyCode: Int, meta: Int) {
            val now = SystemClock.uptimeMillis()
            scenario.onActivity {
                it.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
                it.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
            }
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
        compose.onNodeWithTag(NoteScreenTags.eraserMode(com.stemaker.nibsync.document.erase.EraserMode.PIXEL)).performClick()
        drag(line(400f, 200f, 400f, 400f))
        drag(line(700f, 200f, 700f, 400f))
        waitForStrokes(3)
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitForStrokes(2) // only the second cut is undone
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitForStrokes(1)
    }

    // ---- lasso ----------------------------------------------------------------------------

    /** A closed loop around the rectangle (canvas pixels). */
    private fun loop(left: Float, top: Float, right: Float, bottom: Float) =
        line(left, top, right, top, 10) + line(right, top, right, bottom, 10).drop(1) +
            line(right, bottom, left, bottom, 10).drop(1) + line(left, bottom, left, top, 10).drop(1)

    private fun selection(): Set<com.stemaker.nibsync.model.StrokeId> = checkNotNull(canvas()).selection

    /** Two lines: one at y = 300 (inside the lasso used below) and one at y = 700. */
    private fun drawTwoLinesAndLassoTheUpperOne(): Pair<com.stemaker.nibsync.model.Stroke, com.stemaker.nibsync.model.Stroke> {
        drag(line(300f, 300f, 700f, 300f))
        drag(line(300f, 700f, 700f, 700f))
        waitForStrokes(2)
        val upper = strokes().minBy { it.bounds.top }
        val lower = strokes().maxBy { it.bounds.top }
        compose.onNodeWithTag(NoteScreenTags.LASSO).performClick()
        drag(loop(250f, 250f, 750f, 350f))
        waitUntil("selected") { selection() == setOf(upper.id) }
        return upper to lower
    }

    @Test
    fun lassoSelectsAndDeletes() {
        compose.onNodeWithTag(NoteScreenTags.LASSO).performClick()
        compose.onNodeWithTag(NoteScreenTags.DELETE_SELECTION).assertIsNotEnabled()
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.FOUNTAIN_PEN)).performClick()
        val (_, lower) = drawTwoLinesAndLassoTheUpperOne()
        compose.onNodeWithTag(NoteScreenTags.DELETE_SELECTION).assertIsEnabled().performClick()
        waitForStrokes(1)
        assertThat(strokes().single()).isEqualTo(lower)
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitForStrokes(2)
    }

    @Test
    fun selectionCanBeDraggedWithThePen() {
        val (upper, lower) = drawTwoLinesAndLassoTheUpperOne()
        val scale = checkNotNull(canvas()).viewport.scale
        drag(line(500f, 300f, 600f, 500f, steps = 20)) // grab inside the selection
        waitUntil("moved") { strokes().none { it.id == upper.id } && strokes().size == 2 }
        val moved = strokes().first { it.id != lower.id }
        assertThat(moved.points.first().x - upper.points.first().x).isWithin(1f).of(100f / scale)
        assertThat(moved.points.first().y - upper.points.first().y).isWithin(1f).of(200f / scale)
        assertThat(selection()).containsExactly(moved.id) // still selected for further moves
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitUntil("back") { strokes().any { it.points == upper.points } }
    }

    @Test
    fun selectionCanBeDraggedWithAFinger() {
        val (upper, _) = drawTwoLinesAndLassoTheUpperOne()
        val scrollBefore = checkNotNull(canvas()).viewport.scrollYMm
        drag(line(500f, 300f, 500f, 450f, steps = 20), toolType = MotionEvent.TOOL_TYPE_FINGER)
        waitUntil("moved") { strokes().none { it.id == upper.id } && strokes().size == 2 }
        assertThat(checkNotNull(canvas()).viewport.scrollYMm).isEqualTo(scrollBefore) // did not scroll
    }

    @Test
    fun fingerOutsideTheSelectionStillScrolls() {
        drawTwoLinesAndLassoTheUpperOne()
        val view = checkNotNull(canvas())
        drag(line(500f, view.height * 0.8f, 500f, view.height * 0.3f, steps = 20), toolType = MotionEvent.TOOL_TYPE_FINGER, stepMs = 16)
        waitUntil("scrolled") { view.viewport.scrollYMm > 0f }
        assertThat(strokes()).hasSize(2)
    }

    @Test
    fun copyAndPasteSelection() {
        val (upper, _) = drawTwoLinesAndLassoTheUpperOne()
        compose.onNodeWithTag(NoteScreenTags.PASTE).assertIsNotEnabled()
        compose.onNodeWithTag(NoteScreenTags.COPY_SELECTION).performClick()
        val before = strokes().map { it.id }.toSet()
        compose.onNodeWithTag(NoteScreenTags.PASTE).assertIsEnabled().performClick()
        waitForStrokes(3)
        val pasted = strokes().single { it.id !in before }
        assertThat(selection()).containsExactly(pasted.id)
        assertThat(pasted.brush).isEqualTo(upper.brush)
        // Pasted into the middle of the visible page.
        val visible = checkNotNull(canvas()).viewport.visibleDocumentRect
        val cy = (pasted.bounds.top + pasted.bounds.bottom) / 2
        assertThat(cy).isWithin(1f).of((visible.top + visible.bottom) / 2)
    }

    @Test
    fun tappingOutsideClearsTheSelection() {
        drawTwoLinesAndLassoTheUpperOne()
        drag(listOf(900f to 900f, 901f to 900f))
        waitUntil("cleared") { selection().isEmpty() }
        compose.onNodeWithTag(NoteScreenTags.DELETE_SELECTION).assertIsNotEnabled()
    }

    // ---- text boxes -----------------------------------------------------------------------

    private fun editing(): com.stemaker.nibsync.text.TextEditing? {
        var e: com.stemaker.nibsync.text.TextEditing? = null
        scenario.onActivity { e = it.canvasViewModel?.textEditing?.value }
        return e
    }

    private fun textBoxes() = session().state.value.textBoxes

    /** A stylus tap in canvas pixels. */
    private fun tap(x: Float, y: Float) = drag(listOf(x to y, x to y))

    /** Text tool, then a frame dragged from ([x], [y]) canvas pixels, 60 mm wide. */
    private fun newTextBox(x: Float = 200f, y: Float = 300f) {
        compose.onNodeWithTag(NoteScreenTags.TEXT).performClick()
        val scale = checkNotNull(canvas()).viewport.scale
        drag(line(x, y, x + 60f * scale, y + 30f))
        waitUntil("editing a new text box") { editing() != null }
    }

    /**
     * Taps the page away from the boxes, which ends editing; waits until the editor is gone.
     * High up on the right: the lower part of a landscape screen is under the keyboard.
     */
    private fun tapOutside() {
        tap(2600f, 500f)
        waitUntil("editing ended") { editing() == null }
        compose.waitForIdle()
    }

    @Test
    fun textBoxIsTypedFormattedAndStored() {
        newTextBox()
        // The tool bar now formats text.
        compose.onNodeWithTag(TextTags.BOLD).assertExists()
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).assertDoesNotExist()

        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Hallo ")
        compose.onNodeWithTag(TextTags.BOLD).performClick()
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("fett")
        tapOutside()
        // Back to the pen, without a dot from that tap.
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).assertExists()
        assertThat(strokes()).isEmpty()

        val box = textBoxes().single()
        val p = box.text.paragraphs.single()
        assertThat(p.text).isEqualTo("Hallo fett")
        assertThat(p.marks).contains(TextMark(6, 10, MarkStyle.Bold))
        assertThat(box.heightMm).isGreaterThan(3f)
        assertThat(compose.onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes()).isEmpty()

        // It is drawn on the page: dark pixels inside its frame.
        val view = checkNotNull(canvas())
        waitUntil("canvas shows the box") { view.textBoxes.isNotEmpty() }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        scenario.onActivity { view.draw(Canvas(bitmap)) }
        val t = view.viewport.documentToView
        var dark = 0
        for (x in t.mapX(box.x).toInt() until t.mapX(box.x + box.widthMm).toInt() step 2) {
            for (y in t.mapY(box.y).toInt() until t.mapY(box.y + box.heightMm).toInt() step 2) {
                if (Color.red(bitmap.getPixel(x, y)) < 100) dark++
            }
        }
        assertThat(dark).isGreaterThan(20)

        // And survives a restart from disk.
        scenario.close()
        runBlocking { app.container.closeSession(documentId) }
        scenario = launchActivity()
        waitUntil("reloaded") { textBoxes().size == 1 }
        assertThat(textBoxes().single().text).isEqualTo(box.text)
    }

    @Test
    fun fontsSizesAndListsFromTheToolBar() {
        newTextBox()
        compose.onNodeWithTag(TextTags.FONT).performClick()
        compose.onNodeWithTag(TextTags.font(TextFont.HANDWRITING)).performClick()
        compose.onNodeWithTag(TextTags.SIZE).performClick()
        compose.onNodeWithTag(TextTags.size(24f)).performClick()
        compose.onNodeWithTag(TextTags.BULLETS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Punkt")
        tapOutside()
        val p = textBoxes().single().text.paragraphs.single()
        assertThat(p.text).isEqualTo("Punkt")
        assertThat(p.list).isEqualTo(ListKind.BULLET)
        assertThat(p.marks).containsAtLeast(
            TextMark(0, 5, MarkStyle.Font(TextFont.HANDWRITING)),
            TextMark(0, 5, MarkStyle.Size(24f)),
        )
    }

    @Test
    fun penTapOnABoxOpensItAndDeleteRemovesIt() {
        newTextBox()
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Notiz")
        tapOutside()
        val box = textBoxes().single()

        // With the pen selected, a tap on the box opens it (text mode) instead of a dot.
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        val t = checkNotNull(canvas()).viewport.documentToView
        tap(t.mapX(box.x + 2f), t.mapY(box.y + box.heightMm / 2))
        waitUntil("editing the box") { editing()?.boxId == box.id }
        assertThat(strokes()).isEmpty()
        compose.onNodeWithTag(TextTags.DELETE).performClick()
        waitUntil("removed") { textBoxes().isEmpty() && editing() == null }
        // Undo brings it back.
        compose.onNodeWithTag(NoteScreenTags.UNDO).performClick()
        waitUntil("restored") { textBoxes().singleOrNull()?.text == box.text }
    }

    @Test
    fun draggedFrameSetsTheWidth() {
        compose.onNodeWithTag(NoteScreenTags.TEXT).performClick()
        val v = checkNotNull(canvas()).viewport
        drag(line(100f, 200f, 100f + 40f * v.scale, 260f))
        waitUntil("editing") { editing() != null }
        assertThat(editing()!!.widthMm).isWithin(0.5f).of(40f)
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("schmal")
        tapOutside()
        assertThat(textBoxes().single().widthMm).isWithin(0.5f).of(40f)
    }

    @Test
    fun fingerDrawsATextFrameToo() {
        compose.onNodeWithTag(NoteScreenTags.TEXT).performClick()
        val v = checkNotNull(canvas()).viewport
        drag(line(100f, 200f, 100f + 60f * v.scale, 260f), toolType = MotionEvent.TOOL_TYPE_FINGER)
        waitUntil("editing") { editing() != null }
        assertThat(editing()!!.widthMm).isWithin(0.5f).of(60f)
        // The page did not scroll.
        assertThat(checkNotNull(canvas()).viewport).isEqualTo(v)
    }

    @Test
    fun aTapWithTheTextToolMakesABoxOfTheDefaultSize() {
        compose.onNodeWithTag(NoteScreenTags.TEXT).performClick()
        val view = checkNotNull(canvas())
        tap(300f, 300f)
        waitUntil("editing a new text box") { editing() != null }
        val e = checkNotNull(editing())
        val toDoc = view.viewport.viewToDocument
        assertThat(e.widthMm).isEqualTo(com.stemaker.nibsync.model.TextBox.DEFAULT_WIDTH_MM)
        assertThat(e.x).isWithin(0.5f).of(toDoc.mapX(300f))
        assertThat(e.y).isWithin(0.5f).of(toDoc.mapY(300f) - com.stemaker.nibsync.model.TextBox.FIRST_LINE_OFFSET_MM)
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Getippt")
        tapOutside()
        assertThat(textBoxes().single().text.plainText).isEqualTo("Getippt")
    }

    @Test
    fun theOnScreenKeyboardDoesNotHideTheEditedLine() {
        val view = checkNotNull(canvas())
        compose.onNodeWithTag(NoteScreenTags.TEXT).performClick()
        // Near the bottom of the page view, where the keyboard comes up.
        tap(400f, view.height - 120f)
        waitUntil("editing a new text box") { editing() != null }
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Unten")
        fun keyboardShown(): Boolean {
            var shown = false
            scenario.onActivity {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                shown = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            return shown
        }
        waitUntil("keyboard shown", timeoutMs = 10_000) { keyboardShown() }
        // The page scrolled so that the line is above the keyboard (the window got smaller, or
        // the keyboard covers the page's lower part).
        waitUntil("line above the keyboard") {
            val e = editing() ?: return@waitUntil false
            view.viewport.documentToView.mapY(e.y + 6f) < view.height - view.viewport.bottomInsetPx
        }
        tapOutside()
        waitUntil("keyboard gone", timeoutMs = 10_000) { !keyboardShown() && view.viewport.bottomInsetPx == 0f }
        assertThat(textBoxes().single().text.plainText).isEqualTo("Unten")
    }

    @Test
    fun emptyBoxLeavesNothingBehind() {
        newTextBox()
        tapOutside()
        assertThat(textBoxes()).isEmpty()
        compose.onNodeWithTag(NoteScreenTags.UNDO).assertIsNotEnabled()
    }

    @Test
    fun editorSitsExactlyOverTheBox() {
        newTextBox(400f, 400f)
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Position")
        // Three paragraphs: line spacing on the page must match the editor's.
        editing()!!.controller.setContent(com.stemaker.nibsync.model.RichText.plain("Position\nzweite Zeile\ndritte Zeile"))
        tapOutside()
        val box = textBoxes().single()
        val view = checkNotNull(canvas())
        val viewportBefore = view.viewport
        val locBefore = IntArray(2).also { l -> scenario.onActivity { view.getLocationInWindow(l) } }

        val t = viewportBefore.documentToView
        // A finger tap opens it as well.
        drag(listOf(t.mapX(box.x + 2f) to t.mapY(box.y + box.heightMm / 2), t.mapX(box.x + 2f) to t.mapY(box.y + box.heightMm / 2)), toolType = MotionEvent.TOOL_TYPE_FINGER)
        waitUntil("editing the box") { editing()?.boxId == box.id }
        compose.waitForIdle()
        val locAfter = IntArray(2).also { l -> scenario.onActivity { view.getLocationInWindow(l) } }
        val editor = compose.onNodeWithTag(TextTags.EDITOR).fetchSemanticsNode().boundsInWindow
        val expectedLeft = locAfter[0] + view.viewport.documentToView.mapX(box.x)
        val expectedTop = locAfter[1] + view.viewport.documentToView.mapY(box.y)
        // Where the glyphs are: on the page (closed) vs. in the editor (screen shot).
        fun inkBounds(bmp: Bitmap, ox: Int, oy: Int): List<Int> {
            val l0 = ox + t.mapX(box.x).toInt() - 20
            val t0 = oy + t.mapY(box.y).toInt() - 20
            // Only the first letters: the editor's cursor sits after the last one.
            val r0 = ox + t.mapX(box.x + 6f).toInt()
            val b0 = oy + t.mapY(box.y + box.heightMm).toInt() + 20
            var l = Int.MAX_VALUE; var tp = Int.MAX_VALUE; var r = -1; var b = -1
            for (x in l0 until r0) for (y in t0 until b0) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) < 90 && Color.green(c) < 90 && Color.blue(c) < 90) {
                    l = minOf(l, x - ox); r = maxOf(r, x - ox); tp = minOf(tp, y - oy); b = maxOf(b, y - oy)
                }
            }
            return listOf(l, tp, r, b)
        }
        val shot = instrumentation.uiAutomation.takeScreenshot()
        val inEditor = inkBounds(shot, locAfter[0], locAfter[1])
        val closed = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        scenario.onActivity {
            view.editingTextBox = null
            view.draw(Canvas(closed))
        }
        val onPage = inkBounds(closed, 0, 0)
        for (i in 0..3) assertThat(inEditor[i]).isWithin(3).of(onPage[i])
        assertThat(locAfter.toList()).isEqualTo(locBefore.toList())
        // The page does not move. A docked keyboard may make the view shorter, nothing else.
        assertThat(view.viewport.copy(viewHeightPx = viewportBefore.viewHeightPx)).isEqualTo(viewportBefore)
        assertThat(editor.left).isWithin(2f).of(expectedLeft)
        assertThat(editor.top).isWithin(2f).of(expectedTop)
        assertThat(editor.width).isWithin(2f).of(box.widthMm * view.viewport.scale)
        // The stored height comes from the page layout; the editor is as tall.
        assertThat(editor.height).isWithin(3f).of(box.heightMm * view.viewport.scale)
    }

    // ---- pictures and file links -----------------------------------------------------------

    private val redSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="960" height="480"><rect width="960" height="480" fill="#ff0000"/></svg>"""

    private fun viewModel(): com.stemaker.nibsync.canvas.CanvasViewModel {
        var vm: com.stemaker.nibsync.canvas.CanvasViewModel? = null
        scenario.onActivity { vm = it.canvasViewModel }
        return checkNotNull(vm)
    }

    /** Stores [bytes] with the test document and inserts it like the file picker does. */
    private fun insertFile(bytes: ByteArray, type: String, name: String): com.stemaker.nibsync.model.PageElement {
        val sha = runBlocking { app.container.repository.putAsset(documentId, bytes) }
        val draft = checkNotNull(com.stemaker.nibsync.media.MediaImport.draft(com.stemaker.nibsync.model.PickedFile(bytes, type, name), sha, 210f, 105f, 50f))
        var added: com.stemaker.nibsync.model.PageElement? = null
        scenario.onActivity { added = it.canvasViewModel!!.insert(listOf(draft)).single() }
        compose.waitForIdle()
        return checkNotNull(added)
    }

    private fun files() = session().state.value.files

    private fun pixelAt(xMm: Float, yMm: Float): Int {
        val view = checkNotNull(canvas())
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        scenario.onActivity { view.draw(Canvas(bitmap)) }
        val t = view.viewport.documentToView
        return bitmap.getPixel(t.mapX(xMm).toInt(), t.mapY(yMm).toInt())
    }

    @Test
    fun pictureLiesUnderInkIsSelectedByATapAndScales() {
        val picture = insertFile(redSvg.encodeToByteArray(), com.stemaker.nibsync.model.Asset.SVG, "rot.svg")
        // Inserted pictures come selected; a tap on empty paper goes back to the pen.
        waitUntil("selected") { canvas()?.selection == setOf(picture.id) }
        tap(2600f, 1400f)
        waitUntil("back to the pen") { viewModel().tools.value.tool == com.stemaker.nibsync.canvas.Tool.PEN }
        val b = picture.bounds
        waitUntil("picture drawn") { Color.red(pixelAt(b.left + 5f, b.top + 5f)) > 200 && Color.green(pixelAt(b.left + 5f, b.top + 5f)) < 80 }

        // Ink across the picture stays visible on top of it.
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        compose.onNodeWithTag(NoteScreenTags.size(2)).performClick()
        val t = checkNotNull(canvas()).viewport.documentToView
        val midY = t.mapY((b.top + b.bottom) / 2)
        drag(line(t.mapX(b.left - 10f), midY, t.mapX(b.right + 10f), midY))
        waitForStrokes(1)
        waitUntil("overlay handed off") { !checkNotNull(canvas()).hasUnfinishedStrokes() }
        assertThat(Color.red(pixelAt((b.left + b.right) / 2, (b.top + b.bottom) / 2))).isLessThan(80)

        // A pen tap on the picture selects it instead of leaving a dot.
        tap(t.mapX(b.left + 10f), t.mapY(b.top + 10f))
        waitUntil("selected by tap") { canvas()?.selection == setOf(picture.id) }
        assertThat(strokes()).hasSize(1)

        // Dragging the corner handle halfway towards the top left corner halves the picture.
        val v = checkNotNull(canvas()).viewport
        val pad = 2f // selection frame padding
        val hx = v.documentToView.mapX(b.right + pad)
        val hy = v.documentToView.mapY(b.bottom + pad)
        val tx = v.documentToView.mapX(b.left + (b.width + pad) / 2)
        val ty = v.documentToView.mapY(b.top + (b.height + pad) / 2)
        drag(line(hx, hy, tx, ty))
        waitUntil("scaled") { files().single().widthMm < b.width * 0.6f }
        assertThat(files().single().widthMm).isWithin(3f).of(b.width / 2)
        assertThat(files().single().heightMm / files().single().widthMm).isWithin(0.01f).of(b.height / b.width)

        // Tap beside it: back to the pen, nothing selected.
        tap(2600f, 1400f)
        waitUntil("back to the pen") { viewModel().tools.value.tool == com.stemaker.nibsync.canvas.Tool.PEN && canvas()?.selection?.isEmpty() == true }
    }

    @Test
    fun tapOnAPdfLinkOpensIt() {
        val link = insertFile("%PDF-1.4\n%%EOF".encodeToByteArray(), com.stemaker.nibsync.model.Asset.PDF, "Skript.pdf")
        val opened = mutableListOf<com.stemaker.nibsync.model.FileLink>()
        scenario.onActivity { it.canvasViewModel!!.openFile = { l -> opened += l } }
        tap(2600f, 1400f)
        waitUntil("back to the pen") { viewModel().tools.value.tool == com.stemaker.nibsync.canvas.Tool.PEN }
        val t = checkNotNull(canvas()).viewport.documentToView
        tap(t.mapX(link.bounds.left + 5f), t.mapY(link.bounds.top + 5f))
        waitUntil("opened") { opened.isNotEmpty() }
        assertThat(opened.single().fileName).isEqualTo("Skript.pdf")
        assertThat(strokes()).isEmpty()
        // The file is stored with the document.
        assertThat(runBlocking { app.container.repository.hasAsset(documentId, opened.single().asset.sha256) }).isTrue()
    }

    @Test
    fun longPressOnAPdfLinkSelectsIt() {
        val link = insertFile("%PDF-1.4\n%%EOF".encodeToByteArray(), com.stemaker.nibsync.model.Asset.PDF, "Skript.pdf")
        val opened = mutableListOf<com.stemaker.nibsync.model.FileLink>()
        scenario.onActivity { it.canvasViewModel!!.openFile = { l -> opened += l } }
        tap(2600f, 1400f)
        waitUntil("back to the pen") { viewModel().tools.value.tool == com.stemaker.nibsync.canvas.Tool.PEN }
        val t = checkNotNull(canvas()).viewport.documentToView
        val x = t.mapX(link.bounds.left + 5f)
        val y = t.mapY(link.bounds.top + 5f)
        drawLive(listOf(x to y, x to y), holdMs = 800)
        waitUntil("selected") { canvas()?.selection == setOf(link.id) }
        assertThat(opened).isEmpty()
        assertThat(strokes()).isEmpty()
        assertThat(viewModel().tools.value.tool).isEqualTo(com.stemaker.nibsync.canvas.Tool.LASSO)
    }

    // ---- to-dos ---------------------------------------------------------------------------

    private fun todoEdit(): com.stemaker.nibsync.text.TodoEdit? {
        var e: com.stemaker.nibsync.text.TodoEdit? = null
        scenario.onActivity { e = it.canvasViewModel?.todoEditing?.value }
        return e
    }

    private fun todos() = session().state.value.todos

    @Test
    fun handwrittenTodoLiesAboveInkAndIsTickedOffByATap() {
        // A thick black line first; the to-do goes over it.
        compose.onNodeWithTag(NoteScreenTags.pen(BrushFamily.BALLPOINT)).performClick()
        compose.onNodeWithTag(NoteScreenTags.size(2)).performClick()
        val view = checkNotNull(canvas())
        val t = view.viewport.documentToView
        drag(line(t.mapX(20f), t.mapY(32f), t.mapX(150f), t.mapY(32f)))
        waitForStrokes(1)

        // A finger on the tool button picks the keyboard; the settings bar switches to the pen.
        compose.onNodeWithTag(NoteScreenTags.TODO).performClick()
        compose.onNode(hasTestTag(NoteScreenTags.TODO_INK) and hasAnyAncestor(hasTestTag(NoteScreenTags.TOOL_SETTINGS))).performClick()
        waitUntil("pen mode") { viewModel().tools.value.todoInk }
        tap(t.mapX(25f), t.mapY(30f))
        waitUntil("new handwritten to-do") { todoEdit()?.isInk == true }
        val e = checkNotNull(todoEdit())
        val cx = e.x + com.stemaker.nibsync.model.TodoItem.CONTENT_OFFSET_MM
        drag(line(t.mapX(cx + 3f), t.mapY(e.y + 5f), t.mapX(cx + 40f), t.mapY(e.y + 7f)))
        waitUntil("stored with its ink") { todos().singleOrNull()?.content.let { it is com.stemaker.nibsync.model.TodoContent.Ink && it.strokes.size == 1 } }
        // Only the black line is on the page itself.
        assertThat(strokes()).hasSize(1)
        tap(2600f, 500f)
        waitUntil("to-do closed") { todoEdit() == null }
        val item = todos().single()

        // The card covers the line where there is no handwriting.
        waitUntil("drawn") { view.todos.isNotEmpty() }
        val covered = pixelAt(item.x + item.widthMm - 5f, 32f)
        assertThat(Color.red(covered)).isGreaterThan(200)

        // A pen tap on the circle ticks it off; it turns green.
        val c = item.checkBounds
        tap(t.mapX((c.left + c.right) / 2), t.mapY((c.top + c.bottom) / 2))
        waitUntil("done") { session().state.value.isDone(item.taskId) }
        assertThat(strokes()).hasSize(1)
        // Inside the circle, away from the white tick and the rim (red even when open).
        waitUntil("drawn green") { pixelAt((c.left + c.right) / 2 - 1f, (c.top + c.bottom) / 2 - 0.8f).let { Color.green(it) > 100 && Color.red(it) < 100 } }
    }

    @Test
    fun typedTodoWithAFingerAndResized() {
        compose.onNodeWithTag(NoteScreenTags.TODO).performClick()
        waitUntil("keyboard mode") { viewModel().tools.value.let { it.tool == com.stemaker.nibsync.canvas.Tool.TODO && !it.todoInk } }
        val t = checkNotNull(canvas()).viewport.documentToView
        drag(listOf(t.mapX(30f) to t.mapY(30f), t.mapX(30f) to t.mapY(30f)), toolType = MotionEvent.TOOL_TYPE_FINGER)
        waitUntil("new typed to-do") { todoEdit()?.text != null }
        compose.onNodeWithTag(TextTags.EDITOR).performTextInput("Rechnung bezahlen")
        val before = checkNotNull(todoEdit()).widthMm
        compose.onNodeWithTag(NoteScreenTags.RESIZE_TODO).performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(200f, 80f), 300) }
        waitUntil("wider") { checkNotNull(todoEdit()).widthMm > before + 10f }
        tap(2600f, 500f)
        waitUntil("to-do closed") { todoEdit() == null }
        val item = todos().single()
        assertThat((item.content as com.stemaker.nibsync.model.TodoContent.Text).text.plainText).isEqualTo("Rechnung bezahlen")
        assertThat(item.widthMm).isGreaterThan(before + 10f)
        assertThat(item.heightMm).isGreaterThan(com.stemaker.nibsync.model.TodoItem.DEFAULT_HEIGHT_MM)
        assertThat(strokes()).isEmpty()
    }
}
