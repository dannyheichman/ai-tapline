package app.handsfree.copilot.core.model

/**
 * A platform-neutral view of one accessibility / UI Automation node.
 * Android fills this from [android.view.accessibility.AccessibilityNodeInfo].
 * A future Windows driver would fill it from UI Automation.
 */
data class NodeSnapshot(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val hintText: String? = null,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isPassword: Boolean = false,
    val isScrollable: Boolean = false,
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val packageName: String? = null,
    val depth: Int = 0,
)

enum class AccessLevel {
    OK,
    WARNING,
    BLOCKED,
}

/**
 * What the bridge could see of the target app. [detail] is written for a person
 * standing in front of the phone, not for a parser.
 */
data class AccessDiagnostics(
    val level: AccessLevel,
    val title: String,
    val detail: String,
    val targetPackage: String = "",
    val serviceConnected: Boolean = false,
    val installedInCurrentUser: Boolean = false,
    val visibleInAnotherProfile: Boolean = false,
    val runningInManagedProfile: Boolean = false,
    val windowFound: Boolean = false,
    val contentReadable: Boolean = false,
)

enum class SessionPhase {
    Idle,
    Opening,
    Listening,
    Sending,
    Waiting,
    Speaking,
}

data class SessionSnapshot(
    val running: Boolean = false,
    val phase: SessionPhase = SessionPhase.Idle,
    val lastUserText: String = "",
    val lastSpokenText: String = "",
    val lastError: String = "",
    val statusDetail: String = "",
)

data class ListenOptions(
    val preferOffline: Boolean,
    val completeSilenceMs: Long,
    val possiblyCompleteSilenceMs: Long,
    val minimumLengthMs: Long,
    val partials: Boolean = true,
)

sealed interface Recognition {
    data class Final(val text: String) : Recognition

    data class Failure(
        val code: Int,
        val message: String,
        val retrySilently: Boolean,
        val speakable: Boolean,
        val fatal: Boolean = false,
    ) : Recognition
}

sealed interface DriverStatus {
    data object Ok : DriverStatus
    data class Failed(val message: String) : DriverStatus
    data class Blocked(val message: String) : DriverStatus
}

data class ResponseObservation(
    val messagesTopToBottom: List<String>,
    val generatingIndicatorVisible: Boolean,
    val diagnostics: AccessDiagnostics,
)

sealed interface ObserveResult {
    data class Completed(val text: String) : ObserveResult
    data class TimedOut(val lastText: String) : ObserveResult
    data class Blocked(val detail: String) : ObserveResult
    data class Failed(val detail: String) : ObserveResult
}
