package app.handsfree.copilot.ui.session

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.handsfree.copilot.AppGraph
import app.handsfree.copilot.R
import app.handsfree.copilot.automation.AccessibilityBridge
import app.handsfree.copilot.core.model.AccessLevel
import app.handsfree.copilot.core.model.SessionPhase
import app.handsfree.copilot.service.SessionForegroundService
import app.handsfree.copilot.tile.SessionTileService
import app.handsfree.copilot.ui.accessibilityEnabled
import app.handsfree.copilot.ui.ignoringBatteryOptimizations
import app.handsfree.copilot.ui.openAccessibilitySettings
import app.handsfree.copilot.ui.openAppDetails
import app.handsfree.copilot.ui.requestUnrestrictedBattery

@Composable
fun SessionScreen(graph: AppGraph) {
    val context = LocalContext.current
    val snap by graph.session.snapshot.collectAsState()
    val log by graph.log.lines.collectAsState()
    val diagnostics by graph.driver.diagnosticsFlow.collectAsState()
    val serviceOn by AccessibilityBridge.connected.collectAsState()
    var micGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }
    val a11yOn = accessibilityEnabled(context) || serviceOn
    val batteryOk = ignoringBatteryOptimizations(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Copilot Voice Bridge", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Speaks with the model already selected in the Microsoft 365 Copilot text chat. " +
                "Copilot's own voice mode is not used, and this app does not call Microsoft APIs.",
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusCard(
            title = diagnostics.title.ifBlank { "Not checked yet" },
            body = diagnostics.detail.ifBlank {
                "Open Calibrate once so the app can see whether Copilot is installed in this profile."
            },
            level = diagnostics.level,
        )
        CheckRow("Microphone", micGranted) {
            if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        CheckRow("Notifications", notificationsGranted) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        CheckRow("Accessibility service", a11yOn) { openAccessibilitySettings(context) }
        CheckRow("Battery unrestricted", batteryOk) { requestUnrestrictedBattery(context) }
        Text(
            "On Samsung One UI, sideloaded apps need Allow restricted settings in App info before the accessibility switch will stay on.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { openAppDetails(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open app info")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(
                onClick = {
                    val manager = context.getSystemService(StatusBarManager::class.java)
                    manager.requestAddTileService(
                        ComponentName(context, SessionTileService::class.java),
                        context.getString(R.string.tile_label),
                        Icon.createWithResource(context, R.drawable.ic_tile),
                        context.mainExecutor,
                    ) { }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add Quick Settings tile") }
        } else {
            Text(
                "Add the Copilot Voice tile from the Quick Settings editor.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = {
                if (snap.running) {
                    graph.session.stopFromUser()
                } else {
                    if (!micGranted) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        SessionForegroundService.start(context)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (snap.running) "End session" else "Start session")
        }
        if (snap.phase == SessionPhase.Speaking) {
            OutlinedButton(onClick = { graph.session.bargeIn() }, modifier = Modifier.fillMaxWidth()) {
                Text("Interrupt speech")
            }
        }
        Text("Status: ${phaseLabel(snap.phase)}", style = MaterialTheme.typography.titleMedium)
        if (snap.statusDetail.isNotBlank()) {
            Text(snap.statusDetail, style = MaterialTheme.typography.bodyMedium)
        }
        if (snap.lastError.isNotBlank()) {
            Text(snap.lastError, color = MaterialTheme.colorScheme.error)
        }
        TranscriptCard("Heard", snap.lastUserText.ifBlank { "Nothing yet" })
        TranscriptCard("Spoken", snap.lastSpokenText.ifBlank { "Nothing yet" })
        Text("Session log", style = MaterialTheme.typography.titleMedium)
        Text(
            log.takeLast(30).joinToString("\n").ifBlank { "Log lines appear here. Chat text is not written to this log." },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String, level: AccessLevel) {
    val color = when (level) {
        AccessLevel.OK -> MaterialTheme.colorScheme.primary
        AccessLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        AccessLevel.BLOCKED -> MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = color, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label: ${if (ok) "ready" else "needed"}")
        if (!ok) {
            OutlinedButton(onClick = onClick) { Text("Fix") }
        }
    }
}

@Composable
private fun TranscriptCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body)
        }
    }
}

private fun phaseLabel(phase: SessionPhase): String = when (phase) {
    SessionPhase.Idle -> "Idle"
    SessionPhase.Opening -> "Opening Copilot"
    SessionPhase.Listening -> "Listening"
    SessionPhase.Sending -> "Sending"
    SessionPhase.Waiting -> "Waiting for the reply"
    SessionPhase.Speaking -> "Speaking"
}
