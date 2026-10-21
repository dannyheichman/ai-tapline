package app.handsfree.copilot.core.text

import app.handsfree.copilot.core.config.MessagePickSettings

/**
 * Picks the assistant bubble that appeared or grew after the user message was sent.
 *
 * Lists are visual order, top to bottom. Chrome that was already on screen stays in
 * [baselineTopToBottom] and is ignored. A streaming bubble is detected when its text
 * is a longer prefix-extension of a baseline string, or when it is new.
 */
class AssistantTextResolver {
    fun resolve(
        baselineTopToBottom: List<String>,
        currentTopToBottom: List<String>,
        userText: String,
        excludes: List<String>,
        pick: MessagePickSettings,
    ): String {
        val base = clean(baselineTopToBottom, userText, excludes)
        val now = clean(currentTopToBottom, userText, excludes)
        if (now.isEmpty()) return ""

        val growths = now.filter { text ->
            base.any { earlier -> text.length > earlier.length && text.startsWith(earlier) }
        }
        if (growths.isNotEmpty()) return growths.last()

        val fresh = now.filter { text -> base.none { earlier -> earlier == text } }
        if (fresh.isNotEmpty()) return skipShortTail(fresh, pick)
        return ""
    }

    private fun clean(items: List<String>, userText: String, excludes: List<String>): List<String> {
        val user = normalize(userText)
        return items.map(::normalize).filter { text ->
            text.isNotEmpty() &&
                text != user &&
                excludes.none { needle -> needle.isNotBlank() && text.contains(needle.trim(), ignoreCase = true) }
        }
    }

    private fun normalize(value: String): String = value.replace('\u00A0', ' ').trim()

    /**
     * Footers such as a short "Sources" caption often sit under the real answer and
     * show up as the last new node. Walk back while the tail is much shorter than
     * the text above it. Thresholds come from config, not from a hard-coded Copilot id.
     */
    private fun skipShortTail(fresh: List<String>, pick: MessagePickSettings): String {
        var items = fresh
        while (items.size >= 2) {
            val last = items.last()
            val longestAbove = items.dropLast(1).maxOf { it.length }
            val caption = last.length <= pick.shortTailMaxChars &&
                longestAbove > 0 &&
                last.length <= longestAbove * pick.shortTailRatio
            if (!caption) break
            items = items.dropLast(1)
        }
        return items.last()
    }
}
