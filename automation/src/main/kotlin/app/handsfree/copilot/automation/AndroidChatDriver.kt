package app.handsfree.copilot.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.loop.AccessDiagnoser
import app.handsfree.copilot.core.loop.AccessLevel
import app.handsfree.copilot.core.loop.AccessReport
import app.handsfree.copilot.core.loop.ChatControlFinder
import app.handsfree.copilot.core.model.BlockedReason
import app.handsfree.copilot.core.model.address
import app.handsfree.copilot.core.ports.ChatDriver
import app.handsfree.copilot.core.ports.PrepareResult
import app.handsfree.copilot.core.ports.SubmitResult
import app.handsfree.copilot.core.ports.UiObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class AndroidChatDriver(
    private val context: Context,
    private val selectors: SelectorRepository,
) : ChatDriver {
    private val facts = AccessFactsCollector(context)

    override suspend fun prepare(): PrepareResult = withContext(Dispatchers.Main) {
        val config = selectors.current()
        if (config.targetPackage.isBlank()) {
            return@withContext PrepareResult(
                ok = false,
                message = "Set the Copilot package name on the Calibration screen.",
                blocked = BlockedReason.PACKAGE_NOT_INSTALLED,
            )
        }
        var report = diagnose(config)
        if (report.level == AccessLevel.BLOCKED) {
            return@withContext report.toResult()
        }
        val launched = CopilotLauncher.open(context, config)
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            report = diagnose(config)
            if (report.level == AccessLevel.BLOCKED) return@withContext report.toResult()
            if (report.level == AccessLevel.OK) return@withContext PrepareResult(true, report.detail)
            delay(300)
        }
        val blocked = when (report.code) {
            "INPUT_NOT_FOUND" -> BlockedReason.INPUT_NOT_FOUND
            "WINDOW_NOT_FOUND" -> if (launched) BlockedReason.WINDOW_NOT_FOUND else BlockedReason.LAUNCH_FAILED
            else -> report.toBlockedReason()
        }
        val message = if (!launched && report.code == "WINDOW_NOT_FOUND") {
            report.detail + " Starting Copilot from the background was blocked. Start the session from the app or the notification while the phone is unlocked."
        } else {
            report.detail
        }
        PrepareResult(ok = false, message = message, blocked = blocked)
    }

    override suspend fun captureBaseline(): String = withContext(Dispatchers.Main) {
        val service = CopilotAccessibilityService.instance ?: return@withContext ""
        val config = selectors.current()
        val nodes = try {
            WindowSnapshotter.capture(service, config.targetPackage)
        } catch (_: Exception) {
            emptyList()
        }
        ChatControlFinder.assistantCandidate(nodes, config, userUtterance = "", baseline = "")
    }

    override suspend fun submit(text: String): SubmitResult = withContext(Dispatchers.Main) {
        val service = CopilotAccessibilityService.instance
            ?: return@withContext SubmitResult(false, "Accessibility service is not connected.")
        val config = selectors.current()
        val snapshot = try {
            WindowSnapshotter.capture(service, config.targetPackage)
        } catch (_: Exception) {
            return@withContext SubmitResult(false, "Couldn't read the Copilot window. A work profile or Intune policy may be blocking it.")
        }
        val inputIndex = ChatControlFinder.findInput(snapshot, config.input)
        if (inputIndex < 0) {
            return@withContext SubmitResult(false, "Chat box not found. Open a Copilot text chat, or tune the input selector.")
        }
        val inputNode = WindowSnapshotter.find(service, snapshot[inputIndex].address())
            ?: return@withContext SubmitResult(false, "Chat box disappeared before it could be filled.")
        val typed = try {
            setText(inputNode, text)
        } finally {
            inputNode.release()
        }
        if (!typed) {
            return@withContext SubmitResult(
                false,
                "Couldn't insert text into the chat box. Dump the node tree and point the input selector at the editable field.",
            )
        }
        delay(350)
        val after = try {
            WindowSnapshotter.capture(service, config.targetPackage)
        } catch (_: Exception) {
            emptyList()
        }
        val refreshedInput = ChatControlFinder.findInput(after, config.input)
        val sendIndex = ChatControlFinder.findSend(after, config.send, refreshedInput)
        if (sendIndex >= 0) {
            val sendNode = WindowSnapshotter.find(service, after[sendIndex].address())
            if (sendNode != null) {
                val clicked = try {
                    click(sendNode)
                } finally {
                    sendNode.release()
                }
                if (clicked) return@withContext SubmitResult(true, "Sent.")
            }
        }
        if (refreshedInput >= 0 && pressImeEnter(service, after[refreshedInput].address())) {
            return@withContext SubmitResult(true, "Sent with the keyboard action.")
        }
        SubmitResult(
            false,
            "The text is in the box, but no Send control matched. Tune the send selector, or tap Send yourself this once and update the calibration dump.",
        )
    }

    override suspend fun observe(userUtterance: String, baseline: String): UiObservation = withContext(Dispatchers.Main) {
        val service = CopilotAccessibilityService.instance
            ?: return@withContext UiObservation("", false, false, "Accessibility service not connected.")
        val config = selectors.current()
        val nodes = try {
            WindowSnapshotter.capture(service, config.targetPackage)
        } catch (_: Exception) {
            return@withContext UiObservation("", false, false, "Copilot window could not be read.")
        }
        UiObservation(
            assistantCandidate = ChatControlFinder.assistantCandidate(nodes, config, userUtterance, baseline),
            generatingVisible = ChatControlFinder.generatingVisible(nodes, config),
            inputFound = ChatControlFinder.findInput(nodes, config.input) >= 0,
        )
    }

    fun diagnose(config: CopilotSelectors = selectors.current()): AccessReport {
        val report = AccessDiagnoser.diagnose(facts.collect(config, CopilotAccessibilityService.instance))
        return report
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.refresh()
        if (containsPrompt(node, text)) return true
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val previous = try {
            clipboard.primaryClip
        } catch (_: Exception) {
            null
        }
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("handsfree-copilot", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            node.refresh()
            containsPrompt(node, text)
        } catch (_: Exception) {
            false
        } finally {
            if (previous != null) {
                try {
                    clipboard.setPrimaryClip(previous)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun containsPrompt(node: AccessibilityNodeInfo, text: String): Boolean {
        val probe = text.take(80)
        val visible = node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()
        return visible.contains(probe)
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            val clicked = parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next = if (clicked) null else parent.parent
            parent.release()
            if (clicked) return true
            parent = next
            depth += 1
        }
        return false
    }

    private fun pressImeEnter(service: CopilotAccessibilityService, address: app.handsfree.copilot.core.model.NodeAddress): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = WindowSnapshotter.find(service, address) ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } finally {
            node.release()
        }
    }

    private fun AccessReport.toResult(): PrepareResult =
        PrepareResult(ok = false, message = "$title. $detail", blocked = toBlockedReason())

    private fun AccessReport.toBlockedReason(): BlockedReason? = when (code) {
        "ACCESSIBILITY_DISABLED", "SERVICE_NOT_CONNECTED" -> BlockedReason.ACCESSIBILITY_DISABLED
        "NOT_INSTALLED" -> BlockedReason.PACKAGE_NOT_INSTALLED
        "WORK_PROFILE_ONLY" -> BlockedReason.WORK_PROFILE_ONLY
        "CONTENT_PROTECTED", "CONTENT_REDACTED" -> BlockedReason.CONTENT_PROTECTED
        "WINDOW_NOT_FOUND" -> BlockedReason.WINDOW_NOT_FOUND
        "INPUT_NOT_FOUND" -> BlockedReason.INPUT_NOT_FOUND
        else -> null
    }

    private companion object {
        const val LAUNCH_WAIT_MS = 12_000L
    }
}
