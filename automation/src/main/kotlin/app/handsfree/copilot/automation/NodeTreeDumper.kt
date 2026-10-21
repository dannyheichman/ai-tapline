package app.handsfree.copilot.automation

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant

internal object NodeTreeDumper {
    fun dump(service: CopilotAccessibilityService, header: List<String>): String {
        val builder = StringBuilder()
        header.forEach { builder.appendLine(it) }
        builder.appendLine("capturedAt=${Instant.now()}")
        builder.appendLine("This file can contain the text visible in Copilot. Do not commit it or post it publicly.")
        val windows = service.windows ?: emptyList()
        builder.appendLine("windowCount=${windows.size}")
        val counter = intArrayOf(0)
        for ((index, window) in windows.withIndex()) {
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            val active = if (Build.VERSION.SDK_INT >= 33) window.isActive else false
            builder.appendLine(
                "window[$index] id=${window.id} type=${window.type} title=${oneLine(window.title?.toString().orEmpty())} " +
                    "package=${root?.packageName} active=$active focused=${window.isFocused}",
            )
            if (root != null) {
                append(builder, root, 1, counter)
                root.release()
            } else {
                builder.appendLine("  (no root — the window may be blocked by a work profile or Intune policy)")
            }
            window.release()
            if (counter[0] >= MAX_NODES) {
                builder.appendLine("… truncated at $MAX_NODES nodes")
                break
            }
        }
        if (windows.isEmpty()) {
            builder.appendLine("No windows. The accessibility service cannot see other apps until it is enabled, and it cannot see a work-profile Copilot from the personal profile.")
        }
        return builder.toString()
    }

    private fun append(
        builder: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
    ) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return
        counter[0] += 1
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append(node.className ?: "unknown")
        val id = node.viewIdResourceName
        if (!id.isNullOrBlank()) builder.append(" id=").append(id)
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (text.isNotEmpty()) builder.append(" text=\"").append(oneLine(text)).append('"')
        if (description.isNotEmpty()) builder.append(" desc=\"").append(oneLine(description)).append('"')
        builder.append(" clickable=").append(node.isClickable)
        builder.append(" editable=").append(node.isEditable)
        builder.append(" enabled=").append(node.isEnabled)
        builder.append(" visible=").append(node.isVisibleToUser)
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        builder.append(" bounds=").append(bounds.toShortString())
        builder.appendLine()
        val count = node.childCount
        for (index in 0 until count) {
            if (counter[0] >= MAX_NODES) return
            val child = try {
                node.getChild(index)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                append(builder, child, depth + 1, counter)
            } finally {
                child.release()
            }
        }
    }

    private fun oneLine(value: String): String {
        val flat = value.replace("\n", "\\n").replace("\r", "")
        return if (flat.length <= 300) flat else flat.take(300) + "…(${value.length} chars)"
    }

    private const val MAX_NODES = 4_000
    private const val MAX_DEPTH = 50
}
