package app.handsfree.copilot.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceSettings(
    val enginePackage: String? = null,
    val voiceName: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    /** BCP-47 tag for speech recognition. Null uses the device locale. */
    val languageTag: String? = null,
    val preferOfflineStt: Boolean = true,
)

class VoiceSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<VoiceSettings> = _settings.asStateFlow()

    fun read(): VoiceSettings = VoiceSettings(
        enginePackage = prefs.getString(KEY_ENGINE, null).blankToNull(),
        voiceName = prefs.getString(KEY_VOICE, null).blankToNull(),
        rate = prefs.getFloat(KEY_RATE, 1f),
        pitch = prefs.getFloat(KEY_PITCH, 1f),
        languageTag = prefs.getString(KEY_LANGUAGE, null).blankToNull(),
        preferOfflineStt = prefs.getBoolean(KEY_OFFLINE, true),
    )

    fun update(block: (VoiceSettings) -> VoiceSettings) {
        val next = block(read())
        prefs.edit()
            .putString(KEY_ENGINE, next.enginePackage)
            .putString(KEY_VOICE, next.voiceName)
            .putFloat(KEY_RATE, next.rate.coerceIn(0.5f, 2f))
            .putFloat(KEY_PITCH, next.pitch.coerceIn(0.5f, 2f))
            .putString(KEY_LANGUAGE, next.languageTag)
            .putBoolean(KEY_OFFLINE, next.preferOfflineStt)
            .apply()
        _settings.value = read()
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

    private companion object {
        const val PREFS = "voice_settings"
        const val KEY_ENGINE = "engine"
        const val KEY_VOICE = "voice"
        const val KEY_RATE = "rate"
        const val KEY_PITCH = "pitch"
        const val KEY_LANGUAGE = "language"
        const val KEY_OFFLINE = "offline"
    }
}
