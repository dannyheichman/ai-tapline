package app.handsfree.copilot.core.access

import app.handsfree.copilot.core.model.AccessDiagnostics
import app.handsfree.copilot.core.model.AccessLevel

/**
 * Facts gathered by the Android profile / window probe. The decision itself has
 * no Android dependency so the messages can be unit tested.
 */
data class AccessFacts(
    val targetPackage: String,
    val serviceConnected: Boolean,
    val installedInCurrentUser: Boolean,
    val visibleInAnotherProfile: Boolean,
    val runningInManagedProfile: Boolean,
    val otherProfileHidden: Boolean,
    val packageLookupBlocked: Boolean,
    val windowFound: Boolean,
    val readableRoots: Int,
    val unreadableWindows: Int,
    val probedForeground: Boolean,
)

object AccessDecision {
    fun decide(facts: AccessFacts): AccessDiagnostics {
        val pkg = facts.targetPackage
        val base = AccessDiagnostics(
            level = AccessLevel.OK,
            title = "",
            detail = "",
            targetPackage = pkg,
            serviceConnected = facts.serviceConnected,
            installedInCurrentUser = facts.installedInCurrentUser,
            visibleInAnotherProfile = facts.visibleInAnotherProfile,
            runningInManagedProfile = facts.runningInManagedProfile,
            windowFound = facts.windowFound,
            contentReadable = facts.readableRoots > 0,
        )

        if (!facts.serviceConnected) {
            return base.copy(
                level = AccessLevel.WARNING,
                title = "Accessibility service is off",
                detail = "Turn on Copilot Voice Bridge under Settings, Accessibility, Installed apps. " +
                    "On Samsung One UI, a sideloaded app also needs Allow restricted settings in App info " +
                    "before that switch will stay on.",
            )
        }
        if (facts.packageLookupBlocked) {
            return base.copy(
                level = AccessLevel.BLOCKED,
                title = "Package lookup was blocked",
                detail = "Android refused to query $pkg. A work profile or device policy is hiding apps " +
                    "from this profile. Install Copilot Voice Bridge inside the same profile as Microsoft 365 " +
                    "Copilot and enable its accessibility service there.",
            )
        }
        if (!facts.installedInCurrentUser && facts.visibleInAnotherProfile) {
            return base.copy(
                level = AccessLevel.BLOCKED,
                title = "Copilot is in another profile",
                detail = "$pkg is installed in another profile, not in this one. Android does not let a " +
                    "personal-profile accessibility service read work-profile windows. Install this app into " +
                    "the work profile (adb install --user <workUserId>), enable the accessibility service there, " +
                    "and start the session from that profile.",
            )
        }
        if (!facts.installedInCurrentUser && facts.otherProfileHidden) {
            return base.copy(
                level = AccessLevel.BLOCKED,
                title = "Copilot is not visible in this profile",
                detail = "$pkg is not installed for this user, and another profile on the device hides its " +
                    "app list. That usually means a work profile. Install and enable Copilot Voice Bridge inside " +
                    "the work profile, next to Microsoft 365 Copilot. This app will not try to cross that boundary.",
            )
        }
        if (!facts.installedInCurrentUser) {
            return base.copy(
                level = AccessLevel.BLOCKED,
                title = "Copilot package is not installed",
                detail = "No installed app has package $pkg for this user. The enterprise Microsoft 365 Copilot " +
                    "app (work or school) uses com.microsoft.office.officehubrow. The separate consumer Copilot " +
                    "app uses com.microsoft.copilot. On the phone, Calibrate shows the foreground package, or run " +
                    "adb shell pm path com.microsoft.office.officehubrow.",
            )
        }
        if (facts.probedForeground && facts.windowFound && facts.readableRoots == 0 && facts.unreadableWindows > 0) {
            return base.copy(
                level = AccessLevel.BLOCKED,
                title = "Copilot content is blocked",
                detail = "The $pkg window is on screen but its contents are not exposed to accessibility. " +
                    "Intune app protection, a work-profile boundary, or a secure-window flag can cause this. " +
                    "Install this bridge in the same profile as Copilot, enable the accessibility service there, " +
                    "and ask your admin whether an app protection policy blocks accessibility or screen content. " +
                    "This app cannot bypass that policy.",
            )
        }
        if (facts.probedForeground && facts.windowFound && facts.readableRoots == 0) {
            return base.copy(
                level = AccessLevel.BLOCKED,
                title = "Copilot content is not readable",
                detail = "A window for $pkg was found but it has no readable accessibility tree. This often " +
                    "means Intune app protection or a work profile is blocking access. This app only reads what " +
                    "Android exposes. It does not use Microsoft APIs.",
            )
        }
        if (facts.runningInManagedProfile) {
            return base.copy(
                level = AccessLevel.OK,
                title = "Running in a work profile",
                detail = "$pkg is installed here and this app is running inside a managed profile. That is the " +
                    "supported setup when Copilot is a work app. If a reply stays blank, an Intune policy may " +
                    "still be hiding content. The calibration dump will say so.",
            )
        }
        if (!facts.probedForeground) {
            return base.copy(
                level = AccessLevel.OK,
                title = "Copilot is installed",
                detail = "$pkg is installed for this user. Open a text chat, pick the model there, and leave " +
                    "that chat on screen. This bridge types into that chat. It does not switch models and it " +
                    "does not call Microsoft APIs.",
            )
        }
        return base.copy(
            level = AccessLevel.OK,
            title = "Copilot content is readable",
            detail = "The accessibility service can read the $pkg window in this profile.",
        )
    }
}
