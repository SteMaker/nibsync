package com.stemaker.nibsync.storage

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
    @SerialName("f") val files: List<FileElementRecord> = emptyList(),
    @SerialName("o") val todos: List<TodoRecord> = emptyList(),
    @SerialName("td") val todoDone: TodoDoneRecord? = null,
    @SerialName("n") val links: List<LinkRecord> = emptyList(),
    @SerialName("tb") val tables: List<TableRecord> = emptyList(),
    @SerialName("tc") val tableCells: TableCellsRecord? = null,
) {
    companion object {
        const val TYPE_ADD = 1
        const val TYPE_REMOVE = 2
        const val TYPE_BACKGROUND = 3
        const val TYPE_ADD_TEXT = 4
        const val TYPE_ADD_FILES = 5
        const val TYPE_ADD_TODOS = 6
        const val TYPE_TODO_DONE = 7
        const val TYPE_ADD_LINKS = 8
        const val TYPE_ADD_TABLES = 9
        const val TYPE_TABLE_CELLS = 10
    }
}

/**
 * A picture ([kind] "image") or file link ("link"); position and size in position steps,
 * [sha256] and [mimeType] name the asset file. Unknown kinds are skipped.
 */
@Serializable
internal data class FileElementRecord(
    @SerialName("k") val kind: String,
    @SerialName("id") val id: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("w") val width: Int,
    @SerialName("h") val height: Int,
    @SerialName("ts") val createdAtEpochMs: Long,
    @SerialName("a") val author: String,
    @SerialName("s") val sha256: String,
    @SerialName("m") val mimeType: String,
    @SerialName("n") val name: String? = null,
)

/**
 * An anchor ([kind] "anchor": [anchor] id and [name]) or a link to a note ("note": [document],
 * optional [anchor], the names [name] (document title) and [anchorName] when it was made).
 * Position in position steps. Unknown kinds are skipped.
 */
@Serializable
internal data class LinkRecord(
    @SerialName("k") val kind: String,
    @SerialName("id") val id: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("ts") val createdAtEpochMs: Long,
    @SerialName("a") val author: String,
    @SerialName("an") val anchor: String? = null,
    @SerialName("nm") val name: String? = null,
    @SerialName("doc") val document: String? = null,
    @SerialName("ann") val anchorName: String? = null,
)

/** A text box; position and size in [com.stemaker.nibsync.model.Quantization] position steps. */
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

/**
 * A to-do; position and size in position steps. Its content is either typed text
 * ([paragraphs]) or handwriting ([strokes], positions relative to the content area).
 */
@Serializable
internal data class TodoRecord(
    @SerialName("id") val id: String,
    @SerialName("k") val task: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("w") val width: Int,
    @SerialName("h") val height: Int,
    @SerialName("ts") val createdAtEpochMs: Long,
    @SerialName("a") val author: String,
    @SerialName("p") val paragraphs: List<ParagraphRecord>? = null,
    @SerialName("s") val strokes: List<StrokeRecord>? = null,
)

/**
 * A table: [columns] with their widths, [rows] by id, the [cells] holding content and the
 * joined areas [merges]; positions and sizes in position steps.
 */
@Serializable
internal data class TableRecord(
    @SerialName("id") val id: String,
    @SerialName("k") val table: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("cols") val columns: List<ColumnRecord>,
    @SerialName("rows") val rows: List<String>,
    @SerialName("hd") val header: Boolean = false,
    @SerialName("cl") val cells: List<CellRecord> = emptyList(),
    @SerialName("mg") val merges: List<MergeRecord> = emptyList(),
    @SerialName("ts") val createdAtEpochMs: Long,
    @SerialName("a") val author: String,
)

@Serializable
internal data class ColumnRecord(
    @SerialName("i") val id: String,
    @SerialName("w") val width: Int,
)

/** Content of the cell in row [row], column [column]; [height] of its text in position steps. */
@Serializable
internal data class CellRecord(
    @SerialName("r") val row: String,
    @SerialName("c") val column: String,
    @SerialName("p") val paragraphs: List<ParagraphRecord>,
    @SerialName("h") val height: Int = 0,
)

/** Cells joined from row [fromRow], column [fromColumn] to row [toRow], column [toColumn]. */
@Serializable
internal data class MergeRecord(
    @SerialName("r0") val fromRow: String,
    @SerialName("c0") val fromColumn: String,
    @SerialName("r1") val toRow: String,
    @SerialName("c1") val toColumn: String,
)

/** New content of cells of the table [table]. */
@Serializable
internal data class TableCellsRecord(
    @SerialName("k") val table: String,
    @SerialName("cl") val cells: List<CellRecord>,
)

/** Done state of the to-do task [task]. */
@Serializable
internal data class TodoDoneRecord(
    @SerialName("k") val task: String,
    @SerialName("d") val done: Boolean,
)

@Serializable
internal data class ParagraphRecord(
    @SerialName("t") val text: String,
    @SerialName("l") val list: String = "NONE",
    @SerialName("n") val indent: Int = 0,
    @SerialName("m") val marks: List<MarkRecord> = emptyList(),
    /** A further line of the list item above; older readers show it as an item of its own. */
    @SerialName("c") val continued: Boolean = false,
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
 * Stroke points as parallel, delta-encoded integer channels on the [com.stemaker.nibsync.model.Quantization]
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
