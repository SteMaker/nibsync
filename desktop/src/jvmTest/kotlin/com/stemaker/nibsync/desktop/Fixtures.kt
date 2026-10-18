package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.OpStamp
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TextBox

val DEV = DeviceId("pc")

fun stroke(id: String, family: BrushFamily, points: List<StrokePoint>, color: Int = 0xff000000.toInt(), size: Float = 1f, tool: InputTool = InputTool.STYLUS) =
    Stroke(StrokeId(id), family.defaultLayer, BrushSpec(family, color, size), tool, points, 0, DEV)

fun line(x0: Float, x1: Float, y: Float, pressure: Float = 0.25f, n: Int = 40) =
    List(n + 1) { i -> StrokePoint(x0 + (x1 - x0) * i / n, y, i * 5L, pressure) }

/** A4 document with lined paper, a stroke and a text box. */
fun sampleDocument(): DocumentState {
    val meta = DocumentMeta(DocumentId("sample"), "Sample", background = BackgroundSpec(BackgroundKind.LINED, 7f), createdAtEpochMs = 0)
    return DocumentState.empty(meta)
        .apply(DocumentOp.AddStrokes(OpStamp(1, DEV), listOf(stroke("ink", BrushFamily.BALLPOINT, line(20f, 120f, 30f), size = 1.5f))))
        .apply(DocumentOp.AddTextBoxes(OpStamp(2, DEV), listOf(TextBox(StrokeId("text"), 20f, 50f, 100f, 10f, RichText.plain("Hallo PC"), 0, DEV))))
}
