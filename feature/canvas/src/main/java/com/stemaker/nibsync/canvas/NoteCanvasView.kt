package com.stemaker.nibsync.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withTranslation
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.input.motionprediction.MotionEventPredictor
import com.stemaker.nibsync.ink.BrushMapper
import com.stemaker.nibsync.ink.InkStrokeCache
import com.stemaker.nibsync.ink.StrokeConverter
import com.stemaker.nibsync.ink.toMatrix
import com.stemaker.nibsync.document.erase.EraseGesture
import com.stemaker.nibsync.document.erase.EraseResult
import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.document.select.boundsOf
import com.stemaker.nibsync.model.BackgroundPattern
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.Layer
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.media.MediaRenderer
import com.stemaker.nibsync.model.LinkElement
import com.stemaker.nibsync.text.TextBoxRenderer
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.geometry.Viewport
import com.stemaker.nibsync.shapes.HoldDetector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.ink.strokes.Stroke as InkStroke

/**
 * The endless page. Finished strokes are drawn in [onDraw]; the stroke currently being written
 * is rendered with low latency by an [InProgressStrokesView] overlay and handed over when done.
 *
 * Coordinates: strokes are stored in millimetres ("document space"); [viewport] maps them to
 * view pixels. The stylus writes, fingers scroll and pinch-zoom (see [InputRouting]).
 *
 * Holding the pen still at the end of a stroke asks [onShapeRequested] for a clean shape; if
 * one comes back it replaces the stroke immediately, while the pen is still down.
 */
@SuppressLint("ViewConstructor")
class NoteCanvasView(context: Context) : FrameLayout(context) {

    /** Receives a finished stroke and returns it as stored in the document (or null if rejected). */
    var onStrokeDrafted: (StrokeDraft) -> Stroke? = { null }

    /** Receives the stroke drawn so far when the pen is held still; returns the stored shape or null. */
    var onShapeRequested: (StrokeDraft) -> CommittedEdit? = { null }

    /** Receives the outcome of an erase gesture; returns what was stored, or null. */
    var onEraseFinished: (EraseResult) -> CommittedEdit? = { null }

    var onViewportChanged: (Viewport) -> Unit = {}

    /** A lasso was drawn: polygon x0, y0, x1, y1, ... in document mm. */
    var onLassoFinished: (FloatArray) -> Unit = {}

    /** The selection was dragged by (dx, dy) mm; returns what was stored, or null. */
    var onSelectionMoved: (Float, Float) -> CommittedEdit? = { _, _ -> null }

    /** Starting a new lasso drops the current selection. */
    var onSelectionCleared: () -> Unit = {}

    /** A frame of the given width was dragged with the text tool from (x, y) mm. */
    var onTextFrame: (Float, Float, Float) -> Unit = { _, _, _ -> }

    /**
     * A tap at (x, y) mm: with the text tool, the lasso or a finger anywhere, and with the pen
     * on a text box, picture or file link (instead of leaving a dot).
     */
    var onPageTapped: (Float, Float) -> Unit = { _, _ -> }

    /** A long press at (x, y) mm, e.g. to select a file link or text box for moving. */
    var onPageLongPressed: (Float, Float) -> Unit = { _, _ -> }

    /** The selected picture or link was scaled by the given factor; returns what was stored. */
    var onSelectionResized: (Float) -> CommittedEdit? = { null }

    /** The check circle of the to-do with this task was tapped (any tool but the eraser). */
    var onTodoChecked: (StrokeId) -> Unit = {}

    /** A stroke was written into the open handwritten to-do ([openTodo]); page coordinates. */
    var onTodoInk: (StrokeDraft) -> Unit = {}

    /** Draws pictures and file links; the screen tells it where the document's files are. */
    val mediaRenderer = MediaRenderer(onLoaded = { invalidate() })

    /** Pictures and file links currently shown, back to front. */
    var files: List<FileElement> = emptyList()
        private set

    /** Draws anchors and links to notes. */
    val linkRenderer = LinkRenderer()

    /** Anchors and links to notes currently shown, back to front. */
    var links: List<LinkElement> = emptyList()
        private set

    /** Lays out and draws text boxes; also used by the screen to measure them. */
    val textRenderer = TextBoxRenderer(context)

    /** Text box hidden from the page because the editor shows it, or null. */
    var editingTextBox: StrokeId? = null
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** Text boxes currently shown, back to front. */
    var textBoxes: List<TextBox> = emptyList()
        private set

    /** Draws to-dos; shares the text layout with the text boxes. */
    private val todoRenderer = TodoRenderer(textRenderer)

    /** To-dos currently shown, back to front, and the tasks among them that are done. */
    var todos: List<TodoItem> = emptyList()
        private set
    private var doneTasks: Set<StrokeId> = emptySet()

    /**
     * The to-do being edited, drawn as it is now (its stored version is hidden); null if none.
     * Handwriting in its frame goes to [onTodoInk] while it is not typed ([openTodoTyped]).
     */
    var openTodo: TodoItem? = null
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** The open to-do is typed: an editor over the page shows its text. */
    var openTodoTyped: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** Ids of the selected elements, framed on the page and draggable. */
    var selection: Set<StrokeId> = emptySet()
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    var brush: BrushSpec = BrushSpec(BrushFamily.FOUNTAIN_PEN, Color.BLACK, 0.8f)

    var drawWithFinger: Boolean = false

