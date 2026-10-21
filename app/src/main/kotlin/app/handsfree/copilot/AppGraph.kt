package app.handsfree.copilot

import android.content.Context
import android.os.SystemClock
import app.handsfree.copilot.automation.CopilotAccessibilityDriver
import app.handsfree.copilot.core.loop.ConversationLoop
import app.handsfree.copilot.core.loop.LoopSettings
import app.handsfree.copilot.core.model.Clock
import app.handsfree.copilot.core.text.AssistantTextResolver
import app.handsfree.copilot.core.text.EchoFilter
import app.handsfree.copilot.core.text.MarkdownSpeechSanitizer
import app.handsfree.copilot.session.MemoryLog
import app.handsfree.copilot.session.SessionController
import app.handsfree.copilot.settings.DriverConfigStore
import app.handsfree.copilot.settings.VoiceSettings
import app.handsfree.copilot.settings.VoiceSettingsStore
import app.handsfree.copilot.speech.AndroidSpeechInput
import app.handsfree.copilot.speech.AndroidSpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val log = MemoryLog()
    val voiceStore = VoiceSettingsStore(appContext)
    val driverStore = DriverConfigStore(appContext)
    val speaker = AndroidSpeechOutput(appContext)
    val speech = AndroidSpeechInput(appContext) { log.log(it) }
    val driver = CopilotAccessibilityDriver(appContext, { driverStore.snapshot() }) { log.log(it) }
    val loop = ConversationLoop(
        speech = speech,
        speaker = speaker,
        chat = driver,
        sanitizer = MarkdownSpeechSanitizer(),
        resolver = AssistantTextResolver(),
        echo = EchoFilter(),
        clock = Clock { SystemClock.elapsedRealtime() },
        settings = { currentSettings() },
        log = { log.log(it) },
    )
    val session = SessionController(appContext, loop, ::currentSettings)

    init {
        scope.launch {
            voiceStore.settings.collect { voice ->
                speaker.apply(voice.enginePackage, voice.voiceName, voice.rate, voice.pitch)
            }
        }
    }

    fun currentSettings(): LoopSettings {
        val voice: VoiceSettings = voiceStore.snapshot()
        return LoopSettings(
            driver = driverStore.snapshot(),
            preferOfflineStt = voice.preferOfflineStt,
            bargeInEnabled = voice.bargeInEnabled,
            speakErrors = voice.speakErrors,
            keepScreenOn = voice.keepScreenOn,
            listenCompleteSilenceMs = voice.listenCompleteSilenceMs,
            listenPossiblyCompleteSilenceMs = voice.listenPossiblyCompleteSilenceMs,
            listenMinimumMs = voice.listenMinimumMs,
        )
    }
}
