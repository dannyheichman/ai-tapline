package app.handsfree.copilot.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.handsfree.copilot.core.model.NodeAddress
import app.handsfree.copilot.core.model.UiNode

internal object WindowSnapshotter {
    fun capture(service: AccessibilityService, packageName: String): List<UiNode> {
        val out = mutableListOf<UiNode>()
        val windows = service.windows ?: return emptyList()
        for (window in windows) {
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            try {
                if (root != null && root.packageName?.toString() == packageName) {
                    walk(window.id, root, emptyList(), out, intArrayOf(0), depth = 0)
                }
            } finally {
                root?.release()
                window.release()
            }
        }
        return out
    }

    fun packagesInWindows(service: AccessibilityService): List<String> {
        val windows = service.windows ?: return emptyList()
        val names = mutableListOf<String>()
        for (window in windows) {
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            try {
                root?.packageName?.toString()?.let { names += it }
            } finally {
                root?.release()
                window.release()
            }
        }
        return names
    }

    fun find(service: AccessibilityService, address: NodeAddress): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        val window = windows.firstOrNull { it.id == address.windowId }
        windows.filter { it !== window }.forEach { it.release() }
        if (window == null) return null
        var node = try {
            window.root
        } catch (_: Exception) {
            null
        } ?: return null
        for (index in address.path) {
            if (index < 0 || index >= node.childCount) {
                node.release()
                return null
            }
            val child = node.getChild(index)
            node.release()
            if (child == null) return null
            node = child
        }
        return node
    }

    private fun walk(
        windowId: Int,
        node: AccessibilityNodeInfo,
        path: List<Int>,
        out: MutableList<UiNode>,
        counter: IntArray,
        depth: Int,
    ) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return
        counter[0] += 1
        out += node.toUi(windowId, path)
        val count = node.childCount
        for (index in 0 until count) {
            val child = try {
                node.getChild(index)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                walk(windowId, child, path + index, out, counter, depth + 1)
            } finally {
                child.release()
            }
        }
    }

    private fun AccessibilityNodeInfo.toUi(windowId: Int, path: List<Int>): UiNode {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return UiNode(
            text = text?.toString().orEmpty(),
            contentDescription = contentDescription?.toString().orEmpty(),
            className = className?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            top = bounds.top,
            bottom = bounds.bottom,
            left = bounds.left,
            right = bounds.right,
            editable = isEditable,
            clickable = isClickable,
            enabled = isEnabled,
            visible = isVisibleToUser,
            password = isPassword,
            windowId = windowId,
            path = path,
        )
    }

    private const val MAX_NODES = 4_000
    private const val MAX_DEPTH = 50
}
