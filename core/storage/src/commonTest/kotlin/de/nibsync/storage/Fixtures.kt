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

/** A formatted text box on the quantization grid, exercising every mark kind and list type. */
fun formattedTextBox(id: String) = de.nibsync.model.TextBox(
    id = StrokeId(id),
    x = 12.5f,
    y = 40.25f,
    widthMm = 80f,
    heightMm = 21.37f,
    text = de.nibsync.model.RichText(
        listOf(
            de.nibsync.model.TextParagraph(
                "Überschrift fett",
                marks = listOf(
                    de.nibsync.model.TextMark(0, 16, de.nibsync.model.MarkStyle.Size(18f)),
                    de.nibsync.model.TextMark(12, 16, de.nibsync.model.MarkStyle.Bold),
                ),
            ),
            de.nibsync.model.TextParagraph("Punkt", list = de.nibsync.model.ListKind.BULLET),
            de.nibsync.model.TextParagraph(
                "Unterpunkt kursiv",
                list = de.nibsync.model.ListKind.NUMBERED,
                indent = 1,
                marks = listOf(
                    de.nibsync.model.TextMark(11, 17, de.nibsync.model.MarkStyle.Italic),
                    de.nibsync.model.TextMark(0, 10, de.nibsync.model.MarkStyle.Font(de.nibsync.model.TextFont.HANDWRITING)),
                ),
            ),
            de.nibsync.model.TextParagraph(""),
        ),
    ),
    createdAtEpochMs = 1_700_000_000_789,
    author = DEV_A,
)

const val SAMPLE_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

fun sampleImage(id: String) = de.nibsync.model.ImageElement(
    StrokeId(id), 20.5f, 30.25f, 80f, 45.12f, de.nibsync.model.Asset(SAMPLE_SHA, de.nibsync.model.Asset.JPEG), 1_700_000_001_000, DEV_A,
)

fun sampleLink(id: String) = de.nibsync.model.FileLink(
    StrokeId(id), 10f, 100f, 60f, 12f, de.nibsync.model.Asset(SAMPLE_SHA, de.nibsync.model.Asset.PDF), "Skript Kapitel 3.pdf", 1_700_000_001_500, DEV_B,
)
