package com.stemaker.nibsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaFilesTest {
    private val sha = "ab".repeat(32)

    @Test
    fun anyFileGetsAType() {
        val text = "hallo".encodeToByteArray()
        assertEquals("text/plain", MediaFiles.fileType(text, null, "notes.txt"))
        assertEquals("application/x-custom", MediaFiles.fileType(text, "Application/X-Custom", "a.bin"))
        // A declared picture type the content does not match is not trusted.
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", MediaFiles.fileType(text, "image/png", "brief.docx"))
        assertEquals(Asset.BINARY, MediaFiles.fileType(text, "image/png", "x"))
        assertEquals(Asset.BINARY, MediaFiles.fileType(text, null, "data.xyz"))
        assertEquals(Asset.PDF, MediaFiles.fileType("%PDF-1.4".encodeToByteArray(), Asset.BINARY, "scan"))
    }

    @Test
    fun filesThatAreNoPicturesBecomeLinks() {
        val file = PickedFile("x".encodeToByteArray(), "image/gif", "anim.gif")
        val draft = assertIs<FileLinkDraft>(MediaFiles.draft(file, sha, null, 210f, 105f, 50f))
        assertEquals("anim.gif", draft.fileName)
    }

    @Test
    fun linksShowTheirTypeOrExtension() {
        assertEquals("PDF", FileLink.badgeFor(Asset.PDF, "x"))
        assertEquals("DOCX", FileLink.badgeFor("application/msword", "Brief.docx"))
        assertEquals("FILE", FileLink.badgeFor(Asset.BINARY, "README"))
        assertEquals("FILE", FileLink.badgeFor(Asset.BINARY, "archive.backup"))
    }
}
