package app.handsfree.copilot.automation

import android.content.Context
import app.handsfree.copilot.core.loop.AccessReport
import app.handsfree.copilot.core.ports.ChatDriver
import app.handsfree.copilot.core.ports.PrepareResult
import app.handsfree.copilot.core.ports.SubmitResult
import app.handsfree.copilot.core.ports.UiObservation

/**
 * Android side of the chat driver: selector config, Copilot launch, and the
 * accessibility actions. The conversation loop in :core talks only to [ChatDriver].
 */
class CopilotAutomation(context: Context) : ChatDriver {
    private val appContext = context.applicationContext
    val selectors = SelectorRepository(appContext)
    private val driver = AndroidChatDriver(appContext, selectors)

    override suspend fun prepare(): PrepareResult = driver.prepare()

    override suspend fun captureBaseline(): String = driver.captureBaseline()

    override suspend fun submit(text: String): SubmitResult = driver.submit(text)

    override suspend fun observe(userUtterance: String, baseline: String): UiObservation =
        driver.observe(userUtterance, baseline)

    fun diagnose(): AccessReport = driver.diagnose()

    fun dumpTree(): String {
        val service = CopilotAccessibilityService.instance
        val report = diagnose()
        val header = listOf(
            "Hands-free Copilot accessibility tree",
            "targetPackage=${selectors.current().targetPackage}",
            "diagnosis=${report.code}: ${report.title}",
            report.detail,
        )
        if (service == null) {
            return (header + "Accessibility service is not connected. Enable it, then dump again.").joinToString("\n")
        }
        return NodeTreeDumper.dump(service, header)
    }
}
