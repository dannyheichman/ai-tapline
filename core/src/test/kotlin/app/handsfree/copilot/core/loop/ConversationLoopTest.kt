package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.config.DriverConfig
import app.handsfree.copilot.core.model.AccessDiagnostics
import app.handsfree.copilot.core.model.AccessLevel
import app.handsfree.copilot.core.model.ChatDriver
import app.handsfree.copilot.core.model.Clock
import app.handsfree.copilot.core.model.DriverStatus
import app.handsfree.copilot.core.model.ListenOptions
import app.handsfree.copilot.core.model.ObserveResult
import app.handsfree.copilot.core.model.Recognition
import app.handsfree.copilot.core.model.ResponseObservation
import app.handsfree.copilot.core.model.SessionPhase
import app.handsfree.copilot.core.model.SpeechInput
import app.handsfree.copilot.core.model.SpeechOutput
import app.handsfree.copilot.core.text.AssistantTextResolver
import app.handsfree.copilot.core.text.EchoFilter
import app.handsfree.copilot.core.text.MarkdownSpeechSanitizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationLoopTest {
    @Test
    fun sendsDictationThenSpeaksTheSanitizedReplyAndStopsOnThePhrase() = runTest(UnconfinedTestDispatcher()) {
        val clock = MutableClock()
        val chat = FakeChat(clock)
        val speech = FakeSpeech(
            ArrayDeque(
                listOf(
                    Recognition.Final("what is two plus two"),
                    Recognition.Final("stop session"),
                ),
            ),
        )
        val speaker = FakeSpeaker()
        val loop = loop(clock, chat, speech, speaker, bargeIn = false)

        loop.start(this)
        advanceUntilIdle()

        assertEquals(listOf("what is two plus two"), chat.sent)
        assertEquals(listOf("Hello world."), speaker.spoken)
        assertEquals(SessionPhase.Idle, loop.snapshot.value.phase)
        assertFalse(loop.snapshot.value.running)
        assertEquals("stop session", loop.snapshot.value.lastUserText)
    }

    @Test
    fun bargeInSendsTheInterruptingUtteranceInsteadOfWaitingOutTheReply() = runTest(UnconfinedTestDispatcher()) {
        val clock = MutableClock()
        val chat = FakeChat(clock)
        chat.replies.clear()
        chat.replies.add(listOf("Four."))
        chat.replies.add(listOf("More detail."))
        val speech = FakeSpeech(
            ArrayDeque(
                listOf(
                    Recognition.Final("what is two plus two"),
                    Recognition.Final("tell me more"),
                    Recognition.Final("stop session"),
                ),
            ),
        )
        val speaker = HoldingSpeaker()
        val loop = loop(clock, chat, speech, speaker, bargeIn = true)

        loop.start(this)
        advanceUntilIdle()

        assertEquals(listOf("what is two plus two", "tell me more"), chat.sent)
        assertEquals(listOf("Four.", "More detail."), speaker.spoken)
        assertEquals(SessionPhase.Idle, loop.snapshot.value.phase)
    }

    private fun loop(
        clock: MutableClock,
        chat: FakeChat,
        speech: FakeSpeech,
        speaker: SpeechOutput,
        bargeIn: Boolean,
    ) = ConversationLoop(
        speech = speech,
        speaker = speaker,
        chat = chat,
        sanitizer = MarkdownSpeechSanitizer(),
        resolver = AssistantTextResolver(),
        echo = EchoFilter(),
        clock = clock,
        settings = { LoopSettings(driver = DriverConfig(), bargeInEnabled = bargeIn, speakErrors = false) },
    )

    private class MutableClock(var elapsed: Long = 0) : Clock {
        override fun elapsedMs(): Long = elapsed
    }

    private class FakeSpeech(private val script: ArrayDeque<Recognition>) : SpeechInput {
        override suspend fun recognize(options: ListenOptions, onPartial: (String) -> Unit): Recognition {
            return script.removeFirstOrNull() ?: Recognition.Failure(0, "done", retrySilently = true, speakable = false)
        }

        override fun cancel() = Unit
    }

    private class FakeSpeaker : SpeechOutput {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) {
            spoken += text
        }

        override fun stop() = Unit
    }

    private class HoldingSpeaker : SpeechOutput {
        val spoken = mutableListOf<String>()
        private var waiting: Continuation<Unit>? = null

        override suspend fun speak(text: String) {
            spoken += text
            kotlin.coroutines.suspendCoroutine { continuation ->
                waiting = continuation
            }
        }

        override fun stop() {
            val pending = waiting
            waiting = null
            if (pending != null) pending.resume(Unit)
        }
    }

    private class FakeChat(private val clock: MutableClock) : ChatDriver {
        val sent = mutableListOf<String>()
        val replies: ArrayDeque<List<String>> = ArrayDeque(listOf(listOf("Hello **world** [1]")))

        override suspend fun openTarget(): DriverStatus = DriverStatus.Ok

        override suspend fun captureMessages(): List<String> = listOf("Earlier answer")

        override suspend fun sendUserMessage(text: String): DriverStatus {
            sent += text
            return DriverStatus.Ok
        }

        override suspend fun observeUntil(
            timeoutMs: Long,
            pollIntervalMs: Long,
            onSnapshot: (ResponseObservation) -> String?,
        ): ObserveResult {
            val reply = replies.removeFirst()
            val diagnostics = AccessDiagnostics(AccessLevel.OK, "ok", "ok", contentReadable = true)
            onSnapshot(ResponseObservation(reply, generatingIndicatorVisible = true, diagnostics = diagnostics))
            clock.elapsed += 1_500
            val done = onSnapshot(ResponseObservation(reply, generatingIndicatorVisible = false, diagnostics = diagnostics))
            return if (done != null) ObserveResult.Completed(done) else ObserveResult.TimedOut("")
        }

        override fun diagnostics(): AccessDiagnostics =
            AccessDiagnostics(AccessLevel.OK, "ok", "ok", contentReadable = true)
    }
}
