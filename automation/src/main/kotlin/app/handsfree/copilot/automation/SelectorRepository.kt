package app.handsfree.copilot.automation

import android.content.Context
import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.config.SelectorConfigJson

/**
 * Selectors live in assets/copilot_selectors.json. A calibration save replaces
 * that file's contents in preferences; the asset stays the reset target.
 */
class SelectorRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): CopilotSelectors {
        val raw = prefs.getString(KEY_JSON, null) ?: defaultJson()
        return try {
            SelectorConfigJson.parse(raw)
        } catch (_: Exception) {
            SelectorConfigJson.parse(defaultJson())
        }
    }

    fun rawJson(): String = prefs.getString(KEY_JSON, null) ?: defaultJson()

    fun defaultJson(): String =
        appContext.assets.open(ASSET).bufferedReader().use { it.readText() }

    /** @return null when the JSON was saved, or an error message. */
    fun save(json: String): String? {
        return try {
            val parsed = SelectorConfigJson.parse(json)
            if (parsed.targetPackage.isBlank()) {
                return "targetPackage is empty."
            }
            prefs.edit().putString(KEY_JSON, SelectorConfigJson.encode(parsed)).apply()
            null
        } catch (error: Exception) {
            error.message ?: "Selector JSON could not be parsed."
        }
    }

    fun reset() {
        prefs.edit().remove(KEY_JSON).apply()
    }

    private companion object {
        const val PREFS = "copilot_selectors"
        const val KEY_JSON = "json"
        const val ASSET = "copilot_selectors.json"
    }
}
