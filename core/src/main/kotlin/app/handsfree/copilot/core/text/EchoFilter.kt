package app.handsfree.copilot.core.text

/**
 * Barge-in listens while the reply is being spoken, so the microphone often hears
 * the speaker. A contiguous phrase from the reply is treated as echo and ignored.
 * Overlap of loose words is only applied to longer utterances, so a short real
 * command is not discarded just because the reply used the same words.
 */
class EchoFilter {
    fun isEcho(heard: String, spoken: String): Boolean {
        val heardTokens = tokens(heard)
        if (heardTokens.isEmpty()) return true
        val spokenTokens = tokens(spoken)
        if (spokenTokens.isEmpty()) return false
        if (containsContiguous(spokenTokens, heardTokens)) return true
        if (heardTokens.size >= 5) {
            val spokenSet = spokenTokens.toSet()
            val overlap = heardTokens.count { it in spokenSet }.toDouble() / heardTokens.size
            if (overlap >= 0.8) return true
        }
        return false
    }

    private fun tokens(value: String): List<String> =
        value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }

    private fun containsContiguous(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            if (haystack.subList(start, start + needle.size) == needle) return true
        }
        return false
    }
}
