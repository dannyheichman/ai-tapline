package app.handsfree.copilot.settings

import android.content.Context
import app.handsfree.copilot.core.config.DefaultDriverConfig
import app.handsfree.copilot.core.config.DriverConfig
import app.handsfree.copilot.core.config.DriverConfigJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class DriverConfigStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "driver_config.json")
    private val defaults = DefaultDriverConfig.load()
    private val _config = MutableStateFlow(read())
    val config: StateFlow<DriverConfig> = _config.asStateFlow()
    var lastError: String = ""
        private set

    fun snapshot(): DriverConfig = _config.value

    fun save(config: DriverConfig) {
        file.writeText(DriverConfigJson.encode(config))
        lastError = ""
        _config.value = config
    }

    fun saveRaw(json: String): Result<DriverConfig> = runCatching {
        val parsed = DriverConfigJson.parse(json)
        require(parsed.targetPackage.isNotBlank()) { "targetPackage is empty" }
        save(parsed)
        parsed
    }.onFailure { lastError = it.message ?: "Could not read that JSON" }

    fun reset() {
        if (file.exists()) file.delete()
        lastError = ""
        _config.value = defaults
    }

    fun raw(): String = DriverConfigJson.encode(snapshot())

    private fun read(): DriverConfig {
        if (!file.exists()) return defaults
        return runCatching { DriverConfigJson.parse(file.readText()) }
            .onFailure { lastError = "Saved selector file was ignored: ${it.message}" }
            .getOrElse { defaults }
    }
}
