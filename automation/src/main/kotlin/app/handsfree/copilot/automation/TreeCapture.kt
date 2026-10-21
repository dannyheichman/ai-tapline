package app.handsfree.copilot.automation

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.handsfree.copilot.core.model.NodeSnapshot
import java.util.Collections
import java.util.IdentityHashMap

internal data class LiveNode(
    val node: AccessibilityNodeInfo,
    val snapshot: NodeSnapshot,
)

internal class TreeSession(
    val nodes: List<LiveNode>,
    val sawPackage: Boolean,
    val unreadableWindows: Int,
) : AutoCloseable {
    override fun close() {
        if (Build.VERSION.SDK_INT >= 33) return
        val seen = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        nodes.forEach { live ->
            if (seen.add(live.node)) {
                try {
                    live.node.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

internal fun CopilotAccessibilityService.captureTree(targetPackage: String): TreeSession {
    val flat = mutableListOf<LiveNode>()
    var saw = false
    var unreadable = 0
    val windows = try {
        windows
    } catch (_: SecurityException) {
        return TreeSession(flat, sawPackage = true, unreadableWindows = 1)
    }
    if (windows != null) {
        for (window in windows) {
            val root = try {
                window.root
            } catch (_: SecurityException) {
                unreadable += 1
                null
            }
            val windowPackage = root?.packageName?.toString()
            if (windowPackage == targetPackage && root != null) {
                saw = true
                flatten(root, 0, flat, Collections.newSetFromMap(IdentityHashMap()))
            } else if (root != null && Build.VERSION.SDK_INT < 33) {
                try {
                    root.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }
    if (!saw) {
        val active = try {
            rootInActiveWindow
        } catch (_: SecurityException) {
            return TreeSession(flat, sawPackage = true, unreadableWindows = unreadable + 1)
        }
        if (active != null) {
            if (active.packageName?.toString() == targetPackage) {
                saw = true
                flatten(active, 0, flat, Collections.newSetFromMap(IdentityHashMap()))
            } else if (Build.VERSION.SDK_INT < 33) {
                try {
                    active.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }
    return TreeSession(flat, saw, unreadable)
}

internal fun CopilotAccessibilityService.foregroundPackages(): List<String> {
    val names = linkedSetOf<String>()
    val windows = try {
        windows
    } catch (_: SecurityException) {
        null
    }
    windows?.forEach { window ->
        val root = try {
            window.root
        } catch (_: SecurityException) {
            null
        }
        root?.packageName?.toString()?.let { names += it }
        if (root != null && Build.VERSION.SDK_INT < 33) {
            try {
                root.recycle()
            } catch (_: Throwable) {
            }
        }
    }
    val active = try {
        rootInActiveWindow
    } catch (_: SecurityException) {
        null
    }
    active?.packageName?.toString()?.let { names += it }
    if (active != null && Build.VERSION.SDK_INT < 33) {
        try {
            active.recycle()
        } catch (_: Throwable) {
        }
    }
    return names.toList()
}

private fun flatten(
    node: AccessibilityNodeInfo,
    depth: Int,
    out: MutableList<LiveNode>,
    seen: MutableSet<AccessibilityNodeInfo>,
) {
    if (depth > 40 || !seen.add(node)) return
    out += LiveNode(node, node.toSnapshot(depth))
    val count = try {
        node.childCount
    } catch (_: Exception) {
        0
    }
    for (index in 0 until count) {
        val child = try {
            node.getChild(index)
        } catch (_: Exception) {
            null
        } ?: continue
        flatten(child, depth + 1, out, seen)
    }
}

private fun AccessibilityNodeInfo.toSnapshot(depth: Int): NodeSnapshot {
    val bounds = Rect()
    try {
        getBoundsInScreen(bounds)
    } catch (_: Exception) {
    }
    return NodeSnapshot(
        className = className?.toString(),
        text = safe { text?.toString() },
        contentDescription = safe { contentDescription?.toString() },
        viewIdResourceName = safe { viewIdResourceName },
        hintText = safe { hintText?.toString() },
        isEditable = isEditable,
        isClickable = isClickable,
        isEnabled = isEnabled,
        isPassword = isPassword,
        isScrollable = isScrollable,
        boundsLeft = bounds.left,
        boundsTop = bounds.top,
        boundsRight = bounds.right,
        boundsBottom = bounds.bottom,
        packageName = packageName?.toString(),
        depth = depth,
    )
}

private inline fun <T> safe(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}
