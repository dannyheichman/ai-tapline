package app.handsfree.copilot.core.model

/**
 * A platform-neutral description of one accessibility/UI node.
 * Android maps [android.view.accessibility.AccessibilityNodeInfo] into this;
 * a future Windows port can map UI Automation elements into the same shape.
 */
data class NodeAddress(
    val windowId: Int,
    val path: List<Int>,
)

fun UiNode.address(): NodeAddress = NodeAddress(windowId, path)

data class UiNode(
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val viewId: String = "",
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val password: Boolean = false,
    val windowId: Int = -1,
    val path: List<Int> = emptyList(),
)

enum class Role {
    USER,
    ASSISTANT,
}

data class Turn(
    val role: Role,
    val text: String,
)

enum class BlockedReason {
    ACCESSIBILITY_DISABLED,
    PACKAGE_NOT_INSTALLED,
    WORK_PROFILE_ONLY,
    CONTENT_PROTECTED,
    WINDOW_NOT_FOUND,
    INPUT_NOT_FOUND,
    LAUNCH_FAILED,
}

sealed interface Phase {
    data object Idle : Phase
    data object Preparing : Phase
    data object Listening : Phase
    data class Sending(val text: String) : Phase
    data class Waiting(val partial: String) : Phase
    data class Speaking(val text: String) : Phase
    data class Error(val message: String, val blocked: BlockedReason?) : Phase
}

fun Phase.label(): String = when (this) {
    Phase.Idle -> "Idle"
    Phase.Preparing -> "Opening Copilot"
    Phase.Listening -> "Listening"
    is Phase.Sending -> "Sending"
    is Phase.Waiting -> "Waiting for Copilot"
    is Phase.Speaking -> "Speaking"
    is Phase.Error -> "Needs attention"
}

fun Phase.isSessionActive(): Boolean = when (this) {
    Phase.Idle, is Phase.Error -> false
    else -> true
}
