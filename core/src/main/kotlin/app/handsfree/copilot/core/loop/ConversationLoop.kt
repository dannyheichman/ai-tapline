package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.completion.ResponseCompletionDetector
import app.handsfree.copilot.core.model.ChatDriver
import app.handsfree.copilot.core.model.Clock
import app.handsfree.copilot.core.model.DriverStatus
import app.handsfree.copilot.core.model.ListenOptions
import app.handsfree.copilot.core.model.ObserveResult
import app.handsfree.copilot.core.model.Recognition
import app.handsfree.copilot.core.model.SessionPhase
import app.handsfree.copilot.core.model.SessionSnapshot
import app.handsfree.copilot.core.model.SpeechInput
import app.handsfree.copilot.core.model.SpeechOutput
import app.handsfree.copilot.core.model.SpeechSanitizer
import app.handsfree.copilot.core.text.AssistantTextResolver
import app.handsfree.copilot.core.text.EchoFilter
import app.handsfree.copilot.core.text.StopCommandMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands-free turn loop. It does not know about Android, Copilot view ids, or TTS engines.
 * Those are [SpeechInput], [SpeechOutput], and [ChatDriver].
 *
 * Start, listen, send, wait until the reply is stable, speak, listen again.
 * "stop session" (and the other configured phrases) ends the loop.
 * Speaking can be cut off by [bargeIn] or by speech that is not an echo of the reply.
 */
