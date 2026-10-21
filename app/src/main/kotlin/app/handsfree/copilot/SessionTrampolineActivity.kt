package app.handsfree.copilot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * User-initiated entry from the Quick Settings tile and the idle notification.
 * A visible activity is what Android allows to start a microphone foreground
 * service and to bring Copilot forward.
 */
class SessionTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_STOP -> {
                startService(
                    Intent(this, ConversationForegroundService::class.java)
                        .setAction(ConversationForegroundService.ACTION_STOP),
                )
            }
            else -> {
                val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (!micGranted) {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val selectors = appGraph().automation.selectors.current()
                    CopilotLaunchBridge.open(this, selectors)
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, ConversationForegroundService::class.java)
                            .setAction(ConversationForegroundService.ACTION_START),
                    )
                }
            }
        }
        finish()
    }

    companion object {
        const val ACTION_START = "app.handsfree.copilot.TRAMPOLINE_START"
        const val ACTION_STOP = "app.handsfree.copilot.TRAMPOLINE_STOP"
    }
}
