package app.handsfree.copilot.core.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopAndEchoTest {
    private val stops = StopCommandMatcher {
        listOf("stop session", "end session")
    }
    private val echo = EchoFilter()

    @Test
    fun stopPhraseMatchesPunctuationAndAShortPrefix() {
        assertTrue(stops.matches("Stop session"))
        assertTrue(stops.matches("please stop session now"))
        assertTrue(stops.matches("end session!"))
        assertFalse(stops.matches("stopping session"))
        assertFalse(stops.matches("what is the session about"))
    }

    @Test
    fun echoIsTheContiguousSpokenPhrase() {
        val spoken = "The budget for the east region is already approved."
        assertTrue(echo.isEcho("budget for the east", spoken))
        assertFalse(echo.isEcho("what about payroll", spoken))
        assertTrue(echo.isEcho("   ", spoken))
    }
}
