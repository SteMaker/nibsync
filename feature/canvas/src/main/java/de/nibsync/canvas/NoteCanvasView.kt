package de.nibsync.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.core.graphics.withMatrix
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.input.motionprediction.MotionEventPredictor
import de.nibsync.ink.BrushMapper
import de.nibsync.ink.InkStrokeCache
import de.nibsync.ink.StrokeConverter
import de.nibsync.ink.toMatrix
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.PageFormat
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.geometry.Viewport
import kotlin.math.roundToInt
import androidx.ink.strokes.Stroke as InkStroke

/**
 * The endless page. Finished strokes are drawn in [onDraw]; the stroke currently being written
 * is rendered with low latency by an [InProgressStrokesView] overlay and handed over when done.
 *
 * Coordinates: strokes are stored in millimetres ("document space"); [viewport] maps them to
 * view pixels. The stylus writes, fingers scroll and pinch-zoom (see [InputRouting]).
 */
@SuppressLint("ViewConstructor")
class NoteCanvasView(context: Context) : FrameLayout(context) {

    /** Receives a finished stroke and returns it as stored in the document (or null if rejected). */
    var onStrokeDrafted: (StrokeDraft) -> Stroke? = { null }

    var onViewportChanged: (Viewport) -> Unit = {}

    var brush: BrushSpec = BrushSpec(BrushFamily.FOUNTAIN_PEN, Color.BLACK, 0.8f)

    var drawWithFinger: Boolean = false

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

    private val predictor = MotionEventPredictor.newInstance(this)
    private val brushesInProgress = HashMap<InProgressStrokeId, BrushSpec>()
    private var drawingPointerId = NO_POINTER
    private var drawingStrokeId: InProgressStrokeId? = null
    private var navigating = false

    private val scroller = OverScroller(context)
    private var flingLastX = 0
    private var flingLastY = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            setViewport(viewport.scrollByPx(distanceX, distanceY))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            flingLastX = 0
            flingLastY = 0
            scroller.fling(0, 0, -velocityX.roundToInt(), -velocityY.roundToInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
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

    /** Shows the given document strokes (back to front). Cheap if nothing changed. */
    fun setStrokes(newStrokes: List<Stroke>) {
        if (newStrokes === strokes) return
        strokes = newStrokes
        inkCache.retainOnly(newStrokes.mapTo(HashSet()) { it.id })
        updateContentBottom()
        invalidate()
    }

    private fun updateContentBottom() {
        val bottom = strokes.maxOfOrNull { it.bounds.bottom } ?: 0f
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

        val visible = viewport.visibleDocumentRect
        canvas.withMatrix(documentToView) {
            for (stroke in strokes) {
                if (stroke.bounds.intersects(visible)) renderer.draw(this, inkCache[stroke], documentToView)
            }
        }
    }

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
        if (navigating) {
            scaleGestures.onTouchEvent(event)
            gestures.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            navigating = false
        }
        return true
    }

    private fun onPointerDown(event: MotionEvent) {
        val index = event.actionIndex
        val action = InputRouting.actionFor(event.getToolType(index), drawWithFinger)
        if (action == InputRouting.Action.DRAW && drawingPointerId == NO_POINTER) {
            // A pen landing during a finger gesture (e.g. resting palm) takes over.
            if (navigating) {
                navigating = false
                scroller.forceFinished(true)
            }
            startStroke(event, index)
        } else if (action == InputRouting.Action.NAVIGATE && drawingPointerId == NO_POINTER &&
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
    }

    private fun onMove(event: MotionEvent) {
        val id = drawingStrokeId ?: return
        if (event.findPointerIndex(drawingPointerId) < 0) return
        val predicted = predictor.predict()
        try {
            inProgressStrokesView.addToStroke(event, drawingPointerId, id, predicted)
        } finally {
            predicted?.recycle()
        }
    }

    private fun onPointerUp(event: MotionEvent) {
        val id = drawingStrokeId ?: return
        if (event.getPointerId(event.actionIndex) != drawingPointerId) return
        if (InputRouting.isCanceled(event)) {
            inProgressStrokesView.cancelStroke(id, event)
            brushesInProgress.remove(id)
        } else {
            inProgressStrokesView.finishStroke(event, drawingPointerId, id)
        }
        clearDrawingPointer()
    }

    private fun onCancel(event: MotionEvent) {
        drawingStrokeId?.let {
            inProgressStrokesView.cancelStroke(it, event)
            brushesInProgress.remove(it)
        }
        clearDrawingPointer()
    }

    private fun clearDrawingPointer() {
        drawingPointerId = NO_POINTER
        drawingStrokeId = null
    }

    /** True while a stroke is being written or not yet handed over from the overlay. */
    fun hasUnfinishedStrokes(): Boolean = inProgressStrokesView.hasUnfinishedStrokes()

    private fun handOver(strokes: Map<InProgressStrokeId, InkStroke>) {
        val added = ArrayList<Stroke>(strokes.size)
        for ((id, ink) in strokes) {
            val spec = brushesInProgress.remove(id) ?: continue
            val stroke = onStrokeDrafted(StrokeConverter.toDraft(ink, spec)) ?: continue
            inkCache.put(stroke.id, ink)
            added += stroke
        }
        if (added.isNotEmpty()) {
            // Show the strokes in the same frame the overlay drops them to avoid flicker. The
            // document state will deliver the same list again; the cache keeps that cheap.
            val known = this.strokes.mapTo(HashSet()) { it.id }
            val merged = this.strokes + added.filter { it.id !in known }
            this.strokes = merged.sortedBy { it.layer.zIndex } // stable: keeps creation order per layer
            updateContentBottom()
            invalidate()
        }
        inProgressStrokesView.removeFinishedStrokes(strokes.keys)
    }

    private companion object {
        const val NO_POINTER = -1
        const val SHADOW_PX = 4f
    }
}
