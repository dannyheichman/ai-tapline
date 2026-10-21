package app.handsfree.copilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class SessionTileService : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val running = ConversationForegroundService.isRunning(appGraph().session.phase.value)
        if (running) {
            startService(
                Intent(this, ConversationForegroundService::class.java)
                    .setAction(ConversationForegroundService.ACTION_STOP),
            )
            return
        }
        val intent = Intent(this, SessionTrampolineActivity::class.java)
            .setAction(SessionTrampolineActivity.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = android.app.PendingIntent.getActivity(
                this,
                7,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = ConversationForegroundService.isRunning(appGraph().session.phase.value)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (running) "Session on" else "Tap to talk"
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            requestListeningState(context, ComponentName(context, SessionTileService::class.java))
        }
    }
}
