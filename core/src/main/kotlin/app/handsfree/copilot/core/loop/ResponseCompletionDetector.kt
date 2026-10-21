package app.handsfree.copilot.core.loop

/**
 * Decides when a streaming Copilot reply is finished.
 *
 * The reply is finished when either:
 * - the captured text has been unchanged for [settleMillis] and no generating
 *   indicator is showing, or
 * - a generating / stop indicator was seen and has disappeared, and the text
 *   has then been unchanged for the shorter [indicatorClearedSettleMillis], or
 * - the indicator is stuck on but the text has been unchanged for
 *   [indicatorStuckMillis] (a wrong selector must not hang the session).
 *
 * Empty text never completes. Callers pass the timestamps, so tests do not sleep.
 */
class ResponseCompletionDetector(
    private val settleMillis: Long,
    private val indicatorClearedSettleMillis: Long = settleMillis,
    private val indicatorStuckMillis: Long = settleMillis * 3,
) {
    private var lastNormalized: String? = null
    private var lastChangeAt: Long = 0
    private var sawGenerating: Boolean = false
    private var completed: Boolean = false

    fun reset() {
        lastNormalized = null
        lastChangeAt = 0
        sawGenerating = false
        completed = false
    }

    fun update(text: String, generatingVisible: Boolean, atMillis: Long): CompletionUpdate {
        if (completed) {
            return CompletionUpdate(false, text, CompletionReason.ALREADY_COMPLETED)
        }
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) {
            if (generatingVisible) sawGenerating = true
            return CompletionUpdate(false, lastNormalized.orEmpty(), CompletionReason.EMPTY)
        }
        if (lastNormalized == null || normalized != lastNormalized) {
            lastNormalized = normalized
            lastChangeAt = atMillis
            if (generatingVisible) sawGenerating = true
            return CompletionUpdate(false, normalized, CompletionReason.IN_PROGRESS)
        }
        val stableFor = atMillis - lastChangeAt
        if (generatingVisible) {
            sawGenerating = true
            if (indicatorStuckMillis > 0 && stableFor >= indicatorStuckMillis) {
                completed = true
                return CompletionUpdate(true, normalized, CompletionReason.STABLE_DESPITE_INDICATOR)
            }
            return CompletionUpdate(false, normalized, CompletionReason.GENERATING)
        }
        val required = if (sawGenerating) indicatorClearedSettleMillis else settleMillis
        if (stableFor >= required) {
            completed = true
            val reason = if (sawGenerating) {
                CompletionReason.INDICATOR_CLEARED_AND_STABLE
            } else {
                CompletionReason.STABLE_TEXT
            }
            return CompletionUpdate(true, normalized, reason)
        }
        return CompletionUpdate(false, normalized, CompletionReason.SETTLING)
    }
}

data class CompletionUpdate(
    val completed: Boolean,
    val text: String,
    val reason: CompletionReason,
)

enum class CompletionReason {
    EMPTY,
    IN_PROGRESS,
    GENERATING,
    SETTLING,
    STABLE_TEXT,
    INDICATOR_CLEARED_AND_STABLE,
    STABLE_DESPITE_INDICATOR,
    ALREADY_COMPLETED,
    TIMEOUT_PARTIAL,
}
