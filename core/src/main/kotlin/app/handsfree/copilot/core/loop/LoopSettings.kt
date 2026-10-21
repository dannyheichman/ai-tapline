package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.config.DriverConfig

data class LoopSettings(
    val driver: DriverConfig = DriverConfig(),
    val preferOfflineStt: Boolean = true,
    val bargeInEnabled: Boolean = true,
    val speakErrors: Boolean = true,
    val keepScreenOn: Boolean = true,
    val listenCompleteSilenceMs: Long = 1_400,
    val listenPossiblyCompleteSilenceMs: Long = 900,
    val listenMinimumMs: Long = 1_500,
)
