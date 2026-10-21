package app.handsfree.copilot.core.loop

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessDiagnoserTest {
    @Test
    fun reportsADisabledService() {
        val report = AccessDiagnoser.diagnose(base().copy(serviceEnabledInSettings = false))
        assertEquals("ACCESSIBILITY_DISABLED", report.code)
        assertEquals(AccessLevel.BLOCKED, report.level)
    }

    @Test
    fun reportsCopilotHiddenInAWorkProfile() {
        val report = AccessDiagnoser.diagnose(
            base().copy(
                packageInstalledForCurrentUser = false,
                packageEnabledInOtherProfiles = true,
                otherProfileLabels = listOf("Android user serial 12"),
            ),
        )
        assertEquals("WORK_PROFILE_ONLY", report.code)
        assertEquals(AccessLevel.BLOCKED, report.level)
    }

    @Test
    fun reportsAnEmptyWindowAsProtectedContent() {
        val report = AccessDiagnoser.diagnose(
            base().copy(copilotWindowPresent = true, totalNodeCount = 0, textNodeCount = 0),
        )
        assertEquals("CONTENT_PROTECTED", report.code)
    }

    @Test
    fun reportsRedactedTextSeparatelyFromAMissingWindow() {
        val report = AccessDiagnoser.diagnose(
            base().copy(copilotWindowPresent = true, totalNodeCount = 12, textNodeCount = 0, inputFound = false),
        )
        assertEquals("CONTENT_REDACTED", report.code)
        assertEquals(AccessLevel.BLOCKED, report.level)
    }

    @Test
    fun reportsOkWhenTheChatInputIsVisible() {
        val report = AccessDiagnoser.diagnose(
            base().copy(
                copilotWindowPresent = true,
                totalNodeCount = 20,
                textNodeCount = 4,
                inputFound = true,
                runningInManagedProfile = true,
            ),
        )
        assertEquals("OK", report.code)
        assertEquals(AccessLevel.OK, report.level)
    }

    private fun base() = AccessFacts(
        targetPackage = "com.microsoft.office.officehubrow",
        serviceEnabledInSettings = true,
        serviceConnected = true,
        packageInstalledForCurrentUser = true,
        packageEnabledInOtherProfiles = false,
    )
}
