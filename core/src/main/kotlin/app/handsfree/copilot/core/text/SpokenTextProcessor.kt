package app.handsfree.copilot.core.text

class SpokenTextProcessor {
    fun forSpeech(raw: String): String {
        val stripped = MarkdownStripper.strip(raw)
        return stripped.ifBlank { "The reply had no readable text." }
    }
}
