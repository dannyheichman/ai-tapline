package app.handsfree.copilot.core.config

import kotlinx.serialization.json.Json

object DriverConfigJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
    }

    fun parse(text: String): DriverConfig = json.decodeFromString(DriverConfig.serializer(), text)

    fun encode(config: DriverConfig): String = json.encodeToString(DriverConfig.serializer(), config)
}

object DefaultDriverConfig {
    private const val RESOURCE = "default_driver_config.json"

    fun text(): String {
        val stream = DefaultDriverConfig::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from core resources")
        return stream.bufferedReader().use { it.readText() }
    }

    fun load(): DriverConfig = DriverConfigJson.parse(text())
}
