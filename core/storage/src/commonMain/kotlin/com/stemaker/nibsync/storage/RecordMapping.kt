package com.stemaker.nibsync.storage

import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.document.OpStamp
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.Layer
import com.stemaker.nibsync.model.Orientation
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.PaperSize
import com.stemaker.nibsync.model.Quantization
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.withoutStrayBreaks

internal const val FORMAT_VERSION = 1

internal fun DocumentMeta.toRecord() = MetaRecord(
    formatVersion = FORMAT_VERSION,
    id = id.value,
    title = title,
    paper = format.paper.name,
    orientation = format.orientation.name,
    background = background.toRecord(),
    createdAtEpochMs = createdAtEpochMs,
)

internal fun MetaRecord.toDomain(): DocumentMeta {
    if (formatVersion > FORMAT_VERSION) throw UnsupportedFormatException(formatVersion)
    return DocumentMeta(
        id = DocumentId(id),
        title = title,
        format = PageFormat(enumOr(paper, PaperSize.A4), enumOr(orientation, Orientation.PORTRAIT)),
        background = background.toDomain(),
        createdAtEpochMs = createdAtEpochMs,
    )
}

internal fun DocumentOp.toRecord(): OpRecord = when (this) {
    is DocumentOp.AddStrokes -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_ADD,
        strokes = strokes.map { it.toRecord() },
    )
    is DocumentOp.RemoveStrokes -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_REMOVE,
        removed = ids.map { it.value }.sorted(),
    )
    is DocumentOp.AddTextBoxes -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_ADD_TEXT,
        textBoxes = boxes.map { it.toRecord() },
    )
    is DocumentOp.AddFiles -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_ADD_FILES,
        files = elements.map { it.toRecord() },
    )
    is DocumentOp.AddTodos -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_ADD_TODOS,
        todos = items.map { it.toRecord() },
    )
    is DocumentOp.SetTodoDone -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_TODO_DONE,
        todoDone = TodoDoneRecord(taskId.value, done),
    )
    is DocumentOp.SetBackground -> OpRecord(
        counter = stamp.counter,
        device = stamp.device.value,
        type = OpRecord.TYPE_BACKGROUND,
        background = background.toRecord(),
    )
}

internal fun BackgroundSpec.toRecord() = BackgroundRecord(kind.name, spacingMm)

/** Unknown kinds (from a newer app version) fall back to a blank page. */
internal fun BackgroundRecord.toDomain() = BackgroundSpec(enumOr(kind, BackgroundKind.BLANK), spacingMm)

/** Returns null for op types written by a newer app version, so they are skipped, not fatal. */
internal fun OpRecord.toDomain(): DocumentOp? {
    val stamp = OpStamp(counter, DeviceId(device))
    return when (type) {
        OpRecord.TYPE_ADD -> DocumentOp.AddStrokes(stamp, strokes.mapNotNull { it.toDomain() })
        OpRecord.TYPE_REMOVE -> DocumentOp.RemoveStrokes(stamp, removed.map(::StrokeId).toSet())
        OpRecord.TYPE_BACKGROUND -> background?.let { DocumentOp.SetBackground(stamp, it.toDomain()) }
        OpRecord.TYPE_ADD_TEXT -> DocumentOp.AddTextBoxes(stamp, textBoxes.map { it.toDomain() })
        OpRecord.TYPE_ADD_FILES -> DocumentOp.AddFiles(stamp, files.mapNotNull { it.toDomain() })
        OpRecord.TYPE_ADD_TODOS -> DocumentOp.AddTodos(stamp, todos.mapNotNull { it.toDomain() })
        OpRecord.TYPE_TODO_DONE -> todoDone?.let { DocumentOp.SetTodoDone(stamp, StrokeId(it.task), it.done) }
        else -> null
    }
}

internal fun Stroke.toRecord() = StrokeRecord(
    id = id.value,
    layer = layer.name,
    family = brush.family.name,
    colorArgb = brush.colorArgb,
    sizeMm = brush.sizeMm,
    tool = tool.name,
    createdAtEpochMs = createdAtEpochMs,
    author = author.value,
    points = encodePoints(points),
)

