package app.handsfree.copilot.ui.calibrate

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.handsfree.copilot.AppGraph
import app.handsfree.copilot.automation.CalibrationReport
import app.handsfree.copilot.core.config.DriverConfigJson
import app.handsfree.copilot.core.model.AccessLevel
import app.handsfree.copilot.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CalibrateScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by graph.driverStore.config.collectAsState()
    var editor by remember(config) { mutableStateOf(DriverConfigJson.encode(config)) }
    var message by remember { mutableStateOf(graph.driverStore.lastError) }
    var report by remember { mutableStateOf<CalibrationReport?>(null) }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.Main) { graph.driver.calibrate() }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@rememberLauncherForActivityResult
        editor = text
        graph.driverStore.saveRaw(text).onSuccess {
            message = "Imported selector file."
        }.onFailure {
            message = it.message ?: "Import failed"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Calibrate", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Copilot's view ids are not part of this app. Put the Copilot text chat on screen, refresh the dump, " +
                "and adjust the JSON until chatInput and sendButton each match the right node. " +
                "generatingIndicator should be 0 while Copilot is idle and 1 while a reply is streaming.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "The dump contains whatever text is on screen. Treat it as work data and do not commit it to the public repository.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        val current = report
        if (current != null) {
            val color = when (current.diagnostics.level) {
                AccessLevel.OK -> MaterialTheme.colorScheme.primary
                AccessLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                AccessLevel.BLOCKED -> MaterialTheme.colorScheme.error
            }
            Text(current.diagnostics.title, color = color, style = MaterialTheme.typography.titleMedium)
            Text(current.diagnostics.detail)
            Text("Foreground packages: ${current.foregroundPackages.joinToString().ifBlank { "(none)" }}")
            Text("Configured target: ${config.targetPackage}")
            current.hits.forEach { hit ->
                Text("${hit.name}: ${hit.count}" + if (hit.sample.isBlank()) "" else " — ${hit.sample}")
            }
            val foreground = current.foregroundPackages.firstOrNull { it != context.packageName }
            if (foreground != null && foreground != config.targetPackage) {
                OutlinedButton(
                    onClick = {
                        val updated = config.copy(targetPackage = foreground)
                        graph.driverStore.save(updated)
                        editor = DriverConfigJson.encode(updated)
                        message = "Target package set to $foreground"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use foreground package $foreground") }
            }
        }
        Button(
            onClick = {
                scope.launch {
                    report = graph.driver.calibrate()
                    message = "Dump refreshed."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh node tree") }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val status = graph.driver.openTarget()
                    report = graph.driver.calibrate()
                    message = when (status) {
                        is app.handsfree.copilot.core.model.DriverStatus.Ok -> "Copilot is open and readable."
                        is app.handsfree.copilot.core.model.DriverStatus.Blocked -> status.message
                        is app.handsfree.copilot.core.model.DriverStatus.Failed -> status.message
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Copilot") }
        OutlinedButton(
            onClick = {
                val dump = report?.text ?: "No dump yet."
                val dir = File(context.cacheDir, "dumps").apply { mkdirs() }
                val file = File(dir, "copilot-a11y-dump.txt")
                file.writeText(dump)
                shareFile(context, file, "text/plain")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share dump") }
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("copilot-a11y-dump", report?.text.orEmpty()))
                message = "Dump copied. It may contain work chat text."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy dump") }
        Text("Node tree", style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(
                text = report?.text ?: "Refresh to capture the tree.",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Selector JSON", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = editor,
            onValueChange = { editor = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Button(
            onClick = {
                graph.driverStore.saveRaw(editor).onSuccess {
                    message = "Selectors saved."
                }.onFailure {
                    message = it.message ?: "Invalid JSON"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save selectors") }
        OutlinedButton(
            onClick = {
                graph.driverStore.reset()
                editor = DriverConfigJson.encode(graph.driverStore.snapshot())
                message = "Restored the built-in selectors."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset to built-in selectors") }
        OutlinedButton(
            onClick = {
                val dir = File(context.cacheDir, "dumps").apply { mkdirs() }
                val file = File(dir, "driver-config.json")
                file.writeText(editor)
                shareFile(context, file, "application/json")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share selector file") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import selector file") }
        if (message.isNotBlank()) Text(message)
        Text(
            "Known packages are documented in the JSON notes. The work app is com.microsoft.office.officehubrow. " +
                "The consumer app is com.microsoft.copilot. The foreground package above is the one to trust on this phone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
