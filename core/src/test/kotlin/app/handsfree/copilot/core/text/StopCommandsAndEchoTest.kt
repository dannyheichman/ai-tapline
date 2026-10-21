package app.handsfree.copilot.core.text

import app.handsfree.copilot.core.loop.AssistantTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCommandsAndEchoTest {
    private val phrases = listOf("stop session", "end session", "end voice session")

    @Test
    fun stopPhraseMatchesWithFiller() {
        assertTrue(StopCommands.matches("stop session", phrases))
        assertTrue(StopCommands.matches("Please stop session now.", phrases))
        assertTrue(StopCommands.matches("hey end voice session", phrases))
    }

    @Test
    fun longPromptThatMentionsThePhraseIsNotAStopCommand() {
        val prompt = "please stop session is not what I want you to write about the quarterly plan for the team"
        assertFalse(StopCommands.matches(prompt, phrases))
    }

    @Test
    fun echoOverlapsTheSpokenReply() {
        val spoken = "The sprint is a two week planning cycle."
        assertTrue(EchoFilter.isEcho("sprint is a two week", spoken))
        assertFalse(EchoFilter.isInterruption("sprint is a two week", spoken))
    }

    @Test
    fun aDifferentUtteranceIsAnInterruption() {
        val spoken = "The sprint is a two week planning cycle."
        assertTrue(EchoFilter.isInterruption("what about Tuesday", spoken))
        assertTrue(EchoFilter.isInterruption("stop", spoken))
        assertFalse(EchoFilter.isInterruption("ok", spoken))
    }

    @Test
    fun extractorSeparatesTheNewReply() {
        assertEquals("", AssistantTextExtractor.newContent("  ", "", "hi"))
        assertEquals("", AssistantTextExtractor.newContent("Hello", "Hello", "hi"))
        assertEquals("", AssistantTextExtractor.newContent("What is a sprint?", "older", "What is a sprint?"))
        assertEquals(
            "And the rest of the answer.",
            AssistantTextExtractor.newContent(
                candidate = "This is the previous long answer that stays in the node. And the rest of the answer.",
                baseline = "This is the previous long answer that stays in the node.",
                userUtterance = "hi",
            ),
        )
        assertEquals(
            "Here is the answer.",
            AssistantTextExtractor.newContent(
                candidate = "What is a sprint?\nHere is the answer.",
                baseline = "",
                userUtterance = "What is a sprint?",
            ),
        )
        assertEquals("OK sure", AssistantTextExtractor.newContent("OK sure", "OK", "hi"))
    }
}