class ConversationLoop(
    private val speech: SpeechInput,
    private val speaker: SpeechOutput,
    private val chat: ChatDriver,
    private val sanitizer: SpeechSanitizer,
    private val resolver: AssistantTextResolver,
    private val echo: EchoFilter,
    private val clock: Clock,
    private val settings: () -> LoopSettings,
    private val log: (String) -> Unit = {},
) {
    private val _snapshot = MutableStateFlow(SessionSnapshot())
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    private val started = AtomicBoolean(false)
    private var job: Job? = null
    private val taps = Channel<Unit>(capacity = Channel.CONFLATED)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        job = scope.launch {
            try {
                runSession()
            } catch (cancelled: CancellationException) {
                log("Session cancelled")
                throw cancelled
            } catch (error: Throwable) {
                log("Session error: ${error.message ?: error.javaClass.simpleName}")
                update {
                    copy(
                        running = false,
                        phase = SessionPhase.Idle,
                        lastError = error.message ?: "Session failed",
                    )
                }
            } finally {
                speaker.stop()
                speech.cancel()
                update { copy(running = false, phase = SessionPhase.Idle) }
                started.set(false)
            }
        }
    }

    fun stop() {
        log("Stop requested")
        job?.cancel()
        speech.cancel()
        speaker.stop()
    }

    fun bargeIn() {
        if (_snapshot.value.phase == SessionPhase.Speaking) {
            taps.trySend(Unit)
            speaker.stop()
        }
    }

    private suspend fun runSession() {
        val initial = settings()
        update {
            SessionSnapshot(
                running = true,
                phase = SessionPhase.Opening,
                statusDetail = "Opening ${initial.driver.targetPackage}",
            )
        }
        log("Opening ${initial.driver.targetPackage}")
        when (val opened = chat.openTarget()) {
            is DriverStatus.Ok -> Unit
            is DriverStatus.Blocked -> {
                fail(opened.message)
                return
            }
            is DriverStatus.Failed -> {
                fail(opened.message)
                return
            }
        }

        try {
            runTurns()
        } catch (stop: SessionStop) {
            fail(stop.message ?: "Session stopped")
        }
    }

    private suspend fun runTurns() {
        var queued: String? = null
        while (currentCoroutineContext().isActive) {
            val heard = queued?.also { queued = null } ?: listen()
            if (!currentCoroutineContext().isActive) break
            val text = heard?.trim().orEmpty()
            if (text.isEmpty()) continue
            update { copy(lastUserText = text, lastError = "") }
            val stop = StopCommandMatcher { settings().driver.stopPhrases }
            if (stop.matches(text)) {
                log("Stop phrase recognized")
                update { copy(statusDetail = "Stop phrase recognized") }
                break
            }

            update { copy(phase = SessionPhase.Sending, statusDetail = "Sending ${text.length} characters") }
            log("Sending ${text.length} characters")
            val baseline = chat.captureMessages()
            when (val sent = chat.sendUserMessage(text)) {
                is DriverStatus.Ok -> Unit
                is DriverStatus.Blocked -> {
                    fail(sent.message)
                    return
                }
                is DriverStatus.Failed -> {
                    note(sent.message)
                    speakError(sent.message)
                    continue
                }
            }

            update { copy(phase = SessionPhase.Waiting, statusDetail = "Waiting for Copilot") }
            val cfg = settings().driver
            val detector = ResponseCompletionDetector(cfg.completion.stableForMs, cfg.completion.mode)
            var latest = ""
            val observed = chat.observeUntil(cfg.responseTimeoutMs, cfg.pollIntervalMs) { obs ->
                val resolved = resolver.resolve(
                    baselineTopToBottom = baseline,
                    currentTopToBottom = obs.messagesTopToBottom,
                    userText = text,
                    excludes = cfg.messageTextExcludes,
                    pick = cfg.messagePick,
                )
                if (resolved.isNotBlank() && resolved != latest) {
                    latest = resolved
                    val chars = resolved.length
                    update { copy(statusDetail = "Receiving reply ($chars characters)") }
                }
                detector.onSnapshot(resolved, obs.generatingIndicatorVisible, clock.elapsedMs())
            }

            when (observed) {
                is ObserveResult.Blocked -> {
                    fail(observed.detail)
                    return
                }
                is ObserveResult.Failed -> {
                    note(observed.detail)
                    speakError(observed.detail)
                }
                is ObserveResult.TimedOut -> {
                    note("Timed out waiting for a finished Copilot reply.")
                    speakError("Timed out waiting for Copilot.")
                }
                is ObserveResult.Completed -> {
                    val spoken = sanitizer.sanitize(observed.text)
                    if (spoken.isBlank()) {
                        log("Reply sanitized to empty text")
                    } else {
                        update {
                            copy(
                                phase = SessionPhase.Speaking,
                                lastSpokenText = spoken,
                                statusDetail = "Speaking",
                            )
                        }
                        log("Speaking ${spoken.length} characters")
                        val barged = speakWithBargeIn(spoken)
                        if (!barged.isNullOrBlank()) queued = barged
                    }
                }
            }
        }
        update { copy(running = false, phase = SessionPhase.Idle, statusDetail = "Session ended") }
        log("Session ended")
    }

    private suspend fun listen(): String? {
        update { copy(phase = SessionPhase.Listening, statusDetail = "Listening") }
        val prefs = settings()
        val options = ListenOptions(
            preferOffline = prefs.preferOfflineStt,
            completeSilenceMs = prefs.listenCompleteSilenceMs,
            possiblyCompleteSilenceMs = prefs.listenPossiblyCompleteSilenceMs,
            minimumLengthMs = prefs.listenMinimumMs,
        )
        return when (val result = speech.recognize(options) {}) {
            is Recognition.Final -> result.text
            is Recognition.Failure -> {
                note(result.message)
                if (result.fatal) {
                    throw SessionStop(result.message)
                } else if (result.retrySilently) {
                    delay(300)
                    null
                } else {
                    if (result.speakable) speakError(result.message)
                    delay(800)
                    null
                }
            }
        }
    }

    private suspend fun speakWithBargeIn(spoken: String): String? {
        if (!settings().bargeInEnabled) {
            speaker.speak(spoken)
            return null
        }
        while (taps.tryReceive().isSuccess) {
            // Drop a tap that arrived before this reply started.
        }
        var captured: String? = null
        coroutineScope {
            val speaking = launch { speaker.speak(spoken) }
            val listening = launch {
                while (isActive && speaking.isActive && captured == null) {
                    val prefs = settings()
                    val result = speech.recognize(
                        ListenOptions(
                            preferOffline = prefs.preferOfflineStt,
                            completeSilenceMs = 700,
                            possiblyCompleteSilenceMs = 500,
                            minimumLengthMs = 400,
                        ),
                    ) { partial ->
                        val piece = partial.trim()
                        if (piece.length >= 3 && !echo.isEcho(piece, spoken)) {
                            speaker.stop()
                        }
                    }
                    when (result) {
                        is Recognition.Final -> {
                            val text = result.text.trim()
                            if (text.isNotEmpty() && !echo.isEcho(text, spoken)) {
                                captured = text
                                speaker.stop()
                            }
                        }
                        is Recognition.Failure -> {
                            if (result.fatal) {
                                speaking.cancel()
                                throw SessionStop(result.message)
                            } else if (!result.retrySilently) {
                                log("Barge-in listen: ${result.message}")
                            }
                        }
                    }
                }
            }
            val tapping = launch {
                for (ignored in taps) {
                    speaker.stop()
                    speech.cancel()
                    speaking.cancel()
                    listening.cancel()
                }
            }
            speaking.join()
            speech.cancel()
            listening.cancel()
            tapping.cancel()
        }
        return captured
    }

    private suspend fun speakError(message: String) {
        if (!settings().speakErrors) return
        val spoken = sanitizer.sanitize(message)
        if (spoken.isBlank()) return
        update { copy(phase = SessionPhase.Speaking, statusDetail = "Speaking a status") }
        speaker.speak(spoken)
    }

    private class SessionStop(message: String) : RuntimeException(message)

    private fun fail(message: String) {
        log(message)
        update {
            copy(
                running = false,
                phase = SessionPhase.Idle,
                lastError = message,
                statusDetail = message,
            )
        }
    }

    private fun note(message: String) {
        log(message)
        update { copy(lastError = message, statusDetail = message) }
    }

    private inline fun update(block: SessionSnapshot.() -> SessionSnapshot) {
        _snapshot.value = _snapshot.value.block()
    }
}
