package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.text.TextNormalize

/**
 * Separates the new assistant reply from the previous bubble and from the
 * user's own utterance when a single node contains more than the reply.
 */
object AssistantTextExtractor {
    fun newContent(candidate: String, baseline: String, userUtterance: String): String {
        val text = candidate.trim()
        if (text.isEmpty()) return ""
        val baselineTrimmed = baseline.trim()
        val user = userUtterance.trim()
        if (baselineTrimmed.isNotEmpty() && same(text, baselineTrimmed)) return ""
        if (user.isNotEmpty() && same(text, user)) return ""
        if (baselineTrimmed.length >= 40 && text.startsWith(baselineTrimmed) && text.length > baselineTrimmed.length) {
            return text.removePrefix(baselineTrimmed).trim()
        }
        if (user.isNotEmpty() && text.startsWith(user) && text.length > user.length) {
            val boundary = text.substring(user.length)
            if (user.length >= 12 || boundary.startsWith("\n")) {
                return boundary.trim()
            }
        }
        return text
    }

    private fun same(left: String, right: String): Boolean =
        TextNormalize.whitespace(left) == TextNormalize.whitespace(right)
}
