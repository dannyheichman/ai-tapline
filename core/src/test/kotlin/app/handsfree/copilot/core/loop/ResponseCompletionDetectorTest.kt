package app.handsfree.copilot.core.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCompletionDetectorTest {
    private fun detector(
        settle: Long = 1_000,
        cleared: Long = 400,
        stuck: Long = 3_000,
    ) = ResponseCompletionDetector(settle, cleared, stuck)

    @Test
    fun emptyTextNeverCompletes() {
        val detector = detector()
        val first = detector.update("", generatingVisible = false, atMillis = 0)
        val later = detector.update("   ", generatingVisible = false, atMillis = 10_000)
        assertFalse(first.completed)
        assertEquals(CompletionReason.EMPTY, first.reason)
        assertFalse(later.completed)
        assertEquals(CompletionReason.EMPTY, later.reason)
    }

    @Test
    fun growingTextDoesNotComplete() {
        val detector = detector()
        assertFalse(detector.update("Hel", false, 0).completed)
        assertFalse(detector.update("Hello", false, 500).completed)
        val stillGrowing = detector.update("Hello there", false, 2_000)
        assertFalse(stillGrowing.completed)
        assertEquals(CompletionReason.IN_PROGRESS, stillGrowing.reason)
    }

    @Test
    fun stableTextCompletesAfterSettleInterval() {
        val detector = detector(settle = 1_000)
        assertEquals(CompletionReason.IN_PROGRESS, detector.update("Done.", false, 0).reason)
        assertEquals(CompletionReason.SETTLING, detector.update("Done.", false, 999).reason)
        val done = detector.update("Done.", false, 1_000)
        assertTrue(done.completed)
        assertEquals("Done.", done.text)
        assertEquals(CompletionReason.STABLE_TEXT, done.reason)
    }

    @Test
    fun whitespaceOnlyChangeDoesNotResetTheTimer() {
        val detector = detector(settle = 500)
        detector.update("Hello", false, 0)
        val done = detector.update("Hello\n", false, 500)
        assertTrue(done.completed)
        assertEquals(CompletionReason.STABLE_TEXT, done.reason)
    }

    @Test
    fun textChangeResetsTheSettleTimer() {
        val detector = detector(settle = 500)
        detector.update("A", false, 0)
        assertFalse(detector.update("AB", false, 400).completed)
        assertFalse(detector.update("AB", false, 899).completed)
        assertTrue(detector.update("AB", false, 900).completed)
    }

    @Test
    fun visibleGeneratingIndicatorBlocksTheNormalSettle() {
        val detector = detector(settle = 500, stuck = 5_000)
        detector.update("Partial", generatingVisible = true, atMillis = 0)
        val held = detector.update("Partial", generatingVisible = true, atMillis = 2_000)
        assertFalse(held.completed)
        assertEquals(CompletionReason.GENERATING, held.reason)
    }

    @Test
    fun indicatorClearedCompletesAfterTheShorterSettle() {
        val detector = detector(settle = 5_000, cleared = 300, stuck = 10_000)
        detector.update("Answer", generatingVisible = true, atMillis = 0)
        assertFalse(detector.update("Answer", generatingVisible = false, atMillis = 200).completed)
        val done = detector.update("Answer", generatingVisible = false, atMillis = 300)
        assertTrue(done.completed)
        assertEquals(CompletionReason.INDICATOR_CLEARED_AND_STABLE, done.reason)
    }

    @Test
    fun stuckIndicatorStillCompletesOnceTextHasBeenStable() {
        val detector = detector(settle = 500, stuck = 1_000)
        detector.update("Final answer", generatingVisible = true, atMillis = 0)
        assertFalse(detector.update("Final answer", generatingVisible = true, atMillis = 999).completed)
        val done = detector.update("Final answer", generatingVisible = true, atMillis = 1_000)
        assertTrue(done.completed)
        assertEquals(CompletionReason.STABLE_DESPITE_INDICATOR, done.reason)
    }

    @Test
    fun flickerBackToGeneratingWaitsAgain() {
        val detector = detector(settle = 5_000, cleared = 200, stuck = 5_000)
        detector.update("Answer", true, 0)
        detector.update("Answer", false, 100)
        val back = detector.update("Answer", true, 250)
        assertFalse(back.completed)
        assertEquals(CompletionReason.GENERATING, back.reason)
    }

    @Test
    fun doesNotCompleteTwice() {
        val detector = detector(settle = 100)
        assertTrue(detector.update("Yes", false, 0).let { detector.update("Yes", false, 100).completed })
        val again = detector.update("Yes", false, 500)
        assertFalse(again.completed)
        assertEquals(CompletionReason.ALREADY_COMPLETED, again.reason)
    }

    @Test
    fun blankFrameDoesNotWipeAStableReply() {
        val detector = detector(settle = 400)
        detector.update("Kept", false, 0)
        assertEquals(CompletionReason.EMPTY, detector.update("", false, 100).reason)
        val done = detector.update("Kept", false, 400)
        assertTrue(done.completed)
        assertEquals("Kept", done.text)
    }
}
