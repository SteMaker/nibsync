package com.stemaker.nibsync.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.isUnspecified
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import org.junit.Test

class HeadingTest {
    private val heading = TextParagraph("Titel", marks = listOf(TextMark(0, 5, MarkStyle.Bold), TextMark(0, 5, MarkStyle.Size(22f))))

    @Test
    fun aBoldLargeParagraphIsAHeading() {
        assertThat(TextPreset.isHeading(heading)).isTrue()
        assertThat(TextPreset.isHeading(TextParagraph("Text"))).isFalse()
        assertThat(TextPreset.isHeading(heading.copy(marks = heading.marks.drop(1)))).isFalse()
    }

    @Test
    fun enterAtTheEndOfAHeadingGoesOnInNormalText() {
        val c = TextEditController.create(RichText(listOf(heading)))
        c.state.selection = TextRange(5)
        assertThat(c.lineBreak(soft = false)).isTrue()
        assertThat(c.preset).isEqualTo(TextPreset.NORMAL)
        c.state.replaceSelectedText("weiter")
        val text = c.text
        assertThat(text.paragraphs.map { it.text }).containsExactly("Titel", "weiter").inOrder()
        assertThat(TextPreset.isHeading(text.paragraphs[0])).isTrue()
        assertThat(TextPreset.isHeading(text.paragraphs[1])).isFalse()
        assertThat(text.paragraphs[1].marks.none { it.style == MarkStyle.Bold }).isTrue()
    }

    @Test
    fun theEditorGivesOnlyHeadingsTheGapBelow() {
        val c = TextEditController.create(RichText(listOf(heading, TextParagraph("Text"))))
        assertThat(c.state.getParagraphStyle(TextRange(1)).lineHeight).isEqualTo(TextMetrics.headingStyle(22f).lineHeight)
        assertThat(c.state.getParagraphStyle(TextRange(8)).lineHeight.isUnspecified).isTrue()
        c.state.selection = TextRange(2)
        c.applyPreset(TextPreset.NORMAL)
        assertThat(c.state.getParagraphStyle(TextRange(1)).lineHeight.isUnspecified).isTrue()
    }

    @Test
    fun enterInsideAHeadingIsLeftToTheEditor() {
        val c = TextEditController.create(RichText(listOf(heading)))
        c.state.selection = TextRange(2)
        assertThat(c.lineBreak(soft = false)).isFalse()
    }
}