/** Strokes with an unknown brush family (from a newer app version) are skipped. */
internal fun StrokeRecord.toDomain(): Stroke? {
    val family = BrushFamily.entries.firstOrNull { it.name == family } ?: return null
    val layer = Layer.entries.firstOrNull { it.name == layer && it.holdsStrokes } ?: family.defaultLayer
    val points = decodePoints(points)
    if (points.isEmpty()) return null
    return Stroke(
        id = StrokeId(id),
        layer = layer,
        brush = BrushSpec(family, colorArgb, sizeMm),
        tool = enumOr(tool, InputTool.UNKNOWN),
        points = points,
        createdAtEpochMs = createdAtEpochMs,
        author = DeviceId(author),
    )
}

internal fun TextBox.toRecord() = TextBoxRecord(
    id = id.value,
    x = Quantization.positionToSteps(x),
    y = Quantization.positionToSteps(y),
    width = Quantization.positionToSteps(widthMm),
    height = Quantization.positionToSteps(heightMm),
    createdAtEpochMs = createdAtEpochMs,
    author = author.value,
    paragraphs = text.toRecords(),
)

private fun TextMark.toRecord(): MarkRecord = when (val s = style) {
    MarkStyle.Bold -> MarkRecord(start, end, MARK_BOLD)
    MarkStyle.Italic -> MarkRecord(start, end, MARK_ITALIC)
    is MarkStyle.Size -> MarkRecord(start, end, MARK_SIZE, s.pt.toString())
    is MarkStyle.Font -> MarkRecord(start, end, MARK_FONT, s.font.name)
}

private fun RichText.toRecords(): List<ParagraphRecord> = paragraphs.map { p ->
    ParagraphRecord(p.text, p.list.name, p.indent, p.marks.map { it.toRecord() })
}

private fun List<ParagraphRecord>.toRichText(): RichText {
    val paragraphs = map { p ->
        TextParagraph(p.text, enumOr(p.list, ListKind.NONE), p.indent.coerceAtLeast(0), p.marks.mapNotNull { it.toDomain(p.text.length) })
    }
    // Texts stored before pasted line breaks were removed may still hold some.
    return if (paragraphs.isEmpty()) RichText.EMPTY else RichText(paragraphs).withoutStrayBreaks()
}

internal fun TodoItem.toRecord(): TodoRecord {
    val p = Quantization::positionToSteps
    val content = content
    return TodoRecord(
        id = id.value,
        task = taskId.value,
        x = p(x),
        y = p(y),
        width = p(widthMm),
        height = p(heightMm),
        createdAtEpochMs = createdAtEpochMs,
        author = author.value,
        paragraphs = (content as? TodoContent.Text)?.text?.toRecords(),
        strokes = (content as? TodoContent.Ink)?.strokes?.map { it.toRecord() },
    )
}

/** Invalid records are skipped. */
internal fun TodoRecord.toDomain(): TodoItem? {
    val q = Quantization::stepsToPosition
    if (width <= 0 || height <= 0) return null
    val content = when {
        strokes != null -> TodoContent.Ink(strokes.mapNotNull { it.toDomain() })
        else -> TodoContent.Text(paragraphs.orEmpty().toRichText())
    }
    return TodoItem(StrokeId(id), StrokeId(task), q(x), q(y), q(width), q(height), content, createdAtEpochMs, DeviceId(author))
}

internal fun TextBoxRecord.toDomain(): TextBox {
    return TextBox(
        id = StrokeId(id),
        x = Quantization.stepsToPosition(x),
        y = Quantization.stepsToPosition(y),
        widthMm = Quantization.stepsToPosition(width).coerceAtLeast(TextBox.MIN_WIDTH_MM),
        heightMm = Quantization.stepsToPosition(height).coerceAtLeast(0f),
        text = paragraphs.toRichText(),
        createdAtEpochMs = createdAtEpochMs,
        author = DeviceId(author),
    )
}

