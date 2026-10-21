package app.handsfree.copilot.core.access

import app.handsfree.copilot.core.model.AccessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessDecisionTest {
    @Test
    fun workProfileInstallIsReportedAsBlocked() {
        val result = AccessDecision.decide(
            readyFacts().copy(
                installedInCurrentUser = false,
                visibleInAnotherProfile = true,
            ),
        )
        assertEquals(AccessLevel.BLOCKED, result.level)
        assertTrue(result.detail.contains("work profile", ignoreCase = true))
        assertTrue(result.detail.contains("adb install --user"))
    }

    @Test
    fun unreadableWindowIsReportedAsIntuneOrPolicy() {
        val result = AccessDecision.decide(
            readyFacts().copy(
                probedForeground = true,
                windowFound = true,
                readableRoots = 0,
                unreadableWindows = 1,
            ),
        )
        assertEquals(AccessLevel.BLOCKED, result.level)
        assertTrue(result.detail.contains("Intune"))
        assertTrue(result.detail.contains("cannot bypass"))
    }

    @Test
    fun missingPackageNamesBothKnownApps() {
        val result = AccessDecision.decide(readyFacts().copy(installedInCurrentUser = false))
        assertEquals(AccessLevel.BLOCKED, result.level)
        assertTrue(result.detail.contains("com.microsoft.office.officehubrow"))
        assertTrue(result.detail.contains("com.microsoft.copilot"))
    }

    @Test
    fun serviceOffIsAWarningEvenWhenThePackageExists() {
        val result = AccessDecision.decide(readyFacts().copy(serviceConnected = false))
        assertEquals(AccessLevel.WARNING, result.level)
        assertTrue(result.detail.contains("restricted settings", ignoreCase = true))
    }

    @Test
    fun readableWindowIsOk() {
        val result = AccessDecision.decide(
            readyFacts().copy(probedForeground = true, windowFound = true, readableRoots = 1),
        )
        assertEquals(AccessLevel.OK, result.level)
        assertTrue(result.contentReadable)
    }

    private fun readyFacts() = AccessFacts(
        targetPackage = "com.microsoft.office.officehubrow",
        serviceConnected = true,
        installedInCurrentUser = true,
        visibleInAnotherProfile = false,
        runningInManagedProfile = false,
        otherProfileHidden = false,
        packageLookupBlocked = false,
        windowFound = false,
        readableRoots = 0,
        unreadableWindows = 0,
        probedForeground = false,
    )
}
