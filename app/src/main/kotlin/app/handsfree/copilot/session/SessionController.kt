package app.handsfree.copilot.session

import android.content.Context
import android.content.Intent
import app.handsfree.copilot.automation.AutomationSignals
import app.handsfree.copilot.automation.OverlayState
import app.handsfree.copilot.core.loop.ConversationLoop
import app.handsfree.copilot.core.loop.LoopSettings
import app.handsfree.copilot.core.model.SessionPhase
import app.handsfree.copilot.core.model.SessionSnapshot
import app.handsfree.copilot.service.SessionForegroundService
import app.handsfree.copilot.tile.SessionTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SessionController(
    private val context: Context,
    private val loop: ConversationLoop,
    private val settings: () -> LoopSettings,
) {
    val snapshot: StateFlow<SessionSnapshot> = loop.snapshot
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var onSnapshot: (SessionSnapshot) -> Unit = {}

    init {
        scope.launch {
            var sawRunning = false
            snapshot.collect { snap ->
                if (snap.running) sawRunning = true
                AutomationSignals.overlay.value = OverlayState(
                    visible = snap.running,
                    label = overlayLabel(snap),
                    speaking = snap.phase == SessionPhase.Speaking,
                    keepScreenOn = settings().keepScreenOn,
                )
                onSnapshot(snap)
                SessionTileService.requestUpdate(context)
                if (sawRunning && !snap.running) {
                    context.startService(
                        Intent(context, SessionForegroundService::class.java)
                            .setAction(SessionForegroundService.ACTION_STOP),
                    )
                }
            }
        }
        scope.launch {
            AutomationSignals.stopRequested.collect { stopFromUser() }
        }
        scope.launch {
            AutomationSignals.bargeRequested.collect { loop.bargeIn() }
        }
    }

    fun begin() {
        loop.start(scope)
    }

    fun stopSession() {
        loop.stop()
        AutomationSignals.overlay.value = OverlayState(visible = false)
    }

    fun bargeIn() {
        loop.bargeIn()
    }

    fun stopFromUser() {
        stopSession()
        context.startService(
            Intent(context, SessionForegroundService::class.java)
                .setAction(SessionForegroundService.ACTION_STOP),
        )
    }

    private fun overlayLabel(snap: SessionSnapshot): String = when (snap.phase) {
        SessionPhase.Opening -> "Opening Copilot"
        SessionPhase.Listening -> "Listening"
        SessionPhase.Sending -> "Sending"
        SessionPhase.Waiting -> "Waiting"
        SessionPhase.Speaking -> "Speaking"
        SessionPhase.Idle -> "Idle"
    }
}
