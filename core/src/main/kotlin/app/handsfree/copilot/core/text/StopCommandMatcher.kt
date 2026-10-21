package app.handsfree.copilot.core.text

class StopCommandMatcher(
    private val phrases: () -> List<String>,
) {
    fun matches(transcript: String): Boolean {
        val heard = normalize(transcript)
        if (heard.isEmpty()) return false
        return phrases().any { phrase ->
            val needle = normalize(phrase)
            needle.isNotEmpty() && WORD_PHRASE(needle).containsMatchIn(heard)
        }
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun WORD_PHRASE(phrase: String) = Regex("\\b${Regex.escape(phrase)}\\b")
}
