package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import com.stemaker.nibsync.text.RichTextLayout
import org.junit.Test

class CodeBlockTest {
    @Test
    fun codeParagraphsAreABlockToCopyInlineCodeIsShadedOnly() {
        val mono = MarkStyle.Font(TextFont.MONO)
        val text = RichText(
            listOf(
                TextParagraph("Siehe code hier", marks = listOf(TextMark(6, 10, mono))),
                TextParagraph("val a = 1", marks = listOf(TextMark(0, 9, mono))),
                TextParagraph(""),
                TextParagraph("println(a)", marks = listOf(TextMark(0, 10, mono))),
                TextParagraph("Ende"),
            ),
        )
        val layout = DesktopTextLayout().layout(text, 100f)
        val areas = RichTextLayout.codeAreas(text, layout)
        assertThat(areas.map { it.text }).containsExactly(null, "val a = 1\n\nprintln(a)").inOrder()
        val block = areas[1]
        assertThat(block.rect.right).isEqualTo(100f * DesktopTextLayout.LAYOUT_PX_PER_MM)
        val button = RichTextLayout.copyButton(block, layout)
        assertThat(RichTextLayout.codeToCopyAt(text, layout, button.center.x, button.center.y)).isEqualTo("val a = 1\n\nprintln(a)")
        assertThat(RichTextLayout.codeToCopyAt(text, layout, 10f, block.rect.center.y)).isNull()
    }
}
