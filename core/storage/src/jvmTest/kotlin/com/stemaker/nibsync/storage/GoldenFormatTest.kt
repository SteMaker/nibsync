package com.stemaker.nibsync.storage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

/**
 * Guards the on-disk format against accidental changes. The golden files in
 * `src/jvmTest/resources/golden` were written by format version 1 and must stay readable
 * forever. If the format is changed on purpose, add new golden files for the new version and
 * keep the old ones; regenerate only with `NIBSYNC_UPDATE_GOLDEN=1 ./gradlew :core:storage:jvmTest`.
 */
class GoldenFormatTest {
    private val ops = listOf(
        addOp(1, DEV_A, penStroke("11111111-1111-4111-8111-111111111111"), highlighterStroke("22222222-2222-4222-8222-222222222222")),
        addOp(2, DEV_B, penStroke("33333333-3333-4333-8333-333333333333", author = DEV_B, withPressure = false)),
        removeOp(3, DEV_A, "11111111-1111-4111-8111-111111111111"),
    )

    // Added in format version 1 later on (op type 3); older readers skip it.
    private val backgroundOps = listOf(
        com.stemaker.nibsync.document.DocumentOp.SetBackground(
            com.stemaker.nibsync.document.OpStamp(4, DEV_A),
            com.stemaker.nibsync.model.BackgroundSpec(com.stemaker.nibsync.model.BackgroundKind.GRID, 5f),
        ),
    )

    // Added in format version 1 later on (op type 4); older readers skip it.
    private val textOps = listOf(
        com.stemaker.nibsync.document.DocumentOp.AddTextBoxes(com.stemaker.nibsync.document.OpStamp(5, DEV_A), listOf(formattedTextBox("44444444-4444-4444-8444-444444444444"))),
    )

    // Added in format version 1 later on (op type 5); older readers skip it.
    private val fileOps = listOf(
        com.stemaker.nibsync.document.DocumentOp.AddFiles(com.stemaker.nibsync.document.OpStamp(6, DEV_A), listOf(sampleImage("55555555-5555-4555-8555-555555555555"), sampleLink("66666666-6666-4666-8666-666666666666"))),
    )

