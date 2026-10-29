# NibSync architecture

## Modules

```
app/              Android app: wiring (AppContainer), MainActivity with the library drawer
feature/library/  Library drawer (tree of books/chapters/documents), LibraryViewModel
feature/canvas/   Endless page (NoteCanvasView), tool bar (Compose), CanvasViewModel
shared/text/      KMP (Android + PC): text model <-> editor conversion, editor controller,
                  paragraph layout, embedded fonts
shared/theme/     KMP (Android + PC): NibSyncTheme with colour schemes (NibSyncPalette: grey
                  default, tinted ones, stronger blue and red), light / dark / system
                  (ThemeMode, a setting on both platforms), page chrome colours, the shared
                  appearance settings panel
feature/text/     Android text boxes: page renderer, format bar
feature/media/    Pictures and file links: type detection, sizing, rendering (AndroidSVG)
desktop/          PC app (Linux, Windows, later macOS): Compose Multiplatform for Desktop
ui/background/    Paper pattern picker and preview, used by canvas and library drawer
ink/android/      Adapter domain <-> androidx.ink (brushes, stroke conversion, mesh cache)
core/sync/        Sync engine (platform-neutral) against a RemoteStore
sync/webdav/      RemoteStore for Nextcloud/WebDAV (OkHttp), Nextcloud login flow v2
sync/client/      SyncController: account, status, periodic/change-triggered runs (Android + PC)
sync/cloud/       OneDrive and Google Drive RemoteStores, OAuth with PKCE
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

- A document is a set of ops (`AddStrokes`, `AddTextBoxes`, `AddFiles`, `AddTodos`,
  `AddLinks`, `RemoveStrokes`, `SetBackground`, `SetTodoDone`) with Lamport stamps. Strokes,
  text boxes, pictures, file links, to-dos, anchors and note links are
  `PageElement`s sharing one id space (`StrokeId`, a historical name), so removal and
  tombstones cover all of them.
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
documents/<documentId>/assets/<sha256>        inserted pictures and files, immutable
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

A book can have a colour (`LibraryNode.colorArgb`, record field `"color"`, palette in
`BookColors`), set in its menu; its chapters and documents show it lighter. Older versions
ignore it; merging decides ties (same change time) by title, parent and colour. A search above
the tree lists the documents whose title or folders match (`LinkTargets.documents`); on the
tablet it is a search bar that becomes a field only when tapped, so opening the library does
not bring up the keyboard. A book's account is a small icon (cloud / crossed cloud).

Moving (`Library.move`, `LibraryTree.canMove`/`dropTarget`): chapters and documents go into any
folder, also of another book, never into themselves; books stay top level. It only changes the
node's `parent`. In the UI it is drag and drop (tablet: long press and drag, long press alone
opens the menu); holding the entry over a closed folder opens it, near the list edges it
scrolls. Between books of different accounts the sync does two extra things (`SyncEngine`):
the old account receives the node once more with its new place (its devices then hide it, the
new book is unknown there), and when a document is new to an account, the logs of other devices
present here are uploaded too, so it arrives complete.

## Layers

`BACKGROUND` (pattern) -> `IMAGE` (pictures) -> `MARKER` (highlighter) -> `TEXT` (text boxes,
file links, anchors, note links) -> `INK` -> `TODO` (to-dos). Highlighter strokes are placed on
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
- Text tool: tap = new box (`TextBox.placeNew`: 120 mm wide or up to the margin, first line
  at the tap; a tap beside an open box only ends it), or the tapped box is opened; dragged
  frame = box of that width. The tool bar switches to formatting while a box is open (A−/A+
  step the size through `RichText.SIZES_PT` from the size at the cursor); the text is saved
  1.5 s after typing stops, on "Done", on tool change and when the app goes to the background.
  An emptied box is removed. Erasers ignore text; the lasso selects, moves and copies it.
- Style presets (`TextPreset`: Normal, Heading 1–3, Code) format whole paragraphs touched by
  the cursor or selection. They are only a quick way to set size, bold and font, stored as
  ordinary marks, so there is no paragraph style in the file format.
- Shift+Enter in a list item starts a further line of it (`TextParagraph.continued`, record
  field `"c"`): no bullet, indented like the item's text, numbering goes on; Enter in such a
  line starts the next item. The editor has no such lines: they become plain paragraphs with
  that text indent, and numbered items after one get a start number. Older app versions show
  the line as an item of its own.
- On-screen keyboard (Android): while it is up, the cursor of the edited text is scrolled
  into view (`Viewport.revealing`); if it covers the page instead of shrinking the window
  (edge-to-edge), the page can scroll that much further (`Viewport.bottomInsetPx`).

## Document title on the page

- The title and creation time at the top of the page and the path (book › chapter › document)
  at the bottom right are not page elements: `DocumentHeader`/`DocumentPath` (`shared/text`)
  draw them over the page from the library entry and `DocumentMeta.createdAtEpochMs`, so they
  cannot be moved, erased or selected. The header scrolls and zooms with the page.
- Typing in the title renames the library entry (`Library.rename`), which is synced like any
  rename. Title and date can be hidden separately in the settings (device-local).

## To-dos

- `TodoItem` (`core/model`) is a page element on its own top layer `Layer.TODO`: a card with a
  red check circle on the left and either typed text (`TodoContent.Text`) or handwriting
  (`TodoContent.Ink`, stroke points relative to the content area, so moving needs no rewrite).
- Whether a task is done is not part of the element but a last-writer-wins register per
  `taskId` in `DocumentState` (`DocumentOp.SetTodoDone`, op type 7; `AddTodos` is type 6).
  Moving, resizing or editing a to-do is remove + add with a fresh element id but the same
  `taskId`, so ticking off on one device and moving on another merge. Copies (paste) are new
  tasks. Undo of a tick is `Edit.TodoDone`.
- Editing is shared by both apps: `TodoEditing` (`shared/text`), like `TextBoxEditing`. The
  frame never gets smaller than its content (text wraps, handwriting keeps its size).
- Tool: tapped with the pen, new to-dos are handwritten; with a finger or the mouse, typed. The
  tool settings bar on the right switches. A tap on a check circle ticks off with every tool but
  the eraser. Strokes starting in the open handwritten to-do go into it, not onto the page.
- "Open to-dos" (top of the library) is generated: `TodoIndex`/`TodoCollector`
  (`core/library`) collect the open to-dos of all library documents (open sessions live, stored
  documents cached until the sync reports). Nothing of it is stored or synced; ticking off there
  writes `SetTodoDone` into the to-do's document.

## Pictures and files

Inserted files are stored per document as `assets/<sha256>` (content-addressed, never
changed) and referenced by `ImageElement` (PNG, JPEG, SVG; drawn scaled to its box) or
`FileLink` (any other file up to 50 MB: a chip with the type, "PDF" or the extension, and the
file name; tapping it opens the file in a viewer app if the system has one). The stored type is
`MediaFiles.fileType`: recognised content, else the declared type, else one guessed from the
extension, else `application/octet-stream` (Android then asks by the extension). Sources: the insert
menu (file picker, clipboard), Ctrl+V, and "Share" from other apps into the open document.
Pictures start at their 150 dpi print size, at most 80 % of the page width, centred in view.

- Rendering (`MediaRenderer`): pixel pictures are decoded in the background at the
  resolution the zoom needs (EXIF rotation applied), SVGs become vector `Picture`s; a
  placeholder shows until a file is there (e.g. still syncing).
- Taps: a tap (pen, finger, lasso) on a picture selects it in a temporary lasso mode with a
  corner handle for scaling (aspect kept); a tap on a link opens it; a tap on empty paper
  returns to the previous tool. Erasers ignore pictures and links.
- Sync: assets are uploaded before the log that refers to them and downloaded when a
  document folder changed; pasting into another document copies the files along.

## Tables

A `TableElement` (core/model) has columns (stable `colId`, width) and rows (stable `rowId`),
a header flag, the cells' content (`TableCell`: rich text and its laid-out height, so sizes are
known without a text engine) and joined areas (`CellMerge`, given by their corner ids: a row or
column inserted inside makes the area grow; a deleted corner shrinks it, see `TableEditing`).
`TableGeometry` lays it out: a row is as high as its highest single cell; a joined cell taller
than its rows stretches the last one. Content of a joined area is its top left cell's.

- Ops: `AddTables` (type 9) adds the structure with the content of that time; `SetTableCells`
  (type 10) sets cells of a table by `tableId`. Each cell is a register (highest stamp wins), so
  two devices editing different cells never conflict and a cell edit survives a concurrent
  structure change (remove + add, same `tableId`). `DocumentState` shows tables with their
  registers applied; a copy is a new table (new `tableId`). Older versions skip both ops.
- `DocumentSession.replaceTable` changes structure and cells in one undo step (`Edit.Steps`),
  e.g. joining cells moves the other cell's text into this one.
- `TableEditing` (shared/text) does the editing on both platforms: one cell at a time in a
  `TextEditController`, Tab / Shift+Tab (Tab in the last cell adds a row), rows and columns,
  join right / down, split, header row, column widths and scaling (texts measured anew).
- UI: Insert > Table… (`TableSizeDialog`, grid up to 8 x 8), a tap or click into a cell opens
  it, the tool bar shows "Done", a "Table" menu and the text formatting. Column widths: drag
  the border (PC) or the handle above it (tablet). Tables lie on the TEXT layer on white, the
  header row tinted; lasso, copy and undo work as for other elements, and on the PC the
  selection's corner scales a table.

## Anchors and links to notes

An `Anchor` is a named place in a document (a small flag, 30 % smaller than a link; its name shows only while it is selected, `labelBounds`); a `NoteLink` leads to a
document or to an anchor in it (a chip with an arrow and "Title › Anchor"). Both are
`LinkElement`s on the `TEXT` layer, added with `DocumentOp.AddLinks` (record type 8, list
`"n"` of `LinkRecord`s; older versions skip it). Like a to-do's `taskId`, an anchor's `anchorId`
stays when it is moved or renamed (remove + add), so links keep working; a copy gets a new
one. A link stores the target's title and anchor name when made; the apps show the document's
current title from the library (`titleOf`). Choosing the target and finding it again is
`LinkTargets` (core/library): the documents with their folders and a search, the anchors top
down, and where to scroll (`revealY`: the anchor, else the top; null if the document is not on
this device, e.g. deleted or in an unsynced book).

UI: insert menu "Anchor …" (name, placed in the middle of the view and selected for moving)
and "Link to note …" (document, then its top or an anchor). Tablet: a tap on a link follows it;
a tap on an anchor selects it, a second tap renames it. PC: a click selects, a double click
follows the link or renames the anchor.

## Input

Stylus writes (or erases with the eraser selected); fingers scroll and pinch-zoom; a toggle
enables writing with a finger. Pointers cancelled by the system (palm rejection) cancel the
stroke or erase gesture.

Pens: each pen offers three widths (fine, medium, broad), set per pen in the settings
("Pen widths"). `PenPreferences` (core/model) keeps them on the device together with the last
writing pen (ballpoint or fountain pen) and which width each pen had; both apps restore it for
every document and at start (`ToolState.from`, `DesktopToolState.from`). Changing a width in
the settings keeps a pen's position (a pen on "broad" gets the new broad width).

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
`SyncAppTest` uses `http://10.0.2.2:8089`, the PC as seen from the emulator (`scripts/verify.sh
--tablet` passes the PC's LAN address; override with `NIBSYNC_TEST_NEXTCLOUD_URL`), and is
skipped when the server is unreachable. Both addresses are in the container's
`trusted_domains` (`occ config:system:set trusted_domains <n> --value=<address>`).

Known limits: whole own log is re-uploaded on change (fine for typical sizes; could be split
into segments later); no background sync while the app is closed.

## Sync accounts and services

Several accounts per device (`SyncAccount`: a name such as "Privat" or "Arbeit" plus a
`SyncConfig` for Nextcloud, OneDrive or Google Drive), managed in the settings and kept in
private storage (`AccountStore`, `AccountsCodec`). Every book belongs to one account or stays
on the device only (`BookAccounts`, `book-accounts.txt`, device-local because account ids
differ between devices). Each account has its own `SyncEngine` over its own remote folder,
restricted by a `BookScope` to its books: books it receives from its server and does not know
yet become its books; entries of books owned elsewhere are ignored. A local book can be
assigned to an account later; there is no moving between accounts. Removing an account keeps
its books on the device, unsynced. The single login of versions before accounts becomes the
account `legacy`, which gets all books that existed then.

Nextcloud uses WebDAV, whose folder etags already change with anything below them. OneDrive
(app folder, Microsoft Graph) and Google Drive ("NibSync" in My Drive, scope drive.file) do
not; their stores keep a `RemoteIndex` of the sync folder, updated from the provider's change
feed (Graph delta, Drive changes) whenever the sync folder is listed, and compute such etags
from it, so the same `SyncEngine` runs on all three. Same-named duplicate folders (two devices
creating one at once) count as one. On Android, Google tokens come per Google account from Play
services. Setup of the OAuth clients: `docs/cloud-setup.md`.

## PC app (desktop/)

Compose Multiplatform for Desktop (Apache 2.0) on the JVM, using the core modules unchanged,
so the PC reads and writes exactly the Android file format. Data folder: `DataDir`
(`~/.local/share/nibsync` on Linux, `%APPDATA%\NibSync` on Windows); `--data-dir` overrides
it. Ink is Android-only, so `StrokePainter` redraws strokes from their points (ballpoint and
highlighter constant width, fountain pen with BrushMapper's pressure curve and end taper).
Text boxes use the same embedded fonts (loaded from `feature/text/src/main/res/font`) and the
same paragraph layout as `RichTextLayout`. Plan: D1 viewing ✅, D2 text boxes ✅, D3
Nextcloud sync ✅, D4 drawing/erasing/lasso/pictures ✅, D5 packages (Linux .deb ✅, Windows
.msi to be built on Windows/CI). Packages bundle a trimmed Java runtime (`modules(...)` in
`desktop/build.gradle.kts`); the app icon is `desktop/icons/` (same artwork as the launcher).
Crashes (uncaught exceptions on any thread, errors in the window) are appended with their
stack trace, app version and system to `crash.log` in the data folder (`CrashLog`, rotated at
1 MB to `crash.old.log`); Compose's error dialog then names that file.

Tools on the PC (`NoteController`, `DesktopTool`): text (default), the three pens with the
Android palette and the widths from the settings, both erasers (`EraseGesture`), lasso; holding the pen still recognises shapes
(`GeometricShapeRecognizer`). A click on a picture or link selects it (double-click opens a
link with the system program); the selection is dragged, scaled at its corner handle, copied,
cut, pasted (Ctrl+C/X/V, also between documents with their files) and deleted (Del). Pictures
come from the file dialog, the clipboard (Ctrl+V) or drag & drop; placement is the shared
`MediaFiles` (core/model), EXIF rotation is applied with Skia. Pen pressure from graphics
tablets is used when the platform reports a stylus (AWT usually reports a mouse).

Library on the PC (`LibraryPane`, `LibraryDialogs`): "+" creates a book with its sync account;
a right click on an entry offers a new chapter or document inside (name, DIN width,
orientation, pattern, like the tablet), rename and delete. The tree keeps the shown document's
book and chapters open.

Sync on the PC is the same `SyncController` as on Android (module `sync/client`; the apps
provide a `SyncHost` with their open documents and an `AccountStore`). The PC keeps the
accounts in `<data>/sync-account.properties` (owner-only permissions), logs in with Login Flow
v2 or OAuth in the default browser, syncs on start, every 10 s, after changes and once more on
close.

Text on the PC (`NoteController`): a click on free paper starts a text box at the click
(120 mm wide or up to the margin), a click on a box opens it, Esc closes it; Ctrl+B/I format,
Ctrl+Z/Y undo/redo (inside an open box the editor's own history). Editing itself is the shared
`TextBoxEditing` (shared/text), which the Android `CanvasViewModel` uses too, so both store
boxes the same way.

## Roadmap

| Step | Feature | Where it plugs in |
|------|---------|-------------------|
| A ✅ | Pressure-sensitive handwriting, pen types, highlighter behind ink | done |
| B ✅ | Books with nested chapters, documents with DIN A width, page breaks | `core/library`, `feature/library`; menu button top left |
| C ✅ | Nextcloud sync, concurrent editing | `core/sync`, `sync/webdav`, app `sync/` (controller, UI) |
| D (D1 ✅) | PC app for Linux/Windows | `desktop/`, Compose Multiplatform for Desktop; see "PC app" |
| E ✅ | Shape recognition | hold the pen still ~0.5 s at the end of a stroke; `core/shapes` |
| F ✅ | Pixel and object eraser | `core/document/erase`; two eraser buttons in the tool bar |
| G ✅ | Lasso: delete, move, copy/paste | `core/document/select`; lasso tool + actions in the tool bar |
| H ✅ | Undo/redo | tool bar buttons, Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z; history per open document and app run |
| M ✅ | Pictures (PNG, JPEG, SVG) with scaling, PDFs as links | `FileElement`, assets, `feature/media`, insert menu |
| T ✅ | Text boxes with formatting (bold, italic, font, size, lists) | `TextBox`, `feature/text`, text tool in `NoteScreen` |
| TB ✅ | Tables: typed cells, rows / columns, joined cells, header, sync per cell | `TableElement`, `TableEditing`, `TableRenderer` / `PagePainter` |
| S ✅ | Several sync accounts (Nextcloud, OneDrive, Google Drive), one per book | `sync/client` (`SyncAccount`, `BookAccounts`), `BookScope` in `SyncEngine`; settings "Accounts" |
| Q ✅ | To-dos (typed or handwritten), tick off on the page or in the generated "Open to-dos" page | `TodoItem`, `TodoEditing`, `TodoIndex`; to-do tool in both apps |
| L ✅ | Any file as a link; anchors and links to notes | `MediaFiles.fileType`, `Anchor`/`NoteLink`, `LinkTargets`, insert menu |
| P ✅ | Pen widths in the settings, last pen and width remembered | `PenPreferences` (core/model), `AppSettings`/`Settings`, `ToolState.from` |
| I ✅ | Lined/grid/dotted backgrounds | `BackgroundPattern`, `DocumentOp.SetBackground`, `ui/background`, `NoteCanvasView.drawPattern` |
