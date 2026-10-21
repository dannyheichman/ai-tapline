package app.handsfree.copilot.automation

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.release() {
    try {
        if (Build.VERSION.SDK_INT >= 33) {
            // recycle() is deprecated, and close() is the replacement, but both
            // are safe no-ops once the node is already released.
            recycle()
        } else {
            recycle()
        }
    } catch (_: Exception) {
    }
}

@Suppress("DEPRECATION")
internal fun AccessibilityWindowInfo.release() {
    try {
        recycle()
    } catch (_: Exception) {
    }
}
