package app.handsfree.copilot.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class VoiceSettings(
    val enginePackage: String = "",
    val voiceName: String = "",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val preferOfflineStt: Boolean = true,
    val bargeInEnabled: Boolean = true,
    val speakErrors: Boolean = true,
    val keepScreenOn: Boolean = true,
    val listenCompleteSilenceMs: Long = 1_400,
    val listenPossiblyCompleteSilenceMs: Long = 900,
    val listenMinimumMs: Long = 1_500,
)

private val Context.voiceDataStore by preferencesDataStore(name = "voice_settings")

class VoiceSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.voiceDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(VoiceSettings())
    val settings: StateFlow<VoiceSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            dataStore.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.collect { prefs ->
                _settings.value = prefs.toModel()
            }
        }
    }

    fun snapshot(): VoiceSettings = _settings.value

    suspend fun update(transform: (VoiceSettings) -> VoiceSettings) {
        val next = transform(snapshot())
        dataStore.edit { prefs -> prefs.write(next) }
        _settings.value = next
    }

    private fun Preferences.toModel(): VoiceSettings = VoiceSettings(
        enginePackage = this[KEY_ENGINE] ?: "",
        voiceName = this[KEY_VOICE] ?: "",
        rate = this[KEY_RATE] ?: 1f,
        pitch = this[KEY_PITCH] ?: 1f,
        preferOfflineStt = this[KEY_OFFLINE] ?: true,
        bargeInEnabled = this[KEY_BARGE] ?: true,
        speakErrors = this[KEY_SPEAK_ERRORS] ?: true,
        keepScreenOn = this[KEY_SCREEN] ?: true,
        listenCompleteSilenceMs = this[KEY_SILENCE] ?: 1_400,
        listenPossiblyCompleteSilenceMs = this[KEY_POSSIBLE] ?: 900,
        listenMinimumMs = this[KEY_MINIMUM] ?: 1_500,
    )

    private fun MutablePreferences.write(settings: VoiceSettings) {
        this[KEY_ENGINE] = settings.enginePackage
        this[KEY_VOICE] = settings.voiceName
        this[KEY_RATE] = settings.rate
        this[KEY_PITCH] = settings.pitch
        this[KEY_OFFLINE] = settings.preferOfflineStt
        this[KEY_BARGE] = settings.bargeInEnabled
        this[KEY_SPEAK_ERRORS] = settings.speakErrors
        this[KEY_SCREEN] = settings.keepScreenOn
        this[KEY_SILENCE] = settings.listenCompleteSilenceMs
        this[KEY_POSSIBLE] = settings.listenPossiblyCompleteSilenceMs
        this[KEY_MINIMUM] = settings.listenMinimumMs
    }

    private companion object {
        val KEY_ENGINE = stringPreferencesKey("engine")
        val KEY_VOICE = stringPreferencesKey("voice")
        val KEY_RATE = floatPreferencesKey("rate")
        val KEY_PITCH = floatPreferencesKey("pitch")
        val KEY_OFFLINE = booleanPreferencesKey("offline_stt")
        val KEY_BARGE = booleanPreferencesKey("barge_in")
        val KEY_SPEAK_ERRORS = booleanPreferencesKey("speak_errors")
        val KEY_SCREEN = booleanPreferencesKey("keep_screen_on")
        val KEY_SILENCE = longPreferencesKey("silence_ms")
        val KEY_POSSIBLE = longPreferencesKey("possible_silence_ms")
        val KEY_MINIMUM = longPreferencesKey("minimum_ms")
    }
}
