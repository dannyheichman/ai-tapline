package app.handsfree.copilot

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.handsfree.copilot.ui.CalibrationScreen
import app.handsfree.copilot.ui.HandsFreeTheme
import app.handsfree.copilot.ui.SessionScreen
import app.handsfree.copilot.ui.VoiceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HandsFreeTheme {
                AppShell(
                    onStart = { startSession() },
                    onStop = { stopSession() },
                    onBargeIn = { appGraph().session.bargeIn() },
                )
            }
        }
    }

    private fun startSession() {
        val missingMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (missingMic) return
        CopilotLaunchBridge.open(this, appGraph().automation.selectors.current())
        ContextCompat.startForegroundService(
            this,
            Intent(this, ConversationForegroundService::class.java)
                .setAction(ConversationForegroundService.ACTION_START),
        )
    }

    private fun stopSession() {
        startService(
            Intent(this, ConversationForegroundService::class.java)
                .setAction(ConversationForegroundService.ACTION_STOP),
        )
    }
}

private enum class Destination(val label: String) {
    Session("Session"),
    Voice("Voice"),
    Calibrate("Calibrate"),
}

@Composable
private fun AppShell(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBargeIn: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Session) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    Destination.Session -> Icons.Filled.Home
                                    Destination.Voice -> Icons.Filled.Settings
                                    Destination.Calibrate -> Icons.Filled.Info
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (destination) {
            Destination.Session -> SessionScreen(modifier, onStart, onStop, onBargeIn)
            Destination.Voice -> VoiceScreen(modifier)
            Destination.Calibrate -> CalibrationScreen(modifier)
        }
    }
}

internal fun notificationPermission(): String? =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

internal fun overlaySettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    )

internal fun batterySettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"),
    )
