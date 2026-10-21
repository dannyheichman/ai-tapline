package app.handsfree.copilot

import android.content.Context
import app.handsfree.copilot.automation.CopilotLauncher
import app.handsfree.copilot.core.config.CopilotSelectors

internal object CopilotLaunchBridge {
    fun open(context: Context, selectors: CopilotSelectors) {
        CopilotLauncher.open(context, selectors)
    }
}
