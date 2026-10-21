package app.handsfree.copilot.core.selector

import app.handsfree.copilot.core.config.MatchRule
import app.handsfree.copilot.core.config.PickStrategy
import app.handsfree.copilot.core.config.SelectorSet
import app.handsfree.copilot.core.model.NodeSnapshot

object NodeSelectorMatcher {
    fun matches(node: NodeSnapshot, selector: SelectorSet): Boolean {
        if (selector.anyOf.isEmpty()) return false
        return selector.anyOf.any { matches(node, it) }
    }

    fun matches(node: NodeSnapshot, rule: MatchRule): Boolean {
        if (rule.editable != null && node.isEditable != rule.editable) return false
        if (rule.clickable != null && node.isClickable != rule.clickable) return false
        if (rule.enabled != null && node.isEnabled != rule.enabled) return false
        if (rule.password != null && node.isPassword != rule.password) return false
        if (rule.scrollable != null && node.isScrollable != rule.scrollable) return false
        val text = node.text.orEmpty()
        if (text.length < rule.minTextLength) return false
        if (!containsAny(node.viewIdResourceName, rule.resourceIdContains)) return false
        if (!excludesAll(node.viewIdResourceName, rule.resourceIdNotContains)) return false
        if (!containsAny(node.contentDescription, rule.contentDescriptionContains)) return false
        if (!excludesAll(node.contentDescription, rule.contentDescriptionNotContains)) return false
        if (!equalsAny(node.contentDescription, rule.contentDescriptionEquals)) return false
        if (!containsAny(text, rule.textContains)) return false
        if (!excludesAll(text, rule.textNotContains)) return false
        if (!equalsAny(text, rule.textEquals)) return false
        if (!containsAny(node.hintText, rule.hintContains)) return false
        if (!containsAny(node.className, rule.classNameContains)) return false
        if (!excludesAll(node.className, rule.classNameNotContains)) return false
        return true
    }

    fun pick(candidates: List<NodeSnapshot>, strategy: PickStrategy): NodeSnapshot? {
        if (candidates.isEmpty()) return null
        return when (strategy) {
            PickStrategy.FIRST -> candidates.first()
            PickStrategy.TOP_MOST -> candidates.minWith(compareBy({ it.boundsTop }, { it.boundsLeft }))
            PickStrategy.BOTTOM_MOST -> candidates.maxWith(compareBy({ it.boundsBottom }, { it.boundsRight }))
            PickStrategy.LONGEST_TEXT -> candidates.maxBy { it.text?.length ?: 0 }
        }
    }

    fun matching(nodes: List<NodeSnapshot>, selector: SelectorSet): List<NodeSnapshot> =
        nodes.filter { matches(it, selector) }

    private fun containsAny(value: String?, needles: List<String>): Boolean {
        if (needles.isEmpty()) return true
        val hay = value?.lowercase().orEmpty()
        return needles.any { needle -> needle.isNotBlank() && hay.contains(needle.lowercase()) }
    }

    private fun excludesAll(value: String?, needles: List<String>): Boolean {
        if (needles.isEmpty()) return true
        val hay = value?.lowercase().orEmpty()
        return needles.none { needle -> needle.isNotBlank() && hay.contains(needle.lowercase()) }
    }

    private fun equalsAny(value: String?, expected: List<String>): Boolean {
        if (expected.isEmpty()) return true
        val hay = value?.trim().orEmpty()
        return expected.any { it.equals(hay, ignoreCase = true) }
    }
}
