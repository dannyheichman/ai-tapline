package app.handsfree.copilot.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.handsfree.copilot.core.model.ListenOptions
import app.handsfree.copilot.core.model.Recognition
import app.handsfree.copilot.core.model.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Android SpeechRecognizer. [ListenOptions.preferOffline] sets EXTRA_PREFER_OFFLINE,
 * which uses the on-device model when the device has one. If that model is missing,
 * the call is retried once without the flag so the device's default recognizer
 * (Samsung or Google) can answer. That recognizer is not a Microsoft API.
 */
class AndroidSpeechInput(
    context: Context,
    private val log: (String) -> Unit = {},
) : SpeechInput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicReference<SpeechRecognizer?>(null)

    override suspend fun recognize(options: ListenOptions, onPartial: (String) -> Unit): Recognition {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Recognition.Failure(
                code = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                message = "Microphone permission is missing. Grant it in Copilot Voice Bridge, then start the session again.",
                retrySilently = false,
                speakable = false,
                fatal = true,
            )
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return Recognition.Failure(
                code = SpeechRecognizer.ERROR_CLIENT,
                message = "This phone has no speech recognizer. Install or enable Samsung voice input or Google speech services.",
                retrySilently = false,
                speakable = true,
                fatal = true,
            )
        }
        val first = listenOnce(options, preferOffline = options.preferOffline, onPartial = onPartial)
        val shouldRetryOffline = options.preferOffline && first is Recognition.Failure && first.code in OFFLINE_FALLBACK
        if (!shouldRetryOffline) return first
        log("On-device recognizer unavailable (${first.code}). Retrying with the default recognizer.")
        return listenOnce(options, preferOffline = false, onPartial = onPartial)
    }

    override fun cancel() {
        main.post {
            destroy(active.getAndSet(null))
        }
    }

    private suspend fun listenOnce(
        options: ListenOptions,
        preferOffline: Boolean,
        onPartial: (String) -> Unit,
    ): Recognition = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            active.set(recognizer)
            val resumed = AtomicBoolean(false)
            fun finish(result: Recognition) {
                if (resumed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(result)
                }
                if (active.compareAndSet(recognizer, null)) destroy(recognizer)
            }
            continuation.invokeOnCancellation {
                if (active.compareAndSet(recognizer, null)) destroy(recognizer)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults.bestText()
                    if (text.isNotBlank()) onPartial(text)
                }

                override fun onResults(results: Bundle?) {
                    finish(Recognition.Final(results.bestText()))
                }

                override fun onError(error: Int) {
                    finish(mapError(error))
                }
            })
            try {
                recognizer.startListening(intent(options, preferOffline))
            } catch (error: Exception) {
                finish(
                    Recognition.Failure(
                        code = SpeechRecognizer.ERROR_CLIENT,
                        message = error.message ?: "Could not start listening.",
                        retrySilently = false,
                        speakable = true,
                    ),
                )
            }
        }
    }

    private fun intent(options: ListenOptions, preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partials)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                options.completeSilenceMs.coerceIn(200L, 10_000L).toInt(),
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                options.possiblyCompleteSilenceMs.coerceIn(200L, 10_000L).toInt(),
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                options.minimumLengthMs.coerceIn(200L, 20_000L).toInt(),
            )
        }

    private fun mapError(code: Int): Recognition.Failure {
        val message = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be opened."
            SpeechRecognizer.ERROR_CLIENT -> "Listening was cancelled."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
            SpeechRecognizer.ERROR_NETWORK -> "The speech recognizer could not reach its network."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech recognizer timed out on the network."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
            SpeechRecognizer.ERROR_SERVER -> "The speech recognizer returned a service error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language is not supported by the speech recognizer."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The on-device speech model is not installed."
            else -> "Speech recognition error ($code)."
        }
        val silent = code == SpeechRecognizer.ERROR_NO_MATCH ||
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            code == SpeechRecognizer.ERROR_CLIENT ||
            code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        val fatal = code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        return Recognition.Failure(
            code = code,
            message = message,
            retrySilently = silent,
            speakable = !silent && !fatal,
            fatal = fatal,
        )
    }

    private fun Bundle?.bestText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun destroy(recognizer: SpeechRecognizer?) {
        if (recognizer == null) return
        try {
            recognizer.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer.destroy()
        } catch (_: Exception) {
        }
    }

    private companion object {
        val OFFLINE_FALLBACK = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        )
    }
}
