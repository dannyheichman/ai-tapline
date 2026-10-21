package app.handsfree.copilot.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.handsfree.copilot.AppGraph
import app.handsfree.copilot.speech.EngineInfo
import app.handsfree.copilot.speech.VoiceInfo
import kotlinx.coroutines.launch

@Composable
fun VoiceScreen(graph: AppGraph) {
    val settings by graph.voiceStore.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var engines by remember { mutableStateOf<List<EngineInfo>>(emptyList()) }
    var voices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var engineMenu by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        engines = graph.speaker.engines()
    }
    LaunchedEffect(settings.enginePackage) {
        status = "Loading voices…"
        voices = graph.speaker.loadVoices(settings.enginePackage)
        status = if (voices.isEmpty()) {
            "No voices reported. Open the engine's own settings and download a voice, then come back."
        } else {
            "${voices.size} voices"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Voice", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Pick the Android text-to-speech engine and voice. On a Galaxy S25 Ultra this list usually includes Samsung TTS (com.samsung.SMT) and Google TTS (com.google.android.tts).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Engine", style = MaterialTheme.typography.titleMedium)
        val engineLabel = engines.firstOrNull { it.packageName == settings.enginePackage }?.label
            ?: settings.enginePackage.ifBlank { "System default" }
        OutlinedButton(onClick = { engineMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(engineLabel)
            DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                engines.forEach { engine ->
                    DropdownMenuItem(
                        text = { Text("${engine.label}\n${engine.packageName.ifBlank { "default" }}") },
                        onClick = {
                            engineMenu = false
                            scope.launch {
                                graph.voiceStore.update { it.copy(enginePackage = engine.packageName, voiceName = "") }
                            }
                        },
                    )
                }
            }
        }
        Text("Voice", style = MaterialTheme.typography.titleMedium)
        val voiceLabel = voices.firstOrNull { it.name == settings.voiceName }?.display
            ?: settings.voiceName.ifBlank { "Engine default" }
        OutlinedButton(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(voiceLabel)
            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Engine default") },
                    onClick = {
                        voiceMenu = false
                        scope.launch { graph.voiceStore.update { it.copy(voiceName = "") } }
                    },
                )
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.display) },
                        onClick = {
                            voiceMenu = false
                            scope.launch { graph.voiceStore.update { it.copy(voiceName = voice.name) } }
                        },
                    )
                }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Rate ${"%.2f".format(settings.rate)}")
        Slider(
            value = settings.rate,
            onValueChange = { value -> scope.launch { graph.voiceStore.update { it.copy(rate = value) } } },
            valueRange = 0.5f..2f,
        )
        Text("Pitch ${"%.2f".format(settings.pitch)}")
        Slider(
            value = settings.pitch,
            onValueChange = { value -> scope.launch { graph.voiceStore.update { it.copy(pitch = value) } } },
            valueRange = 0.5f..2f,
        )
        Button(
            onClick = {
                scope.launch {
                    graph.speaker.preview(
                        "This is Copilot Voice Bridge. The model you picked in the text chat will answer.",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Preview voice") }
        ToggleRow("Prefer on-device speech recognition", settings.preferOfflineStt) { checked ->
            scope.launch { graph.voiceStore.update { it.copy(preferOfflineStt = checked) } }
        }
        Text(
            "Uses Android's on-device recognizer when that model is installed. If it is missing, the app retries once with the phone's default recognizer. Install an offline voice pack from Samsung or Google settings if you want this to stay on the device.",
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow("Barge-in while speaking", settings.bargeInEnabled) { checked ->
            scope.launch { graph.voiceStore.update { it.copy(bargeInEnabled = checked) } }
        }
        Text(
            "Speaking over the reply interrupts it. The microphone can also hear the speaker; phrases that match the reply are ignored. The Interrupt button always works.",
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow("Speak errors", settings.speakErrors) { checked ->
            scope.launch { graph.voiceStore.update { it.copy(speakErrors = checked) } }
        }
        ToggleRow("Keep the screen on during a session", settings.keepScreenOn) { checked ->
            scope.launch { graph.voiceStore.update { it.copy(keepScreenOn = checked) } }
        }
        Text("Silence before end of speech: ${settings.listenCompleteSilenceMs} ms")
        Slider(
            value = settings.listenCompleteSilenceMs.toFloat(),
            onValueChange = { value ->
                scope.launch { graph.voiceStore.update { it.copy(listenCompleteSilenceMs = value.toLong()) } }
            },
            valueRange = 400f..3_000f,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
