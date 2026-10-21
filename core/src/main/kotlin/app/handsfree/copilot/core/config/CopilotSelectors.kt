package app.handsfree.copilot.core.config

import kotlinx.serialization.Serializable

/**
 * How to find one control. Every field is optional. Matching is scored in
 * [app.handsfree.copilot.core.loop.ChatControlFinder], which also has generic
 * widget fallbacks (editable field, nearby clickable control) so a Copilot
 * build with unknown ids still has a chance.
 *
 * Resource ids, content descriptions, and class names belong in
 * `automation/src/main/assets/copilot_selectors.json` (or the user's saved
 * override), not in Kotlin conditionals.
 */
@Serializable
data class NodeSelector(
    val resourceIds: List<String> = emptyList(),
    val resourceIdSuffixes: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val contentDescriptionContains: List<String> = emptyList(),
    val texts: List<String> = emptyList(),
    val textContains: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
)

enum class NewestEdge {
    BOTTOM,
    TOP,
}

@Serializable
data class CopilotSelectors(
    /** Package the driver brings to the foreground and reads. */
    val targetPackage: String = "",
    /**
     * Not tried automatically. Listed so a calibration file can record other
     * ids (for example the consumer Copilot app) without switching to them.
     */
    val alternatePackages: List<String> = emptyList(),
    /** Optional explicit component (package/class or just the class name). */
    val launchActivity: String = "",
    val input: NodeSelector = NodeSelector(),
    val send: NodeSelector = NodeSelector(),
    val stopGenerating: NodeSelector = NodeSelector(),
    val generating: NodeSelector = NodeSelector(),
    val assistantMessage: NodeSelector = NodeSelector(),
    val messageList: NodeSelector = NodeSelector(),
    /** Text must be unchanged this long, and no generating indicator visible. */
    val settleMillis: Long = 1_500,
    /** Shorter settle once a generating/stop indicator was seen and then left. */
    val indicatorClearedSettleMillis: Long = 800,
    /**
     * If the indicator stays visible but the text has not changed for this long,
     * treat the reply as finished anyway so a mismatched "Stop" selector cannot
     * hang the session. 0 uses three times [settleMillis].
     */
    val indicatorStuckMillis: Long = 4_500,
    val responseTimeoutMillis: Long = 120_000,
    val pollMillis: Long = 350,
    val minAssistantChars: Int = 1,
    /** "bottom" (typical chat) or "top" (reversed list). */
    val newestMessageEdge: String = "bottom",
    val stopPhrases: List<String> = listOf(
        "stop session",
        "end session",
        "end voice session",
    ),
    val notes: String = "",
) {
    fun newestEdge(): NewestEdge =
        if (newestMessageEdge.equals("top", ignoreCase = true)) NewestEdge.TOP else NewestEdge.BOTTOM

    fun resolvedStuckMillis(): Long =
        if (indicatorStuckMillis > 0) indicatorStuckMillis else settleMillis * 3
}
