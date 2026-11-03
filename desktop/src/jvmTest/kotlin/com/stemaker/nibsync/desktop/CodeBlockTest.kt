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

    @Test
    fun theCheckBoxOfAToDoInTextIsFoundWhereItIsDrawn() {
        val sign = MarkStyle.Task.SIGN
        val task = MarkStyle.Task(com.stemaker.nibsync.model.StrokeId("t"))
        val text = RichText(listOf(TextParagraph("Liste"), TextParagraph(sign + "Milch", marks = listOf(TextMark(0, sign.length, task)))))
        val box = com.stemaker.nibsync.model.TextBox(com.stemaker.nibsync.model.StrokeId("b"), 10f, 10f, 100f, 12f, text, 0, com.stemaker.nibsync.model.DeviceId("d"))
        val layout = DesktopTextLayout()
        val (_, r) = RichTextLayout.taskBoxes(text, layout.layoutOf(box)).single()
        val px = DesktopTextLayout.LAYOUT_PX_PER_MM
        assertThat(layout.taskAt(box, 10f + (r.left + r.right) / 2 / px, 10f + (r.top + r.bottom) / 2 / px)).isEqualTo(task)
        // On the first line ("Liste"): nothing.
        assertThat(layout.taskAt(box, 11f, 11f)).isNull()
    }
}
