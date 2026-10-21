package app.handsfree.copilot.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDriverConfigTest {
    @Test
    fun defaultFileParsesAndTargetsTheWorkCopilotPackage() {
        val config = DefaultDriverConfig.load()
        assertEquals(1, config.version)
        assertEquals("com.microsoft.office.officehubrow", config.targetPackage)
        assertTrue(config.knownPackages.any { it.packageName == "com.microsoft.copilot" })
        assertTrue(config.selectors.chatInput.anyOf.isNotEmpty())
        assertTrue(config.selectors.sendButton.anyOf.isNotEmpty())
        assertTrue(config.selectors.generatingIndicator.anyOf.isNotEmpty())
        assertTrue(config.selectors.assistantMessage.anyOf.isNotEmpty())
        assertEquals(CompletionMode.BOTH, config.completion.mode)
        assertTrue(config.stopPhrases.contains("stop session"))
    }

    @Test
    fun roundTripKeepsTheTargetPackageAndIgnoresUnknownKeys() {
        val parsed = DriverConfigJson.parse(
            """
            {
              "targetPackage": "com.example.chat",
              "futureField": true,
              "completion": { "stableForMs": 900, "mode": "TEXT_STABLE" }
            }
            """.trimIndent(),
        )
        assertEquals("com.example.chat", parsed.targetPackage)
        assertEquals(900L, parsed.completion.stableForMs)
        assertEquals(CompletionMode.TEXT_STABLE, parsed.completion.mode)
        val again = DriverConfigJson.parse(DriverConfigJson.encode(parsed))
        assertEquals(parsed.targetPackage, again.targetPackage)
        assertEquals(parsed.completion, again.completion)
    }
}
