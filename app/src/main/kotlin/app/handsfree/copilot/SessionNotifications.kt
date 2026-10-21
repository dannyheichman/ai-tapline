package app.handsfree.copilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.handsfree.copilot.core.model.Phase
import app.handsfree.copilot.core.model.label

object SessionNotifications {
    const val CHANNEL_ID = "voice_session"
    const val ID = 42

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun showIdle(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ID, idle(context))
    }

    fun idle(context: Context): Notification {
        return base(context)
            .setContentTitle("Hands-free Copilot")
            .setContentText("Start a voice session. Copilot keeps the model you picked in text chat.")
            .setOngoing(true)
            .addAction(0, "Start", activityAction(context, SessionTrampolineActivity.ACTION_START))
            .build()
    }

    fun session(context: Context, phase: Phase): Notification {
        val detail = when (phase) {
            is Phase.Waiting -> phase.partial.take(80).ifBlank { "Waiting for the reply to finish." }
            is Phase.Speaking -> "Speaking. Talk or tap Interrupt to cut in."
            is Phase.Listening -> "Listening."
            is Phase.Sending -> "Sending."
            is Phase.Error -> phase.message
            else -> phase.label()
        }
        return base(context)
            .setContentTitle(phase.label())
            .setContentText(detail)
            .setOngoing(true)
            .addAction(0, "Interrupt", serviceAction(context, ConversationForegroundService.ACTION_BARGE))
            .addAction(0, "Stop", serviceAction(context, ConversationForegroundService.ACTION_STOP))
            .build()
    }

    private fun base(context: Context): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun activityAction(context: Context, action: String): PendingIntent {
        val intent = Intent(context, SessionTrampolineActivity::class.java).setAction(action)
        return PendingIntent.getActivity(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(context: Context, action: String): PendingIntent {
        val intent = Intent(context, ConversationForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
