package app.handsfree.copilot.core.text

object StopCommands {
    private val fillers = setOf("please", "now", "the", "a", "hey", "ok", "okay")

    /**
     * True when the utterance is the stop command, possibly with a little filler.
     * A long sentence that merely mentions the phrase is left alone so it can be
     * sent to Copilot as a normal prompt.
     */
    fun matches(utterance: String, phrases: List<String>): Boolean {
        val words = TextNormalize.words(utterance).filterNot { it in fillers }
        if (words.isEmpty()) return false
        val compact = words.joinToString(" ")
        return phrases.map { TextNormalize.phrase(it) }.filter { it.isNotEmpty() }.any { phrase ->
            val phraseWords = phrase.split(' ')
            val room = phraseWords.size + 3
            compact == phrase ||
                (words.size <= room && (compact.startsWith("$phrase ") || compact.contains(phrase)))
        }
    }
}
