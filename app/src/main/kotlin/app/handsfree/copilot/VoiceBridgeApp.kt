package app.handsfree.copilot

import android.app.Application

class VoiceBridgeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        instance = this
    }

    companion object {
        lateinit var instance: VoiceBridgeApp
            private set
    }
}
