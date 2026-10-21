package app.handsfree.copilot.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownSpeechSanitizerTest {
    private val sanitizer = MarkdownSpeechSanitizer()

    @Test
    fun stripsEmphasisHeadingsListsLinksAndCodeFences() {
        val raw = """
            # Weekly brief

            **Hello** and *welcome*.

            See [the notes](https://example.com/notes) and https://example.com/raw.

            - first item
            - second item

            ```kotlin
            val answer = 4
            ```

            Use `inline` code.
        """.trimIndent()

        val spoken = sanitizer.sanitize(raw)

        assertEquals(
            "Weekly brief. Hello and welcome. See the notes and. first item. second item. val answer = 4. Use inline code.",
            spoken,
        )
        assertFalse(spoken.contains("**"))
        assertFalse(spoken.contains("```"))
        assertFalse(spoken.contains("http"))
        assertFalse(spoken.contains("`"))
    }

    @Test
    fun stripsCitationMarkersButKeepsOrdinaryNumbers() {
        val raw = "Windows 11 ships in section (1) of the plan [1] and [2, 3]. See【4:2†source】 plus [^5] and ^6^ and a superscript ¹."
        val spoken = sanitizer.sanitize(raw)

        assertEquals(
            "Windows 11 ships in section (1) of the plan and. See plus and and a superscript.",
            spoken,
        )
        assertFalse(spoken.contains("【"))
        assertFalse(spoken.contains("†"))
        assertFalse(spoken.contains("[1]"))
        assertFalse(spoken.contains("¹"))
    }

    @Test
    fun stripsParentheticalSourceMarkersAndMarkdownTables() {
        val raw = """
            | Name | Count |
            | --- | --- |
            | Pens | 2 |

            Details (source: budget.docx).
        """.trimIndent()

        val spoken = sanitizer.sanitize(raw)
        assertFalse(spoken.contains("|"))
        assertFalse(spoken.contains("---"))
        assertFalse(spoken.contains("source:"))
        assertFalse(spoken.contains("budget.docx"))
        assert(spoken.contains("Name"))
        assert(spoken.contains("Pens"))
        assert(spoken.contains("2"))
    }

    @Test
    fun collapsesARealisticCopilotReply() {
        val raw = """
            Here is the short version:

            1. Open the text chat.
            2. Pick Claude Opus.

            The model stays on the one you picked [1].
            Copilot can make mistakes (citation).
        """.trimIndent()

        assertEquals(
            "Here is the short version: Open the text chat. Pick Claude Opus. The model stays on the one you picked. Copilot can make mistakes.",
            sanitizer.sanitize(raw),
        )
    }

    @Test
    fun emptyAndWhitespaceBecomeEmpty() {
        assertEquals("", sanitizer.sanitize("   \n\n  "))
        assertEquals("", sanitizer.sanitize(""))
    }
}
