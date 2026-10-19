package com.stemaker.nibsync.storage

import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.document.OpStamp
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.Orientation
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.PaperSize
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.StrokePoint

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
    layer = com.stemaker.nibsync.model.Layer.INK,
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
    layer = com.stemaker.nibsync.model.Layer.MARKER,
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
fun formattedTextBox(id: String) = com.stemaker.nibsync.model.TextBox(
    id = StrokeId(id),
    x = 12.5f,
    y = 40.25f,
    widthMm = 80f,
    heightMm = 21.37f,
    text = com.stemaker.nibsync.model.RichText(
        listOf(
            com.stemaker.nibsync.model.TextParagraph(
                "Überschrift fett",
                marks = listOf(
                    com.stemaker.nibsync.model.TextMark(0, 16, com.stemaker.nibsync.model.MarkStyle.Size(18f)),
                    com.stemaker.nibsync.model.TextMark(12, 16, com.stemaker.nibsync.model.MarkStyle.Bold),
                ),
            ),
            com.stemaker.nibsync.model.TextParagraph("Punkt", list = com.stemaker.nibsync.model.ListKind.BULLET),
            com.stemaker.nibsync.model.TextParagraph(
                "Unterpunkt kursiv",
                list = com.stemaker.nibsync.model.ListKind.NUMBERED,
                indent = 1,
                marks = listOf(
                    com.stemaker.nibsync.model.TextMark(11, 17, com.stemaker.nibsync.model.MarkStyle.Italic),
                    com.stemaker.nibsync.model.TextMark(0, 10, com.stemaker.nibsync.model.MarkStyle.Font(com.stemaker.nibsync.model.TextFont.HANDWRITING)),
                ),
            ),
            com.stemaker.nibsync.model.TextParagraph(""),
        ),
    ),
    createdAtEpochMs = 1_700_000_000_789,
    author = DEV_A,
)

const val SAMPLE_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

fun sampleImage(id: String) = com.stemaker.nibsync.model.ImageElement(
    StrokeId(id), 20.5f, 30.25f, 80f, 45.12f, com.stemaker.nibsync.model.Asset(SAMPLE_SHA, com.stemaker.nibsync.model.Asset.JPEG), 1_700_000_001_000, DEV_A,
)

fun sampleLink(id: String) = com.stemaker.nibsync.model.FileLink(
    StrokeId(id), 10f, 100f, 60f, 12f, com.stemaker.nibsync.model.Asset(SAMPLE_SHA, com.stemaker.nibsync.model.Asset.PDF), "Skript Kapitel 3.pdf", 1_700_000_001_500, DEV_B,
)

fun textTodo(id: String, task: String) = com.stemaker.nibsync.model.TodoItem(
    StrokeId(id), StrokeId(task), 15f, 60.5f, 90f, 14f,
    com.stemaker.nibsync.model.TodoContent.Text(com.stemaker.nibsync.model.RichText.plain("Angebot schicken")), 1_700_000_002_000, DEV_A,
)

/** A handwritten to-do; the stroke's points are relative to its content area. */
fun inkTodo(id: String, task: String) = com.stemaker.nibsync.model.TodoItem(
    StrokeId(id), StrokeId(task), 15f, 80f, 70f, 16.25f,
    com.stemaker.nibsync.model.TodoContent.Ink(listOf(penStroke("99999999-9999-4999-8999-999999999999"))), 1_700_000_002_500, DEV_B,
)
