package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.model.Phase
import app.handsfree.copilot.core.model.Role
import app.handsfree.copilot.core.model.Turn
import app.handsfree.copilot.core.ports.AwaitResult
import app.handsfree.copilot.core.ports.ChatDriver
import app.handsfree.copilot.core.ports.Dictation
import app.handsfree.copilot.core.ports.DictationEvent
import app.handsfree.copilot.core.ports.Sleeper
import app.handsfree.copilot.core.ports.Speaker
import app.handsfree.copilot.core.text.EchoFilter
import app.handsfree.copilot.core.text.SpokenTextProcessor
import app.handsfree.copilot.core.text.StopCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Hands-free turn loop. Platform code supplies speech and a chat driver;
 * this class only decides what happens next.
 *
 * Listening -> send dictated text -> wait until the reply settles -> speak -> listen.
 * A stop phrase ends the session instead of being sent. Speech heard over TTS
 * (and [bargeIn]) cuts the readout off and, for a real utterance, becomes the next prompt.
 */
class HandsFreeConversation(
    private val driver: ChatDriver,
    private val dictation: Dictation,
    private val speaker: Speaker,
    private val processor: SpokenTextProcessor,
    private val selectors: () -> CopilotSelectors,
    private val scope: CoroutineScope,
    private val sleeper: Sleeper,
) {
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _banner = MutableStateFlow("")
    val banner: StateFlow<String> = _banner.asStateFlow()

    @Volatile
    private var running: Boolean = false
    private var loopJob: Job? = null

    fun start() {
        if (running) return
        running = true
        loopJob = scope.launch {
            try {
                runSession()
            } finally {
                running = false
                dictation.stop()
                speaker.stop()
                _partial.value = ""
                if (_phase.value !is Phase.Error) {
                    _phase.value = Phase.Idle
                }
            }
        }
    }

    fun stopSession() {
        running = false
        speaker.stop()
        dictation.stop()
        loopJob?.cancel()
        _partial.value = ""
        _banner.value = "Session stopped."
        _phase.value = Phase.Idle
    }

    /** Tap-to-interrupt. Voice barge-in is handled inside the speaking step. */
    fun bargeIn() {
        speaker.stop()
        _banner.value = "Interrupted."
    }

    private suspend fun runSession() {
        _phase.value = Phase.Preparing
        _banner.value = "Bringing Copilot to the front."
        val prepared = driver.prepare()
        if (!prepared.ok) {
            _banner.value = prepared.message
            _phase.value = Phase.Error(prepared.message, prepared.blocked)
            return
        }
        _banner.value = prepared.message
        var pending: String? = null
        while (running) {
            val heard = pending ?: listenForUtterance() ?: break
            pending = null
            if (!running) break
            if (StopCommands.matches(heard, selectors().stopPhrases)) {
                _banner.value = "Session ended by voice command."
                break
            }
            pending = submitAndSpeak(heard)
        }
    }

    private suspend fun listenForUtterance(): String? {
        _phase.value = Phase.Listening
        _partial.value = ""
        var attempts = 0
        while (running && attempts < 6) {
            try {
                return dictation.listen().transform { event ->
                    when (event) {
                        is DictationEvent.Partial -> _partial.value = event.text
                        is DictationEvent.Final -> {
                            val text = event.text.trim()
                            if (text.isNotEmpty()) {
                                _partial.value = ""
                                emit(text)
                            }
                        }
                        is DictationEvent.Failure -> {
                            _banner.value = event.message
                            if (!event.retry) attempts += 1
                        }
                        DictationEvent.Listening -> Unit
                    }
                }.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoSuchElementException) {
                attempts += 1
                sleeper.sleep(400)
            }
        }
        if (running) {
            val message = "Speech recognition stopped. Check the microphone permission and that a recognition service is installed."
            _banner.value = message
            _phase.value = Phase.Error(message, null)
        }
        return null
    }

    /**
     * @return a barge-in utterance that should be handled as the next user turn, or null.
     */
    private suspend fun submitAndSpeak(userText: String): String? {
        _phase.value = Phase.Sending(userText)
        _partial.value = ""
        append(Role.USER, userText)
        val baseline = driver.captureBaseline()
        val submitted = driver.submit(userText)
        if (!submitted.ok) {
            _banner.value = submitted.message
            speakPlain("I couldn't send that. ${submitted.message}")
            return null
        }
        _phase.value = Phase.Waiting("")
        val awaited = waitForResponse(userText, baseline)
        if (!running) return null
        if (!awaited.ok || awaited.text.isBlank()) {
            val message = awaited.message.ifBlank { "Copilot didn't return a readable reply." }
            _banner.value = message
            speakPlain(message)
            return null
        }
        _banner.value = awaited.message
        val plain = processor.forSpeech(awaited.text)
        append(Role.ASSISTANT, plain)
        return speakWithBargeIn(plain)
    }

    private suspend fun waitForResponse(userText: String, baseline: String): AwaitResult {
        val config = selectors()
        val detector = ResponseCompletionDetector(
            settleMillis = config.settleMillis,
            indicatorClearedSettleMillis = config.indicatorClearedSettleMillis,
            indicatorStuckMillis = config.resolvedStuckMillis(),
        )
        val started = sleeper.now()
        var lastText = ""
        while (running && sleeper.now() - started < config.responseTimeoutMillis) {
            val observation = driver.observe(userText, baseline)
            val candidate = AssistantTextExtractor.newContent(
                candidate = observation.assistantCandidate,
                baseline = baseline,
                userUtterance = userText,
            )
            val update = detector.update(
                text = candidate,
                generatingVisible = observation.generatingVisible,
                atMillis = sleeper.now(),
            )
            if (update.text.isNotBlank()) {
                lastText = update.text
                _phase.value = Phase.Waiting(update.text)
            }
            if (update.completed) {
                return AwaitResult(true, update.text, "Response complete.", update.reason)
            }
            sleeper.sleep(config.pollMillis)
        }
        return if (lastText.isNotBlank()) {
            AwaitResult(
                ok = true,
                text = lastText,
                message = "Timed out waiting for the reply to settle. Speaking what was captured.",
                reason = CompletionReason.TIMEOUT_PARTIAL,
            )
        } else {
            AwaitResult(
                ok = false,
                text = "",
                message = "Timed out waiting for Copilot's reply. If the answer is on screen, tune selectors from the Calibration screen.",
            )
        }
    }

    private suspend fun speakPlain(text: String) {
        val plain = processor.forSpeech(text)
        _phase.value = Phase.Speaking(plain)
        try {
            speaker.speak(plain)
        } catch (cancelled: CancellationException) {
            if (!running) throw cancelled
        }
    }

    private suspend fun speakWithBargeIn(plain: String): String? {
        _phase.value = Phase.Speaking(plain)
        val interrupted = CompletableDeferred<String?>()
        var bargeIn: String? = null
        coroutineScope {
            val speakJob = launch {
                try {
                    speaker.speak(plain)
                } catch (cancelled: CancellationException) {
                    if (!running) throw cancelled
                }
                if (!interrupted.isCompleted) interrupted.complete(null)
            }
            val listenJob = launch {
                try {
                    dictation.listen().collect { event ->
                        when (event) {
                            is DictationEvent.Partial -> {
                                if (EchoFilter.isInterruption(event.text, plain)) {
                                    speaker.stop()
                                    _banner.value = "Interrupted."
                                }
                            }
                            is DictationEvent.Final -> {
                                val text = event.text.trim()
                                if (text.isEmpty() || EchoFilter.isEcho(text, plain)) return@collect
                                speaker.stop()
                                bargeIn = text
                                if (!interrupted.isCompleted) interrupted.complete(text)
                                speakJob.cancel()
                            }
                            is DictationEvent.Failure -> _banner.value = event.message
                            DictationEvent.Listening -> Unit
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
            val fromSpeech = interrupted.await()
            if (fromSpeech != null) bargeIn = fromSpeech
            listenJob.cancel()
            speakJob.join()
        }
        return if (running) bargeIn else null
    }

    private fun append(role: Role, text: String) {
        _turns.value = (_turns.value + Turn(role, text)).takeLast(40)
    }
}
