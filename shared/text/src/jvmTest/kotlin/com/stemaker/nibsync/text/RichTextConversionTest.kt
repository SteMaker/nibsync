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

    @Test
    fun pastedLineBreaksAndOverlongSpansDoNotBreakTheText() {
        // As after pasting from a web page on Windows: "\r" at the paragraph end, inside bold.
        @OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)
        val document = com.mohamedrejeb.richeditor.document.RichTextDocument(
            listOf(
                com.mohamedrejeb.richeditor.document.RichTextBlock(
                    text = "Fett normal\r",
                    type = com.mohamedrejeb.richeditor.document.RichTextBlockType.ListItem(ordered = false, indent = 0),
                    spans = listOf(com.mohamedrejeb.richeditor.document.RichTextSpanMark.Bold(0 until 12)),
                ),
                com.mohamedrejeb.richeditor.document.RichTextBlock(text = "Ende", type = com.mohamedrejeb.richeditor.document.RichTextBlockType.Paragraph, spans = emptyList()),
            ),
        )
        val text = RichTextConversion.fromDocument(document)
        assertThat(text.paragraphs.map { it.text }).containsExactly("Fett normal", "Ende").inOrder()
        assertThat(text.paragraphs.first().marks).containsExactly(TextMark(0, 11, MarkStyle.Bold))
        assertThat(text.paragraphs.first().list).isEqualTo(ListKind.BULLET)
    }

    @Test
    fun selectAllAndBoldTwiceAfterAPaste() {
        val controller = TextEditController.create(
            RichText(
                listOf(
                    TextParagraph("Fett normal\r", ListKind.BULLET, marks = listOf(TextMark(0, 4, MarkStyle.Bold))),
                    TextParagraph("Zwei stark\r", ListKind.BULLET, marks = listOf(TextMark(5, 10, MarkStyle.Bold))),
                    TextParagraph("Ende"),
                ),
            ),
        )
        controller.state.selection = TextRange(0, controller.state.annotatedString.text.length)
        controller.toggleBold()
        controller.toggleBold()
        val text = controller.text
        assertThat(text.plainText).isEqualTo("Fett normal\nZwei stark\nEnde")
        assertThat(text.paragraphs.none { p -> '\r' in p.text }).isTrue()
    }

    @Test
    fun shiftEnterGoesOnInTheSameItemAndEnterStartsTheNext() {
        val controller = TextEditController.create(
            RichText(listOf(TextParagraph("eins zwei", ListKind.NUMBERED, marks = listOf(TextMark(5, 9, MarkStyle.Bold))), TextParagraph("drei", ListKind.NUMBERED))),
        )
        // Cursor behind "eins " ("1. eins |zwei").
        controller.state.selection = TextRange(8)
        assertThat(controller.lineBreak(soft = true)).isTrue()
        assertThat(controller.text.paragraphs).containsExactly(
            TextParagraph("eins ", ListKind.NUMBERED),
            TextParagraph("zwei", ListKind.NUMBERED, marks = listOf(TextMark(0, 4, MarkStyle.Bold)), continued = true),
            TextParagraph("drei", ListKind.NUMBERED),
        ).inOrder()
        controller.state.addTextAfterSelection("x")
        assertThat(controller.text.paragraphs[1].text).isEqualTo("xzwei")
        // The editor goes on counting after the further line.
        assertThat(controller.state.annotatedString.text).contains("2. drei")

        // Enter at the end of the further line starts the next item.
        controller.state.selection = TextRange(controller.state.annotatedString.text.indexOf("xzwei") + 5)
        assertThat(controller.lineBreak(soft = false)).isTrue()
        assertThat(controller.text.paragraphs.map { it.text to it.continued }).containsExactly(
            "eins " to false, "xzwei" to true, "" to false, "drei" to false,
        ).inOrder()
        // Elsewhere both keys are left to the editor.
        controller.state.selection = TextRange(3)
        assertThat(controller.lineBreak(soft = false)).isFalse()
        val plain = TextEditController.create(RichText.plain("abc"))
        plain.state.selection = TextRange(1)
        assertThat(plain.lineBreak(soft = true)).isFalse()
    }

    @Test
    fun aFurtherLineIsIndentedLikeItsItemAndKeepsTheNumbering() {
        val text = RichText(
            listOf(
                TextParagraph("a", ListKind.NUMBERED),
                TextParagraph("a2", ListKind.NUMBERED, continued = true),
                TextParagraph("b", ListKind.NUMBERED),
                TextParagraph("c", ListKind.BULLET, indent = 1),
                TextParagraph("c2", ListKind.BULLET, indent = 1, continued = true),
            ),
        )
        assertThat(TextEditController.create(text).text).isEqualTo(text)
        val annotated = RichTextLayout.annotated(text) { 5f }
        assertThat(annotated.text).isEqualTo("1. a a2 2. b ◦ c c2")
        val styles = annotated.paragraphStyles.map { it.item }
        assertThat(styles[1].textIndent).isEqualTo(androidx.compose.ui.text.style.TextIndent(20.sp, 20.sp))
        assertThat(styles[4].textIndent).isEqualTo(androidx.compose.ui.text.style.TextIndent(40.sp, 40.sp))
    }

    @Test
    fun presetsFormatWholeParagraphs() {
        val controller = TextEditController.create(
            RichText(listOf(TextParagraph("Titel"), TextParagraph("Text mit kursiv", marks = listOf(TextMark(9, 15, MarkStyle.Italic))), TextParagraph("x = 1"))),
        )
        // The cursor in the first paragraph makes all of it a heading.
        controller.state.selection = TextRange(2)
        assertThat(controller.preset).isEqualTo(TextPreset.NORMAL)
        controller.applyPreset(TextPreset.HEADING_1)
        assertThat(controller.state.selection).isEqualTo(TextRange(2))
        assertThat(controller.preset).isEqualTo(TextPreset.HEADING_1)
        assertThat(controller.text.paragraphs[0].marks).containsExactly(
            TextMark(0, 5, MarkStyle.Bold),
            TextMark(0, 5, MarkStyle.Size(24f)),
            TextMark(0, 5, MarkStyle.Font(TextFont.SANS)),
        )
        assertThat(controller.text.paragraphs[1].marks).containsExactly(TextMark(9, 15, MarkStyle.Italic))

        // A selection over the last two paragraphs: code, italics stay.
        controller.state.selection = TextRange(8, 24)
        controller.applyPreset(TextPreset.CODE)
        val p = controller.text.paragraphs
        assertThat(p[1].marks).contains(TextMark(0, 15, MarkStyle.Font(TextFont.MONO)))
        assertThat(p[1].marks).contains(TextMark(9, 15, MarkStyle.Italic))
        assertThat(p[2].marks).contains(TextMark(0, 5, MarkStyle.Font(TextFont.MONO)))
        assertThat(p[0].marks).contains(TextMark(0, 5, MarkStyle.Size(24f)))

        // Back to normal: no bold, the default size and font.
        controller.state.selection = TextRange(1)
        controller.applyPreset(TextPreset.NORMAL)
        assertThat(controller.preset).isEqualTo(TextPreset.NORMAL)
        assertThat(controller.text.paragraphs[0].marks.map { it.style }).containsNoneOf(MarkStyle.Bold, MarkStyle.Size(24f))
    }

    @Test
    fun aListBelowAHeadingIsNotBold() {
        val controller = TextEditController.create(RichText.plain("Titel"))
        controller.state.selection = TextRange(5)
        controller.applyPreset(TextPreset.HEADING_2)
        controller.state.addTextAfterSelection("\n")
        controller.applyPreset(TextPreset.NORMAL)
        controller.toggleBulletList()
        controller.state.addTextAfterSelection("Punkt")
        val item = controller.text.paragraphs[1]
        assertThat(item.list).isEqualTo(ListKind.BULLET)
        assertThat(item.marks.map { it.style }).containsNoneOf(MarkStyle.Bold, MarkStyle.Size(18f))
    }
}
