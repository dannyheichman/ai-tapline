package app.handsfree.copilot.core.ports

import kotlinx.coroutines.delay

class SystemSleeper : Sleeper {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun sleep(millis: Long) {
        delay(millis)
    }
}
