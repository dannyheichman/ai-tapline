package app.handsfree.copilot.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectorConfigJsonTest {
    @Test
    fun roundTripKeepsThePackageAndStopPhrase() {
        val selectors = CopilotSelectors(
            targetPackage = "com.microsoft.office.officehubrow",
            settleMillis = 1_200,
            stopPhrases = listOf("stop session"),
            input = NodeSelector(resourceIdSuffixes = listOf("chat_input")),
        )
        val parsed = SelectorConfigJson.parse(SelectorConfigJson.encode(selectors))
        assertEquals(selectors.targetPackage, parsed.targetPackage)
        assertEquals(1_200L, parsed.settleMillis)
        assertEquals(listOf("stop session"), parsed.stopPhrases)
        assertEquals(listOf("chat_input"), parsed.input.resourceIdSuffixes)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val parsed = SelectorConfigJson.parse(
            """
            {"targetPackage":"com.example","futureField":true}
            """.trimIndent(),
        )
        assertEquals("com.example", parsed.targetPackage)
        assertEquals("stop session", parsed.stopPhrases.first())
    }

    @Test
    fun shippedSelectorFileParsesAndTargetsTheWorkCopilotApp() {
        val file = File("../automation/src/main/assets/copilot_selectors.json")
        assertTrue("Missing ${file.absolutePath}", file.isFile)
        val parsed = SelectorConfigJson.parse(file.readText())
        assertEquals("com.microsoft.office.officehubrow", parsed.targetPackage)
        assertTrue(parsed.stopPhrases.any { it.equals("stop session", ignoreCase = true) })
        assertTrue(parsed.settleMillis > 0)
        assertTrue(parsed.alternatePackages.contains("com.microsoft.copilot"))
    }
}