/** Marks of unknown kinds (from a newer app version) or with bad values are dropped. */
private fun MarkRecord.toDomain(length: Int): TextMark? {
    val s = start.coerceIn(0, length)
    val e = end.coerceIn(0, length)
    if (s >= e) return null
    val style = when (kind) {
        MARK_BOLD -> MarkStyle.Bold
        MARK_ITALIC -> MarkStyle.Italic
        MARK_SIZE -> value?.toFloatOrNull()?.takeIf { it > 0f }?.let { MarkStyle.Size(it) }
        MARK_FONT -> TextFont.entries.firstOrNull { it.name == value }?.let { MarkStyle.Font(it) }
        else -> null
    } ?: return null
    return TextMark(s, e, style)
}

private const val MARK_BOLD = "b"
private const val MARK_ITALIC = "i"
private const val MARK_SIZE = "z"
private const val MARK_FONT = "f"

internal fun FileElement.toRecord(): FileElementRecord {
    val p = Quantization::positionToSteps
    return when (this) {
        is ImageElement -> FileElementRecord(KIND_IMAGE, id.value, p(x), p(y), p(widthMm), p(heightMm), createdAtEpochMs, author.value, asset.sha256, asset.mimeType)
        is FileLink -> FileElementRecord(KIND_LINK, id.value, p(x), p(y), p(widthMm), p(heightMm), createdAtEpochMs, author.value, asset.sha256, asset.mimeType, fileName)
    }
}

/** Unknown kinds (from a newer app version) and invalid records are skipped. */
internal fun FileElementRecord.toDomain(): FileElement? {
    val q = Quantization::stepsToPosition
    if (width <= 0 || height <= 0) return null
    val asset = runCatching { Asset(sha256, mimeType) }.getOrNull() ?: return null
    return when (kind) {
        KIND_IMAGE -> ImageElement(StrokeId(id), q(x), q(y), q(width), q(height), asset, createdAtEpochMs, DeviceId(author))
        KIND_LINK -> FileLink(StrokeId(id), q(x), q(y), q(width), q(height), asset, name ?: "", createdAtEpochMs, DeviceId(author))
        else -> null
    }
}

private const val KIND_IMAGE = "image"
private const val KIND_LINK = "link"

internal fun encodePoints(points: List<StrokePoint>): PointsRecord {
    fun deltas(values: IntArray): IntArray {
        val out = IntArray(values.size)
        var prev = 0
        for (i in values.indices) {
            out[i] = values[i] - prev
            prev = values[i]
        }
        return out
    }
    fun optional(values: IntArray): IntArray = if (values.all { it < 0 }) IntArray(0) else values

    return PointsRecord(
        x = deltas(IntArray(points.size) { Quantization.positionToSteps(points[it].x) }),
        y = deltas(IntArray(points.size) { Quantization.positionToSteps(points[it].y) }),
        t = deltas(IntArray(points.size) { points[it].timeMs.toInt() }),
        pressure = optional(IntArray(points.size) { Quantization.pressureToSteps(points[it].pressure) }),
        tilt = optional(IntArray(points.size) { Quantization.angleToSteps(points[it].tiltRad) }),
        orientation = optional(IntArray(points.size) { Quantization.angleToSteps(points[it].orientationRad) }),
    )
}

internal fun decodePoints(r: PointsRecord): List<StrokePoint> {
    val n = r.x.size
    if (r.y.size != n || r.t.size != n) throw CorruptDataException("point channels differ in length")
    fun check(channel: IntArray, name: String) {
        if (channel.isNotEmpty() && channel.size != n) throw CorruptDataException("$name channel has wrong length")
    }
    check(r.pressure, "pressure")
    check(r.tilt, "tilt")
    check(r.orientation, "orientation")

    var x = 0
    var y = 0
    var t = 0
    return List(n) { i ->
        x += r.x[i]
        y += r.y[i]
        t += r.t[i]
        StrokePoint(
            x = Quantization.stepsToPosition(x),
            y = Quantization.stepsToPosition(y),
            timeMs = t.toLong(),
            pressure = if (r.pressure.isEmpty()) StrokePoint.NO_VALUE else Quantization.stepsToPressure(r.pressure[i]),
            tiltRad = if (r.tilt.isEmpty()) StrokePoint.NO_VALUE else Quantization.stepsToAngle(r.tilt[i]),
            orientationRad = if (r.orientation.isEmpty()) StrokePoint.NO_VALUE else Quantization.stepsToAngle(r.orientation[i]),
        )
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
