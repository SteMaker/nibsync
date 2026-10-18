package de.nibsync.storage

import de.nibsync.model.Quantization
import de.nibsync.model.StrokePoint
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpLogCodecTest {
    private val ops = listOf(
        addOp(1, DEV_A, penStroke("s1"), highlighterStroke("h1")),
        addOp(2, DEV_A, penStroke("s2", withPressure = false)),
        removeOp(3, DEV_A, "s1", "h1"),
        de.nibsync.document.DocumentOp.SetBackground(
            de.nibsync.document.OpStamp(4, DEV_A),
            de.nibsync.model.BackgroundSpec(de.nibsync.model.BackgroundKind.DOTTED, 5f),
        ),
        de.nibsync.document.DocumentOp.AddTextBoxes(de.nibsync.document.OpStamp(5, DEV_A), listOf(formattedTextBox("t1"))),
        de.nibsync.document.DocumentOp.AddFiles(de.nibsync.document.OpStamp(6, DEV_A), listOf(sampleImage("i1"), sampleLink("l1"))),
    )

    private fun encode(list: List<de.nibsync.document.DocumentOp>): Buffer {
        val b = Buffer().write(OpLogCodec.header())
        list.forEach { b.write(OpLogCodec.encodeRecord(it)) }
        return b
    }

    @Test
    fun roundTripsAllOpTypes() {
        val result = OpLogCodec.read(encode(ops))
        assertEquals(ops, result.ops)
        assertFalse(result.truncatedTail)
    }

    @Test
    fun emptyInputIsAnEmptyLog() {
        val result = OpLogCodec.read(Buffer())
        assertEquals(emptyList(), result.ops)
        assertFalse(result.truncatedTail)
    }

    @Test
    fun truncatedLastRecordIsIgnored() {
        val full = encode(ops).readByteString()
        for (cut in 1..12) {
            val result = OpLogCodec.read(Buffer().write(full.substring(0, full.size - cut)))
            assertEquals(ops.dropLast(1), result.ops, "cut $cut")
            assertTrue(result.truncatedTail)
            assertEquals(encode(ops.dropLast(1)).size, result.validLength)
        }
    }

    @Test
    fun rejectsForeignFiles() {
        assertFailsWith<CorruptDataException> { OpLogCodec.read(Buffer().writeUtf8("hello world")) }
    }

    @Test
    fun rejectsNewerVersion() {
        assertFailsWith<UnsupportedFormatException> {
            OpLogCodec.read(Buffer().write(OpLogCodec.MAGIC).writeByte(99))
        }
    }

    @Test
    fun rejectsGarbageRecord() {
        val b = Buffer().write(OpLogCodec.header()).writeInt(3).write(byteArrayOf(-1, -1, -1))
        assertFailsWith<CorruptDataException> { OpLogCodec.read(b) }
    }

    @Test
    fun unknownOpTypesAreSkipped() {
        val unknown = OpRecord(counter = 9, device = "x", type = 77)
        val payload = kotlinx.serialization.cbor.Cbor.encodeToByteArray(OpRecord.serializer(), unknown)
        val b = encode(ops.take(1)).writeInt(payload.size).write(payload)
        assertEquals(ops.take(1), OpLogCodec.read(b).ops)
    }

    @Test
    fun unknownBackgroundKindBecomesBlank() {
        val record = OpRecord(counter = 9, device = "x", type = OpRecord.TYPE_BACKGROUND, background = BackgroundRecord("HEXAGONS", 6f))
        val payload = kotlinx.serialization.cbor.Cbor.encodeToByteArray(OpRecord.serializer(), record)
        val op = OpLogCodec.read(Buffer().write(OpLogCodec.header()).writeInt(payload.size).write(payload)).ops.single()
        assertEquals(
            de.nibsync.model.BackgroundSpec(de.nibsync.model.BackgroundKind.BLANK, 6f),
            (op as de.nibsync.document.DocumentOp.SetBackground).background,
        )
    }

    @Test
    fun badTextMarksAreDroppedNotFatal() {
        val record = OpRecord(
            counter = 9, device = "x", type = OpRecord.TYPE_ADD_TEXT,
            textBoxes = listOf(
                TextBoxRecord(
                    "t", 0, 0, 5000, 1000, 0, "x",
                    listOf(
                        ParagraphRecord(
                            "abc", list = "CHECKBOX",
                            marks = listOf(
                                MarkRecord(0, 2, "u"), // unknown kind
                                MarkRecord(1, 99, "b"), // clipped to the text
                                MarkRecord(0, 3, "z", "huge"), // bad value
                                MarkRecord(0, 3, "f", "COMIC"), // unknown font
                            ),
                        ),
                    ),
                ),
            ),
        )
        val payload = kotlinx.serialization.cbor.Cbor.encodeToByteArray(OpRecord.serializer(), record)
        val op = OpLogCodec.read(Buffer().write(OpLogCodec.header()).writeInt(payload.size).write(payload)).ops.single()
        val p = (op as de.nibsync.document.DocumentOp.AddTextBoxes).boxes.single().text.paragraphs.single()
        assertEquals(de.nibsync.model.ListKind.NONE, p.list)
        assertEquals(listOf(de.nibsync.model.TextMark(1, 3, de.nibsync.model.MarkStyle.Bold)), p.marks)
    }

    @Test
    fun pointEncodingIsCompact() {
        // A typical handwriting stroke: 200 points, small steps, full stylus data.
        val points = List(200) { i ->
            Quantization.quantize(StrokePoint(50f + i * 0.3f, 80f + (i % 7) * 0.2f, i * 4L, 0.4f + (i % 10) / 50f, 0.6f, 1.1f))
        }
        val stroke = penStroke("big").copy(points = points)
        val size = OpLogCodec.encodeRecord(addOp(1, DEV_A, stroke)).size
        // 6 channels * 200 points; deltas fit in 1-3 bytes each.
        assertTrue(size < 200 * 6 * 3, "record size $size")
    }

    @Test
    fun pointsDecodeToQuantizedValues() {
        val raw = listOf(StrokePoint(1.234f, 5.678f, 3, 0.3333f, 0.12345f, 2.71828f))
        assertEquals(Quantization.quantize(raw), decodePoints(encodePoints(raw)))
    }

    @Test
    fun inconsistentChannelsAreCorrupt() {
        assertFailsWith<CorruptDataException> {
            decodePoints(PointsRecord(intArrayOf(1, 2), intArrayOf(1), intArrayOf(0, 1)))
        }
        assertFailsWith<CorruptDataException> {
            decodePoints(PointsRecord(intArrayOf(1), intArrayOf(1), intArrayOf(0), pressure = intArrayOf(1, 2)))
        }
    }
}
