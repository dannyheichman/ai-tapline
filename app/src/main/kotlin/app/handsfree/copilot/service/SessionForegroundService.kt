package app.handsfree.copilot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.handsfree.copilot.MainActivity
import app.handsfree.copilot.R
import app.handsfree.copilot.VoiceBridgeApp
import app.handsfree.copilot.core.model.SessionPhase
import app.handsfree.copilot.core.model.SessionSnapshot

class SessionForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        manager.createNotificationChannel(channel)
        val graph = (application as VoiceBridgeApp).graph
        graph.session.onSnapshot = { snap ->
            if (inForeground) {
                manager.notify(NOTIFICATION_ID, buildNotification(snap))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                (application as VoiceBridgeApp).graph.session.stopSession()
                releaseWakeLock()
                if (inForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    inForeground = false
                }
                stopSelf()
            }
            ACTION_BARGE_IN -> (application as VoiceBridgeApp).graph.session.bargeIn()
            else -> {
                val graph = (application as VoiceBridgeApp).graph
                val notification = buildNotification(graph.session.snapshot.value)
                startInForeground(notification)
                acquireWakeLock()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    val denied = graph.session.snapshot.value.copy(
                        lastError = "Microphone permission is missing.",
                        statusDetail = "Grant microphone permission, then start again.",
                    )
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(denied, forceDetail = denied.statusDetail))
                } else {
                    graph.session.begin()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as VoiceBridgeApp).graph.session.stopSession()
        releaseWakeLock()
        inForeground = false
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        inForeground = true
    }

    private fun buildNotification(snap: SessionSnapshot, forceDetail: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = serviceIntent(ACTION_STOP, 1)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(forceDetail ?: notificationText(snap))
            .setContentIntent(open)
            .setOngoing(snap.running)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "End", stop)
        if (snap.phase == SessionPhase.Speaking) {
            builder.addAction(0, "Interrupt", serviceIntent(ACTION_BARGE_IN, 2))
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, SessionForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationText(snap: SessionSnapshot): String = when (snap.phase) {
        SessionPhase.Opening -> "Opening Copilot"
        SessionPhase.Listening -> "Listening"
        SessionPhase.Sending -> "Sending"
        SessionPhase.Waiting -> "Waiting for Copilot"
        SessionPhase.Speaking -> "Speaking the reply"
        SessionPhase.Idle -> snap.statusDetail.ifBlank { "Session ended" }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicebridge:session").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "app.handsfree.copilot.START"
        const val ACTION_STOP = "app.handsfree.copilot.STOP"
        const val ACTION_BARGE_IN = "app.handsfree.copilot.BARGE_IN"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