    // Added in format version 1 later on (op types 6 and 7); older readers skip them.
    private val todoOps = listOf(
        com.stemaker.nibsync.document.DocumentOp.AddTodos(
            com.stemaker.nibsync.document.OpStamp(7, DEV_A),
            listOf(textTodo("77777777-7777-4777-8777-777777777777", "88888888-8888-4888-8888-888888888888"), inkTodo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
        ),
        com.stemaker.nibsync.document.DocumentOp.SetTodoDone(
            com.stemaker.nibsync.document.OpStamp(8, DEV_B),
            com.stemaker.nibsync.model.StrokeId("88888888-8888-4888-8888-888888888888"),
            true,
        ),
    )

    // Added in format version 1 later on (op type 8); older readers skip it.
    private val linkOps = listOf(
        com.stemaker.nibsync.document.DocumentOp.AddLinks(
            com.stemaker.nibsync.document.OpStamp(9, DEV_A),
            listOf(
                com.stemaker.nibsync.model.Anchor(
                    com.stemaker.nibsync.model.StrokeId("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                    com.stemaker.nibsync.model.StrokeId("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                    10f, 20.5f, "Kapitel 1", 1_700_000_000_000, DEV_A,
                ),
                com.stemaker.nibsync.model.NoteLink(
                    com.stemaker.nibsync.model.StrokeId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    30f, 40f, com.stemaker.nibsync.model.DocumentId("ffffffff-ffff-4fff-8fff-ffffffffffff"),
                    com.stemaker.nibsync.model.StrokeId("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), "Projekt", "Kapitel 1", 1_700_000_000_001, DEV_A,
                ),
                com.stemaker.nibsync.model.NoteLink(
                    com.stemaker.nibsync.model.StrokeId("12121212-1212-4212-8212-121212121212"),
                    30f, 60f, com.stemaker.nibsync.model.DocumentId("ffffffff-ffff-4fff-8fff-ffffffffffff"),
                    null, "Projekt", null, 1_700_000_000_002, DEV_A,
                ),
            ),
        ),
    )

    // Added in format version 1 later on (paragraph field "c"); older readers show the further
    // line as a list item of its own.
    private val continuedLineOps = listOf(
        com.stemaker.nibsync.document.DocumentOp.AddTextBoxes(
            com.stemaker.nibsync.document.OpStamp(10, DEV_A),
            listOf(
                com.stemaker.nibsync.model.TextBox(
                    com.stemaker.nibsync.model.StrokeId("34343434-3434-4434-8434-343434343434"), 10f, 20f, 100f, 15f,
                    com.stemaker.nibsync.model.RichText(
                        listOf(
                            com.stemaker.nibsync.model.TextParagraph("Punkt", com.stemaker.nibsync.model.ListKind.NUMBERED),
                            com.stemaker.nibsync.model.TextParagraph("weiter", com.stemaker.nibsync.model.ListKind.NUMBERED, continued = true),
                        ),
                    ),
                    1_700_000_000_003, DEV_A,
                ),
            ),
        ),
    )

    // Added in format version 1 later on (op types 9 and 10); older readers skip them.
    private val tableOps = run {
        fun id(s: String) = com.stemaker.nibsync.model.StrokeId(s)
        val r1 = id("51515151-5151-4515-8515-515151515151")
        val r2 = id("52525252-5252-4525-8525-525252525252")
        val c1 = id("61616161-6161-4616-8616-616161616161")
        val c2 = id("62626262-6262-4626-8626-626262626262")
        val table = id("71717171-7171-4717-8717-717171717171")
        listOf(
            com.stemaker.nibsync.document.DocumentOp.AddTables(
                com.stemaker.nibsync.document.OpStamp(11, DEV_A),
                listOf(
                    com.stemaker.nibsync.model.TableElement(
                        id("70707070-7070-4707-8707-707070707070"), table, 10f, 30f,
                        listOf(com.stemaker.nibsync.model.TableColumn(c1, 40f), com.stemaker.nibsync.model.TableColumn(c2, 55.5f)),
                        listOf(com.stemaker.nibsync.model.TableRow(r1), com.stemaker.nibsync.model.TableRow(r2)),
                        header = true,
                        cells = mapOf(
                            com.stemaker.nibsync.model.CellKey(r1, c1) to com.stemaker.nibsync.model.TableCell(com.stemaker.nibsync.model.RichText.plain("Zusammen"), 5f),
                        ),
                        // The header row's two cells joined.
                        merges = listOf(com.stemaker.nibsync.model.CellMerge(r1, c1, r1, c2)),
                        createdAtEpochMs = 1_700_000_000_004, author = DEV_A,
                    ),
                ),
            ),
            com.stemaker.nibsync.document.DocumentOp.SetTableCells(
                com.stemaker.nibsync.document.OpStamp(12, DEV_B), table,
                listOf(com.stemaker.nibsync.document.CellUpdate(com.stemaker.nibsync.model.CellKey(r2, c2), com.stemaker.nibsync.model.TableCell(com.stemaker.nibsync.model.RichText.plain("42"), 5f))),
            ),
        )
    }

    private val goldenDir = "src/jvmTest/resources/golden".toPath()

    private fun encodedLog(ops: List<com.stemaker.nibsync.document.DocumentOp> = this.ops): okio.ByteString {
        val b = Buffer().write(OpLogCodec.header())
        ops.forEach { b.write(OpLogCodec.encodeRecord(it)) }
        return b.readByteString()
    }

    private fun golden(name: String): okio.ByteString {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden file $name" }
        return stream.use { okio.ByteString.of(*it.readBytes()) }
    }

    private fun metaJson(): String {
        var text = ""
        runTest {
            val fs = FakeFileSystem()
            FileDocumentRepository(fs, "/r".toPath(), StandardTestDispatcher(testScheduler)).create(META)
            text = fs.read("/r/${META.id.value}/meta.json".toPath()) { readUtf8() }
        }
        return text
    }

    @Test
    fun goldenFilesCanBeRegenerated() {
        if (System.getenv("NIBSYNC_UPDATE_GOLDEN") != "1") return
        FileSystem.SYSTEM.createDirectories(goldenDir)
        FileSystem.SYSTEM.write(goldenDir / "ops-v1.oplog") { write(encodedLog()) }
        FileSystem.SYSTEM.write(goldenDir / "meta-v1.json") { writeUtf8(metaJson()) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-background.oplog") { write(encodedLog(backgroundOps)) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-text.oplog") { write(encodedLog(textOps)) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-files.oplog") { write(encodedLog(fileOps)) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-todos.oplog") { write(encodedLog(todoOps)) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-links.oplog") { write(encodedLog(linkOps)) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-continued-line.oplog") { write(encodedLog(continuedLineOps)) }
        FileSystem.SYSTEM.write(goldenDir / "ops-v1-tables.oplog") { write(encodedLog(tableOps)) }
    }

    @Test
    fun tableOpsAreStable() {
        val golden = golden("ops-v1-tables.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(tableOps)
        assertThat(encodedLog(tableOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun continuedLineIsStable() {
        val golden = golden("ops-v1-continued-line.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(continuedLineOps)
        assertThat(encodedLog(continuedLineOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun linkOpsAreStable() {
        val golden = golden("ops-v1-links.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(linkOps)
        assertThat(encodedLog(linkOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun todoOpsAreStable() {
        val golden = golden("ops-v1-todos.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(todoOps)
        assertThat(encodedLog(todoOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun fileOpIsStable() {
        val golden = golden("ops-v1-files.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(fileOps)
        assertThat(encodedLog(fileOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun textOpIsStable() {
        val golden = golden("ops-v1-text.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(textOps)
        assertThat(encodedLog(textOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun backgroundOpIsStable() {
        val golden = golden("ops-v1-background.oplog")
        assertThat(OpLogCodec.read(Buffer().write(golden)).ops).isEqualTo(backgroundOps)
        assertThat(encodedLog(backgroundOps).hex()).isEqualTo(golden.hex())
    }

    @Test
    fun goldenOpLogDecodesToKnownOps() {
        val result = OpLogCodec.read(Buffer().write(golden("ops-v1.oplog")))
        assertThat(result.truncatedTail).isFalse()
        assertThat(result.ops).isEqualTo(ops)
    }

    @Test
    fun encodingIsByteStable() {
        assertThat(encodedLog().hex()).isEqualTo(golden("ops-v1.oplog").hex())
    }

    @Test
    fun goldenMetaLoads() = runTest {
        val fs = FakeFileSystem()
        val dir = "/r/${META.id.value}".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "meta.json") { write(golden("meta-v1.json")) }
        val loaded = FileDocumentRepository(fs, "/r".toPath(), StandardTestDispatcher(testScheduler)).load(META.id)
        assertThat(loaded.meta).isEqualTo(META)
    }

    @Test
    fun metaEncodingIsStable() {
        assertThat(metaJson()).isEqualTo(golden("meta-v1.json").utf8())
    }
}
