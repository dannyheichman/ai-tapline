package app.handsfree.copilot.automation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide link between the accessibility service (owned by the system)
 * and the conversation loop (owned by the app). Both live in one process.
 */
object AccessibilityBridge {
    @Volatile
    var service: CopilotAccessibilityService? = null
        private set

    val connected = MutableStateFlow(false)

    fun attach(service: CopilotAccessibilityService) {
        this.service = service
        connected.value = true
    }

    fun detach(service: CopilotAccessibilityService) {
        if (this.service === service) {
            this.service = null
            connected.value = false
        }
    }
}

data class OverlayState(
    val visible: Boolean = false,
    val label: String = "",
    val speaking: Boolean = false,
    val keepScreenOn: Boolean = true,
)

object AutomationSignals {
    val overlay = MutableStateFlow(OverlayState())
    val stopRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val bargeRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
