package app.handsfree.copilot.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStripperTest {
    private fun spoken(raw: String) = SpokenTextProcessor().forSpeech(raw)

    @Test
    fun stripsBoldItalicStrikeAndHeadings() {
        assertEquals("Hello world", MarkdownStripper.strip("**Hello** world"))
        assertEquals("this is important now", MarkdownStripper.strip("this is *important* now"))
        assertEquals("strike", MarkdownStripper.strip("~~strike~~"))
        assertEquals("Title\n\nBody", MarkdownStripper.strip("# Title\n\nBody"))
    }

    @Test
    fun keepsLinkAndImageLabels() {
        assertEquals("docs", MarkdownStripper.strip("[docs](https://example.com)"))
        assertEquals("chart", MarkdownStripper.strip("![chart](https://example.com/a.png)"))
    }

    @Test
    fun stripsCitationMarkers() {
        assertEquals("Answer", MarkdownStripper.strip("Answer [1]"))
        assertEquals("See here", MarkdownStripper.strip("See 【12†source】 here"))
        assertEquals("word", MarkdownStripper.strip("word^1^"))
        assertEquals("note", MarkdownStripper.strip("note[^1]"))
        assertEquals("Claim", MarkdownStripper.strip("Claim [1](https://contoso.example/doc)"))
        assertEquals("Done", MarkdownStripper.strip("Done (citation: annual report)"))
        assertEquals("Sources and more", MarkdownStripper.strip("Sources [1] and [2] more"))
    }

    @Test
    fun keepsCodeWordsWithoutFences() {
        assertEquals("inline code here", MarkdownStripper.strip("inline `code` here"))
        assertEquals("fun x()", MarkdownStripper.strip("```kotlin\nfun x()\n```"))
    }

    @Test
    fun flattensListsTablesAndHtml() {
        assertEquals("one\ntwo", MarkdownStripper.strip("* one\n* two"))
        assertEquals("First stays numbered only as words", MarkdownStripper.strip("1. First stays numbered only as words"))
        assertEquals("A B\n1 2", MarkdownStripper.strip("| A | B |\n| --- | --- |\n| 1 | 2 |"))
        assertEquals("Hi", MarkdownStripper.strip("<b>Hi</b>"))
        assertEquals("line", MarkdownStripper.strip("line<br>"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("a b", MarkdownStripper.strip("a   b"))
        assertEquals("Hello.", MarkdownStripper.strip("Hello ."))
    }

    @Test
    fun processorSpeaksAFallbackWhenNothingIsLeft() {
        assertEquals("The reply had no readable text.", spoken(" [1] 【2†source】 "))
        assertTrue(spoken("**Hello** [1]").contains("Hello"))
        assertFalse(spoken("See 【4:0†source】").contains("【"))
    }
}
