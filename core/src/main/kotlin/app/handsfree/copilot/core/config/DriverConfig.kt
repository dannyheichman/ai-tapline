package app.handsfree.copilot.core.config

import kotlinx.serialization.Serializable

/**
 * Everything that describes *how to drive the chat UI*. Stored as JSON so it can be
 * edited on the device and shared, without a code change. Selectors are data:
 * do not hard-code resource ids in Kotlin.
 */
@Serializable
data class DriverConfig(
    val version: Int = 1,
    val notes: String = "",
    val targetPackage: String = "com.microsoft.office.officehubrow",
    val knownPackages: List<KnownPackage> = emptyList(),
    val openTimeoutMs: Long = 8_000,
    val pollIntervalMs: Long = 350,
    val responseTimeoutMs: Long = 120_000,
    val completion: CompletionSettings = CompletionSettings(),
    val stopPhrases: List<String> = listOf(
        "stop session",
        "end session",
        "stop voice",
        "cancel session",
    ),
    val messageTextExcludes: List<String> = emptyList(),
    val messagePick: MessagePickSettings = MessagePickSettings(),
    val sendFallbackIme: Boolean = true,
    val selectors: SelectorBundle = SelectorBundle(),
)

@Serializable
data class KnownPackage(
    val packageName: String,
    val label: String,
    val notes: String = "",
)

@Serializable
data class CompletionSettings(
    val stableForMs: Long = 1_500,
    val mode: CompletionMode = CompletionMode.BOTH,
)

@Serializable
enum class CompletionMode {
    /** Speak once the assistant text has not changed for [CompletionSettings.stableForMs]. */
    TEXT_STABLE,

    /**
     * Speak when a generating/stop indicator that was visible disappears.
     * If the indicator is never seen, fall back to text stability so a missing
     * selector cannot hang the session forever.
     */
    INDICATOR_HIDDEN,

    /**
     * Default. Text must be stable and the generating indicator must not be showing.
     * An indicator that is never found counts as "not showing".
     */
    BOTH,

    /** Stable text, or the indicator's falling edge, whichever comes first. */
    EITHER,
}

@Serializable
data class MessagePickSettings(
    val shortTailMaxChars: Int = 80,
    val shortTailRatio: Double = 0.35,
)

@Serializable
data class SelectorBundle(
    val chatInput: SelectorSet = SelectorSet(),
    val sendButton: SelectorSet = SelectorSet(),
    val generatingIndicator: SelectorSet = SelectorSet(),
    val assistantMessage: SelectorSet = SelectorSet(),
)

/**
 * A node matches when it satisfies any rule. Inside one rule every constraint is AND.
 * A non-empty "contains" or "equals" list matches if any entry hits (OR).
 * A non-empty "not contains" list fails the rule if any entry hits.
 */
@Serializable
data class SelectorSet(
    val anyOf: List<MatchRule> = emptyList(),
    val pick: PickStrategy = PickStrategy.BOTTOM_MOST,
)

@Serializable
enum class PickStrategy {
    BOTTOM_MOST,
    TOP_MOST,
    FIRST,
    LONGEST_TEXT,
}

@Serializable
data class MatchRule(
    val resourceIdContains: List<String> = emptyList(),
    val resourceIdNotContains: List<String> = emptyList(),
    val contentDescriptionContains: List<String> = emptyList(),
    val contentDescriptionNotContains: List<String> = emptyList(),
    val contentDescriptionEquals: List<String> = emptyList(),
    val textContains: List<String> = emptyList(),
    val textNotContains: List<String> = emptyList(),
    val textEquals: List<String> = emptyList(),
    val hintContains: List<String> = emptyList(),
    val classNameContains: List<String> = emptyList(),
    val classNameNotContains: List<String> = emptyList(),
    val editable: Boolean? = null,
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val password: Boolean? = null,
    val scrollable: Boolean? = null,
    val minTextLength: Int = 0,
)
