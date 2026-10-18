package com.stemaker.nibsync.text

import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import org.junit.Test

class RichTextConversionTest {
    private val sample = RichText(
        listOf(
            TextParagraph(
                "Titel fett",
                marks = listOf(TextMark(0, 10, MarkStyle.Size(18f)), TextMark(6, 10, MarkStyle.Bold)),
            ),
            TextParagraph("Erster Punkt", ListKind.BULLET),
            TextParagraph("Unterpunkt", ListKind.NUMBERED, indent = 1, marks = listOf(TextMark(0, 5, MarkStyle.Italic))),
            TextParagraph("Handschrift", marks = listOf(TextMark(0, 11, MarkStyle.Font(TextFont.HANDWRITING)))),
        ),
    )

    @Test
    fun roundTripsThroughTheEditor() {
        val controller = TextEditController.create(sample)
        assertThat(controller.text).isEqualTo(sample)
    }

    @Test
    fun emptyTextStaysOneParagraph() {
        assertThat(TextEditController.create(RichText.EMPTY).text).isEqualTo(RichText.EMPTY)
    }

    @Test
    fun boldOnASelectionBecomesAMark() {
        val controller = TextEditController.create(RichText.plain("Hallo Welt"))
        controller.state.selection = TextRange(6, 10)
        controller.toggleBold()
        assertThat(controller.isBold).isTrue()
        assertThat(controller.text.paragraphs.single().marks).containsExactly(TextMark(6, 10, MarkStyle.Bold))
    }

    @Test
    fun fontAndSizeApplyToTheSelection() {
        val controller = TextEditController.create(RichText.plain("abc def"))
        controller.state.selection = TextRange(0, 3)
        controller.setFont(TextFont.SERIF)
        controller.setSize(24f)
        assertThat(controller.font).isEqualTo(TextFont.SERIF)
        assertThat(controller.sizePt).isEqualTo(24f)
        assertThat(controller.text.paragraphs.single().marks).containsExactly(
            TextMark(0, 3, MarkStyle.Size(24f)),
            TextMark(0, 3, MarkStyle.Font(TextFont.SERIF)),
        )
    }

    @Test
    fun listsToggleOnTheCurrentParagraph() {
        val controller = TextEditController.create(RichText.plain("eins\nzwei"))
        controller.state.selection = TextRange(6)
        controller.toggleNumberedList()
        assertThat(controller.isNumberedList).isTrue()
        assertThat(controller.text.paragraphs.map { it.list }).containsExactly(ListKind.NONE, ListKind.NUMBERED).inOrder()
        controller.toggleBulletList()
        assertThat(controller.text.paragraphs[1].list).isEqualTo(ListKind.BULLET)
    }

    @Test
    fun styleChosenBeforeAListIsKeptForTyping() {
        val controller = TextEditController.create(RichText.EMPTY)
        controller.setFont(TextFont.HANDWRITING)
        controller.setSize(24f)
        controller.toggleBulletList()
        assertThat(controller.font).isEqualTo(TextFont.HANDWRITING)
        assertThat(controller.sizePt).isEqualTo(24f)
    }

    @Test
    fun pageLayoutHasPrefixesAndIndents() {
        val annotated = RichTextLayout.annotated(
            RichText(
                listOf(
                    TextParagraph("a", ListKind.NUMBERED),
                    TextParagraph("b", ListKind.NUMBERED),
                    TextParagraph("c", ListKind.BULLET, indent = 1),
                    TextParagraph("d", ListKind.NUMBERED),
                    TextParagraph("e"),
                    TextParagraph("f", ListKind.NUMBERED),
                ),
            ),
        ) { 5f }
        assertThat(annotated.text).isEqualTo("1. a 2. b ◦ c 3. d e 1. f")
        assertThat(annotated.paragraphStyles).hasSize(6)
        val styles = annotated.paragraphStyles.map { it.item }
        assertThat(styles[0].textIndent!!.restLine).isEqualTo(20.sp)
        assertThat(styles[0].textIndent!!.firstLine).isEqualTo(15.sp)
        assertThat(styles[2].textIndent!!.restLine).isEqualTo(40.sp)
        assertThat(styles[4]).isEqualTo(ParagraphStyle())
    }

    @Test
    fun pageLayoutAppliesMarksAfterThePrefix() {
        val annotated = RichTextLayout.annotated(
            RichText(listOf(TextParagraph("fett", ListKind.BULLET, marks = listOf(TextMark(0, 4, MarkStyle.Bold))))),
        ) { 5f }
        val bold = annotated.spanStyles.single()
        assertThat(annotated.text.substring(bold.start, bold.end)).isEqualTo("fett")
    }
}
