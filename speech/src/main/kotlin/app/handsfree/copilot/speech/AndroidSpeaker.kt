package app.handsfree.copilot.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.handsfree.copilot.core.ports.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

class AndroidSpeaker(
    private val context: Context,
    private val settings: VoiceSettingsStore,
) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var engine: TextToSpeech? = null
    private var enginePackage: String? = null

    override suspend fun speak(text: String) {
        val prefs = settings.read()
        val tts = ensureEngine(prefs)
        withContext(Dispatchers.Main.immediate) {
            apply(tts, prefs)
            val chunks = chunk(text)
            _speaking.value = true
            try {
                for (chunk in chunks) {
                    if (chunk.isBlank()) continue
                    speakChunk(tts, chunk)
                }
            } finally {
                _speaking.value = false
            }
        }
    }

    override fun stop() {
        _speaking.value = false
        main.post {
            try {
                engine?.stop()
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        stop()
        main.post {
            engine?.shutdown()
            engine = null
        }
    }

    private suspend fun speakChunk(tts: TextToSpeech, chunk: String) {
        suspendCancellableCoroutine { continuation ->
            val id = "hf-${System.nanoTime()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }

                @Deprecated("Deprecated in the platform listener")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }
            })
            continuation.invokeOnCancellation { tts.stop() }
            val result = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result == TextToSpeech.ERROR && continuation.isActive) continuation.resume(Unit)
        }
    }

    private suspend fun ensureEngine(prefs: VoiceSettings): TextToSpeech = withContext(Dispatchers.Main.immediate) {
        val wanted = prefs.enginePackage
        val current = engine
        if (current != null && enginePackage == wanted) return@withContext current
        try {
            current?.shutdown()
        } catch (_: Exception) {
        }
        engine = null
        suspendCancellableCoroutine { continuation ->
            val holder = arrayOfNulls<TextToSpeech>(1)
            fun finish(status: Int) {
                val created = holder[0]
                if (created == null) {
                    main.post { finish(status) }
                    return
                }
                if (status == TextToSpeech.SUCCESS && continuation.isActive) {
                    engine = created
                    enginePackage = wanted
                    continuation.resume(created)
                } else if (continuation.isActive) {
                    created.shutdown()
                    continuation.resumeWith(
                        Result.failure(IllegalStateException("Text-to-speech engine failed to start.")),
                    )
                }
            }
            val listener = TextToSpeech.OnInitListener { status -> finish(status) }
            val created = if (wanted.isNullOrBlank()) {
                TextToSpeech(context.applicationContext, listener)
            } else {
                TextToSpeech(context.applicationContext, listener, wanted)
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

    private fun apply(tts: TextToSpeech, prefs: VoiceSettings) {
        tts.setSpeechRate(prefs.rate.coerceIn(0.5f, 2f))
        tts.setPitch(prefs.pitch.coerceIn(0.5f, 2f))
        val voice = prefs.voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (voice != null) {
            tts.voice = voice
        } else if (!prefs.languageTag.isNullOrBlank()) {
            val locale = Locale.forLanguageTag(prefs.languageTag)
            tts.language = locale
        }
    }

    private fun chunk(text: String): List<String> {
        val clean = text.trim()
        if (clean.length <= MAX_CHUNK) return listOf(clean)
        val parts = mutableListOf<String>()
        var rest = clean
        while (rest.length > MAX_CHUNK) {
            val window = rest.take(MAX_CHUNK)
            val breakAt = listOf(
                window.lastIndexOf("\n\n"),
                window.lastIndexOf(". "),
                window.lastIndexOf("? "),
                window.lastIndexOf("! "),
                window.lastIndexOf(" "),
            ).max().let { if (it < 200) MAX_CHUNK else it + 1 }
            parts += rest.take(breakAt).trim()
            rest = rest.substring(breakAt).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    private companion object {
        const val MAX_CHUNK = 3_500
    }
}
