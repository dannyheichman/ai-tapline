package app.handsfree.copilot.ui

import android.content.Intent
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.handsfree.copilot.appGraph
import app.handsfree.copilot.core.config.SelectorConfigJson
import app.handsfree.copilot.core.loop.AccessReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CalibrationScreen(modifier: Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph() }
    val repository = graph.automation.selectors
    var packageName by remember { mutableStateOf("") }
    var settle by remember { mutableStateOf("1500") }
    var cleared by remember { mutableStateOf("800") }
    var timeout by remember { mutableStateOf("120000") }
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<AccessReport?>(null) }
    var dumpPreview by remember { mutableStateOf("") }
    var savedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        json = repository.rawJson()
        applyFields(json, onPackage = { packageName = it }, onSettle = { settle = it }, onCleared = { cleared = it }, onTimeout = { timeout = it })
        report = graph.automation.diagnose()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Calibration", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Copilot's view ids are not part of this app. Dump the live accessibility tree with a text chat open, then paste resource ids and content descriptions into the JSON. The dump contains chat text. Don't commit it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        report?.let { current ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium)
                    Text(current.detail, style = MaterialTheme.typography.bodyMedium)
                    Text(current.code, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        OutlinedButton(
            onClick = { report = graph.automation.diagnose() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check Copilot access again") }
        OutlinedTextField(
            value = packageName,
            onValueChange = { packageName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Target package") },
            supportingText = {
                Text("Work Copilot is com.microsoft.office.officehubrow. Consumer com.microsoft.copilot is a different app.")
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = settle,
            onValueChange = { settle = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Settle time (ms)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = cleared,
            onValueChange = { cleared = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Settle after the stop indicator clears (ms)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = timeout,
            onValueChange = { timeout = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Response timeout (ms)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = json,
            onValueChange = { json = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            label = { Text("Selector JSON") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val parsed = runCatching { SelectorConfigJson.parse(json) }.getOrElse {
                    error = it.message ?: "Selector JSON could not be parsed."
                    return@Button
                }
                val updated = parsed.copy(
                    targetPackage = packageName.trim(),
                    settleMillis = settle.toLongOrNull() ?: parsed.settleMillis,
                    indicatorClearedSettleMillis = cleared.toLongOrNull() ?: parsed.indicatorClearedSettleMillis,
                    responseTimeoutMillis = timeout.toLongOrNull() ?: parsed.responseTimeoutMillis,
                )
                val encoded = SelectorConfigJson.encode(updated)
                error = repository.save(encoded)
                if (error == null) {
                    json = repository.rawJson()
                    report = graph.automation.diagnose()
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Save selectors") }
        OutlinedButton(
            onClick = {
                repository.reset()
                json = repository.rawJson()
                applyFields(json, { packageName = it }, { settle = it }, { cleared = it }, { timeout = it })
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset to the shipped selectors") }
        Button(
            onClick = {
                val text = graph.automation.dumpTree()
                val dir = File(context.cacheDir, "calibration").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "copilot-tree-$stamp.txt")
                file.writeText(text)
                savedFile = file
                dumpPreview = text.lineSequence().take(80).joinToString("\n")
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Dump Copilot node tree") }
        savedFile?.let { file ->
            Text(file.absolutePath, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Copilot accessibility tree")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share node tree"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share dump") }
        }
        if (dumpPreview.isNotBlank()) {
            SelectionContainer {
                Text(dumpPreview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

private fun applyFields(
    json: String,
    onPackage: (String) -> Unit,
    onSettle: (String) -> Unit,
    onCleared: (String) -> Unit,
    onTimeout: (String) -> Unit,
) {
    val parsed = runCatching { SelectorConfigJson.parse(json) }.getOrNull() ?: return
    onPackage(parsed.targetPackage)
    onSettle(parsed.settleMillis.toString())
    onCleared(parsed.indicatorClearedSettleMillis.toString())
    onTimeout(parsed.responseTimeoutMillis.toString())
}
