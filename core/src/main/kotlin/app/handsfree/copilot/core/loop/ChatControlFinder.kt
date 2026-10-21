package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.config.NewestEdge
import app.handsfree.copilot.core.config.NodeSelector
import app.handsfree.copilot.core.model.UiNode
import app.handsfree.copilot.core.text.TextNormalize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scores configured selectors and, when nothing matches, falls back to generic
 * widget shape: an editable field for the composer, a clickable control beside
 * it for send, and the newest substantial text node for the reply.
 */
object ChatControlFinder {
    fun score(node: UiNode, selector: NodeSelector): Int {
        var score = 0
        val id = node.viewId
        if (id.isNotEmpty()) {
            if (selector.resourceIds.any { it.equals(id, ignoreCase = true) }) score += 100
            if (selector.resourceIdSuffixes.any { suffix ->
                    suffix.isNotBlank() && (id.endsWith("/$suffix", ignoreCase = true) || id.equals(suffix, ignoreCase = true))
                }
            ) {
                score += 70
            }
        }
        val description = node.contentDescription
        if (description.isNotEmpty()) {
            if (selector.contentDescriptions.any { it.equals(description, ignoreCase = true) }) score += 60
            if (selector.contentDescriptionContains.any { it.isNotBlank() && description.contains(it, ignoreCase = true) }) {
                score += 40
            }
        }
        val text = node.text
        if (text.isNotEmpty()) {
            if (selector.texts.any { it.equals(text, ignoreCase = true) }) score += 50
            if (selector.textContains.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }) score += 30
        }
        if (node.className.isNotEmpty() && selector.classNames.any { classMatches(node.className, it) }) {
            score += 25
        }
        return score
    }

    fun findInput(nodes: List<UiNode>, selector: NodeSelector): Int {
        val scored = nodes.mapIndexedNotNull { index, node ->
            val value = score(node, selector)
            if (value > 0) index to value else null
        }
        val editableScored = scored.filter { nodes[it.first].editable }
        if (editableScored.isNotEmpty()) return best(editableScored, nodes)
        val heuristic = nodes.mapIndexedNotNull { index, node ->
            if (node.editable && !node.password && node.visible) index else null
        }.maxByOrNull { nodes[it].bottom }
        if (heuristic != null) return heuristic
        if (scored.isNotEmpty()) return best(scored, nodes)
        return -1
    }

    fun findSend(nodes: List<UiNode>, selector: NodeSelector, inputIndex: Int): Int {
        val scored = nodes.mapIndexedNotNull { index, node ->
            if (index == inputIndex || !node.clickable) return@mapIndexedNotNull null
            val value = score(node, selector)
            if (value > 0) index to value else null
        }
        if (scored.isNotEmpty()) return scored.maxBy { it.second }.first
        if (inputIndex !in nodes.indices) return -1
        val input = nodes[inputIndex]
        val nearby = nodes.mapIndexedNotNull { index, node ->
            if (index == inputIndex || !node.clickable || node.editable) return@mapIndexedNotNull null
            if (!verticallyNear(node, input) || node.left < input.left) return@mapIndexedNotNull null
            index to node
        }
        if (nearby.isEmpty()) return -1
        val labeled = nearby.filter { (_, node) ->
            val haystack = "${node.text} ${node.contentDescription}".lowercase()
            haystack.contains("send") || haystack.contains("submit")
        }
        val pool = labeled.ifEmpty { nearby }
        return pool.minBy { (_, node) -> node.left - input.right }.first
    }

    fun generatingVisible(nodes: List<UiNode>, selectors: CopilotSelectors): Boolean {
        return nodes.any { node ->
            val matched = score(node, selectors.generating) > 0 || score(node, selectors.stopGenerating) > 0
            if (!matched) return@any false
            val length = displayText(node).length
            node.clickable || length in 1..48
        }
    }

    fun assistantCandidate(
        nodes: List<UiNode>,
        selectors: CopilotSelectors,
        userUtterance: String,
        baseline: String,
    ): String {
        val scoped = scopeToMessageList(nodes, selectors)
        val inputIndex = findInput(scoped, selectors.input).let { local ->
            if (local < 0) -1 else nodes.indexOf(scoped[local])
        }
        val excluded = setOf(
            TextNormalize.whitespace(userUtterance).lowercase(),
            TextNormalize.whitespace(baseline).lowercase(),
        ).filter { it.isNotEmpty() }.toSet()
        fun usable(index: Int, node: UiNode): Boolean {
            if (index == inputIndex || node.editable) return false
            val value = displayText(node)
            val normalized = TextNormalize.whitespace(value).lowercase()
            if (normalized.isEmpty() || normalized in excluded || normalized in CHROME) return false
            if (value.length < selectors.minAssistantChars && score(node, selectors.assistantMessage) == 0) return false
            return true
        }
        val matched = scoped.mapIndexedNotNull { _, node ->
            val index = nodes.indexOf(node)
            if (score(node, selectors.assistantMessage) > 0 && usable(index, node)) index else null
        }
        val pool = matched.ifEmpty {
            scoped.mapIndexedNotNull { _, node ->
                val index = nodes.indexOf(node)
                if (usable(index, node) && displayText(node).length >= selectors.minAssistantChars) index else null
            }
        }
        if (pool.isEmpty()) return ""
        val newest = if (selectors.newestEdge() == NewestEdge.TOP) {
            pool.minBy { nodes[it].top }
        } else {
            pool.maxBy { nodes[it].bottom }
        }
        return displayText(nodes[newest])
    }

    fun displayText(node: UiNode): String {
        val text = node.text.trim()
        val description = node.contentDescription.trim()
        return when {
            text.isEmpty() -> description
            description.isEmpty() -> text
            description.length > text.length && description.contains(text) -> description
            else -> text
        }
    }

    private fun best(scored: List<Pair<Int, Int>>, nodes: List<UiNode>): Int =
        scored.maxWith(
            compareBy<Pair<Int, Int>> { it.second }.thenBy { nodes[it.first].bottom },
        ).first

    private fun scopeToMessageList(nodes: List<UiNode>, selectors: CopilotSelectors): List<UiNode> {
        val lists = nodes.filter { score(it, selectors.messageList) > 0 }
        if (lists.isEmpty()) return nodes
        val inside = nodes.filter { node ->
            lists.any { list ->
                node.path.size > list.path.size && node.path.take(list.path.size) == list.path
            }
        }
        return inside.ifEmpty { nodes }
    }

    private fun classMatches(nodeClass: String, selectorClass: String): Boolean {
        if (selectorClass.isBlank()) return false
        if (nodeClass.equals(selectorClass, ignoreCase = true)) return true
        val simple = selectorClass.substringAfterLast('.')
        return simple.isNotEmpty() && (
            nodeClass.equals(simple, ignoreCase = true) ||
                nodeClass.endsWith(".$simple", ignoreCase = true)
            )
    }

    private fun verticallyNear(node: UiNode, input: UiNode): Boolean {
        val overlap = min(node.bottom, input.bottom) - max(node.top, input.top)
        val height = max(1, min(node.bottom - node.top, input.bottom - input.top))
        return overlap >= height / 3 || abs(node.top - input.top) < 200
    }

    private val CHROME = setOf(
        "send",
        "submit",
        "stop",
        "stop generating",
        "copy",
        "like",
        "dislike",
        "share",
        "retry",
        "regenerate",
        "good response",
        "bad response",
    )
}
