package app.handsfree.copilot.automation

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.loop.AccessFacts
import app.handsfree.copilot.core.loop.ChatControlFinder

internal class AccessFactsCollector(private val context: Context) {
    fun collect(selectors: CopilotSelectors, service: CopilotAccessibilityService?): AccessFacts {
        val packageName = selectors.targetPackage
        val installed = isInstalled(packageName)
        val others = otherProfiles(packageName)
        val nodes = if (service != null && packageName.isNotBlank()) {
            try {
                WindowSnapshotter.capture(service, packageName)
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val windowPresent = service != null && try {
            WindowSnapshotter.packagesInWindows(service).any { it == packageName }
        } catch (_: Exception) {
            false
        }
        val textNodes = nodes.count { it.text.isNotBlank() || it.contentDescription.isNotBlank() }
        val inputFound = packageName.isNotBlank() &&
            ChatControlFinder.findInput(nodes, selectors.input) >= 0
        return AccessFacts(
            targetPackage = packageName.ifBlank { "(not set)" },
            serviceEnabledInSettings = isServiceEnabled(),
            serviceConnected = service != null,
            packageInstalledForCurrentUser = installed,
            packageEnabledInOtherProfiles = others.isNotEmpty(),
            otherProfileLabels = others,
            runningInManagedProfile = runningInManagedProfile(),
            copilotWindowPresent = windowPresent,
            totalNodeCount = nodes.size,
            textNodeCount = textNodes,
            inputFound = inputFound,
        )
    }

    private fun isInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun otherProfiles(packageName: String): List<String> {
        if (packageName.isBlank()) return emptyList()
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val users = context.getSystemService(UserManager::class.java)
        val labels = mutableListOf<String>()
        val profiles = try {
            launcher.profiles
        } catch (_: Exception) {
            return emptyList()
        }
        for (handle in profiles) {
            if (handle == Process.myUserHandle()) continue
            val listed = try {
                launcher.getActivityList(packageName, handle).isNotEmpty()
            } catch (_: Exception) {
                false
            }
            val enabled = try {
                launcher.isPackageEnabled(packageName, handle)
            } catch (_: Exception) {
                false
            }
            if (!listed && !enabled) continue
            val serial = try {
                users?.getSerialNumberForUser(handle)?.toString() ?: "?"
            } catch (_: Exception) {
                "?"
            }
            labels += "Android user serial $serial"
        }
        return labels
    }

    private fun runningInManagedProfile(): Boolean {
        val users = context.getSystemService(UserManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) users.isManagedProfile else false
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(context, CopilotAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
