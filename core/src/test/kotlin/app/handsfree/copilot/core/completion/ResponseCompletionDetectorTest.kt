package app.handsfree.copilot.core.completion

import app.handsfree.copilot.core.config.CompletionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseCompletionDetectorTest {
    private val stable = 1_500L

    @Test
    fun bothModeWaitsForStableTextAndAHiddenIndicator() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.BOTH)
        assertNull(detector.onSnapshot("Hi", generatingVisible = true, nowMs = 0))
        assertNull(detector.onSnapshot("Hi there", generatingVisible = true, nowMs = 100))
        assertNull(detector.onSnapshot("Hi there", generatingVisible = true, nowMs = 1_600))
        assertEquals(
            "Hi there",
            detector.onSnapshot("Hi there", generatingVisible = false, nowMs = 1_600),
        )
    }

    @Test
    fun bothModeCompletesWhenTheIndicatorNeverAppears() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.BOTH)
        assertNull(detector.onSnapshot("Done", generatingVisible = false, nowMs = 0))
        assertEquals("Done", detector.onSnapshot("Done", generatingVisible = false, nowMs = 1_500))
    }

    @Test
    fun textStableModeIgnoresAVisibleIndicator() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.TEXT_STABLE)
        assertNull(detector.onSnapshot("Answer", generatingVisible = true, nowMs = 0))
        assertEquals("Answer", detector.onSnapshot("Answer", generatingVisible = true, nowMs = 1_500))
    }

    @Test
    fun indicatorHiddenCompletesOnTheFallingEdgeBeforeTheTextIsStable() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.INDICATOR_HIDDEN)
        assertNull(detector.onSnapshot("Partial", generatingVisible = true, nowMs = 0))
        assertEquals("Partial reply", detector.onSnapshot("Partial reply", generatingVisible = false, nowMs = 40))
    }

    @Test
    fun indicatorHiddenFallsBackToStabilityWhenNoIndicatorIsEverSeen() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.INDICATOR_HIDDEN)
        assertNull(detector.onSnapshot("Answer", generatingVisible = false, nowMs = 0))
        assertEquals("Answer", detector.onSnapshot("Answer", generatingVisible = false, nowMs = 1_500))
    }

    @Test
    fun eitherModeCompletesOnStableTextWhileTheIndicatorIsStillVisible() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.EITHER)
        assertNull(detector.onSnapshot("Answer", generatingVisible = true, nowMs = 0))
        assertEquals("Answer", detector.onSnapshot("Answer", generatingVisible = true, nowMs = 1_500))
    }

    @Test
    fun eitherModeCompletesOnTheFallingEdgeBeforeStability() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.EITHER)
        assertNull(detector.onSnapshot("A", generatingVisible = true, nowMs = 0))
        assertEquals("AB", detector.onSnapshot("AB", generatingVisible = false, nowMs = 10))
    }

    @Test
    fun blankSnapshotsNeverCompleteAndDoNotResetTheTimer() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.BOTH)
        assertNull(detector.onSnapshot("", generatingVisible = false, nowMs = 0))
        assertNull(detector.onSnapshot("   ", generatingVisible = false, nowMs = 5_000))
        assertNull(detector.onSnapshot("Hello", generatingVisible = false, nowMs = 5_000))
        assertNull(detector.onSnapshot("", generatingVisible = false, nowMs = 5_100))
        assertEquals("Hello", detector.onSnapshot("", generatingVisible = false, nowMs = 6_500))
    }

    @Test
    fun growingTextResetsTheStabilityTimer() {
        val detector = ResponseCompletionDetector(stable, CompletionMode.TEXT_STABLE)
        assertNull(detector.onSnapshot("A", generatingVisible = false, nowMs = 0))
        assertNull(detector.onSnapshot("AB", generatingVisible = false, nowMs = 1_500))
        assertNull(detector.onSnapshot("AB", generatingVisible = false, nowMs = 2_999))
        assertEquals("AB", detector.onSnapshot("AB", generatingVisible = false, nowMs = 3_000))
    }
}
