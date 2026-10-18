package de.nibsync.storage

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
        de.nibsync.document.DocumentOp.SetBackground(
            de.nibsync.document.OpStamp(4, DEV_A),
            de.nibsync.model.BackgroundSpec(de.nibsync.model.BackgroundKind.GRID, 5f),
        ),
    )

    // Added in format version 1 later on (op type 4); older readers skip it.
    private val textOps = listOf(
        de.nibsync.document.DocumentOp.AddTextBoxes(de.nibsync.document.OpStamp(5, DEV_A), listOf(formattedTextBox("44444444-4444-4444-8444-444444444444"))),
    )

    private val goldenDir = "src/jvmTest/resources/golden".toPath()

    private fun encodedLog(ops: List<de.nibsync.document.DocumentOp> = this.ops): okio.ByteString {
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
