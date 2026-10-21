package app.handsfree.copilot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.handsfree.copilot.appGraph
import app.handsfree.copilot.batterySettingsIntent
import app.handsfree.copilot.core.model.Phase
import app.handsfree.copilot.core.model.Role
import app.handsfree.copilot.core.model.isSessionActive
import app.handsfree.copilot.core.model.label
import app.handsfree.copilot.notificationPermission
import app.handsfree.copilot.overlaySettingsIntent

@Composable
fun SessionScreen(
    modifier: Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBargeIn: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph() }
    val phase by graph.session.phase.collectAsState()
    val partial by graph.session.partial.collectAsState()
    val banner by graph.session.banner.collectAsState()
    val turns by graph.session.turns.collectAsState()
    var permissionTick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionTick += 1
    }
    val micGranted = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val notificationsGranted = remember(permissionTick) {
        val permission = notificationPermission()
        permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val running = phase.isSessionActive()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hands-free Copilot", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Talks to the Microsoft Copilot text chat you already have open, using whichever model you picked there. Nothing is sent to a Microsoft API.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.label(), style = MaterialTheme.typography.titleLarge)
                val current = phase
                if (partial.isNotBlank() && current is Phase.Listening) {
                    Text(partial, style = MaterialTheme.typography.bodyLarge)
                }
                if (current is Phase.Waiting && current.partial.isNotBlank()) {
                    Text(current.partial, style = MaterialTheme.typography.bodyMedium)
                }
                if (banner.isNotBlank()) {
                    Text(banner, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!micGranted || !notificationsGranted) {
            Button(
                onClick = {
                    val permissions = buildList {
                        if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
                        notificationPermission()?.let { add(it) }
                    }
                    launcher.launch(permissions.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text("Allow microphone and notifications")
            }
        }
        if (running) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("Stop session")
            }
            if (phase is Phase.Speaking) {
                OutlinedButton(onClick = onBargeIn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Interrupt speech")
                }
            }
        } else {
            Button(
                onClick = onStart,
                enabled = micGranted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text("Start session")
            }
        }
        Text(
            "Open a Copilot text chat first and choose the model. Voice mode inside Copilot itself always falls back to the default model; this session does not use that mode.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Accessibility")
            }
            TextButton(onClick = { context.startActivity(overlaySettingsIntent(context.packageName)) }) {
                Text("Tap overlay")
            }
            TextButton(onClick = { context.startActivity(batterySettingsIntent(context.packageName)) }) {
                Text("Battery")
            }
        }
        Text("Transcript", style = MaterialTheme.typography.titleMedium)
        if (turns.isEmpty()) {
            Text("Nothing spoken yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            turns.takeLast(12).forEach { turn ->
                val who = if (turn.role == Role.USER) "You" else "Copilot"
                Text("$who: ${turn.text}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Say “stop session” to end. On a Galaxy, also allow restricted settings for this sideloaded app before the accessibility service will toggle on.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
