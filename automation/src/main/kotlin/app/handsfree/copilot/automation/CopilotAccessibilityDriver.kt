package app.handsfree.copilot.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.handsfree.copilot.core.config.DriverConfig
import app.handsfree.copilot.core.config.SelectorSet
import app.handsfree.copilot.core.model.AccessDiagnostics
import app.handsfree.copilot.core.model.AccessLevel
import app.handsfree.copilot.core.model.ChatDriver
import app.handsfree.copilot.core.model.DriverStatus
import app.handsfree.copilot.core.model.ObserveResult
import app.handsfree.copilot.core.model.ResponseObservation
import app.handsfree.copilot.core.selector.NodeSelectorMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class SelectorHit(val name: String, val count: Int, val sample: String)

data class CalibrationReport(
    val text: String,
    val hits: List<SelectorHit>,
    val foregroundPackages: List<String>,
    val diagnostics: AccessDiagnostics,
)

/**
 * Drives the Copilot UI the way a person would: focus the text field, set the text,
 * press send, and read the newest assistant text back. Every selector comes from
 * [DriverConfig]. Resource ids are not hard-coded here.
 */
class CopilotAccessibilityDriver(
    context: android.content.Context,
    private val config: () -> DriverConfig,
    private val log: (String) -> Unit = {},
) : ChatDriver {
    private val appContext = context.applicationContext
    private val inspector = WorkProfileInspector(appContext)
    private val diagnosticsState = MutableStateFlow(refreshDiagnosticsBlocking(null))
    val diagnosticsFlow: StateFlow<AccessDiagnostics> = diagnosticsState.asStateFlow()
    private var clipboardRestore: ClipData? = null
    private var clipboardTouched = false

    override fun diagnostics(): AccessDiagnostics = diagnosticsState.value

    override suspend fun openTarget(): DriverStatus {
        val service = AccessibilityBridge.service
            ?: return blockedOrFailed("The accessibility service is off. Turn it on in Settings, then start again.")
        val target = config().targetPackage
        val before = withContext(Dispatchers.Main) { publishProbe(service, target) }
        if (before.level == AccessLevel.BLOCKED && !before.installedInCurrentUser) {
            return DriverStatus.Blocked(before.detail)
        }
        val launch = service.packageManager.getLaunchIntentForPackage(target)
            ?: return DriverStatus.Failed(
                "No launch activity for $target. Open Calibrate and set the target to the foreground package.",
            )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            withContext(Dispatchers.Main) { service.startActivity(launch) }
        } catch (error: Exception) {
            return DriverStatus.Failed("Could not open $target. ${error.message ?: ""}".trim())
        }
        val deadline = SystemClock.elapsedRealtime() + config().openTimeoutMs
        var latest = before
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = withContext(Dispatchers.Main) { publishProbe(service, target) }
            if (latest.contentReadable) return DriverStatus.Ok
            if (latest.level == AccessLevel.BLOCKED && latest.windowFound) return DriverStatus.Blocked(latest.detail)
            delay(250)
        }
        return if (latest.level == AccessLevel.BLOCKED) {
            DriverStatus.Blocked(latest.detail)
        } else {
            DriverStatus.Failed(
                "Opened $target but its chat content was not readable. Unlock the phone, leave the screen on, " +
                    "and open a text chat. If Calibrate says the content is blocked, a work profile or Intune policy " +
                    "is hiding it.",
            )
        }
    }

    override suspend fun captureMessages(): List<String> = withContext(Dispatchers.Main) {
        val service = AccessibilityBridge.service ?: return@withContext emptyList()
        service.captureTree(config().targetPackage).use { tree ->
            publish(tree, config().targetPackage)
            messageTexts(tree)
        }
    }

    override suspend fun sendUserMessage(text: String): DriverStatus {
        val service = AccessibilityBridge.service
            ?: return blockedOrFailed("The accessibility service is off.")
        val inserted = insertText(service, text)
        if (inserted !is DriverStatus.Ok) return inserted
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val clicked = withContext(Dispatchers.Main) { clickSend(service) }
            if (clicked) {
                restoreClipboard(service)
                return DriverStatus.Ok
            }
            delay(200)
        }
        if (config().sendFallbackIme && Build.VERSION.SDK_INT >= 30) {
            val entered = withContext(Dispatchers.Main) { pressImeEnter(service) }
            if (entered) {
                restoreClipboard(service)
                return DriverStatus.Ok
            }
        }
        restoreClipboard(service)
        return DriverStatus.Failed(
            "The text was written into the chat box, but the send button was not found. " +
                "Open Calibrate and tune the sendButton selector. Copilot's own microphone is not used.",
        )
    }

    override suspend fun observeUntil(
        timeoutMs: Long,
        pollIntervalMs: Long,
        onSnapshot: (ResponseObservation) -> String?,
    ): ObserveResult {
        val start = SystemClock.elapsedRealtime()
        var last = ""
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val service = AccessibilityBridge.service
                ?: return ObserveResult.Failed("The accessibility service disconnected.")
            val observation = withContext(Dispatchers.Main) { readObservation(service) }
            if (observation.diagnostics.level == AccessLevel.BLOCKED &&
                observation.diagnostics.windowFound &&
                !observation.diagnostics.contentReadable
            ) {
                return ObserveResult.Blocked(observation.diagnostics.detail)
            }
            val done = onSnapshot(observation)
            if (done != null) return ObserveResult.Completed(done)
            last = observation.messagesTopToBottom.lastOrNull().orEmpty()
            delay(pollIntervalMs)
        }
        return ObserveResult.TimedOut(last)
    }

    suspend fun calibrate(): CalibrationReport = withContext(Dispatchers.Main) {
        val service = AccessibilityBridge.service
        val target = config().targetPackage
        if (service == null) {
            val diagnostics = publishProbe(null, target)
            return@withContext CalibrationReport(
                text = "Accessibility service is not connected.\n\n${diagnostics.detail}",
                hits = emptyList(),
                foregroundPackages = emptyList(),
                diagnostics = diagnostics,
            )
        }
        val packages = service.foregroundPackages()
        service.captureTree(target).use { tree ->
            val diagnostics = publish(tree, target)
            val hits = selectorHits(tree)
            CalibrationReport(
                text = formatDump(tree, packages, diagnostics, hits),
                hits = hits,
                foregroundPackages = packages,
                diagnostics = diagnostics,
            )
        }
    }

    private suspend fun insertText(service: CopilotAccessibilityService, text: String): DriverStatus {
        val direct = withContext(Dispatchers.Main) { setText(service, text) }
        if (direct) return DriverStatus.Ok
        val pasted = withContext(Dispatchers.Main) { pasteText(service, text) }
        if (!pasted) {
            return DriverStatus.Failed(
                "Could not find an editable chat box in ${config().targetPackage}. Open a Copilot text chat " +
                    "(the screen where you pick the model) and run Calibrate. Voice mode inside Copilot is not used.",
            )
        }
        delay(200)
        val verified = withContext(Dispatchers.Main) { inputContains(service, text) }
        return if (verified) {
            DriverStatus.Ok
        } else {
            DriverStatus.Failed(
                "The chat box was found, but the dictated text did not stick. Some Copilot builds ignore " +
                    "accessibility text entry until the field is focused. Tap the text box once, then try again.",
            )
        }
    }

    private fun setText(service: CopilotAccessibilityService, text: String): Boolean {
        service.captureTree(config().targetPackage).use { tree ->
            publish(tree, config().targetPackage)
            val input = find(tree, config().selectors.chatInput) ?: return false
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val acted = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            input.refresh()
            return acted && textMatches(input, text)
        }
    }

    private fun pasteText(service: CopilotAccessibilityService, text: String): Boolean {
        service.captureTree(config().targetPackage).use { tree ->
            val input = find(tree, config().selectors.chatInput) ?: return false
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
            if (!clipboardTouched) {
                clipboardRestore = clipboard.primaryClip
                clipboardTouched = true
            }
            return try {
                clipboard.setPrimaryClip(ClipData.newPlainText("copilot-voice-bridge", text))
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (_: SecurityException) {
                log("Clipboard paste was blocked by the device policy.")
                false
            }
        }
    }

    private fun inputContains(service: CopilotAccessibilityService, text: String): Boolean {
        service.captureTree(config().targetPackage).use { tree ->
            val input = find(tree, config().selectors.chatInput) ?: return false
            input.refresh()
            return textMatches(input, text)
        }
    }

    private fun clickSend(service: CopilotAccessibilityService): Boolean {
        service.captureTree(config().targetPackage).use { tree ->
            val send = find(tree, config().selectors.sendButton) ?: return false
            if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            return tapCenter(service, send)
        }
    }

    private fun pressImeEnter(service: CopilotAccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        service.captureTree(config().targetPackage).use { tree ->
            val input = find(tree, config().selectors.chatInput) ?: return false
            return input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    private fun tapCenter(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun readObservation(service: CopilotAccessibilityService): ResponseObservation {
        service.captureTree(config().targetPackage).use { tree ->
            val diagnostics = publish(tree, config().targetPackage)
            val generating = NodeSelectorMatcher.matching(
                tree.nodes.map { it.snapshot },
                config().selectors.generatingIndicator,
            ).isNotEmpty()
            return ResponseObservation(
                messagesTopToBottom = messageTexts(tree),
                generatingIndicatorVisible = generating,
                diagnostics = diagnostics,
            )
        }
    }

    private fun messageTexts(tree: TreeSession): List<String> {
        val matches = tree.nodes.filter { NodeSelectorMatcher.matches(it.snapshot, config().selectors.assistantMessage) }
        return matches
            .sortedWith(compareBy({ it.snapshot.boundsTop }, { it.snapshot.boundsLeft }))
            .mapNotNull { it.snapshot.text?.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun find(tree: TreeSession, selector: SelectorSet): AccessibilityNodeInfo? {
        val matches = tree.nodes.filter { NodeSelectorMatcher.matches(it.snapshot, selector) }
        if (matches.isEmpty()) return null
        val picked = NodeSelectorMatcher.pick(matches.map { it.snapshot }, selector.pick) ?: return null
        return matches.firstOrNull { it.snapshot === picked }?.node
    }

    private fun selectorHits(tree: TreeSession): List<SelectorHit> {
        val selectors = config().selectors
        return listOf(
            hit("chatInput", tree, selectors.chatInput),
            hit("sendButton", tree, selectors.sendButton),
            hit("generatingIndicator", tree, selectors.generatingIndicator),
            hit("assistantMessage", tree, selectors.assistantMessage),
        )
    }

    private fun hit(name: String, tree: TreeSession, selector: SelectorSet): SelectorHit {
        val matches = tree.nodes.filter { NodeSelectorMatcher.matches(it.snapshot, selector) }
        val sample = matches.firstOrNull()?.snapshot?.let { snap ->
            listOfNotNull(snap.viewIdResourceName, snap.contentDescription, snap.text?.take(80))
                .joinToString(" | ")
        }.orEmpty()
        return SelectorHit(name, matches.size, sample)
    }

    private fun publish(tree: TreeSession, target: String): AccessDiagnostics {
        val probe = WindowProbe(
            sawPackage = tree.sawPackage,
            readableRoots = if (tree.nodes.isEmpty()) 0 else 1,
            unreadableWindows = tree.unreadableWindows,
        )
        val diagnostics = inspector.inspect(target, probe)
        diagnosticsState.value = diagnostics
        return diagnostics
    }

    private fun publishProbe(service: CopilotAccessibilityService?, target: String): AccessDiagnostics {
        if (service == null) {
            val diagnostics = inspector.inspect(target, null)
            diagnosticsState.value = diagnostics
            return diagnostics
        }
        service.captureTree(target).use { tree ->
            return publish(tree, target)
        }
    }

    private fun refreshDiagnosticsBlocking(probe: WindowProbe?): AccessDiagnostics =
        inspector.inspect(config().targetPackage, probe)

    private fun blockedOrFailed(message: String): DriverStatus {
        val current = diagnosticsState.value
        return if (current.level == AccessLevel.BLOCKED) DriverStatus.Blocked(current.detail) else DriverStatus.Failed(message)
    }

    private fun restoreClipboard(service: CopilotAccessibilityService) {
        if (!clipboardTouched) return
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return
        val previous = clipboardRestore
        clipboardTouched = false
        clipboardRestore = null
        if (previous == null) return
        try {
            clipboard.setPrimaryClip(previous)
        } catch (_: Exception) {
            log("Could not restore the previous clipboard contents.")
        }
    }

    private fun textMatches(node: AccessibilityNodeInfo, expected: String): Boolean {
        val current = node.text?.toString()?.trim().orEmpty()
        val wanted = expected.trim()
        return current == wanted || current.endsWith(wanted)
    }

    private fun formatDump(
        tree: TreeSession,
        packages: List<String>,
        diagnostics: AccessDiagnostics,
        hits: List<SelectorHit>,
    ): String {
        val target = config().targetPackage
        val version = inspector.versionName(target) ?: "not installed for this user"
        val builder = StringBuilder()
        builder.appendLine("Copilot Voice Bridge accessibility dump")
        builder.appendLine("This file can contain chat text that was on screen. Treat it as work data.")
        builder.appendLine()
        builder.appendLine("Configured target: $target")
        builder.appendLine("Installed version: $version")
        builder.appendLine("Foreground packages: ${packages.joinToString().ifBlank { "(none)" }}")
        builder.appendLine("Diagnostics: ${diagnostics.level} — ${diagnostics.title}")
        builder.appendLine(diagnostics.detail)
        builder.appendLine()
        builder.appendLine("Selector hits (a generating indicator should be 0 when Copilot is idle):")
        hits.forEach { hit ->
            builder.appendLine("- ${hit.name}: ${hit.count}  ${hit.sample}")
        }
        builder.appendLine()
        builder.appendLine("Node tree (${tree.nodes.size} nodes, unreadable windows=${tree.unreadableWindows}):")
        if (tree.nodes.isEmpty()) {
            builder.appendLine("(no readable nodes for the target package)")
        }
        tree.nodes.forEach { live ->
            val snap = live.snapshot
            val indent = "  ".repeat(snap.depth.coerceAtMost(30))
            val text = if (snap.isPassword) "***" else snap.text?.take(1_500).orEmpty()
            builder.append(indent)
            builder.append(snap.className ?: "(no class)")
            snap.viewIdResourceName?.let { builder.append(" id=").append(it) }
            if (text.isNotEmpty()) builder.append(" text=\"").append(text.replace("\n", "\\n")).append('"')
            snap.contentDescription?.take(300)?.let { builder.append(" desc=\"").append(it.replace("\n", "\\n")).append('"') }
            snap.hintText?.take(120)?.let { builder.append(" hint=\"").append(it).append('"') }
            val flags = buildList {
                if (snap.isEditable) add("editable")
                if (snap.isClickable) add("clickable")
                if (snap.isScrollable) add("scrollable")
                if (!snap.isEnabled) add("disabled")
                if (snap.isPassword) add("password")
            }
            if (flags.isNotEmpty()) builder.append(' ').append(flags.joinToString(","))
            builder.append(" bounds=(")
                .append(snap.boundsLeft).append(',')
                .append(snap.boundsTop).append(',')
                .append(snap.boundsRight).append(',')
                .append(snap.boundsBottom).append(')')
            builder.appendLine()
        }
        return builder.toString()
    }
}
