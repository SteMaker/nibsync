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
import de.nibsync.model.Layer
import de.nibsync.model.Orientation
import de.nibsync.model.PageFormat
import de.nibsync.model.PaperSize
import de.nibsync.model.Quantization
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeId
import de.nibsync.model.StrokePoint

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
