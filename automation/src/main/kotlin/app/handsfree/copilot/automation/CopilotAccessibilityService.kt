package app.handsfree.copilot.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CopilotAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private var interruptButton: Button? = null
    private var keepScreenOn: Boolean = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
        scope.launch {
            AutomationSignals.overlay.collect { state -> render(state) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The driver polls the node tree. Events are not required for the loop.
    }

    override fun onInterrupt() {
        AutomationSignals.bargeRequested.tryEmit(Unit)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: OverlayState) {
        keepScreenOn = state.keepScreenOn
        if (!state.visible) {
            removeOverlay()
            return
        }
        val view = overlay ?: buildOverlay().also { overlay = it }
        statusView?.text = state.label
        interruptButton?.visibility = if (state.speaking) android.view.View.VISIBLE else android.view.View.GONE
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = layoutParams()
        if (view.parent == null) {
            manager.addView(view, params)
        } else {
            manager.updateViewLayout(view, params)
        }
    }

    private fun buildOverlay(): LinearLayout {
        val density = resources.displayMetrics.density
        val pad = (10 * density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(0xE6122430.toInt())
            contentDescription = "Copilot Voice Bridge session"
        }
        val status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 0, pad, 0)
        }
        val interrupt = Button(this).apply {
            text = "Interrupt"
            contentDescription = "Interrupt speech and listen"
            setOnClickListener { AutomationSignals.bargeRequested.tryEmit(Unit) }
        }
        val end = Button(this).apply {
            text = "End"
            contentDescription = "End the hands-free session"
            setOnClickListener { AutomationSignals.stopRequested.tryEmit(Unit) }
        }
        row.addView(status)
        row.addView(interrupt)
        row.addView(end)
        statusView = status
        interruptButton = interrupt
        return row
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * resources.displayMetrics.density).toInt()
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        if (view.parent != null) {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            manager.removeView(view)
        }
        overlay = null
        statusView = null
        interruptButton = null
    }
}
