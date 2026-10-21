package app.handsfree.copilot.core.config

import kotlinx.serialization.json.Json

object SelectorConfigJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun parse(raw: String): CopilotSelectors = json.decodeFromString(raw)

    fun encode(selectors: CopilotSelectors): String = json.encodeToString(selectors)
}
