package app.handsfree.copilot.core.ports

import app.handsfree.copilot.core.loop.CompletionReason
import app.handsfree.copilot.core.model.BlockedReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ports the conversation loop needs. :speech and :automation implement these
 * on Android. A Windows port would implement them with UI Automation and SAPI
 * (or any other desktop speech stack) and reuse [app.handsfree.copilot.core.loop.HandsFreeConversation].
 */
interface ChatDriver {
    suspend fun prepare(): PrepareResult

    suspend fun captureBaseline(): String

    suspend fun submit(text: String): SubmitResult

    suspend fun observe(userUtterance: String, baseline: String): UiObservation
}

data class PrepareResult(
    val ok: Boolean,
    val message: String,
    val blocked: BlockedReason? = null,
)

data class SubmitResult(
    val ok: Boolean,
    val message: String,
)

data class UiObservation(
    val assistantCandidate: String,
    val generatingVisible: Boolean,
    val inputFound: Boolean,
    val diagnostics: String = "",
)

data class AwaitResult(
    val ok: Boolean,
    val text: String,
    val message: String,
    val reason: CompletionReason? = null,
)

sealed interface DictationEvent {
    data object Listening : DictationEvent
    data class Partial(val text: String) : DictationEvent
    data class Final(val text: String) : DictationEvent
    data class Failure(val message: String, val retry: Boolean) : DictationEvent
}

interface Dictation {
    fun listen(): Flow<DictationEvent>
    fun stop()
}

interface Speaker {
    val speaking: StateFlow<Boolean>
    suspend fun speak(text: String)
    fun stop()
}

/** Test seam so completion timing does not depend on the wall clock. */
interface Sleeper {
    fun now(): Long
    suspend fun sleep(millis: Long)
}
