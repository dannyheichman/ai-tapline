package app.handsfree.copilot.core.model

/**
 * Ports the conversation loop needs. Android implements them in :speech and :automation.
 * Keep this file free of Android types so a desktop driver can reuse the loop.
 */
interface SpeechInput {
    suspend fun recognize(options: ListenOptions, onPartial: (String) -> Unit = {}): Recognition
    fun cancel()
}

interface SpeechOutput {
    suspend fun speak(text: String)
    fun stop()
}

interface ChatDriver {
    suspend fun openTarget(): DriverStatus
    suspend fun captureMessages(): List<String>
    suspend fun sendUserMessage(text: String): DriverStatus
    suspend fun observeUntil(
        timeoutMs: Long,
        pollIntervalMs: Long,
        onSnapshot: (ResponseObservation) -> String?,
    ): ObserveResult

    fun diagnostics(): AccessDiagnostics
}

fun interface SpeechSanitizer {
    fun sanitize(raw: String): String
}

fun interface Clock {
    fun elapsedMs(): Long
}
