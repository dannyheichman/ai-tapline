package app.handsfree.copilot.core.loop

/**
 * Turns facts collected on the device into a message a person can act on.
 * The Android collector lives in :automation; this object stays free of Android
 * so the work-profile and Intune cases can be unit tested.
 */
object AccessDiagnoser {
    fun diagnose(facts: AccessFacts): AccessReport {
        if (!facts.serviceEnabledInSettings) {
            return blocked(
                code = "ACCESSIBILITY_DISABLED",
                title = "Accessibility service is off",
                detail = "Turn on Hands-free Copilot in Settings, then Accessibility. " +
                    "The service only reads the Copilot screen so it can type your words and copy the reply. " +
                    "It does not call a Microsoft API.",
            )
        }
        if (!facts.serviceConnected) {
            return blocked(
                code = "SERVICE_NOT_CONNECTED",
                title = "Accessibility service is not connected",
                detail = "The service is enabled in settings but this process is not connected to it. " +
                    "Toggle Hands-free Copilot off and on in Accessibility settings, then try again.",
            )
        }
        if (!facts.packageInstalledForCurrentUser && facts.packageEnabledInOtherProfiles) {
            val where = facts.otherProfileLabels.joinToString().ifBlank { "another Android profile" }
            return blocked(
                code = "WORK_PROFILE_ONLY",
                title = "Copilot is in another profile",
                detail = "Package ${facts.targetPackage} is not installed for this user, but it is enabled in $where. " +
                    "Android does not let a personal-profile accessibility service read a work-profile app. " +
                    "Install Hands-free Copilot inside the same work profile (Company Portal or managed Play Store) " +
                    "and turn its accessibility service on there. Intune app protection can still hide the text even after that.",
            )
        }
        if (!facts.packageInstalledForCurrentUser) {
            return blocked(
                code = "NOT_INSTALLED",
                title = "Copilot app not installed for this user",
                detail = "Nothing with package ${facts.targetPackage} is installed here. " +
                    "The work and school Microsoft Copilot app (the renamed Microsoft 365 app) is " +
                    "com.microsoft.office.officehubrow. The consumer app com.microsoft.copilot is a different product. " +
                    "If your organization only deploys Copilot into the work profile, install this companion in that profile too.",
            )
        }
        if (facts.copilotWindowPresent && facts.totalNodeCount == 0) {
            return blocked(
                code = "CONTENT_PROTECTED",
                title = "Copilot's screen is blocked",
                detail = "The Copilot window is open, but accessibility cannot see any of its content. " +
                    "A work profile, an Intune app protection policy, or a secure window usually causes this. " +
                    "Ask your admin whether accessibility is blocked for Copilot, or run this companion inside the work profile.",
            )
        }
        if (facts.copilotWindowPresent && facts.textNodeCount == 0 && facts.totalNodeCount > 0) {
            return blocked(
                code = "CONTENT_REDACTED",
                title = "Copilot's text is hidden",
                detail = "The Copilot window has views, but none of them expose text or a content description. " +
                    "Intune app protection often redacts text from accessibility services. " +
                    "This companion cannot read the reply until that policy allows it. " +
                    "Installing into the work profile does not always override the policy.",
            )
        }
        if (!facts.copilotWindowPresent) {
            return warning(
                code = "WINDOW_NOT_FOUND",
                title = "Copilot is not in front",
                detail = "Package ${facts.targetPackage} is installed, but its window is not visible. " +
                    "Open a text chat, not Copilot's audio mode, and leave the model you want selected.",
            )
        }
        if (!facts.inputFound) {
            return warning(
                code = "INPUT_NOT_FOUND",
                title = "Chat box not found",
                detail = "Copilot is open, but no chat input matched the selectors or the editable-field fallback. " +
                    "Open a text conversation. If the box is already on screen, dump the node tree on the Calibration screen and tune the input selector.",
            )
        }
        val profileNote = if (facts.runningInManagedProfile) {
            " This install is inside a managed profile, which is where a work-profile Copilot can be driven."
        } else {
            ""
        }
        return AccessReport(
            level = AccessLevel.OK,
            code = "OK",
            title = "Copilot is readable",
            detail = "The accessibility service can see the Copilot window and a chat input.$profileNote " +
                "Replies are spoken with whatever model is already selected in that text chat.",
        )
    }

    private fun blocked(code: String, title: String, detail: String) =
        AccessReport(AccessLevel.BLOCKED, code, title, detail)

    private fun warning(code: String, title: String, detail: String) =
        AccessReport(AccessLevel.WARNING, code, title, detail)
}

data class AccessFacts(
    val targetPackage: String,
    val serviceEnabledInSettings: Boolean,
    val serviceConnected: Boolean,
    val packageInstalledForCurrentUser: Boolean,
    val packageEnabledInOtherProfiles: Boolean,
    val otherProfileLabels: List<String> = emptyList(),
    val runningInManagedProfile: Boolean = false,
    val copilotWindowPresent: Boolean = false,
    val totalNodeCount: Int = 0,
    val textNodeCount: Int = 0,
    val inputFound: Boolean = false,
)

enum class AccessLevel {
    OK,
    WARNING,
    BLOCKED,
}

data class AccessReport(
    val level: AccessLevel,
    val code: String,
    val title: String,
    val detail: String,
)
