package app.handsfree.copilot.core.text

/**
 * Turns a Copilot (or model) reply into something a TTS engine can read.
 * Strips markdown syntax and the citation markers those models commonly emit.
 * The readable words stay.
 */
object MarkdownStripper {
    fun strip(markdown: String): String {
        var text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")

        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</li\\s*>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<[^>]+>"), "")

        text = text.replace(Regex("【[^】]*】"), " ")
        text = text.replace(Regex("\\[\\^\\d+\\^?]"), " ")
        text = text.replace(Regex("\\^\\d+\\^"), " ")
        text = text.replace(Regex("\\[\\d+]\\([^)]*\\)"), " ")
        text = text.replace(Regex("\\(citation[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("\\[\\d+]"), " ")

        text = text.lines()
            .filterNot { line -> line.trim().matches(Regex("\\[\\d+]:\\s+\\S+.*")) }
            .joinToString("\n")

        text = text.replace(Regex("(?m)^```[A-Za-z0-9_+.#-]*\\s*$"), "")
        text = text.replace(Regex("(?m)^```\\s*$"), "")
        text = text.replace(Regex("`([^`\\n]+)`"), "$1")

        text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]+)]\\[[^\\]]*\\]"), "$1")

        text = text.replace(Regex("(?m)^#{1,6}\\s*"), "")
        text = text.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        text = text.replace(Regex("~~(.+?)~~"), "$1")
        text = text.replace(Regex("(?m)^>\\s?"), "")
        text = text.replace(Regex("(?m)^\\s*(\\*{3,}|-{3,}|_{3,})\\s*$"), "")

        text = text.lines()
            .filterNot { line ->
                line.trim().matches(Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?"))
            }
            .joinToString("\n")
        text = text.replace("|", " ")

        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        text = text.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")

        // Italic markers after list markers are gone, so a leading "* item" is not eaten.
        text = text.replace(Regex("(\\*|_)(.+?)\\1"), "$2")
        text = text.replace("`", "")

        text = text.replace(Regex("[\\t ]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        text = text.lines().joinToString("\n") { it.trim() }.trim()
        text = text.replace(Regex("\\s+([,.;:!?])"), "$1")
        text = text.replace(Regex(" {2,}"), " ")
        return text.trim()
    }
}
