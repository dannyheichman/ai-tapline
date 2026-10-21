package app.handsfree.copilot.core.text

import app.handsfree.copilot.core.model.SpeechSanitizer

/**
 * Turns a Copilot markdown reply into something a TTS engine can read.
 * Citation markers are removed. Link targets are removed and the visible label is kept.
 * Fenced code is spoken as its body, without the fence or the language tag.
 */
class MarkdownSpeechSanitizer : SpeechSanitizer {
    override fun sanitize(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\u00A0', ' ')
        text = FENCE.replace(text) { match ->
            val body = match.groupValues[1].trim()
            if (body.isEmpty()) " " else "\n$body\n"
        }
        text = INLINE_CODE.replace(text) { it.groupValues[1] }
        text = IMAGE.replace(text) { it.groupValues[1] }
        text = LINK.replace(text) { it.groupValues[1] }
        text = BARE_URL.replace(text) { match ->
            val trailing = match.value.takeLastWhile { it in ".,;:!?)" }
            " $trailing"
        }
        text = HEADING.replace(text, "")
        text = BLOCKQUOTE.replace(text, "")
        text = HR.replace(text, " ")
        text = TABLE_RULE.replace(text, " ")
        text = BULLET.replace(text, "")
        text = ORDERED.replace(text, "")
        text = BOLD.replace(text) { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
        text = STRIKE.replace(text) { it.groupValues[1] }
        text = ITALIC.replace(text) { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        text = FULLWIDTH_CITE.replace(text, " ")
        text = FOOTNOTE.replace(text, " ")
        text = CARET_NUM.replace(text, " ")
        text = BRACKET_NUM.replace(text, " ")
        text = PAREN_CITE.replace(text, " ")
        text = DAGGER.replace(text, " ")
        text = SUPERSCRIPT.replace(text, " ")
        text = HTML.replace(text, " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", " and ")
            .replace("&lt;", " ")
            .replace("&gt;", " ")
            .replace("&quot;", " ")
        text = text.replace("|", ", ")
        text = text.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
        text = text.replace(Regex("[\\t ]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n")
        text = text.replace(Regex("\\n{2,}"), "\n")
        text = text.replace(Regex("\\s*,\\s*"), ", ")
        text = text.replace(Regex("(,\\s*){2,}"), ", ")
        text = text.replace(Regex("(?m)^,\\s*|\\s*,\\s*$"), "")
        text = text.replace(Regex("\\s+([.,;:!?])"), "$1")
        return toSentences(text)
    }

    private fun toSentences(value: String): String {
        val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        return lines.joinToString(" ") { line ->
            val cleaned = line.trim().trimEnd(',', ';')
            if (cleaned.isEmpty()) ""
            else             if (cleaned.last() in ".!?…:") cleaned
            else "$cleaned."
        }.replace(Regex("\\s+"), " ").trim()
    }

    private companion object {
        val FENCE = Regex("```[a-zA-Z0-9_+-]*\\s*([\\s\\S]*?)```")
        val INLINE_CODE = Regex("`([^`]+)`")
        val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
        val LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
        val BARE_URL = Regex("https?://\\S+")
        val HEADING = Regex("(?m)^#{1,6}\\s+")
        val BLOCKQUOTE = Regex("(?m)^>\\s?")
        val HR = Regex("(?m)^(?:---+|\\*\\*\\*+|___+)\\s*$")
        val TABLE_RULE = Regex("(?m)^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$")
        val BULLET = Regex("(?m)^\\s*(?:[-*+]|•)\\s+")
        val ORDERED = Regex("(?m)^\\s*\\d+[.)]\\s+")
        val BOLD = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
        val STRIKE = Regex("~~([^~]+)~~")
        val ITALIC = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)")
        val FULLWIDTH_CITE = Regex("【[^】]{0,80}】")
        val FOOTNOTE = Regex("\\[\\^\\d{1,3}]")
        val CARET_NUM = Regex("\\^\\d{1,3}\\^")
        val BRACKET_NUM = Regex("\\[(?:\\d{1,3})(?:\\s*[,，]\\s*\\d{1,3})*]")
        val PAREN_CITE = Regex(
            "\\(\\s*(?:citation|citations|source|sources|ref|refs)\\b[^)]{0,60}\\)",
            RegexOption.IGNORE_CASE,
        )
        val DAGGER = Regex("†[^\\s\\]\\)]{1,40}")
        val SUPERSCRIPT = Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹]+")
        val HTML = Regex("</?[a-zA-Z][^>]*>")
    }
}
