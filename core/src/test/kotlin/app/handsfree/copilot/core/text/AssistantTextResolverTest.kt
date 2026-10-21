package app.handsfree.copilot.core.text

import app.handsfree.copilot.core.config.MessagePickSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantTextResolverTest {
    private val resolver = AssistantTextResolver()
    private val pick = MessagePickSettings(shortTailMaxChars = 80, shortTailRatio = 0.35)

    @Test
    fun returnsEmptyWhenNothingNewAppeared() {
        val text = resolver.resolve(
            baselineTopToBottom = listOf("Earlier"),
            currentTopToBottom = listOf("Earlier"),
            userText = "Question",
            excludes = emptyList(),
            pick = pick,
        )
        assertEquals("", text)
    }

    @Test
    fun ignoresTheUserBubbleAndExcludedChrome() {
        val text = resolver.resolve(
            baselineTopToBottom = listOf("Earlier"),
            currentTopToBottom = listOf(
                "Earlier",
                "What is 2 plus 2",
                "Four",
                "AI-generated content may be incorrect",
            ),
            userText = "What is 2 plus 2",
            excludes = listOf("AI-generated content may be incorrect"),
            pick = pick,
        )
        assertEquals("Four", text)
    }

    @Test
    fun followsAStreamingBubbleThatGrows() {
        val text = resolver.resolve(
            baselineTopToBottom = listOf("Hello"),
            currentTopToBottom = listOf("Hello, this is the rest of the reply"),
            userText = "Continue",
            excludes = emptyList(),
            pick = pick,
        )
        assertEquals("Hello, this is the rest of the reply", text)
    }

    @Test
    fun skipsAShortCaptionUnderALongNewAnswer() {
        val answer = "A".repeat(200)
        val text = resolver.resolve(
            baselineTopToBottom = listOf("Earlier"),
            currentTopToBottom = listOf("Earlier", answer, "Sources"),
            userText = "Explain",
            excludes = emptyList(),
            pick = pick,
        )
        assertEquals(answer, text)
    }
}
