package app.handsfree.copilot.core.text

object TextNormalize {
    fun phrase(value: String): String = words(value).joinToString(" ")

    fun words(value: String): List<String> =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .split(' ')
            .filter { it.isNotBlank() }

    fun whitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
