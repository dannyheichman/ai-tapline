package app.handsfree.copilot.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.handsfree.copilot.core.ports.Dictation
import app.handsfree.copilot.core.ports.DictationEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Android SpeechRecognizer. Uses the on-device recognizer when the device
 * offers one (Samsung and Google both ship one on current Galaxy phones) and
 * otherwise falls back to the default recognizer, which may use the network.
 */
class AndroidDictation(
    private val context: Context,
    private val settings: VoiceSettingsStore,
) : Dictation {
    private val main = Handler(Looper.getMainLooper())
    private var live: SpeechRecognizer? = null

    override fun stop() {
        val recognizer = live
        live = null
        main.post { release(recognizer) }
    }

    override fun listen(): Flow<DictationEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(DictationEvent.Failure("No speech recognition service is installed on this phone.", retry = false))
            close()
            return@callbackFlow
        }
        val created = CompletableDeferred<SpeechRecognizer>()
        main.post {
            try {
                val recognizer = createRecognizer()
                live = recognizer
                recognizer.setRecognitionListener(SessionListener(recognizer, this))
                recognizer.startListening(buildIntent())
                created.complete(recognizer)
            } catch (error: Exception) {
                created.completeExceptionally(error)
            }
        }
        val recognizer = try {
            created.await()
        } catch (error: Exception) {
            trySend(DictationEvent.Failure(error.message ?: "Speech recognizer failed to start.", retry = false))
            close()
            return@callbackFlow
        }
        awaitClose {
            main.post {
                release(recognizer)
                if (live === recognizer) live = null
            }
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun createRecognizer(): SpeechRecognizer {
        val preferOffline = settings.read().preferOfflineStt
        if (preferOffline && Build.VERSION.SDK_INT >= 31) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
            } catch (_: Exception) {
                // Fall through to the default recognizer.
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun buildIntent(): Intent {
        val current = settings.read()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (current.preferOfflineStt) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            current.languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
    }

    private fun release(recognizer: SpeechRecognizer?) {
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

    private inner class SessionListener(
        private val recognizer: SpeechRecognizer,
        private val producer: kotlinx.coroutines.channels.ProducerScope<DictationEvent>,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            producer.trySend(DictationEvent.Listening)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val retry = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            producer.trySend(DictationEvent.Failure(errorMessage(error), retry))
            if (!retry || producer.isClosedForSend) {
                producer.close()
                return
            }
            main.post {
                if (producer.isClosedForSend) return@post
                try {
                    recognizer.cancel()
                    recognizer.startListening(buildIntent())
                } catch (_: Exception) {
                    producer.trySend(DictationEvent.Failure("Speech recognizer restarted unsuccessfully.", retry = false))
                    producer.close()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                onError(SpeechRecognizer.ERROR_NO_MATCH)
                return
            }
            producer.trySend(DictationEvent.Final(text))
            producer.close()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) producer.trySend(DictationEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be opened."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Online recognition failed. On-device recognition will be used when this phone has it."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Retrying."
        SpeechRecognizer.ERROR_SERVER -> "The recognition service returned an error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Still listening."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "That recognition language is not installed."
        else -> "Speech recognition error $error."
    }
}
