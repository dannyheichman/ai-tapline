package app.handsfree.copilot.core.text

/**
 * While the reply is being spoken, the recognizer often hears the TTS audio.
 * Overlap with the spoken words is treated as echo. Something else is a barge-in.
 */
object EchoFilter {
    fun isEcho(heard: String, spoken: String): Boolean {
        val heardWords = TextNormalize.words(heard)
        val spokenWords = TextNormalize.words(spoken).toSet()
        if (heardWords.isEmpty() || spokenWords.isEmpty()) return false
        val overlap = heardWords.count { it in spokenWords }
        return overlap.toFloat() / heardWords.size >= 0.6f
    }

    fun isInterruption(heard: String, spoken: String): Boolean {
        val heardWords = TextNormalize.words(heard)
        if (heardWords.isEmpty() || isEcho(heard, spoken)) return false
        return heardWords.size >= 2 || heardWords.first().length >= 4
    }
}
