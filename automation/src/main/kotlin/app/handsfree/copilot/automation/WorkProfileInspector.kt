package app.handsfree.copilot.automation

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import app.handsfree.copilot.core.access.AccessDecision
import app.handsfree.copilot.core.access.AccessFacts
import app.handsfree.copilot.core.model.AccessDiagnostics

data class WindowProbe(
    val sawPackage: Boolean,
    val readableRoots: Int,
    val unreadableWindows: Int,
)

/**
 * Collects profile and window facts. The wording of the result lives in
 * [AccessDecision] so it can be tested without a device.
 */
class WorkProfileInspector(private val context: Context) {
    fun inspect(targetPackage: String, probe: WindowProbe?): AccessDiagnostics =
        AccessDecision.decide(collect(targetPackage, probe))

    fun versionName(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName
    } catch (_: Exception) {
        null
    }

    private fun collect(targetPackage: String, probe: WindowProbe?): AccessFacts {
        var lookupBlocked = false
        val installed = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(targetPackage, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(targetPackage, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            lookupBlocked = true
            false
        }

        var visibleElsewhere = false
        var hiddenOther = false
        try {
            val launcher = context.getSystemService(LauncherApps::class.java)
            val me = Process.myUserHandle()
            launcher?.profiles?.forEach { profile ->
                if (profile == me) return@forEach
                try {
                    val activities = launcher.getActivityList(targetPackage, profile)
                    if (!activities.isNullOrEmpty()) visibleElsewhere = true
                } catch (_: SecurityException) {
                    hiddenOther = true
                }
            }
        } catch (_: SecurityException) {
            hiddenOther = true
        } catch (_: Exception) {
            // Profile enumeration is best-effort. The window probe still reports content blocks.
        }

        return AccessFacts(
            targetPackage = targetPackage,
            serviceConnected = AccessibilityBridge.connected.value,
            installedInCurrentUser = installed,
            visibleInAnotherProfile = visibleElsewhere,
            runningInManagedProfile = runningInManagedProfile(),
            otherProfileHidden = hiddenOther,
            packageLookupBlocked = lookupBlocked,
            windowFound = probe?.sawPackage == true,
            readableRoots = probe?.readableRoots ?: 0,
            unreadableWindows = probe?.unreadableWindows ?: 0,
            probedForeground = probe != null,
        )
    }

    private fun runningInManagedProfile(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val users = context.getSystemService(UserManager::class.java) ?: return false
        return users.isManagedProfile
    }
}
