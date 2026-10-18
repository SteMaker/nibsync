package com.stemaker.nibsync.storage

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDocumentRepositoryTest {
    private val fs = FakeFileSystem()
    private val root = "/data/documents".toPath()

    @AfterTest
    fun checkNoLeaks() = fs.checkNoOpenFiles()

    private fun TestScope.repo() = FileDocumentRepository(fs, root, StandardTestDispatcher(testScheduler))

    @Test
    fun assetsAreStoredByContent() = runTest {
        val repo = repo()
        repo.create(META)
        val bytes = "hello".encodeToByteArray()
        val sha = repo.putAsset(DOC, bytes)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha)
        assertTrue(repo.hasAsset(DOC, sha))
        assertEquals("/data/documents/${DOC.value}/assets/$sha", repo.assetPath(DOC, sha).toString())
        // Storing the same content again changes nothing.
        assertEquals(sha, repo.putAsset(DOC, bytes))
        assertEquals(listOf(sha), fs.list(root / DOC.value / "assets").map { it.name })
        repo.delete(DOC)
        assertTrue(!repo.hasAsset(DOC, sha))
    }

    @Test
    fun createListAndLoadMeta() = runTest {
        val repo = repo()
        assertEquals(emptyList(), repo.list())
        repo.create(META)
        assertTrue(repo.exists(DOC))
        assertEquals(listOf(META), repo.list())
        assertEquals(META, repo.load(DOC).meta)
        assertEquals(0, repo.load(DOC).strokeCount)
    }

    @Test
    fun loadMissingDocumentFails() = runTest {
        assertFailsWith<DocumentNotFoundException> { repo().load(DocumentId("nope")) }
    }

    @Test
    fun journaledOpsAreLoadedAgain() = runTest {
        val repo = repo()
        repo.create(META)
        val journal = repo.journal(DOC, DEV_A)
        journal.append(addOp(1, DEV_A, penStroke("s1"), highlighterStroke("h1")))
        journal.append(addOp(2, DEV_A, penStroke("s2")))
        journal.append(removeOp(3, DEV_A, "s1"))

        val loaded = repo.load(DOC)
        assertEquals(listOf("h1", "s2"), loaded.strokes.map { it.id.value })
        assertEquals(highlighterStroke("h1"), loaded.strokes[0])
        assertEquals(3, loaded.clock)
    }

    @Test
    fun logsOfAllDevicesAreMerged() = runTest {
        val repo = repo()
        repo.create(META)
        repo.journal(DOC, DEV_A).append(addOp(1, DEV_A, penStroke("a1")))
        repo.journal(DOC, DEV_B).append(addOp(1, DEV_B, penStroke("b1", author = DEV_B)))
        repo.journal(DOC, DEV_B).append(removeOp(2, DEV_B, "a1"))
        assertEquals(listOf("b1"), repo.load(DOC).strokes.map { it.id.value })
        assertEquals(
            listOf("device-a.oplog", "device-b.oplog"),
            fs.list(root / DOC.value / "ops").map { it.name },
        )
    }

    @Test
    fun truncatedLogIsRepairedBeforeAppending() = runTest {
        val repo = repo()
        repo.create(META)
        repo.journal(DOC, DEV_A).append(addOp(1, DEV_A, penStroke("s1")))
        repo.journal(DOC, DEV_A).append(addOp(2, DEV_A, penStroke("s2")))
        // Simulate a crash in the middle of writing the second record.
        val log = root / DOC.value / "ops" / "device-a.oplog"
        val bytes = fs.read(log) { readByteString() }
        fs.write(log) { write(bytes.substring(0, bytes.size - 5)) }
        assertEquals(listOf("s1"), repo.load(DOC).strokes.map { it.id.value })

        // A fresh app start appends after the valid prefix, not after the garbage.
        repo().journal(DOC, DEV_A).append(addOp(2, DEV_A, penStroke("s3")))
        assertEquals(listOf("s1", "s3"), repo.load(DOC).strokes.map { it.id.value })
    }

    @Test
    fun deleteRemovesEverything() = runTest {
        val repo = repo()
        repo.create(META)
        repo.journal(DOC, DEV_A).append(addOp(1, DEV_A, penStroke("s1")))
        repo.delete(DOC)
        assertFalse(repo.exists(DOC))
        assertEquals(emptyList(), repo.list())
    }

    @Test
    fun corruptMetaIsSkippedInList() = runTest {
        val repo = repo()
        repo.create(META)
        fs.createDirectories(root / "broken")
        fs.write(root / "broken" / "meta.json") { writeUtf8("{ not json") }
        assertEquals(listOf(META), repo.list())
        assertFailsWith<CorruptDataException> { repo.load(DocumentId("broken")) }
    }

    @Test
    fun metaFromNewerAppIsRejected() = runTest {
        val repo = repo()
        repo.create(META)
        val file = root / DOC.value / "meta.json"
        val text = fs.read(file) { readUtf8() }.replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        fs.write(file) { writeUtf8(text) }
        assertFailsWith<UnsupportedFormatException> { repo.load(DOC) }
    }

    @Test
    fun sessionStateSurvivesReload() = runTest {
        val repo = repo()
        repo.create(META)
        val session = DocumentSession(
            initial = repo.load(DOC),
            device = DEV_A,
            journal = repo.journal(DOC, DEV_A),
            scope = backgroundScope,
            clock = { 42L },
        )
        val brush = BrushSpec(BrushFamily.FOUNTAIN_PEN, 0xff000000.toInt(), 0.6f)
        // Raw, unquantized input as it comes from a stylus.
        val draft = StrokeDraft(
            brush, InputTool.STYLUS,
            listOf(StrokePoint(1.23456f, 2.34567f, 0, 0.123f, 0.4567f, 1.0101f), StrokePoint(3.3333f, 4.4444f, 7, 0.987f, 0.5f, 1.2f)),
        )
        session.addStroke(draft)
        session.addStroke(draft.copy(brush = BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffff00.toInt(), 5f)))
        session.undo()
        session.addStroke(draft)
        session.flush()

        val reloaded = repo.load(DOC)
        assertEquals(session.state.value.strokes, reloaded.strokes)
        assertEquals(session.state.value.clock, reloaded.clock)
    }

    @Test
    fun deviceIdIsStable() {
        val file = "/data/device-id".toPath()
        val first = DeviceIdStore.loadOrCreate(fs, file)
        assertEquals(first, DeviceIdStore.loadOrCreate(fs, file))
        fs.write(file) { writeUtf8("??") }
        val replaced = DeviceIdStore.loadOrCreate(fs, file)
        assertTrue(replaced != first)
    }
}
