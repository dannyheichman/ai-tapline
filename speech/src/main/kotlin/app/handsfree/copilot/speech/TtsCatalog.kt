package app.handsfree.copilot.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class TtsEngineChoice(
    val packageName: String,
    val label: String,
)

data class TtsVoiceChoice(
    val name: String,
    val localeLabel: String,
    val localeTag: String,
    val quality: Int,
) {
    val label: String get() = "$localeLabel — $name"
}

class TtsCatalog(private val context: Context) {
    fun engines(): List<TtsEngineChoice> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        return services.map { resolve ->
            val info = resolve.serviceInfo
            val label = resolve.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { info.packageName }
            TtsEngineChoice(packageName = info.packageName, label = label)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    suspend fun voices(enginePackage: String?): List<TtsVoiceChoice> = suspendCancellableCoroutine { continuation ->
        val main = Handler(Looper.getMainLooper())
        val holder = arrayOfNulls<TextToSpeech>(1)
        fun deliver(status: Int) {
            val tts = holder[0]
            if (tts == null) {
                main.post { deliver(status) }
                return
            }
            val result = if (status == TextToSpeech.SUCCESS) {
                tts.voices.orEmpty().map { voice ->
                    TtsVoiceChoice(
                        name = voice.name,
                        localeLabel = voice.locale.displayName,
                        localeTag = voice.locale.toLanguageTag(),
                        quality = voice.quality,
                    )
                }.sortedWith(compareBy({ it.localeLabel }, { it.name }))
            } else {
                emptyList()
            }
            try {
                tts.shutdown()
            } catch (_: Exception) {
            }
            if (continuation.isActive) continuation.resume(result)
        }
        val listener = TextToSpeech.OnInitListener { status -> deliver(status) }
        val created = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(context.applicationContext, listener)
        } else {
            TextToSpeech(context.applicationContext, listener, enginePackage)
        }
        holder[0] = created
        continuation.invokeOnCancellation {
            try {
                created.shutdown()
            } catch (_: Exception) {
            }
        }
    }
}
