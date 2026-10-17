# NibSync architecture

## Modules

```
app/              Android app: wiring (AppContainer), MainActivity
feature/canvas/   Endless page (NoteCanvasView), tool bar (Compose), CanvasViewModel
ink/android/      Adapter domain <-> androidx.ink (brushes, stroke conversion, mesh cache)
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

Stylus writes; fingers scroll and pinch-zoom; a toggle enables writing with a finger. Pointers
cancelled by the system (palm rejection) cancel the stroke. The stylus eraser end is
reserved for feature F.

## Roadmap

| Step | Feature | Where it plugs in |
|------|---------|-------------------|
| A ✅ | Pressure-sensitive handwriting, pen types, highlighter behind ink | done |
| B | Books with nested chapters, documents with DIN A width, page breaks | new `core/library`, `feature/library`; `PageFormat.pageHeightMm` for page breaks |
| C | Nextcloud sync, concurrent editing | new `sync/nextcloud` mirroring `documents/`; `DocumentSession.applyRemote` |
| D | Windows | `jvm`/desktop target for `core/*`, Compose Desktop UI, own renderer |
| E | Shape recognition | implement `ShapeRecognizer` (already called for every new stroke) |
| F | Pixel and object eraser | object eraser: `RemoveStrokes` + Ink geometry; pixel eraser: remove + add pieces |
| G | Lasso: delete, move, copy | Ink dashed-line brush + `PartitionedMesh` coverage; move = remove + add |
| H | Undo/redo UI | `DocumentSession.undo/redo` and `canUndo/canRedo` exist and are tested |
| I | Lined/grid backgrounds | `BackgroundSpec` is stored; render in `NoteCanvasView.onDraw` |
