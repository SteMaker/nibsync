# NibSync architecture

## Modules

```
app/              Android app: wiring (AppContainer), MainActivity with the library drawer
feature/library/  Library drawer (tree of books/chapters/documents), LibraryViewModel
feature/canvas/   Endless page (NoteCanvasView), tool bar (Compose), CanvasViewModel
feature/text/     Text boxes: embedded fonts, page renderer, rich text editor + format bar
ui/background/    Paper pattern picker and preview, used by canvas and library drawer
ink/android/      Adapter domain <-> androidx.ink (brushes, stroke conversion, mesh cache)
core/sync/        Sync engine (platform-neutral) against a RemoteStore
sync/webdav/      RemoteStore for Nextcloud/WebDAV (OkHttp), Nextcloud login flow v2
core/library/     Library tree: books, nested chapters, documents; one file per node
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

- A document is a set of ops (`AddStrokes`, `AddTextBoxes`, `RemoveStrokes`,
  `SetBackground`) with Lamport stamps. Strokes and text boxes are `PageElement`s sharing
  one id space (`StrokeId`, a historical name), so removal and tombstones cover both.
- `DocumentState` is a two-phase-set CRDT: idempotent, order-independent, removals win.
  Stroke z-order = (layer, stamp of the adding op), identical on every device.
- Every device appends only to its own `ops/<deviceId>.oplog`, so file-level sync over
  WebDAV never has write conflicts; merging is replaying all logs.
- Points are quantized (1/100 mm, 1/4096 pressure, 1/1000 rad) before entering a
  document, so all devices hold bit-identical strokes.

Storage layout (`filesDir/` on Android; `documents/` and `library/` are what gets synced):

```
documents/<documentId>/meta.json
documents/<documentId>/ops/<deviceId>.oplog   "NSOL" v1 header + length-prefixed CBOR records
library/<nodeId>.json                         one file per book / chapter / document entry
device-id                                     local only
```

## Library

Books are top-level folders; folders nest to any depth and hold folders and documents
(folders first, then by title). A document entry's node id is its document id. Every node is
its own file, so devices creating different entries never touch the same file; deleting marks
nodes as tombstones (`deleted`) so a sync cannot bring them back, and removes the documents'
stored content. The drawer opens from the menu button (top left; no swipe gesture, it would
fight with writing at the edge). Tapping a folder selects it as the place for "New folder" /
"New document" (name, A0-A6, portrait/landscape); long press renames or deletes. The last
opened document and the page-break toggle are device settings. The single document of earlier
versions (`default`) was adopted into a first book on upgrade.

## Layers

`BACKGROUND` (pattern) -> `MARKER` (highlighter) -> `TEXT` (text boxes) -> `INK`. Highlighter strokes are placed on
`MARKER` and therefore always appear behind the ink. While being drawn, the in-progress
highlighter is shown on top (Ink overlay limitation) and drops behind once finished.

## Backgrounds

A document's paper is a `BackgroundSpec` (BLANK, LINED, GRID, DOTTED + spacing in mm).
`meta.json` holds the pattern chosen at creation; a later change is a
`DocumentOp.SetBackground` in the op log (record type 3), so it syncs like strokes. The op
with the highest stamp wins in any arrival order; it is a setting, not an edit, so undo
leaves it alone. Where lines and dots go is computed platform-neutrally in
`BackgroundPattern` (rows from one spacing below the top, columns centred on the page); the
Android view only draws them and skips the pattern when zoomed out below 6 px spacing.

## Text boxes

A `TextBox` has a position, a user-chosen width and formatted text (`RichText`: paragraphs
with list kind and indent, character marks bold/italic/size/font). Its stored `heightMm` is
the laid-out height at save time, so platform-neutral code (lasso, page growth) knows its
extent. Like strokes, a box is immutable: every save of an edit is remove + add with a new
id (one undo step). Two devices editing the same box at once therefore end up with both
versions side by side, nothing is lost.

- Fonts: Noto Sans/Serif/Sans Mono and Caveat (handwriting), latin subsets embedded in
  `feature/text/src/main/res/font` (SIL OFL, licences in `feature/text/licenses`). Sizes are
  points; 1 pt = 25.4/72 mm, so text scales with the page like strokes.
- Editing uses the Compose Rich Editor library (`com.mohamedrejeb.richeditor`), confined to
  `feature/text`; `RichTextConversion` maps to and from the stored model. Fonts are a custom
  span (`FontRun`), since the library has no font mark.
- The page shows boxes with `TextBoxRenderer`: Compose text layout at 20 px/mm, cached per
  box, painted onto the view canvas between marker and ink strokes. While a box is edited it
  is hidden on the page and the editor is drawn over it at the current zoom.
- Text tool: tap = new box (80 mm, or the tapped box is opened), dragged frame = box of that
  width. The tool bar switches to formatting while a box is open; the text is saved 1.5 s
  after typing stops, on "Done", on tool change and when the app goes to the background.
  An emptied box is removed. Erasers ignore text; the lasso selects, moves and copies it.

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

## Lasso selection

With the lasso tool the pen draws a freehand loop; strokes with at least half of their points
inside are selected (`Lasso`, even-odd test) and framed. Dragging inside the frame with pen or
finger moves the selection (one undo step; moved strokes are new strokes with fresh ids and
stay selected); a finger outside still scrolls, a tap outside clears the selection. Delete,
copy and paste are tool bar actions. The clipboard (`StrokeClipboard`) keeps strokes relative
to their centre and pastes into the middle of the visible page; it is meant to be shared
app-wide so that pasting into other documents works once there are several (feature B).

## Nextcloud sync

The sync folder on the server (default `NibSync/`) mirrors `library/` and `documents/`.
`SyncEngine`:

1. Compares the folder's etag (Nextcloud propagates etags to parent folders) and local
   pending changes; if neither changed, a sync is a single PROPFIND.
2. Library: downloads changed `library/*.json`, merges (`LibraryNode.mergedWith`: latest change
   wins, deleted stays deleted), uploads nodes whose content differs from the server's.
3. Documents (only those in the library): uploads this device's op log (complete records
   only) and meta; for changed document folders fetches only the new bytes of other devices'
   logs (HTTP Range) and appends complete records to the local copies. Received ops are
   applied live to open sessions. Deleted documents are removed on the server.

Every device writes only its own files, so there are no write conflicts. The Android
`SyncController` syncs on start, every 10 s and 2 s after local changes while the app is in
the foreground, and once when it goes to the background. Login uses Nextcloud Login Flow v2
(browser); the app password is kept in app-private storage. Cleartext HTTP is allowed in debug
builds only (local test server).

Development server: `docker run ... --name nibsync-nextcloud -p 8089:80 nextcloud:apache`
(SQLite; user `nibsync` / `nibsync-test-pass`). The JVM integration test runs with
`NIBSYNC_NEXTCLOUD_URL=http://localhost:8089 NIBSYNC_NEXTCLOUD_USER=nibsync
NIBSYNC_NEXTCLOUD_PASSWORD=nibsync-test-pass ./gradlew :sync:webdav:jvmTest`; the device test
`SyncAppTest` uses `http://192.168.4.100:8089` (override with `NIBSYNC_TEST_NEXTCLOUD_URL`) and
is skipped when the server is unreachable.

Known limits: whole own log is re-uploaded on change (fine for typical sizes; could be split
into segments later); no background sync while the app is closed.

## Roadmap

| Step | Feature | Where it plugs in |
|------|---------|-------------------|
| A ✅ | Pressure-sensitive handwriting, pen types, highlighter behind ink | done |
| B ✅ | Books with nested chapters, documents with DIN A width, page breaks | `core/library`, `feature/library`; menu button top left |
| C ✅ | Nextcloud sync, concurrent editing | `core/sync`, `sync/webdav`, app `sync/` (controller, UI) |
| D | Windows | `jvm`/desktop target for `core/*`, Compose Desktop UI, own renderer |
| E ✅ | Shape recognition | hold the pen still ~0.5 s at the end of a stroke; `core/shapes` |
| F ✅ | Pixel and object eraser | `core/document/erase`; two eraser buttons in the tool bar |
| G ✅ | Lasso: delete, move, copy/paste | `core/document/select`; lasso tool + actions in the tool bar |
| H ✅ | Undo/redo | tool bar buttons, Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z; history per open document and app run |
| T ✅ | Text boxes with formatting (bold, italic, font, size, lists) | `TextBox`, `feature/text`, text tool in `NoteScreen` |
| I ✅ | Lined/grid/dotted backgrounds | `BackgroundPattern`, `DocumentOp.SetBackground`, `ui/background`, `NoteCanvasView.drawPattern` |
