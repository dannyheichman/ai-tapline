package app.handsfree.copilot.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.handsfree.copilot.appGraph
import app.handsfree.copilot.speech.TtsEngineChoice
import app.handsfree.copilot.speech.TtsVoiceChoice
import kotlinx.coroutines.launch

@Composable
fun VoiceScreen(modifier: Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph() }
    val saved by graph.voiceSettings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var engines by remember { mutableStateOf<List<TtsEngineChoice>>(emptyList()) }
    var voices by remember { mutableStateOf<List<TtsVoiceChoice>>(emptyList()) }
    var voiceFilter by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        engines = graph.catalog.engines()
    }
    LaunchedEffect(saved.enginePackage) {
        status = "Loading voices…"
        voices = try {
            graph.catalog.voices(saved.enginePackage)
        } catch (error: Exception) {
            status = error.message ?: "Could not read voices."
            emptyList()
        }
        if (voices.isNotEmpty()) status = ""
    }

    val shownVoices = voices.filter { choice ->
        voiceFilter.isBlank() || choice.label.contains(voiceFilter, ignoreCase = true)
    }.take(40)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Spoken voice", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Uses an engine already installed on the phone, such as Samsung TTS or Google TTS. The list is whatever this device reports.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Engine", style = MaterialTheme.typography.titleMedium)
        EngineRow(
            selected = saved.enginePackage == null,
            label = "System default",
            onSelect = { graph.voiceSettings.update { it.copy(enginePackage = null, voiceName = null) } },
        )
        engines.forEach { engine ->
            EngineRow(
                selected = saved.enginePackage == engine.packageName,
                label = "${engine.label} (${engine.packageName})",
                onSelect = {
                    graph.voiceSettings.update { it.copy(enginePackage = engine.packageName, voiceName = null) }
                },
            )
        }
        Text("Rate ${"%.2f".format(saved.rate)}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = saved.rate,
            onValueChange = { value -> graph.voiceSettings.update { it.copy(rate = value) } },
            valueRange = 0.5f..2f,
        )
        Text("Pitch ${"%.2f".format(saved.pitch)}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = saved.pitch,
            onValueChange = { value -> graph.voiceSettings.update { it.copy(pitch = value) } },
            valueRange = 0.5f..2f,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = saved.preferOfflineStt,
                onCheckedChange = { checked -> graph.voiceSettings.update { it.copy(preferOfflineStt = checked) } },
            )
            Text("Prefer on-device speech recognition")
        }
        OutlinedTextField(
            value = saved.languageTag.orEmpty(),
            onValueChange = { value ->
                graph.voiceSettings.update { it.copy(languageTag = value.trim().ifBlank { null }) }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Recognition language (BCP-47, optional)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = voiceFilter,
            onValueChange = { voiceFilter = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter voices") },
            singleLine = true,
        )
        Text("Voice", style = MaterialTheme.typography.titleMedium)
        EngineRow(
            selected = saved.voiceName == null,
            label = "Engine default voice",
            onSelect = { graph.voiceSettings.update { it.copy(voiceName = null) } },
        )
        shownVoices.forEach { voice ->
            EngineRow(
                selected = saved.voiceName == voice.name,
                label = voice.label,
                onSelect = { graph.voiceSettings.update { it.copy(voiceName = voice.name) } },
            )
        }
        if (status.isNotBlank()) Text(status)
        Button(
            onClick = {
                scope.launch {
                    try {
                        graph.speaker.speak("This is how I will read Copilot's replies.")
                    } catch (error: Exception) {
                        status = error.message ?: "Preview failed."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("Preview voice")
        }
    }
}

@Composable
private fun EngineRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}