    /** Shows where pages of [pageFormat] would end, as dashed lines with page numbers. */
    var showPageBreaks: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** Paper pattern drawn on the page behind all strokes. */
    var pageBackground: BackgroundSpec = BackgroundSpec()
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** What the pen does: write, erase (see [eraserMode]) or select with the lasso. */
    var tool: Tool = Tool.PEN
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }
    var eraserMode: EraserMode = EraserMode.OBJECT
    var eraserRadiusMm: Float = 1f

    var pageFormat: PageFormat = PageFormat()
        set(value) {
            if (value == field) return
            field = value
            setViewport(viewport.copy(pageWidthMm = value.widthMm, minPageHeightMm = value.pageHeightMm).clamped())
        }

    var viewport: Viewport = Viewport(
        pageWidthMm = pageFormat.widthMm,
        viewWidthPx = 0f,
        viewHeightPx = 0f,
        minPageHeightMm = pageFormat.pageHeightMm,
    )
        private set

    /** Strokes currently shown, back to front. */
    var strokes: List<Stroke> = emptyList()
        private set

    private val inProgressStrokesView = InProgressStrokesView(context)

    private val renderer = CanvasStrokeRenderer.create()
    private val inkCache = InkStrokeCache()
    private val documentToView = Matrix()
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val pageShadowPaint = Paint().apply { color = 0x22000000 }
    private val deskColor = 0xffe0e0e0.toInt()
    private val pageBreakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xff9e9e9e.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PATTERN_COLOR
        strokeCap = Paint.Cap.ROUND
    }
    private var patternPoints = FloatArray(0)
    private val pageNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff9e9e9e.toInt()
        textSize = 28f
        textAlign = Paint.Align.RIGHT
    }

    private val predictor = MotionEventPredictor.newInstance(this)
    private val brushesInProgress = HashMap<InProgressStrokeId, BrushSpec>()
    private var drawingPointerId = NO_POINTER
    private var drawingStrokeId: InProgressStrokeId? = null
    private var navigating = false

    // Input of the stroke being drawn, in document coordinates, for shape recognition.
    private val capturedPoints = ArrayList<StrokePoint>()
    private var capturedTool = InputTool.UNKNOWN
    private var strokeDownTime = 0L
    private var holdDetector: HoldDetector? = null
    private val holdCheck = Runnable { checkHold() }

    private var eraseGesture: EraseGesture? = null
    private var erasingPointerId = NO_POINTER
    private var eraserX = 0f
    private var eraserY = 0f
    private var previewPieceCounter = 0

    // Lasso path being drawn (document mm) and selection being dragged.
    private var lassoPath: ArrayList<Float>? = null
    private var lassoPointerId = NO_POINTER
    private var movePointerId = NO_POINTER
    private var moveStartX = 0f
    private var moveDownTime = 0L
    private var moveStartY = 0f
    private var moveDx = 0f
    private var moveDy = 0f
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xff1a3fa8.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val lassoDrawPath = android.graphics.Path()

    // Scaling the selected picture or link with its corner handle.
    private var resizePointerId = NO_POINTER
    private var resizeAnchorDist = 0f
    private var resizeFactor = 1f
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff1a3fa8.toInt() }

    // A tap on a to-do's check circle.
    private var checkPointerId = NO_POINTER
    private var checkTask: StrokeId? = null
    private var checkDownX = 0f
    private var checkDownY = 0f

    /** Strokes being written into the open to-do instead of onto the page. */
    private val todoStrokes = HashSet<InProgressStrokeId>()

    // Text tool gesture: where it started (mm) and the frame dragged so far.
    private var textPointerIsTodo = false
    private var textPointerId = NO_POINTER
    private var textPointerIsFinger = false
    private var textStartX = 0f
    private var textStartY = 0f
    private var textFrame: com.stemaker.nibsync.model.geometry.Rect? = null
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xff757575.toInt()
    }

    private val scroller = OverScroller(context)
    private var flingLastX = 0
    private var flingLastY = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        // Finger taps open text boxes and links, select pictures, or end those modes.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!navigating) return false
            val toDoc = viewport.viewToDocument
            onPageTapped(toDoc.mapX(e.x), toDoc.mapY(e.y))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!navigating) return
            val toDoc = viewport.viewToDocument
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onPageLongPressed(toDoc.mapX(e.x), toDoc.mapY(e.y))
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!navigating) return false
            setViewport(viewport.scrollByPx(distanceX, distanceY))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!navigating) return false
            flingLastX = 0
            flingLastY = 0
            scroller.fling(0, 0, -velocityX.roundToInt(), -velocityY.roundToInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!navigating) return false
            setViewport(viewport.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY))
            return true
        }
    })

    init {
        setWillNotDraw(false)
        addView(inProgressStrokesView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgressStrokesView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, InkStroke>) = handOver(strokes)
        })
        inProgressStrokesView.eagerInit()
    }

    /** Shows the given pictures and file links (back to front). Cheap if nothing changed. */
    fun setFiles(elements: List<FileElement>) {
        if (elements === files) return
        files = elements
        updateContentBottom()
        invalidate()
    }

    /** Shows the given anchors and links to notes (back to front). Cheap if nothing changed. */
    fun setLinks(elements: List<LinkElement>) {
        if (elements === links) return
        links = elements
        updateContentBottom()
        invalidate()
    }

    /** Shows the given text boxes (back to front). Cheap if nothing changed. */
    fun setTextBoxes(boxes: List<TextBox>) {
        if (boxes === textBoxes) return
        textBoxes = boxes
        textRenderer.retainOnly(boxes.mapTo(HashSet()) { it.id })
        updateContentBottom()
        invalidate()
    }

    /** Shows the given to-dos (back to front) and which tasks are done. Cheap if nothing changed. */
    fun setTodos(items: List<TodoItem>, done: Set<StrokeId>) {
        if (items === todos && done == doneTasks) return
        todos = items
        doneTasks = done
        todoRenderer.retainOnly(items + listOfNotNull(openTodo))
        updateContentBottom()
        invalidate()
    }

    /** Shows the given document strokes (back to front). Cheap if nothing changed. */
    fun setStrokes(newStrokes: List<Stroke>) {
        if (newStrokes === strokes) return
        strokes = newStrokes
        inkCache.retainOnly(newStrokes.mapTo(HashSet()) { it.id })
        updateContentBottom()
        invalidate()
    }

    private fun updateContentBottom() {
        val bottom = maxOf(
            strokes.maxOfOrNull { it.bounds.bottom } ?: 0f,
            textBoxes.maxOfOrNull { it.bounds.bottom } ?: 0f,
            files.maxOfOrNull { it.bounds.bottom } ?: 0f,
            links.maxOfOrNull { it.bounds.bottom } ?: 0f,
            todos.maxOfOrNull { it.bounds.bottom } ?: 0f,
        )
        if (bottom != viewport.contentBottomMm) setViewport(viewport.withContentBottom(bottom))
    }

    private fun setViewport(v: Viewport) {
        if (v == viewport) return
        viewport = v
        invalidate()
        onViewportChanged(v)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setViewport(viewport.withViewSize(w.toFloat(), h.toFloat()))
        pendingReveal?.let { revealY(it) }
    }

    /** Height (mm) to scroll to once the view has a size. */
    private var pendingReveal: Float? = null

    /** Scrolls so that [yMm] is near the top of the view, e.g. a to-do opened from the list. */
    fun revealY(yMm: Float) {
        if (!viewport.hasSize) {
            pendingReveal = yMm
            return
        }
        pendingReveal = null
        setViewport(viewport.copy(scrollYMm = maxOf(0f, yMm - REVEAL_MARGIN_MM)).clamped())
    }

    override fun onDraw(canvas: Canvas) {
        // Hosts like Compose's AndroidView do not clip children, so stay inside our bounds.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(deskColor)
        if (!viewport.hasSize) return
        val transform = viewport.documentToView
        transform.toMatrix(documentToView)

        val pageLeft = transform.mapX(0f)
        val pageRight = transform.mapX(viewport.pageWidthMm)
        val pageBottom = transform.mapY(viewport.documentHeightMm)
        val pageTop = transform.mapY(0f)
        canvas.drawRect(pageLeft + SHADOW_PX, pageTop + SHADOW_PX, pageRight + SHADOW_PX, pageBottom + SHADOW_PX, pageShadowPaint)
        canvas.drawRect(pageLeft, pageTop, pageRight, pageBottom, pagePaint)
        drawPattern(canvas)

        if (showPageBreaks) drawPageBreaks(canvas, pageLeft, pageRight)

        val visible = viewport.visibleDocumentRect
        val shown = eraseGesture?.visible ?: strokes
        val moving = movePointerId != NO_POINTER
        canvas.withMatrix(documentToView) {
            // Pictures, marker strokes, text and links, ink (see Layer).
            drawFiles(this, Layer.IMAGE, visible, moving)
            var textDrawn = false
            for (stroke in shown) {
                if (!textDrawn && stroke.layer.zIndex > Layer.TEXT.zIndex) {
                    drawFiles(this, Layer.TEXT, visible, moving)
                    drawLinks(this, visible, moving)
                    drawTextBoxes(this, visible, moving)
                    textDrawn = true
                }
                if (moving && stroke.id in selection) {
                    withTranslation(moveDx, moveDy) { renderer.draw(this, inkCache[stroke], documentToView) }
                } else if (stroke.bounds.intersects(visible)) {
                    renderer.draw(this, inkCache[stroke], documentToView)
                }
            }
            if (!textDrawn) {
                drawFiles(this, Layer.TEXT, visible, moving)
                drawLinks(this, visible, moving)
                drawTextBoxes(this, visible, moving)
            }
            drawTodos(this, visible, moving)
        }
        textFrame?.let { f ->
            canvas.drawRect(transform.mapX(f.left), transform.mapY(f.top), transform.mapX(f.right), transform.mapY(f.bottom), selectionPaint)
        }
        if (eraseGesture != null) canvas.drawCircle(eraserX, eraserY, eraserRadiusMm * viewport.scale, eraserPaint)
        selectionBounds()?.let { b ->
            canvas.drawRect(
                transform.mapX(b.left + moveDx), transform.mapY(b.top + moveDy),
                transform.mapX(b.right + moveDx), transform.mapY(b.bottom + moveDy),
                selectionPaint,
            )
            if (resizable() != null && !moving) {
                canvas.drawCircle(transform.mapX(b.right), transform.mapY(b.bottom), handleRadiusPx(), handlePaint)
            }
        }
        lassoPath?.let { path ->
            lassoDrawPath.reset()
            for (i in path.indices step 2) {
                val x = transform.mapX(path[i])
                val y = transform.mapY(path[i + 1])
                if (i == 0) lassoDrawPath.moveTo(x, y) else lassoDrawPath.lineTo(x, y)
            }
            canvas.drawPath(lassoDrawPath, selectionPaint)
        }
    }

    private fun drawFiles(canvas: Canvas, layer: Layer, visible: com.stemaker.nibsync.model.geometry.Rect, moving: Boolean) {
        for (f in files) {
            if (f.layer != layer) continue
            val selected = f.id in selection
            when {
                moving && selected -> canvas.withTranslation(moveDx, moveDy) { mediaRenderer.draw(this, f, viewport.scale) }
                resizePointerId != NO_POINTER && selected -> {
                    val save = canvas.save()
                    canvas.translate(f.x, f.y)
                    canvas.scale(resizeFactor, resizeFactor)
                    canvas.translate(-f.x, -f.y)
                    mediaRenderer.draw(canvas, f, viewport.scale * resizeFactor)
                    canvas.restoreToCount(save)
                }
                f.bounds.intersects(visible) -> mediaRenderer.draw(canvas, f, viewport.scale)
            }
        }
    }

    private fun drawLinks(canvas: Canvas, visible: com.stemaker.nibsync.model.geometry.Rect, moving: Boolean) {
        for (link in links) {
            if (moving && link.id in selection) {
                canvas.withTranslation(moveDx, moveDy) { linkRenderer.draw(this, link, viewport.scale) }
            } else if (link.bounds.intersects(visible)) {
                linkRenderer.draw(canvas, link, viewport.scale)
            }
        }
    }

    private fun drawTextBoxes(canvas: Canvas, visible: com.stemaker.nibsync.model.geometry.Rect, moving: Boolean) {
        for (box in textBoxes) {
            if (box.id == editingTextBox) continue
            if (moving && box.id in selection) {
                canvas.withTranslation(moveDx, moveDy) { textRenderer.draw(this, box) }
            } else if (box.bounds.intersects(visible)) {
                textRenderer.draw(canvas, box)
            }
        }
    }

    /** To-dos on top of everything; the open one as it is being edited. */
    private fun drawTodos(canvas: Canvas, visible: com.stemaker.nibsync.model.geometry.Rect, moving: Boolean) {
        val open = openTodo
        for (item in todos) {
            if (item.id == open?.id) continue
            val done = item.taskId in doneTasks
            if (moving && item.id in selection) {
                canvas.withTranslation(moveDx, moveDy) { todoRenderer.draw(this, item, done, documentToView.withTranslation(moveDx, moveDy)) }
            } else if (item.bounds.intersects(visible)) {
                todoRenderer.draw(canvas, item, done, documentToView)
            }
        }
        if (open != null) todoRenderer.draw(canvas, open, open.taskId in doneTasks, documentToView, content = !openTodoTyped, editing = true)
    }

    private fun Matrix.withTranslation(dx: Float, dy: Float) = Matrix(this).apply { preTranslate(dx, dy) }

    /** The to-do whose check circle is at view pixel ([x], [y]) (a little generous), or null. */
    private fun todoCheckAt(x: Float, y: Float): TodoItem? {
        val toDoc = viewport.viewToDocument
        val dx = toDoc.mapX(x)
        val dy = toDoc.mapY(y)
        val reach = CHECK_REACH_DP * resources.displayMetrics.density / viewport.scale
        return (todos + listOfNotNull(openTodo)).lastOrNull { t ->
            val c = t.checkBounds
            dx >= c.left - reach && dx <= c.right + reach && dy >= c.top - reach && dy <= c.bottom + reach
        }
    }

    /** Lines or dots of [pageBackground] on the visible part of the page. */
    private fun drawPattern(canvas: Canvas) {
        val spec = pageBackground
        val scale = viewport.scale
        // Zoomed out so far that the pattern would turn into a grey wash: leave the paper plain.
        if (!BackgroundPattern.hasRows(spec) || spec.spacingMm * scale < MIN_PATTERN_SPACING_PX) return
        val transform = viewport.documentToView
        val visible = viewport.visibleDocumentRect
        val top = maxOf(0f, visible.top)
        val bottom = minOf(viewport.documentHeightMm, visible.bottom)
        val rows = BackgroundPattern.rows(spec, top, bottom)
        val columns = BackgroundPattern.columns(spec, viewport.pageWidthMm).filter { it >= visible.left && it <= visible.right }
        if (BackgroundPattern.isDotted(spec)) {
            patternPaint.strokeWidth = maxOf(2f, DOT_DIAMETER_MM * scale)
            val needed = rows.size * columns.size * 2
            if (patternPoints.size < needed) patternPoints = FloatArray(needed)
            var i = 0
            for (y in rows) {
                val vy = transform.mapY(y)
                for (x in columns) {
                    patternPoints[i++] = transform.mapX(x)
                    patternPoints[i++] = vy
                }
            }
            canvas.drawPoints(patternPoints, 0, i, patternPaint)
        } else {
            patternPaint.strokeWidth = maxOf(1f, LINE_WIDTH_MM * scale)
            val left = transform.mapX(0f)
            val right = transform.mapX(viewport.pageWidthMm)
            for (y in rows) transform.mapY(y).let { canvas.drawLine(left, it, right, it, patternPaint) }
            val vTop = transform.mapY(top)
            val vBottom = transform.mapY(bottom)
            for (x in columns) transform.mapX(x).let { canvas.drawLine(it, vTop, it, vBottom, patternPaint) }
        }
    }

    private fun drawPageBreaks(canvas: Canvas, left: Float, right: Float) {
        val transform = viewport.documentToView
        val pageHeight = viewport.pageHeightMmOrNull() ?: return
        val visible = viewport.visibleDocumentRect
        var page = maxOf(1, (visible.top / pageHeight).toInt())
        while (page * pageHeight <= minOf(visible.bottom, viewport.documentHeightMm)) {
            val y = transform.mapY(page * pageHeight)
            canvas.drawLine(left, y, right, y, pageBreakPaint)
            // Number of the page that starts below the line.
            canvas.drawText("${page + 1}", right - pageNumberPaint.textSize, y + pageNumberPaint.textSize * 1.3f, pageNumberPaint)
            page++
        }
    }

    private fun Viewport.pageHeightMmOrNull(): Float? = pageFormat.pageHeightMm.takeIf { it > 0f }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - flingLastX
            val dy = scroller.currY - flingLastY
            flingLastX = scroller.currX
            flingLastY = scroller.currY
            val next = viewport.scrollByPx(dx.toFloat(), dy.toFloat())
            if (next == viewport) scroller.forceFinished(true) else setViewport(next)
            postInvalidateOnAnimation()
        }
    }

    // All input is handled here; the in-progress overlay only renders.
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel(event)
        }
        // The detectors see every event, so navigation can also start with a second finger
        // (text tool); their callbacks only act while navigating.
        scaleGestures.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            navigating = false
        }
        return true
    }

    private fun onPointerDown(event: MotionEvent) {
        val index = event.actionIndex
        val toolType = event.getToolType(index)
        val action = InputRouting.actionFor(toolType, drawWithFinger, tool)
        // Text tool: a second finger turns a finger's frame gesture into scrolling/zooming.
        if (textPointerId != NO_POINTER && textPointerIsFinger && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            finishTextFrame(null, commit = false)
            navigating = true
            return
        }
        val busy = drawingPointerId != NO_POINTER || erasingPointerId != NO_POINTER ||
            lassoPointerId != NO_POINTER || movePointerId != NO_POINTER || textPointerId != NO_POINTER ||
            resizePointerId != NO_POINTER || checkPointerId != NO_POINTER
        // A to-do's check circle reacts to every tool but the eraser.
        if (!busy && event.actionMasked == MotionEvent.ACTION_DOWN && tool != Tool.ERASER && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            todoCheckAt(event.getX(index), event.getY(index))?.let { hit ->
                checkPointerId = event.getPointerId(index)
                checkTask = hit.taskId
                checkDownX = event.getX(index)
                checkDownY = event.getY(index)
                return
            }
        }
        // Stylus or finger on the corner handle scales a selected picture or link.
        if (!busy && event.actionMasked == MotionEvent.ACTION_DOWN && toolType != MotionEvent.TOOL_TYPE_ERASER &&
            hitsResizeHandle(event.getX(index), event.getY(index))
        ) {
            startResize(event, index)
            return
        }
        // Stylus or finger on the selection drags it.
        if (!busy && event.actionMasked == MotionEvent.ACTION_DOWN && toolType != MotionEvent.TOOL_TYPE_ERASER &&
            hitsSelection(event.getX(index), event.getY(index))
        ) {
            startMove(event, index)
            return
        }
        if (action == InputRouting.Action.TODO && !busy) {
            if (navigating) {
                navigating = false
                scroller.forceFinished(true)
            }
            val toDoc = viewport.viewToDocument
            val open = openTodo
            if (open != null && !openTodoTyped && open.bounds.contains(toDoc.mapX(event.getX(index)), toDoc.mapY(event.getY(index)))) {
                // Handwriting into the open to-do.
                startStroke(event, index)
                drawingStrokeId?.let { todoStrokes += it }
            } else {
                // A tap: open a to-do or start a new one (see onPageTapped).
                startTextFrame(event, index, todo = true)
            }
        } else if (action == InputRouting.Action.TEXT && !busy) {
            if (navigating) {
                navigating = false
                scroller.forceFinished(true)
            }
            startTextFrame(event, index)
        } else if (action == InputRouting.Action.SELECT && !busy) {
            if (navigating) {
                navigating = false
                scroller.forceFinished(true)
            }
            startLasso(event, index)
        } else if (action == InputRouting.Action.ERASE && !busy) {
            if (navigating) {
                navigating = false
                scroller.forceFinished(true)
            }
            startErase(event, index)
        } else if (action == InputRouting.Action.DRAW && !busy) {
            // A pen landing during a finger gesture (e.g. resting palm) takes over.
            if (navigating) {
                navigating = false
                scroller.forceFinished(true)
            }
            startStroke(event, index)
        } else if (action == InputRouting.Action.NAVIGATE && !busy &&
            event.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            navigating = true
        }
    }

    private fun startStroke(event: MotionEvent, pointerIndex: Int) {
        requestUnbufferedDispatch(event)
        val pointerId = event.getPointerId(pointerIndex)
        val spec = brush
        val motionEventToDocument = viewport.viewToDocument.toMatrix()
        val id = inProgressStrokesView.startStroke(event, pointerId, BrushMapper.toInk(spec), motionEventToDocument)
        brushesInProgress[id] = spec
        drawingPointerId = pointerId
        drawingStrokeId = id

        capturedPoints.clear()
        capturedTool = toolOf(event.getToolType(pointerIndex))
        strokeDownTime = event.eventTime
        holdDetector = HoldDetector(toleranceMm = HOLD_TOLERANCE_DP * resources.displayMetrics.density / viewport.scale)
        capture(event, pointerIndex)
    }

    private fun capture(event: MotionEvent, pointerIndex: Int) {
        val toDoc = viewport.viewToDocument
        val detector = holdDetector ?: return
        fun add(x: Float, y: Float, time: Long, pressure: Float, tilt: Float, orientation: Float) {
            val p = StrokePoint(
                x = toDoc.mapX(x),
                y = toDoc.mapY(y),
                timeMs = time - strokeDownTime,
                pressure = pressure.coerceIn(0f, 1f),
                tiltRad = if (capturedTool == InputTool.STYLUS) tilt else StrokePoint.NO_VALUE,
                orientationRad = if (capturedTool == InputTool.STYLUS) normalizeOrientation(orientation) else StrokePoint.NO_VALUE,
            )
            capturedPoints += p
            detector.onSample(p.x, p.y, time)
        }
        for (h in 0 until event.historySize) {
            add(
                event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h), event.getHistoricalEventTime(h),
                event.getHistoricalPressure(pointerIndex, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h),
            )
        }
        add(
            event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime, event.getPressure(pointerIndex),
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex), event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex),
        )
        removeCallbacks(holdCheck)
        postDelayed(holdCheck, (detector.holdDeadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun checkHold() {
        val id = drawingStrokeId ?: return
        val detector = holdDetector ?: return
        // No shapes or long presses while writing a to-do.
        if (id in todoStrokes) return
        if (!detector.consumeHold(SystemClock.uptimeMillis())) return
        // Pen held still on a text box, picture or link: a long press, not a shape.
        val pressed = pressedElement()
        if (pressed != null) {
            inProgressStrokesView.cancelStroke(id)
            brushesInProgress.remove(id)
            clearDrawingPointer()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onPageLongPressed(pressed.first, pressed.second)
            return
        }
        val spec = brushesInProgress[id] ?: return
        // Leave out the samples taken while resting; they only add jitter at the end.
        val points = capturedPoints.subList(0, detector.anchorSampleCount.coerceAtMost(capturedPoints.size)).toList()
        val shape = onShapeRequested(StrokeDraft(spec, capturedTool, points)) ?: return
        inProgressStrokesView.cancelStroke(id)
        brushesInProgress.remove(id)
        clearDrawingPointer() // the rest of this gesture is ignored
        show(shape.added, removed = shape.removed)
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    private fun selectionBounds(): com.stemaker.nibsync.model.geometry.Rect? {
        if (selection.isEmpty()) return null
        val selected = strokes.filter { it.id in selection } + textBoxes.filter { it.id in selection } +
            todos.filter { it.id in selection } + links.filter { it.id in selection } + files.filter { it.id in selection }.map { f ->
                if (resizePointerId != NO_POINTER) scaledForPreview(f) else f
            }
        val b = boundsOf(selected) ?: return null
        return com.stemaker.nibsync.model.geometry.Rect(b.left - SELECTION_PADDING_MM, b.top - SELECTION_PADDING_MM, b.right + SELECTION_PADDING_MM, b.bottom + SELECTION_PADDING_MM)
    }

    /** The selected picture or link, if it is the whole selection (then it can be scaled). */
    private fun resizable(): FileElement? = files.singleOrNull { it.id in selection }?.takeIf { selection.size == 1 }

    private fun scaledForPreview(f: FileElement): FileElement = when (f) {
        is ImageElement -> f.copy(widthMm = f.widthMm * resizeFactor, heightMm = f.heightMm * resizeFactor)
        is FileLink -> f.copy(widthMm = f.widthMm * resizeFactor, heightMm = f.heightMm * resizeFactor)
    }

    private fun handleRadiusPx() = HANDLE_RADIUS_DP * resources.displayMetrics.density

    private fun hitsResizeHandle(x: Float, y: Float): Boolean {
        if (resizable() == null) return false
        val b = selectionBounds() ?: return false
        val t = viewport.documentToView
        val reach = handleRadiusPx() * 2
        return abs(x - t.mapX(b.right)) <= reach && abs(y - t.mapY(b.bottom)) <= reach
    }

    private fun startResize(event: MotionEvent, pointerIndex: Int) {
        val f = resizable() ?: return
        val toDoc = viewport.viewToDocument
        resizePointerId = event.getPointerId(pointerIndex)
        resizeAnchorDist = hypot(toDoc.mapX(event.getX(pointerIndex)) - f.x, toDoc.mapY(event.getY(pointerIndex)) - f.y)
        resizeFactor = 1f
    }

    private fun updateResize(event: MotionEvent, pointerIndex: Int) {
        val f = resizable() ?: return
        val toDoc = viewport.viewToDocument
        val dist = hypot(toDoc.mapX(event.getX(pointerIndex)) - f.x, toDoc.mapY(event.getY(pointerIndex)) - f.y)
        if (resizeAnchorDist > 0f) resizeFactor = maxOf(MIN_RESIZE_MM / f.widthMm, dist / resizeAnchorDist)
        invalidate()
    }

    private fun finishResize(event: MotionEvent?, commit: Boolean) {
        if (resizePointerId == NO_POINTER) return
        if (commit && event != null) {
            val index = event.findPointerIndex(resizePointerId)
            if (index >= 0) updateResize(event, index)
        }
        val factor = resizeFactor
        resizePointerId = NO_POINTER
        resizeFactor = 1f
        if (commit && abs(factor - 1f) > 0.01f) {
            onSelectionResized(factor)?.let {
                show(it.added, it.removed)
                selection = it.added.mapTo(LinkedHashSet()) { e -> e.id }
            }
        }
        invalidate()
    }

    private fun hitsSelection(x: Float, y: Float): Boolean {
        val b = selectionBounds() ?: return false
        val toDoc = viewport.viewToDocument
        return b.contains(toDoc.mapX(x), toDoc.mapY(y))
    }

    private fun startTextFrame(event: MotionEvent, pointerIndex: Int, todo: Boolean = false) {
        val toDoc = viewport.viewToDocument
        textPointerIsTodo = todo
        textPointerId = event.getPointerId(pointerIndex)
        textPointerIsFinger = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_FINGER
        textStartX = toDoc.mapX(event.getX(pointerIndex))
        textStartY = toDoc.mapY(event.getY(pointerIndex))
        textFrame = null
    }

    private fun updateTextFrame(event: MotionEvent, pointerIndex: Int) {
        // The to-do tool only taps; it draws no frame.
        if (textPointerIsTodo) return
        val toDoc = viewport.viewToDocument
        val x = toDoc.mapX(event.getX(pointerIndex))
        val y = toDoc.mapY(event.getY(pointerIndex))
        // Below the tap tolerance it is still a tap: no frame.
        val tolerance = TEXT_TAP_TOLERANCE_DP * resources.displayMetrics.density / viewport.scale
        textFrame = if (textFrame == null && abs(x - textStartX) < tolerance && abs(y - textStartY) < tolerance) {
            null
        } else {
            com.stemaker.nibsync.model.geometry.Rect(minOf(textStartX, x), minOf(textStartY, y), maxOf(textStartX, x), maxOf(textStartY, y))
        }
        invalidate()
    }

    private fun finishTextFrame(event: MotionEvent?, commit: Boolean) {
        if (textPointerId == NO_POINTER) return
        if (commit && event != null) {
            val index = event.findPointerIndex(textPointerId)
            if (index >= 0) updateTextFrame(event, index)
        }
        val frame = textFrame
        textPointerId = NO_POINTER
        textFrame = null
        invalidate()
        if (!commit) return
        if (textPointerIsTodo) {
            textPointerIsTodo = false
            onPageTapped(textStartX, textStartY)
            return
        }
        if (frame == null) onPageTapped(textStartX, textStartY) else onTextFrame(frame.left, frame.top, frame.width)
    }

    private fun startMove(event: MotionEvent, pointerIndex: Int) {
        val toDoc = viewport.viewToDocument
        movePointerId = event.getPointerId(pointerIndex)
        moveStartX = toDoc.mapX(event.getX(pointerIndex))
        moveStartY = toDoc.mapY(event.getY(pointerIndex))
        moveDownTime = event.eventTime
        moveDx = 0f
        moveDy = 0f
    }

    private fun startLasso(event: MotionEvent, pointerIndex: Int) {
        lassoPointerId = event.getPointerId(pointerIndex)
        lassoPath = ArrayList()
        selection = emptySet()
        onSelectionCleared()
        extendLasso(event, pointerIndex)
    }

    private fun extendLasso(event: MotionEvent, pointerIndex: Int) {
        val path = lassoPath ?: return
        val toDoc = viewport.viewToDocument
        for (h in 0 until event.historySize) {
            path += toDoc.mapX(event.getHistoricalX(pointerIndex, h))
            path += toDoc.mapY(event.getHistoricalY(pointerIndex, h))
        }
        path += toDoc.mapX(event.getX(pointerIndex))
        path += toDoc.mapY(event.getY(pointerIndex))
        invalidate()
    }

    private fun finishSelectionGesture(event: MotionEvent?, commit: Boolean) {
        lassoPath?.let { path ->
            lassoPath = null
            lassoPointerId = NO_POINTER
            if (commit) {
                // A lasso that hardly moved is a tap.
                val tolerance = TEXT_TAP_TOLERANCE_DP * resources.displayMetrics.density / viewport.scale
                val isTap = (path.indices step 2).all { abs(path[it] - path[0]) <= tolerance && abs(path[it + 1] - path[1]) <= tolerance }
                if (isTap) onPageTapped(path[0], path[1]) else onLassoFinished(path.toFloatArray())
            }
        }
        if (movePointerId != NO_POINTER) {
            if (commit && event != null) {
                val index = event.findPointerIndex(movePointerId)
                if (index >= 0) updateMove(event, index)
            }
            movePointerId = NO_POINTER
            val dx = moveDx
            val dy = moveDy
            moveDx = 0f
            moveDy = 0f
            val tolerance = TEXT_TAP_TOLERANCE_DP * resources.displayMetrics.density / viewport.scale
            val tapped = commit && event != null && event.eventTime - moveDownTime <= TAP_MAX_MS &&
                abs(dx) <= tolerance && abs(dy) <= tolerance && links.any { it.bounds.contains(moveStartX, moveStartY) }
            if (tapped) {
                // A short tap on a selected link or anchor follows or renames it.
                onPageTapped(moveStartX, moveStartY)
            } else if (commit && (dx * dx + dy * dy) > MIN_MOVE_MM * MIN_MOVE_MM) {
                onSelectionMoved(dx, dy)?.let {
                    show(it.added, it.removed)
                    selection = it.added.mapTo(LinkedHashSet()) { s -> s.id }
                }
            }
        }
        invalidate()
    }

    private fun updateMove(event: MotionEvent, pointerIndex: Int) {
        val toDoc = viewport.viewToDocument
        moveDx = toDoc.mapX(event.getX(pointerIndex)) - moveStartX
        moveDy = toDoc.mapY(event.getY(pointerIndex)) - moveStartY
        invalidate()
    }

    private fun startErase(event: MotionEvent, pointerIndex: Int) {
        erasingPointerId = event.getPointerId(pointerIndex)
        eraseGesture = EraseGesture(eraserMode, eraserRadiusMm, strokes) { StrokeId("erase-preview-${previewPieceCounter++}") }
        eraseAlong(event, pointerIndex)
    }

    private fun eraseAlong(event: MotionEvent, pointerIndex: Int) {
        val gesture = eraseGesture ?: return
        val toDoc = viewport.viewToDocument
        for (h in 0 until event.historySize) {
            gesture.moveTo(toDoc.mapX(event.getHistoricalX(pointerIndex, h)), toDoc.mapY(event.getHistoricalY(pointerIndex, h)))
        }
        eraserX = event.getX(pointerIndex)
        eraserY = event.getY(pointerIndex)
        gesture.moveTo(toDoc.mapX(eraserX), toDoc.mapY(eraserY))
        invalidate()
    }

    private fun finishErase(commit: Boolean) {
        val gesture = eraseGesture ?: return
        eraseGesture = null
        erasingPointerId = NO_POINTER
        if (commit && gesture.hasChanges) {
            onEraseFinished(gesture.result())?.let { show(it.added, it.removed) }
        }
        invalidate()
    }

    private fun onMove(event: MotionEvent) {
        if (checkPointerId != NO_POINTER) return
        if (resizePointerId != NO_POINTER) {
            val index = event.findPointerIndex(resizePointerId)
            if (index >= 0) updateResize(event, index)
            return
        }
        if (textPointerId != NO_POINTER) {
            val index = event.findPointerIndex(textPointerId)
            if (index >= 0) updateTextFrame(event, index)
            return
        }
        if (lassoPath != null) {
            val index = event.findPointerIndex(lassoPointerId)
            if (index >= 0) extendLasso(event, index)
            return
        }
        if (movePointerId != NO_POINTER) {
            val index = event.findPointerIndex(movePointerId)
            if (index >= 0) updateMove(event, index)
            return
        }
        if (eraseGesture != null) {
            val index = event.findPointerIndex(erasingPointerId)
            if (index >= 0) eraseAlong(event, index)
            return
        }
        val id = drawingStrokeId ?: return
        if (event.findPointerIndex(drawingPointerId) < 0) return
        capture(event, event.findPointerIndex(drawingPointerId))
        val predicted = predictor.predict()
        try {
            inProgressStrokesView.addToStroke(event, drawingPointerId, id, predicted)
        } finally {
            predicted?.recycle()
        }
    }

    private fun onPointerUp(event: MotionEvent) {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerId == checkPointerId) {
            val task = checkTask
            checkPointerId = NO_POINTER
            checkTask = null
            val reach = CHECK_REACH_DP * resources.displayMetrics.density
            val stayed = abs(event.getX(event.actionIndex) - checkDownX) <= reach && abs(event.getY(event.actionIndex) - checkDownY) <= reach
            if (task != null && stayed && !InputRouting.isCanceled(event)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onTodoChecked(task)
            }
            return
        }
        if (pointerId == resizePointerId) {
            finishResize(event, commit = !InputRouting.isCanceled(event))
            return
        }
        if (pointerId == textPointerId) {
            finishTextFrame(event, commit = !InputRouting.isCanceled(event))
            return
        }
        if (pointerId == lassoPointerId || pointerId == movePointerId) {
            finishSelectionGesture(event, commit = !InputRouting.isCanceled(event))
            return
        }
        if (eraseGesture != null) {
            if (event.getPointerId(event.actionIndex) == erasingPointerId) {
                eraseAlong(event, event.actionIndex)
                finishErase(commit = !InputRouting.isCanceled(event))
            }
            return
        }
        val id = drawingStrokeId ?: return
        if (event.getPointerId(event.actionIndex) != drawingPointerId) return
        // A short tap of the pen on a text box, picture or link acts on it instead of leaving a dot.
        val tapped = if (id in todoStrokes) null else tappedElement(event)
        if (tapped != null) {
            inProgressStrokesView.cancelStroke(id, event)
            brushesInProgress.remove(id)
            clearDrawingPointer()
            onPageTapped(tapped.first, tapped.second)
            return
        }
        if (InputRouting.isCanceled(event)) {
            inProgressStrokesView.cancelStroke(id, event)
            brushesInProgress.remove(id)
        } else {
            inProgressStrokesView.finishStroke(event, drawingPointerId, id)
        }
        clearDrawingPointer()
    }

    private fun onCancel(event: MotionEvent) {
        checkPointerId = NO_POINTER
        checkTask = null
        finishResize(null, commit = false)
        finishTextFrame(null, commit = false)
        finishErase(commit = false)
        finishSelectionGesture(null, commit = false)
        drawingStrokeId?.let {
            inProgressStrokesView.cancelStroke(it, event)
            brushesInProgress.remove(it)
            todoStrokes.remove(it)
        }
        clearDrawingPointer()
    }

    /** Document point of a pen tap on a text box, picture or link that just ended, or null. */
    private fun tappedElement(event: MotionEvent): Pair<Float, Float>? {
        if (event.eventTime - strokeDownTime > TAP_MAX_MS) return null
        return pressedElement()
    }

    /**
     * Where the pen touches a text box, picture or link, if it has stayed there (within the
     * tap tolerance) since it came down; else null.
     */
    private fun pressedElement(): Pair<Float, Float>? {
        val first = capturedPoints.firstOrNull() ?: return null
        val tolerance = TEXT_TAP_TOLERANCE_DP * resources.displayMetrics.density / viewport.scale
        if (capturedPoints.any { abs(it.x - first.x) > tolerance || abs(it.y - first.y) > tolerance }) return null
        val onElement = textBoxes.any { it.bounds.contains(first.x, first.y) } || files.any { it.bounds.contains(first.x, first.y) } ||
            links.any { it.bounds.contains(first.x, first.y) } || todos.any { it.bounds.contains(first.x, first.y) }
        return if (onElement) first.x to first.y else null
    }

    private fun clearDrawingPointer() {
        drawingPointerId = NO_POINTER
        drawingStrokeId = null
        holdDetector = null
        removeCallbacks(holdCheck)
    }

    /** True while a stroke is being written or not yet handed over from the overlay. */
    fun hasUnfinishedStrokes(): Boolean = inProgressStrokesView.hasUnfinishedStrokes()

    private fun handOver(strokes: Map<InProgressStrokeId, InkStroke>) {
        val added = ArrayList<Stroke>(strokes.size)
        for ((id, ink) in strokes) {
            val spec = brushesInProgress.remove(id) ?: continue
            if (todoStrokes.remove(id)) {
                // Into the open to-do: it is redrawn from its new content.
                onTodoInk(StrokeConverter.toDraft(ink, spec))
                continue
            }
            val stroke = onStrokeDrafted(StrokeConverter.toDraft(ink, spec)) ?: continue
            inkCache.put(stroke.id, ink)
            added += stroke
        }
        // Show the strokes in the same frame the overlay drops them to avoid flicker.
        show(added)
        inProgressStrokesView.removeFinishedStrokes(strokes.keys)
    }

    /**
     * Adds just committed strokes to the display right away. The document state will deliver
     * the same list again; the cache keeps that cheap.
     */
    private fun show(added: List<PageElement>, removed: Set<StrokeId> = emptySet()) {
        if (added.isEmpty() && removed.isEmpty()) return
        val known = strokes.mapTo(HashSet()) { it.id }
        val merged = strokes.filter { it.id !in removed } + added.filterIsInstance<Stroke>().filter { it.id !in known }
        strokes = merged.sortedBy { it.layer.zIndex } // stable: keeps creation order per layer
        val knownBoxes = textBoxes.mapTo(HashSet()) { it.id }
        textBoxes = textBoxes.filter { it.id !in removed } + added.filterIsInstance<TextBox>().filter { it.id !in knownBoxes }
        val knownFiles = files.mapTo(HashSet()) { it.id }
        files = files.filter { it.id !in removed } + added.filterIsInstance<FileElement>().filter { it.id !in knownFiles }
        val knownLinks = links.mapTo(HashSet()) { it.id }
        links = links.filter { it.id !in removed } + added.filterIsInstance<LinkElement>().filter { it.id !in knownLinks }
        val knownTodos = todos.mapTo(HashSet()) { it.id }
        todos = todos.filter { it.id !in removed } + added.filterIsInstance<TodoItem>().filter { it.id !in knownTodos }
        updateContentBottom()
        invalidate()
    }

    private fun toolOf(toolType: Int): InputTool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> InputTool.STYLUS
        MotionEvent.TOOL_TYPE_FINGER -> InputTool.TOUCH
        MotionEvent.TOOL_TYPE_MOUSE -> InputTool.MOUSE
        else -> InputTool.UNKNOWN
    }

    /** Android reports orientation in -pi..pi; the model uses 0..2pi. */
    private fun normalizeOrientation(rad: Float): Float =
        if (rad < 0f) rad + (2 * Math.PI).toFloat() else rad

    private companion object {
        const val NO_POINTER = -1
        const val SHADOW_PX = 4f

        const val PATTERN_COLOR = 0xffb4c6e2.toInt()
        const val LINE_WIDTH_MM = 0.15f
        const val DOT_DIAMETER_MM = 0.45f

        /** Below this distance between lines the pattern is not drawn. */
        const val MIN_PATTERN_SPACING_PX = 6f

        /** Space between the selected strokes and the selection frame, mm. */
        const val SELECTION_PADDING_MM = 2f

        /** Smaller drags of the selection are treated as taps. */
        const val MIN_MOVE_MM = 0.2f

        /** A text tool gesture shorter than this (dp on screen) is a tap. */
        const val TEXT_TAP_TOLERANCE_DP = 8f

        /** Space above a revealed spot, mm. */
        const val REVEAL_MARGIN_MM = 15f

        /** How far beside a to-do's check circle a tap still hits it, dp. */
        const val CHECK_REACH_DP = 6f

        /** Radius of the scale handle of a selected picture, dp. */
        const val HANDLE_RADIUS_DP = 10f

        /** Pictures are not scaled below this width while dragging the handle, mm. */
        const val MIN_RESIZE_MM = 5f

        /** A pen touch at most this long (and short in distance) is a tap, not a dot. */
        const val TAP_MAX_MS = 300L

        /** How far the pen may wander while "holding still", in dp on screen. */
        const val HOLD_TOLERANCE_DP = 4f
    }
}
