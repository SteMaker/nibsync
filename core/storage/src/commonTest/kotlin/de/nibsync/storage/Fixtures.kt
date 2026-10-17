package de.nibsync.storage

import de.nibsync.document.DocumentOp
import de.nibsync.document.OpStamp
import de.nibsync.model.BackgroundKind
import de.nibsync.model.BackgroundSpec
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.model.InputTool
import de.nibsync.model.Orientation
import de.nibsync.model.PageFormat
import de.nibsync.model.PaperSize
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeId
import de.nibsync.model.StrokePoint

val DEV_A = DeviceId("device-a")
val DEV_B = DeviceId("device-b")
val DOC = DocumentId("doc-1")

val META = DocumentMeta(
    id = DOC,
    title = "Notizen äöü ß",
    format = PageFormat(PaperSize.A5, Orientation.LANDSCAPE),
    background = BackgroundSpec(BackgroundKind.BLANK, 7f),
    createdAtEpochMs = 1_700_000_000_000,
)

/** Points already on the quantization grid so they survive encoding unchanged. */
fun penStroke(id: String, author: DeviceId = DEV_A, withPressure: Boolean = true) = Stroke(
    id = StrokeId(id),
    layer = de.nibsync.model.Layer.INK,
    brush = BrushSpec(BrushFamily.FOUNTAIN_PEN, 0xff1a237e.toInt(), 0.7f),
    tool = InputTool.STYLUS,
    points = listOf(
        StrokePoint(10.25f, 20.5f, 0, if (withPressure) 0.25f else -1f, if (withPressure) 0.5f else -1f, if (withPressure) 1.25f else -1f),
        StrokePoint(11f, 21.75f, 8, if (withPressure) 0.5f else -1f, if (withPressure) 0.5f else -1f, if (withPressure) 1.25f else -1f),
        StrokePoint(9.5f, 25f, 16, if (withPressure) 1f else -1f, if (withPressure) 0.25f else -1f, if (withPressure) 1.5f else -1f),
    ),
    createdAtEpochMs = 1_700_000_000_123,
    author = author,
)

fun highlighterStroke(id: String) = Stroke(
    id = StrokeId(id),
    layer = de.nibsync.model.Layer.MARKER,
    brush = BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffeb3b.toInt(), 6f),
    tool = InputTool.TOUCH,
    points = listOf(StrokePoint(0f, 0f, 0), StrokePoint(100f, 0f, 250)),
    createdAtEpochMs = 1_700_000_000_456,
    author = DEV_A,
)

fun addOp(counter: Long, device: DeviceId, vararg strokes: Stroke) =
    DocumentOp.AddStrokes(OpStamp(counter, device), strokes.toList())

fun removeOp(counter: Long, device: DeviceId, vararg ids: String) =
    DocumentOp.RemoveStrokes(OpStamp(counter, device), ids.map(::StrokeId).toSet())
