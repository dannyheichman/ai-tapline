package app.handsfree.copilot.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.handsfree.copilot.VoiceBridgeApp
import app.handsfree.copilot.service.SessionForegroundService

class SessionTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val running = (application as VoiceBridgeApp).graph.session.snapshot.value.running
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Copilot Voice"
        tile.subtitle = if (running) "On" else "Off"
        tile.updateTile()
    }

    override fun onClick() {
        unlockAndRun {
            val graph = (application as VoiceBridgeApp).graph
            if (graph.session.snapshot.value.running) {
                graph.session.stopFromUser()
            } else {
                SessionForegroundService.start(this)
            }
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestListeningState(context, ComponentName(context, SessionTileService::class.java))
            }
        }
    }
}
