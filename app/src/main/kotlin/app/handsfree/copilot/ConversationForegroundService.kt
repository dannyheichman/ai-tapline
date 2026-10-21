package app.handsfree.copilot

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.handsfree.copilot.core.model.Phase
import app.handsfree.copilot.core.model.isSessionActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false
    private lateinit var overlay: InterruptOverlay

    override fun onCreate() {
        super.onCreate()
        overlay = InterruptOverlay(this) { appGraph().session.bargeIn() }
        scope.launch {
            appGraph().session.phase.collect { phase ->
                if (inForeground) {
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.notify(SessionNotifications.ID, SessionNotifications.session(this@ConversationForegroundService, phase))
                }
                overlay.onPhase(phase)
                SessionTileService.requestUpdate(this@ConversationForegroundService)
                if (inForeground && (phase is Phase.Idle || phase is Phase.Error)) {
                    leaveForeground()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                appGraph().session.stopSession()
                leaveForeground()
                return START_NOT_STICKY
            }
            ACTION_BARGE -> {
                appGraph().session.bargeIn()
                return START_STICKY
            }
            else -> {
                if (!hasMicrophonePermission()) {
                    SessionNotifications.showIdle(this)
                    stopSelf()
                    return START_NOT_STICKY
                }
                promote()
                appGraph().session.start()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun promote() {
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            SessionNotifications.ID,
            SessionNotifications.session(this, appGraph().session.phase.value),
            type,
        )
        inForeground = true
    }

    private fun leaveForeground() {
        if (!inForeground) {
            SessionNotifications.showIdle(this)
            return
        }
        inForeground = false
        overlay.hide()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        SessionNotifications.showIdle(this)
        stopSelf()
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_START = "app.handsfree.copilot.START"
        const val ACTION_STOP = "app.handsfree.copilot.STOP"
        const val ACTION_BARGE = "app.handsfree.copilot.BARGE"

        fun isRunning(phase: Phase): Boolean = phase.isSessionActive()
    }
}
