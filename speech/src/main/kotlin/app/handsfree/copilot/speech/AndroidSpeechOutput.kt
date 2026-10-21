package app.handsfree.copilot.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.handsfree.copilot.core.model.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class EngineInfo(val packageName: String, val label: String)

data class VoiceInfo(
    val name: String,
    val display: String,
    val requiresNetwork: Boolean,
    val localeTag: String,
)

/**
 * Android TextToSpeech. The engine package is whatever the user picked
 * (Samsung TTS, Google TTS, or the system default). Voices, rate, and pitch
 * are applied to that engine. Nothing here talks to Microsoft.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val gate = Mutex()
    private var tts: TextToSpeech? = null
    private var enginePackage: String? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var pendingVoice: String = ""
    private var pendingRate: Float = 1f
    private var pendingPitch: Float = 1f
    private var finishActive: (() -> Unit)? = null

    fun engines(): List<EngineInfo> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val found = appContext.packageManager.queryIntentServices(intent, 0).map { info ->
            EngineInfo(
                packageName = info.serviceInfo.packageName,
                label = info.loadLabel(appContext.packageManager).toString().ifBlank { info.serviceInfo.packageName },
            )
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        return listOf(EngineInfo(packageName = "", label = "System default")) + found
    }

    suspend fun loadVoices(enginePackage: String): List<VoiceInfo> {
        if (!ensureEngine(enginePackage)) return emptyList()
        return withContext(Dispatchers.Main) { readVoices() }
    }

    suspend fun apply(enginePackage: String, voiceName: String, rate: Float, pitch: Float) {
        pendingVoice = voiceName
        pendingRate = rate.coerceIn(0.5f, 2f)
        pendingPitch = pitch.coerceIn(0.5f, 2f)
        if (ensureEngine(enginePackage)) {
            withContext(Dispatchers.Main) { applyPending() }
        }
    }

    suspend fun preview(sample: String) {
        speak(sample)
    }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engineName = enginePackage ?: ""
        if (!ensureEngine(engineName)) return
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val engine = tts
                if (engine == null) {
                    continuation.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                finishActive?.invoke()
                val id = UUID.randomUUID().toString()
                val resumed = AtomicBoolean(false)
                fun finish() {
                    if (resumed.compareAndSet(false, true)) {
                        finishActive = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                finishActive = { finish() }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) finish()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) finish()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) finish()
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == id) finish()
                    }
                })
                continuation.invokeOnCancellation {
                    engine.stop()
                    finish()
                }
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (result == TextToSpeech.ERROR) finish()
            }
        }
    }

    override fun stop() {
        main.post {
            tts?.stop()
            finishActive?.invoke()
        }
    }

    fun shutdown() {
        main.post {
            tts?.shutdown()
            tts = null
            enginePackage = null
            ready = null
        }
    }

    private suspend fun ensureEngine(packageName: String): Boolean {
        val current = ready
        if (current != null && enginePackage == packageName) return current.await()
        return gate.withLock {
            val again = ready
            if (again != null && enginePackage == packageName) return@withLock again.await()
            val deferred = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                tts?.stop()
                tts?.shutdown()
                tts = null
                ready = deferred
                enginePackage = packageName
                val listener = TextToSpeech.OnInitListener { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    if (ok) applyPending()
                    if (!deferred.isCompleted) deferred.complete(ok)
                }
                tts = if (packageName.isBlank()) {
                    TextToSpeech(appContext, listener)
                } else {
                    TextToSpeech(appContext, listener, packageName)
                }
            }
            withTimeoutOrNull(8_000) { deferred.await() } ?: false
        }
    }

    private fun applyPending() {
        val engine = tts ?: return
        engine.setSpeechRate(pendingRate)
        engine.setPitch(pendingPitch)
        if (pendingVoice.isBlank()) {
            engine.language = Locale.getDefault()
            return
        }
        val match = engine.voices?.firstOrNull { it.name == pendingVoice }
        if (match != null) {
            engine.voice = match
        } else {
            engine.language = Locale.getDefault()
        }
    }

    private fun readVoices(): List<VoiceInfo> {
        val voices = tts?.voices.orEmpty()
        val preferred = Locale.getDefault().toLanguageTag()
        return voices.map { it.toInfo() }
            .sortedWith(compareBy<VoiceInfo> { it.requiresNetwork }.thenBy { it.localeTag != preferred }.thenBy { it.display })
    }

    private fun Voice.toInfo(): VoiceInfo {
        val featureText = features?.joinToString(" ").orEmpty()
        val gender = when {
            featureText.contains("female", ignoreCase = true) -> "female"
            featureText.contains("male", ignoreCase = true) -> "male"
            else -> ""
        }
        val where = if (isNetworkConnectionRequired) "network" else "on-device"
        val display = listOf(locale.displayName, gender, name, where)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        return VoiceInfo(
            name = name,
            display = display,
            requiresNetwork = isNetworkConnectionRequired,
            localeTag = locale.toLanguageTag(),
        )
    }
}
