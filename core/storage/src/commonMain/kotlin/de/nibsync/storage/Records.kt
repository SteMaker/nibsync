package de.nibsync.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// On-disk representation. These classes ARE the file format: never rename serial names or
// change field meaning; add new optional fields with defaults and bump the format version only
// for incompatible changes. The domain model is mapped in [RecordMapping].

@Serializable
internal data class MetaRecord(
    @SerialName("formatVersion") val formatVersion: Int,
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("paper") val paper: String,
    @SerialName("orientation") val orientation: String,
    @SerialName("background") val background: BackgroundRecord,
    @SerialName("createdAt") val createdAtEpochMs: Long,
)

@Serializable
internal data class BackgroundRecord(
    @SerialName("kind") val kind: String,
    @SerialName("spacingMm") val spacingMm: Float,
)

/** One entry of an op log. [type] selects which of the payload fields is used. */
@Serializable
internal data class OpRecord(
    @SerialName("c") val counter: Long,
    @SerialName("d") val device: String,
    @SerialName("t") val type: Int,
    @SerialName("s") val strokes: List<StrokeRecord> = emptyList(),
    @SerialName("r") val removed: List<String> = emptyList(),
    @SerialName("b") val background: BackgroundRecord? = null,
    @SerialName("x") val textBoxes: List<TextBoxRecord> = emptyList(),
) {
    companion object {
        const val TYPE_ADD = 1
        const val TYPE_REMOVE = 2
        const val TYPE_BACKGROUND = 3
        const val TYPE_ADD_TEXT = 4
    }
}

/** A text box; position and size in [de.nibsync.model.Quantization] position steps. */
@Serializable
internal data class TextBoxRecord(
    @SerialName("id") val id: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("w") val width: Int,
    @SerialName("h") val height: Int,
    @SerialName("ts") val createdAtEpochMs: Long,
    @SerialName("a") val author: String,
    @SerialName("p") val paragraphs: List<ParagraphRecord>,
)

@Serializable
internal data class ParagraphRecord(
    @SerialName("t") val text: String,
    @SerialName("l") val list: String = "NONE",
    @SerialName("n") val indent: Int = 0,
    @SerialName("m") val marks: List<MarkRecord> = emptyList(),
)

/**
 * A character style over [start] until [end] (exclusive). [kind]: "b" bold, "i" italic,
 * "z" size ([value] = points), "f" font ([value] = TextFont name). Unknown kinds are skipped.
 */
@Serializable
internal data class MarkRecord(
    @SerialName("s") val start: Int,
    @SerialName("e") val end: Int,
    @SerialName("k") val kind: String,
    @SerialName("v") val value: String? = null,
)

@Serializable
internal data class StrokeRecord(
    @SerialName("id") val id: String,
    @SerialName("l") val layer: String,
    @SerialName("f") val family: String,
    @SerialName("c") val colorArgb: Int,
    @SerialName("w") val sizeMm: Float,
    @SerialName("i") val tool: String,
    @SerialName("ts") val createdAtEpochMs: Long,
    @SerialName("a") val author: String,
    @SerialName("p") val points: PointsRecord,
)

/**
 * Stroke points as parallel, delta-encoded integer channels on the [de.nibsync.model.Quantization]
 * grid. Optional channels are empty when no point of the stroke carries that value.
 */
@Serializable
internal class PointsRecord(
    @SerialName("x") val x: IntArray,
    @SerialName("y") val y: IntArray,
    @SerialName("t") val t: IntArray,
    @SerialName("p") val pressure: IntArray = IntArray(0),
    @SerialName("tl") val tilt: IntArray = IntArray(0),
    @SerialName("o") val orientation: IntArray = IntArray(0),
)
