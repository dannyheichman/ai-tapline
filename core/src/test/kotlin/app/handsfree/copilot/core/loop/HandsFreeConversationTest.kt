package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.model.BlockedReason
import app.handsfree.copilot.core.model.Phase
import app.handsfree.copilot.core.ports.ChatDriver
import app.handsfree.copilot.core.ports.Dictation
import app.handsfree.copilot.core.ports.DictationEvent
import app.handsfree.copilot.core.ports.PrepareResult
import app.handsfree.copilot.core.ports.Sleeper
import app.handsfree.copilot.core.ports.Speaker
import app.handsfree.copilot.core.ports.SubmitResult
import app.handsfree.copilot.core.ports.UiObservation
import app.handsfree.copilot.core.text.SpokenTextProcessor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeConversationTest {
    @Test
    fun speaksTheSettledReplyAndStopsOnTheVoiceCommand() = runTest {
        val dictation = FakeDictation()
        val driver = FakeChatDriver(
            listOf(UiObservation("Hello from Copilot.", generatingVisible = false, inputFound = true)),
        )
        val speaker = FakeSpeaker()
        val session = session(driver, dictation, speaker, ManualSleeper(), settleMillis = 500, pollMillis = 250)
        session.start()
        dictation.emit(DictationEvent.Final("What is a sprint?"))
        advanceUntilIdle()

        assertEquals(listOf("What is a sprint?"), driver.submitted)
        assertEquals("Hello from Copilot.", speaker.spoken.single())
        assertTrue(session.phase.value is Phase.Listening)

        dictation.emit(DictationEvent.Final("stop session"))
        advanceUntilIdle()
        assertEquals(Phase.Idle, session.phase.value)
        assertEquals(1, driver.submitted.size)
    }

    @Test
    fun bargeInBecomesTheNextPrompt() = runTest {
        val dictation = FakeDictation()
        val driver = FakeChatDriver(
            listOf(UiObservation("The sprint is two weeks.", generatingVisible = false, inputFound = true)),
        )
        val speaker = FakeSpeaker(hold = true)
        val session = session(driver, dictation, speaker, ManualSleeper(), settleMillis = 500, pollMillis = 250)
        session.start()
        dictation.emit(DictationEvent.Final("What is a sprint?"))
        advanceUntilIdle()
        assertTrue(session.phase.value is Phase.Speaking)

        dictation.emit(DictationEvent.Final("What about Tuesday"))
        advanceUntilIdle()
        assertEquals(listOf("What is a sprint?", "What about Tuesday"), driver.submitted)

        dictation.emit(DictationEvent.Final("please stop session"))
        advanceUntilIdle()
        assertEquals(Phase.Idle, session.phase.value)
        assertEquals(2, driver.submitted.size)
    }

    @Test
    fun prepareFailureDoesNotSend() = runTest {
        val driver = FakeChatDriver(emptyList()).apply {
            prepareResult = PrepareResult(false, "Work profile is blocking access.", BlockedReason.WORK_PROFILE_ONLY)
        }
        val session = session(driver, FakeDictation(), FakeSpeaker(), ManualSleeper(), settleMillis = 500, pollMillis = 250)
        session.start()
        advanceUntilIdle()
        val phase = session.phase.value
        assertTrue(phase is Phase.Error)
        assertEquals(BlockedReason.WORK_PROFILE_ONLY, (phase as Phase.Error).blocked)
        assertTrue(driver.submitted.isEmpty())
    }

    @Test
    fun timeoutWithoutTextIsSpokenAndTheSessionKeepsListening() = runTest {
        val dictation = FakeDictation()
        val driver = FakeChatDriver(listOf(UiObservation("", generatingVisible = false, inputFound = true)))
        val speaker = FakeSpeaker()
        val session = session(
            driver,
            dictation,
            speaker,
            ManualSleeper(),
            settleMillis = 500,
            pollMillis = 250,
            timeoutMillis = 1_000,
        )
        session.start()
        dictation.emit(DictationEvent.Final("hello"))
        advanceUntilIdle()
        assertTrue(speaker.spoken.single().contains("Timed out"))
        assertTrue(session.phase.value is Phase.Listening)
        dictation.emit(DictationEvent.Final("end session"))
        advanceUntilIdle()
        assertEquals(Phase.Idle, session.phase.value)
    }

    private fun kotlinx.coroutines.test.TestScope.session(
        driver: FakeChatDriver,
        dictation: FakeDictation,
        speaker: FakeSpeaker,
        sleeper: ManualSleeper,
        settleMillis: Long,
        pollMillis: Long,
        timeoutMillis: Long = 30_000,
    ) = HandsFreeConversation(
        driver = driver,
        dictation = dictation,
        speaker = speaker,
        processor = SpokenTextProcessor(),
        selectors = {
            CopilotSelectors(
                targetPackage = "com.example",
                settleMillis = settleMillis,
                indicatorClearedSettleMillis = settleMillis,
                indicatorStuckMillis = settleMillis * 3,
                responseTimeoutMillis = timeoutMillis,
                pollMillis = pollMillis,
            )
        },
        scope = this,
        sleeper = sleeper,
    )
}

private class ManualSleeper : Sleeper {
    private var time = 0L
    override fun now(): Long = time
    override suspend fun sleep(millis: Long) {
        time += millis
        delay(millis)
    }
}

private class FakeDictation : Dictation {
    private val events = Channel<DictationEvent>(capacity = Channel.UNLIMITED)
    suspend fun emit(event: DictationEvent) = events.send(event)
    override fun listen(): Flow<DictationEvent> = flow {
        while (true) {
            val event = events.receive()
            emit(event)
            if (event is DictationEvent.Final && event.text.isNotBlank()) break
            if (event is DictationEvent.Failure && !event.retry) break
        }
    }
    override fun stop() = Unit
}

private class FakeSpeaker(private val hold: Boolean = false) : Speaker {
    private val speakingState = MutableStateFlow(false)
    override val speaking = speakingState
    val spoken = mutableListOf<String>()

    override suspend fun speak(text: String) {
        spoken += text
        speakingState.value = true
        try {
            if (hold) {
                suspendCancellableCoroutine<Unit> { }
            }
        } finally {
            speakingState.value = false
        }
    }

    override fun stop() = Unit
}

private class FakeChatDriver(private val plan: List<UiObservation>) : ChatDriver {
    val submitted = mutableListOf<String>()
    var prepareResult = PrepareResult(true, "ready")
    private var observes = 0

    override suspend fun prepare(): PrepareResult = prepareResult

    override suspend fun captureBaseline(): String = ""

    override suspend fun submit(text: String): SubmitResult {
        submitted += text
        observes = 0
        return SubmitResult(true, "sent")
    }

    override suspend fun observe(userUtterance: String, baseline: String): UiObservation {
        if (plan.isEmpty()) return UiObservation("", false, true)
        val observation = plan[observes.coerceAtMost(plan.lastIndex)]
        if (observes < plan.lastIndex) observes += 1
        return observation
    }
}
