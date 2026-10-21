package app.handsfree.copilot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import app.handsfree.copilot.core.model.Phase

/**
 * A small "Interrupt" control drawn over Copilot while a reply is being spoken,
 * so a tap barges in without pulling down the notification shade.
 */
class InterruptOverlay(
    context: Context,
    private val onTap: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var view: Button? = null

    fun onPhase(phase: Phase) {
        if (phase is Phase.Speaking && canDraw()) show() else hide()
    }

    fun hide() {
        val current = view ?: return
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
        view = null
    }

    private fun show() {
        if (view != null) return
        val button = Button(appContext).apply {
            text = "Interrupt"
            setOnClickListener { onTap() }
            contentDescription = "Interrupt Copilot speech"
        }
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 32
            y = 220
        }
        try {
            windowManager.addView(button, params)
            view = button
        } catch (_: Exception) {
            view = null
        }
    }

    private fun canDraw(): Boolean = Settings.canDrawOverlays(appContext)
}
