package app.handsfree.copilot

import android.app.Application
import android.content.Context
import app.handsfree.copilot.automation.CopilotAutomation
import app.handsfree.copilot.core.loop.HandsFreeConversation
import app.handsfree.copilot.core.ports.SystemSleeper
import app.handsfree.copilot.core.text.SpokenTextProcessor
import app.handsfree.copilot.speech.AndroidDictation
import app.handsfree.copilot.speech.AndroidSpeaker
import app.handsfree.copilot.speech.TtsCatalog
import app.handsfree.copilot.speech.VoiceSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HandsFreeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SessionNotifications.ensureChannel(this)
        SessionNotifications.showIdle(this)
    }
}

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val voiceSettings = VoiceSettingsStore(appContext)
    val catalog = TtsCatalog(appContext)
    val automation = CopilotAutomation(appContext)
    val dictation = AndroidDictation(appContext, voiceSettings)
    val speaker = AndroidSpeaker(appContext, voiceSettings)
    val session = HandsFreeConversation(
        driver = automation,
        dictation = dictation,
        speaker = speaker,
        processor = SpokenTextProcessor(),
        selectors = { automation.selectors.current() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        sleeper = SystemSleeper(),
    )
}

fun Context.appGraph(): AppGraph = (applicationContext as HandsFreeApplication).graph
