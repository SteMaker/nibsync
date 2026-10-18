package de.nibsync.document

import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.model.InputTool
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokeId
import de.nibsync.model.StrokePoint
import de.nibsync.model.RichText
import de.nibsync.model.TextBox
import de.nibsync.model.TextBoxDraft

val DEV_A = DeviceId("a")
val DEV_B = DeviceId("b")
val META = DocumentMeta(DocumentId("doc"), "Test", createdAtEpochMs = 0)
val PEN = BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f)
val HIGHLIGHTER = BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffeb3b.toInt(), 6f)

fun stroke(id: String, y: Float = 0f, brush: BrushSpec = PEN, author: DeviceId = DEV_A) = Stroke(
    id = StrokeId(id),
    layer = brush.family.defaultLayer,
    brush = brush,
    tool = InputTool.STYLUS,
    points = listOf(StrokePoint(0f, y, 0), StrokePoint(5f, y, 10)),
    createdAtEpochMs = 0,
    author = author,
)

fun draft(y: Float = 0f, brush: BrushSpec = PEN) =
    StrokeDraft(brush, InputTool.STYLUS, listOf(StrokePoint(0f, y, 0), StrokePoint(5f, y, 10)))

fun add(counter: Long, device: DeviceId, vararg strokes: Stroke) =
    DocumentOp.AddStrokes(OpStamp(counter, device), strokes.toList())

fun remove(counter: Long, device: DeviceId, vararg ids: String) =
    DocumentOp.RemoveStrokes(OpStamp(counter, device), ids.map(::StrokeId).toSet())

val DocumentState.ids: List<String> get() = strokes.map { it.id.value }

fun textBox(id: String, y: Float = 0f, text: String = "Text", author: DeviceId = DEV_A) =
    TextBox(StrokeId(id), 10f, y, 50f, 10f, RichText.plain(text), 0, author)

fun textDraft(y: Float = 0f, text: String = "Text") = TextBoxDraft(10f, y, 50f, 10f, RichText.plain(text))

fun addText(counter: Long, device: DeviceId, vararg boxes: TextBox) =
    DocumentOp.AddTextBoxes(OpStamp(counter, device), boxes.toList())

val DocumentState.elementIds: List<String> get() = elements.map { it.id.value }
