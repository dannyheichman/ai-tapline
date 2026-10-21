package app.handsfree.copilot.core.completion

import app.handsfree.copilot.core.config.CompletionMode

/**
 * Decides when a streaming assistant reply is finished.
 *
 * The caller passes the *new* assistant text (already compared with the pre-send
 * baseline by [app.handsfree.copilot.core.text.AssistantTextResolver]). An empty
 * string means "nothing new yet" and never completes, and it does not reset the
 * stability timer of text we already saw. That keeps a single blank frame during
 * a list redraw from looking like a brand new reply.
 */
class ResponseCompletionDetector(
    private val stableForMs: Long,
    private val mode: CompletionMode,
) {
    private var lastText: String = ""
    private var lastChangeAt: Long = 0L
    private var sawGenerating: Boolean = false
    private var generating: Boolean = false
    private var initialized: Boolean = false

    fun onSnapshot(assistantText: String, generatingVisible: Boolean, nowMs: Long): String? {
        if (!initialized) {
            lastChangeAt = nowMs
            initialized = true
        }
        if (generatingVisible) sawGenerating = true
        generating = generatingVisible

        val incoming = assistantText.trim()
        if (incoming.isNotEmpty() && incoming != lastText) {
            lastText = incoming
            lastChangeAt = nowMs
        }
        if (lastText.isEmpty()) return null

        val stable = nowMs - lastChangeAt >= stableForMs
        val indicatorClear = !generating
        val fallingEdge = sawGenerating && indicatorClear

        val done = when (mode) {
            CompletionMode.TEXT_STABLE -> stable
            CompletionMode.INDICATOR_HIDDEN -> fallingEdge || (!sawGenerating && stable)
            CompletionMode.BOTH -> stable && indicatorClear
            // Stable text finishes the turn even if the indicator is still up,
            // and a falling edge finishes it even if the text just changed.
            CompletionMode.EITHER -> stable || fallingEdge
        }
        return if (done) lastText else null
    }
}
