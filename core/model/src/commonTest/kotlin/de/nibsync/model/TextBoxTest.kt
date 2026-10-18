package de.nibsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextBoxTest {
    private val box = TextBox(StrokeId("t"), 10f, 20f, 80f, 15f, RichText.plain("Hallo\nWelt"), 0, DeviceId("a"))

    @Test
    fun textSitsBetweenMarkerAndInk() {
        assertEquals(Layer.TEXT, box.layer)
        assertTrue(Layer.MARKER.zIndex < Layer.TEXT.zIndex && Layer.TEXT.zIndex < Layer.INK.zIndex)
        assertTrue(!Layer.TEXT.holdsStrokes)
    }

    @Test
    fun boundsFollowPositionAndSize() {
        assertEquals(de.nibsync.model.geometry.Rect(10f, 20f, 90f, 35f), box.bounds)
    }

    @Test
    fun draftRoundTripAndMove() {
        val draft = box.toDraft() as TextBoxDraft
        assertEquals(TextBoxDraft(15f, 18f, 80f, 15f, box.text), draft.translated(5f, -2f))
        assertEquals(StrokeId("u"), box.withId(StrokeId("u")).id)
    }

    @Test
    fun plainTextSplitsParagraphs() {
        assertEquals(listOf("Hallo", "Welt"), box.text.paragraphs.map { it.text })
        assertEquals("Hallo\nWelt", box.text.plainText)
        assertTrue(RichText.EMPTY.isBlank)
        assertTrue(RichText.plain(" \n ").isBlank)
    }

    @Test
    fun marksMustFitTheText() {
        TextParagraph("abc", marks = listOf(TextMark(0, 3, MarkStyle.Bold)))
        assertFailsWith<IllegalArgumentException> { TextParagraph("abc", marks = listOf(TextMark(1, 4, MarkStyle.Bold))) }
        assertFailsWith<IllegalArgumentException> { TextParagraph("abc", marks = listOf(TextMark(2, 2, MarkStyle.Italic))) }
        assertFailsWith<IllegalArgumentException> { MarkStyle.Size(0f) }
        assertFailsWith<IllegalArgumentException> { RichText(emptyList()) }
        assertFailsWith<IllegalArgumentException> { TextBox(StrokeId("t"), 0f, 0f, 0f, 0f, RichText.EMPTY, 0, DeviceId("a")) }
    }
}
