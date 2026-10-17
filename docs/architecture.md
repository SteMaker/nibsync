# NibSync architecture

## Modules

```
app/              Android app: wiring (AppContainer), MainActivity
feature/canvas/   Endless page (NoteCanvasView), tool bar (Compose), CanvasViewModel
ink/android/      Adapter domain <-> androidx.ink (brushes, stroke conversion, mesh cache)
core/shapes/      Shape recognition (lines, arrows, triangles, rectangles, ellipses), hold detection
core/storage/     File format (meta.json + per-device op logs), repository, device id
core/document/    Ops, mergeable DocumentState, UndoManager, DocumentSession
core/model/       Domain types: Stroke, BrushSpec, Layer, PageFormat, Viewport geometry
```

Dependencies point downwards only. `core/*` is platform-neutral Kotlin Multiplatform.

## Handwriting library: Jetpack Ink (androidx.ink 1.0.0)

Ink provides low-latency front-buffer rendering of the stroke being written
(`InProgressStrokesView`), pressure/tilt-aware stock brushes, mesh rendering for finished
strokes and geometry queries (intersection, coverage) for erasers and lasso selection.

Ink has no Windows support, so it is used as an adapter only. The source of truth is the raw
input (x, y, t, pressure, tilt, orientation in millimetres) plus a platform-neutral
`BrushSpec`, which any renderer can reproduce.

## Data model and sync readiness

- A document is a set of ops (`AddStrokes`, `RemoveStrokes`) with Lamport stamps.
- `DocumentState` is a two-phase-set CRDT: idempotent, order-independent, removals win.
  Stroke z-order = (layer, stamp of the adding op), identical on every device.
- Every device appends only to its own `ops/<deviceId>.oplog`, so file-level sync over
  WebDAV never has write conflicts; merging is replaying all logs.
- Points are quantized (1/100 mm, 1/4096 pressure, 1/1000 rad) before entering a
  document, so all devices hold bit-identical strokes.

Storage layout (`filesDir/documents/` on Android):

```
<documentId>/meta.json
<documentId>/ops/<deviceId>.oplog     "NSOL" v1 header + length-prefixed CBOR records
```

## Layers

`BACKGROUND` (pattern) -> `MARKER` (highlighter) -> `INK`. Highlighter strokes are placed on
`MARKER` and therefore always appear behind the ink. While being drawn, the in-progress
highlighter is shown on top (Ink overlay limitation) and drops behind once finished.

## Input

Stylus writes (or erases with the eraser selected); fingers scroll and pinch-zoom; a toggle
enables writing with a finger. Pointers cancelled by the system (palm rejection) cancel the
stroke or erase gesture.

## Shape recognition

Drawing a shape and holding the pen still for 0.5 s (`HoldDetector`) replaces the stroke, while
the pen is still down, with a clean line, arrow, triangle, rectangle/square or ellipse/circle
(`ShapeDetector`, thresholds relative to the drawing's size). Nearly horizontal/vertical shapes
snap to the axes. The replacement is an ordinary stroke with the same pen, drawn with the
stroke's median pressure so it has an even width; it can be undone like any stroke. Without
the hold gesture handwriting is never touched.

Arrows can be drawn in one stroke (shaft, then the head back and forth) or in two: a straight
shaft and then the head as its own stroke (an open "V" or a small triangle) with the hold at
the end. A head whose apex sits on an end of one of the latest strokes of the same layer and
points back along it replaces both strokes with one arrow, in a single undo step
(`DocumentSession.replaceStrokes`). The recognizer is tuned against real arrows recorded on the
test tablet (`RecordedArrows`).

## Erasers

Two tool bar buttons: the object eraser removes every stroke it touches; the pixel eraser
(2/5/12 mm) cuts strokes where it touches them and keeps the rest as ordinary strokes with
the original pen, so the result stays vector data that syncs and can be erased further. The
eraser hits wherever it touches visible ink (centre-line distance <= eraser radius + half the
stroke width). Cut edges lie exactly on the eraser's edge with interpolated pressure/tilt.
`EraseGesture` (platform-neutral) previews the result while erasing; on pen up it is stored
as one undo step via `DocumentSession.replaceStrokes`. The pen's eraser end (TOOL_TYPE_ERASER)
always erases.

## Roadmap

| Step | Feature | Where it plugs in |
|------|---------|-------------------|
| A ✅ | Pressure-sensitive handwriting, pen types, highlighter behind ink | done |
| B | Books with nested chapters, documents with DIN A width, page breaks | new `core/library`, `feature/library`; `PageFormat.pageHeightMm` for page breaks |
| C | Nextcloud sync, concurrent editing | new `sync/nextcloud` mirroring `documents/`; `DocumentSession.applyRemote` |
| D | Windows | `jvm`/desktop target for `core/*`, Compose Desktop UI, own renderer |
| E ✅ | Shape recognition | hold the pen still ~0.5 s at the end of a stroke; `core/shapes` |
| F ✅ | Pixel and object eraser | `core/document/erase`; two eraser buttons in the tool bar |
| G | Lasso: delete, move, copy | Ink dashed-line brush + `PartitionedMesh` coverage; move = remove + add |
| H ✅ | Undo/redo | tool bar buttons, Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z; history per open document and app run |
| I | Lined/grid backgrounds | `BackgroundSpec` is stored; render in `NoteCanvasView.onDraw` |
