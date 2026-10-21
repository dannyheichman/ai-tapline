package app.handsfree.copilot.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.handsfree.copilot.core.config.CopilotSelectors

object CopilotLauncher {
    fun open(context: Context, selectors: CopilotSelectors): Boolean {
        val packageName = selectors.targetPackage
        if (packageName.isBlank()) return false
        val intent = explicit(packageName, selectors.launchActivity)
            ?: context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun explicit(packageName: String, launchActivity: String): Intent? {
        val className = launchActivity.trim()
        if (className.isEmpty()) return null
        val clazz = if (className.contains('/')) className.substringAfter('/') else className
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(packageName, clazz)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
    }
}
